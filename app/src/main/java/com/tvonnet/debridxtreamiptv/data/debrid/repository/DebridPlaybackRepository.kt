package com.tvonnet.debridxtreamiptv.data.debrid.repository

import com.tvonnet.debridxtreamiptv.data.Result
import com.tvonnet.debridxtreamiptv.data.Result.Error
import com.tvonnet.debridxtreamiptv.data.Result.Success
import com.tvonnet.debridxtreamiptv.data.debrid.model.DebridFailureClassifier
import com.tvonnet.debridxtreamiptv.data.debrid.source.RealDebridRemoteDataSource
import com.tvonnet.debridxtreamiptv.util.SensitiveLogRedactor
import kotlinx.coroutines.delay
import okhttp3.OkHttpClient
import okhttp3.Request
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Repository handling torrent→Real-Debrid→stream resolution
 */
@Singleton
class DebridPlaybackRepository @Inject constructor(
    private val realDebridRemote: RealDebridRemoteDataSource,
    private val debridAccountRepository: DebridAccountRepository,
    private val okHttpClient: OkHttpClient,
    private val realDebridRateLimiter: RealDebridRateLimiter
) {
    suspend fun isAddonProxyPlaybackReady(url: String): Result<Boolean> {
        if (!isAddonProxyPlaybackUrl(url)) {
            return Success(true)
        }

        return try {
            val client = okHttpClient.newBuilder()
                .followRedirects(false)
                .followSslRedirects(false)
                .build()
            val request = Request.Builder()
                .url(url)
                .head()
                .build()
            client.newCall(request).execute().use { response ->
                if (response.isRedirect) {
                    val location = response.header("Location")?.lowercase()
                    // Detect if the addon is redirecting to a known error video instead of the actual stream
                    val isErrorVideo = location?.contains("torrent_not_downloaded.mp4") == true ||
                                       location?.contains("api_exception") == true ||
                                       location?.contains("rate_limit") == true ||
                                       location?.contains("invalid_token") == true ||
                                       location?.contains("error.mp4") == true
                    if (isErrorVideo) {
                        return Success(false)
                    }
                }
                Success(true)
            }
        } catch (e: Exception) {
            Error(Exception("Addon proxy playback check failed: ${e.message}", e))
        }
    }

    /**
     * Resolve a magnet/infoHash to a streamable URL via Real-Debrid
     * Flow: Add magnet → Poll until ready → Select file → Unrestrict link
     */
    suspend fun resolveDebridUrl(
        infoHash: String?,
        magnet: String?,
        seasonNumber: Int? = null,
        episodeNumber: Int? = null,
        episodeTitle: String? = null,
        allowDirectStreamUrlPassthrough: Boolean = true
    ): Result<String> {
        if (infoHash.isNullOrBlank() && magnet.isNullOrBlank()) {
            return Error(Exception("No infoHash or magnet provided"))
        }

        val episodeHint = if (seasonNumber != null && episodeNumber != null) {
            EpisodeHint(seasonNumber, episodeNumber, episodeTitle)
        } else {
            null
        }

        // Step 1: Add magnet to Real-Debrid
        val directStreamUrl = magnet?.takeIf { isDirectStreamUrl(it) && !it.endsWith(".torrent", ignoreCase = true) }
        if (directStreamUrl != null && allowDirectStreamUrlPassthrough) {
            android.util.Log.d(
                "DebridPlayback",
                "Direct addon URL detected, using fresh passthrough: ${SensitiveLogRedactor.describeUrl(directStreamUrl)}"
            )
            return Success(directStreamUrl)
        }

        if (directStreamUrl != null && infoHash.isNullOrBlank()) {
            return Error(Exception("Direct addon URL requires fresh metadata"))
        }

        val magnetLink = when {
            !magnet.isNullOrBlank() &&
                magnet.startsWith("http", ignoreCase = true) &&
                magnet.endsWith(".torrent", ignoreCase = true) &&
                !infoHash.isNullOrBlank() -> {
                "magnet:?xt=urn:btih:$infoHash"
            }
            directStreamUrl != null && !infoHash.isNullOrBlank() -> {
                "magnet:?xt=urn:btih:$infoHash"
            }
            !magnet.isNullOrBlank() -> magnet
            else -> "magnet:?xt=urn:btih:$infoHash"
        }
        val sourceKey = normalizeSourceKey(infoHash, magnetLink)

        realDebridRateLimiter.globalCooldown()?.let { cooldown ->
            android.util.Log.w("DebridPlayback", "Skipping RD playback during global cooldown")
            return Error(cooldown)
        }

        realDebridRateLimiter.sourceCooldown(sourceKey)?.let { cooldown ->
            android.util.Log.w("DebridPlayback", "Skipping RD source on cooldown: ${cooldown.type}")
            return Error(cooldown)
        }

        val cachedAuth = debridAccountRepository.getCachedAuthState()
        if (cachedAuth == null) {
            return Error(com.tvonnet.debridxtreamiptv.data.debrid.model.NotAuthenticatedException("Real-Debrid configuration missing"))
        }
        val refreshResult = debridAccountRepository.refreshAuthStateIfNeeded()
        if (refreshResult is Error && shouldForceReauth(refreshResult.exception)) {
            return Error(com.tvonnet.debridxtreamiptv.data.debrid.model.NotAuthenticatedException("Real-Debrid session expired"))
        }

        if (magnetLink.startsWith("http", ignoreCase = true) &&
            !magnetLink.endsWith(".torrent", ignoreCase = true)
        ) {
            android.util.Log.d("DebridPlayback", "HTTP link requires Real-Debrid unrestrict: ${SensitiveLogRedactor.describeUrl(magnetLink)}")
            return unrestrictLinkWithRetry(magnetLink, 3, sourceKey)
        }

        realDebridRateLimiter.awaitPermit()
        val addResult = realDebridRemote.addMagnet(magnetLink)
        var torrentId = when (addResult) {
            is Success -> addResult.data.id
            is Error -> {
                return handleDebridFailure("add magnet", sourceKey, addResult.exception)
            }
            else -> null
        } ?: return Error(Exception("No torrent ID returned"))

        // Step 2: Poll for torrent info until status = downloaded or timeout
        var attempts = 0
        val maxAttempts = 30 // 30 * 2s = 60s timeout
        var torrentInfo: com.tvonnet.debridxtreamiptv.data.debrid.model.RealDebridTorrentInfoResponse? = null
        var hasReAddedTorrent = false
        var finalVideoLink: String? = null

        pollLoop@ while (attempts < maxAttempts) {
            realDebridRateLimiter.awaitPermit()
            val infoResult = realDebridRemote.getTorrentInfo(torrentId)
            when (infoResult) {
                is Success -> {
                    torrentInfo = infoResult.data
                    val status = torrentInfo.status?.lowercase()
                    
                    when (status) {
                        "downloaded", "ready" -> {
                            finalVideoLink = pickVideoLink(torrentInfo, episodeHint)
                            if (finalVideoLink != null) {
                                break@pollLoop // Ready to unrestrict
                            } else if (!hasReAddedTorrent) {
                                android.util.Log.w("DebridPlayback", "Episode not found in selected files. Deleting and re-adding torrent.")
                                realDebridRateLimiter.awaitPermit()
                                realDebridRemote.deleteTorrent(torrentId)
                                realDebridRateLimiter.awaitPermit()
                                val reAddResult = realDebridRemote.addMagnet(magnetLink)
                                if (reAddResult is Success) {
                                    torrentId = reAddResult.data.id ?: return Error(Exception("No torrent ID returned on re-add"))
                                    hasReAddedTorrent = true
                                    attempts = 0
                                    continue@pollLoop
                                } else {
                                    val error = (reAddResult as? Error)?.exception
                                        ?: Exception("Failed to re-add magnet after deletion")
                                    return handleDebridFailure("re-add magnet", sourceKey, error)
                                }
                            } else {
                                return Error(Exception("Desired episode not found in torrent after re-adding"))
                            }
                        }
                        "downloading", "queued", "waiting_files_selection" -> {
                            if (status == "waiting_files_selection") {
                                val fileIds = selectFileIds(torrentInfo.files, episodeHint)
                                if (fileIds.isNotEmpty()) {
                                    realDebridRateLimiter.awaitPermit()
                                    val selectResult = realDebridRemote.selectFiles(torrentId, fileIds.joinToString(","))
                                    if (selectResult is Error) {
                                        return handleDebridFailure("select files", sourceKey, selectResult.exception)
                                    }
                                }
                            }
                            delay(2000) // Wait 2s before retry
                        }
                        "error", "dead", "magnet_error" -> {
                            return Error(Exception("Torrent failed with status: $status"))
                        }
                        else -> {
                            delay(2000)
                        }
                    }
                }
                is Error -> return handleDebridFailure("get torrent info", sourceKey, infoResult.exception)
                else -> return Error(Exception("Unknown error during torrent info fetch"))
            }
            attempts++
        }

        if (torrentInfo == null || torrentInfo.status?.lowercase() !in listOf("downloaded", "ready")) {
            return Error(Exception("Torrent not ready after $maxAttempts attempts"))
        }

        // Step 3: Get video link
        val videoLink = finalVideoLink ?: pickVideoLink(torrentInfo, episodeHint)
            ?: return Error(Exception("No links available in torrent for requested episode"))

        // Step 4: Unrestrict the link with retry
        return unrestrictLinkWithRetry(videoLink, 3, sourceKey)
    }

    private suspend fun unrestrictLinkWithRetry(link: String, maxRetries: Int, sourceKey: String?): Result<String> {
        var attempts = 0
        while (attempts < maxRetries) {
            realDebridRateLimiter.awaitPermit()
            val unrestrictResult = realDebridRemote.unrestrictLink(link)
            when (unrestrictResult) {
                is Success -> {
                    val streamUrl = unrestrictResult.data.downloadUrl ?: unrestrictResult.data.streamingUrl
                        ?: return Error(Exception("No download URL in unrestrict response"))
                    return Success(streamUrl)
                }
                is Error -> {
                    val failure = DebridFailureClassifier.classify(unrestrictResult.exception)
                    android.util.Log.e(
                        "DebridPlayback",
                        "Unrestrict failed for link ${SensitiveLogRedactor.describeUrl(link)} (Attempt $attempts): ${failure.type}"
                    )

                    if (failure.terminal) {
                        realDebridRateLimiter.recordFailure(sourceKey, failure)
                        return Error(failure)
                    }

                    if (attempts < maxRetries - 1) {
                         delay(1000L * (attempts + 1))
                    } else {
                        return Error(failure)
                    }
                }
                else -> {
                     if (attempts < maxRetries - 1) {
                         delay(1000L * (attempts + 1))
                    } else {
                        return Error(Exception("Unknown state during unrestrict"))
                    }
                }
            }
            attempts++
        }
        return Error(Exception("Failed to unrestrict link after retries"))
    }

    private fun handleDebridFailure(
        operation: String,
        sourceKey: String?,
        error: Exception
    ): Error {
        val failure = DebridFailureClassifier.classify(error)
        android.util.Log.e("DebridPlayback", "Real-Debrid $operation failed: ${failure.type}")
        realDebridRateLimiter.recordFailure(sourceKey, failure)
        return Error(failure)
    }



    private data class EpisodeHint(
        val season: Int,
        val episode: Int,
        val title: String?
    )

    private data class FileScore(
        val file: com.tvonnet.debridxtreamiptv.data.debrid.model.RealDebridTorrentFile,
        val score: Int
    )

    private data class LinkScore(
        val index: Int,
        val score: Int,
        val sizeBytes: Long
    )

    private fun selectFileIds(
        files: List<com.tvonnet.debridxtreamiptv.data.debrid.model.RealDebridTorrentFile>?,
        hint: EpisodeHint?
    ): List<Int> {
        val videoFiles = files.orEmpty()
            .filter { it.id != null && isVideoFile(it.path) }

        if (videoFiles.isEmpty()) return emptyList()
        if (hint == null) {
            return videoFiles.mapNotNull { it.id }
        }

        val scoredFiles = videoFiles.map { file ->
            FileScore(file, scoreEpisodeMatch(file.path, hint))
        }
        val bestScore = scoredFiles.maxOf { it.score }
        if (bestScore > 0) {
            val bestCandidates = scoredFiles.filter { it.score == bestScore }
            val best = bestCandidates.maxWithOrNull(
                compareBy<FileScore> { it.file.bytes ?: 0L }
            )
            return best?.file?.id?.let { listOf(it) } ?: emptyList()
        }

        val fallback = videoFiles.maxByOrNull { it.bytes ?: 0L }?.id
        return fallback?.let { listOf(it) } ?: emptyList()
    }

    private fun pickVideoLink(
        torrentInfo: com.tvonnet.debridxtreamiptv.data.debrid.model.RealDebridTorrentInfoResponse,
        hint: EpisodeHint?
    ): String? {
        val links = torrentInfo.links ?: return null
        if (hint == null || torrentInfo.files.isNullOrEmpty()) {
            return links.firstOrNull()
        }

        val selectedFiles = torrentInfo.files
            .filter { it.selected == 1 && it.id != null }

        if (selectedFiles.isEmpty()) {
            return links.firstOrNull()
        }

        val scoredLinks = selectedFiles.mapIndexed { index, file ->
            LinkScore(
                index = index,
                score = scoreEpisodeMatch(file.path, hint),
                sizeBytes = file.bytes ?: 0L
            )
        }

        val best = scoredLinks.maxWithOrNull(
            compareBy<LinkScore> { it.score }.thenBy { it.sizeBytes }
        )

        return if (best != null && best.score > 0) {
            links.getOrNull(best.index) ?: links.firstOrNull()
        } else {
            null
        }
    }

    private fun scoreEpisodeMatch(path: String?, hint: EpisodeHint): Int {
        if (path.isNullOrBlank()) return 0

        val name = path.substringAfterLast('/').lowercase()
        if (name.contains("sample") || name.contains("trailer") || name.contains("extras")) {
            return 0
        }

        val season = hint.season
        val episode = hint.episode
        var score = 0

        val sxePattern = Regex("\\bs0*(\\d{1,2})\\s*[._ -]*e0*(\\d{1,2})(?:[._ -]*e0*(\\d{1,2}))?\\b")
        sxePattern.findAll(name).forEach { match ->
            val s = match.groupValues[1].toIntOrNull()
            if (s == season) {
                val e1 = match.groupValues[2].toIntOrNull()
                val e2 = match.groupValues.getOrNull(3)?.toIntOrNull()
                if (e1 == episode || e2 == episode) {
                    score = maxOf(score, 100)
                }
            }
        }

        val xPattern = Regex("\\b(\\d{1,2})x(\\d{1,2})(?:-(\\d{1,2}))?\\b")
        xPattern.findAll(name).forEach { match ->
            val s = match.groupValues[1].toIntOrNull()
            val e1 = match.groupValues[2].toIntOrNull()
            val e2 = match.groupValues.getOrNull(3)?.toIntOrNull()
            if (s == season && (e1 == episode || e2 == episode)) {
                score = maxOf(score, 95)
            }
        }

        val seasonWord = Regex("\\bseason\\s*0*$season\\b")
        val episodeWord = Regex("\\bepisode\\s*0*$episode\\b")
        if (seasonWord.containsMatchIn(name) && episodeWord.containsMatchIn(name)) {
            score = maxOf(score, 90)
        }

        val epOnly = Regex("\\b(ep|episode)\\s*0*$episode\\b")
        if (epOnly.containsMatchIn(name)) {
            score = maxOf(score, 60)
        }

        score = maxOf(score, scoreTitleMatch(name, hint.title))

        return score
    }

    private fun scoreTitleMatch(name: String, title: String?): Int {
        if (title.isNullOrBlank()) return 0
        val words = title.lowercase()
            .split(Regex("[^a-z0-9]+"))
            .filter { it.length >= 4 }

        if (words.isEmpty()) return 0

        val matches = words.count { name.contains(it) }
        return when {
            matches == words.size -> 50
            matches >= 2 -> 40
            matches == 1 -> 30
            else -> 0
        }
    }

    private fun isVideoFile(path: String?): Boolean {
        if (path.isNullOrBlank()) return false
        val lower = path.lowercase()
        return lower.endsWith(".mkv") ||
            lower.endsWith(".mp4") ||
            lower.endsWith(".avi") ||
            lower.endsWith(".mov") ||
            lower.endsWith(".ts") ||
            lower.endsWith(".m2ts") ||
            lower.endsWith(".wmv") ||
            lower.endsWith(".flv") ||
            lower.endsWith(".webm")
    }

    private fun shouldForceReauth(error: Exception?): Boolean {
        if (error is retrofit2.HttpException) {
            return error.code() == 400 || error.code() == 401 || error.code() == 403
        }
        val message = error?.message?.lowercase() ?: return false
        return message.contains("invalid_grant") ||
            message.contains("invalid_token") ||
            message.contains("invalid token") ||
            message.contains("unauthorized") ||
            message.contains("forbidden")
    }

    private fun normalizeSourceKey(infoHash: String?, magnetLink: String?): String? {
        val hash = infoHash?.trim()?.lowercase()?.takeIf { it.isNotBlank() }
            ?: magnetLink?.let { extractHashFromMagnet(it) }
        return hash ?: magnetLink?.trim()?.lowercase()?.take(120)?.takeIf { it.isNotBlank() }
    }

    private fun extractHashFromMagnet(magnetLink: String): String? {
        val match = Regex("btih:([a-fA-F0-9]{32,40})").find(magnetLink)
        return match?.groupValues?.getOrNull(1)?.lowercase()
    }

    private fun isDirectStreamUrl(url: String): Boolean {
        val lowered = url.lowercase()
        if (!lowered.startsWith("http")) return false
        if (lowered.contains("mediafusion.elfhosted.com")) return true
        if (lowered.contains("aiostreams.elfhosted.com")) return true
        if (lowered.contains("/stremio/")) return true
        if (lowered.contains("/stream/")) return true
        if (lowered.contains("/play/")) return true
        if (lowered.contains("/playback/")) return true

        val clean = lowered.substringBefore('?').substringBefore('#')
        return clean.endsWith(".mp4") ||
            clean.endsWith(".mkv") ||
            clean.endsWith(".m3u8") ||
            clean.endsWith(".mpd") ||
            clean.endsWith(".webm") ||
            clean.endsWith(".m4v") ||
            clean.endsWith(".mov") ||
            clean.endsWith(".ts") ||
            clean.endsWith(".m2ts")
    }

    private fun isAddonProxyPlaybackUrl(url: String): Boolean {
        val lowered = url.lowercase()
        return lowered.contains("mediafusion.elfhosted.com/playback") ||
               lowered.contains("aiostreams.elfhosted.com")
    }
}
