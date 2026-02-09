package com.tvonnet.debridxtreamiptv.data.debrid.repository

import com.tvonnet.debridxtreamiptv.data.Result
import com.tvonnet.debridxtreamiptv.data.Result.Error
import com.tvonnet.debridxtreamiptv.data.Result.Success
import com.tvonnet.debridxtreamiptv.data.debrid.source.RealDebridRemoteDataSource
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
    private val okHttpClient: OkHttpClient
) {
    suspend fun isMediaFusionPlaybackReady(url: String): Result<Boolean> {
        if (!isMediaFusionPlaybackUrl(url)) {
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
                    if (location?.contains("torrent_not_downloaded.mp4") == true) {
                        return Success(false)
                    }
                }
                Success(true)
            }
        } catch (e: Exception) {
            Error(Exception("MediaFusion playback check failed: ${e.message}", e))
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
        episodeTitle: String? = null
    ): Result<String> {
        if (infoHash.isNullOrBlank() && magnet.isNullOrBlank()) {
            return Error(Exception("No infoHash or magnet provided"))
        }

        val cachedAuth = debridAccountRepository.getCachedAuthState()
        if (cachedAuth == null) {
            return Error(com.tvonnet.debridxtreamiptv.data.debrid.model.NotAuthenticatedException("Real-Debrid configuration missing"))
        }
        val refreshResult = debridAccountRepository.refreshAuthStateIfNeeded()
        if (refreshResult is Error && shouldForceReauth(refreshResult.exception)) {
            return Error(com.tvonnet.debridxtreamiptv.data.debrid.model.NotAuthenticatedException("Real-Debrid session expired"))
        }

        val episodeHint = if (seasonNumber != null && episodeNumber != null) {
            EpisodeHint(seasonNumber, episodeNumber, episodeTitle)
        } else {
            null
        }

        // Step 1: Add magnet to Real-Debrid
        val magnetLink = when {
            !magnet.isNullOrBlank() &&
                magnet.startsWith("http", ignoreCase = true) &&
                magnet.endsWith(".torrent", ignoreCase = true) &&
                !infoHash.isNullOrBlank() -> {
                "magnet:?xt=urn:btih:$infoHash"
            }
            !magnet.isNullOrBlank() -> magnet
            else -> "magnet:?xt=urn:btih:$infoHash"
        }

        // CHECK IF DIRECT LINK (HTTP/HTTPS) - Fix for TorrentIO/MediaFusion
        if (magnetLink.startsWith("http", ignoreCase = true) &&
            !magnetLink.endsWith(".torrent", ignoreCase = true)
        ) {
            android.util.Log.d("DebridPlayback", "Detected direct URL, skipping torrent selection: $magnetLink")
            // Proceed directly to unrestrict (or return if already playable)
            // We use the 'magnetLink' as 'videoLink' for unrestrict logic
            if (isDirectStreamUrl(magnetLink)) {
                 android.util.Log.d("DebridPlayback", "URL appears to be a direct stream, returning as is.")
                 return Success(magnetLink)
            }
            return unrestrictLinkWithRetry(magnetLink, 3)
        }

        val addResult = realDebridRemote.addMagnet(magnetLink)
        val torrentId = when (addResult) {
            is Success -> addResult.data.id
            is Error -> {
                val msg = addResult.exception.message ?: ""
                android.util.Log.e("DebridPlayback", "Magnet addition failed: $msg")
                if (msg.contains("451") || addResult.exception.toString().contains("451")) {
                     android.util.Log.e("DebridPlayback", "Magnet blocked due to legal restrictions (451). Aborting.")
                     return Error(Exception("Content Unavailable (Legal Restriction)"))
                }
                return Error(Exception("Failed to add magnet: ${addResult.exception.message}"))
            }
            else -> null
        } ?: return Error(Exception("No torrent ID returned"))

        // Step 2: Poll for torrent info until status = downloaded or timeout
        var attempts = 0
        val maxAttempts = 30 // 30 * 2s = 60s timeout
        var torrentInfo: com.tvonnet.debridxtreamiptv.data.debrid.model.RealDebridTorrentInfoResponse? = null

        while (attempts < maxAttempts) {
            val infoResult = realDebridRemote.getTorrentInfo(torrentId)
            when (infoResult) {
                is Success -> {
                    torrentInfo = infoResult.data
                    val status = torrentInfo.status?.lowercase()
                    
                    when (status) {
                        "downloaded", "ready" -> break // Ready to unrestrict
                        "downloading", "queued", "waiting_files_selection" -> {
                            if (status == "waiting_files_selection") {
                                val fileIds = selectFileIds(torrentInfo.files, episodeHint)
                                if (fileIds.isNotEmpty()) {
                                    realDebridRemote.selectFiles(torrentId, fileIds.joinToString(","))
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
                is Error -> return Error(Exception("Failed to get torrent info: ${infoResult.exception.message}"))
                else -> return Error(Exception("Unknown error during torrent info fetch"))
            }
            attempts++
        }

        if (torrentInfo == null || torrentInfo.status?.lowercase() !in listOf("downloaded", "ready")) {
            return Error(Exception("Torrent not ready after $maxAttempts attempts"))
        }

        // Step 3: Get video link
        val videoLink = pickVideoLink(torrentInfo, episodeHint)
            ?: return Error(Exception("No links available in torrent"))

        // Step 4: Unrestrict the link with retry
        return unrestrictLinkWithRetry(videoLink, 3)
    }

    private suspend fun unrestrictLinkWithRetry(link: String, maxRetries: Int): Result<String> {
        var attempts = 0
        while (attempts < maxRetries) {
            val unrestrictResult = realDebridRemote.unrestrictLink(link)
            when (unrestrictResult) {
                is Success -> {
                    val streamUrl = unrestrictResult.data.downloadUrl ?: unrestrictResult.data.streamingUrl
                        ?: return Error(Exception("No download URL in unrestrict response"))
                    return Success(streamUrl)
                }
                is Error -> {
                    val msg = unrestrictResult.exception.message ?: "Unknown error"
                    android.util.Log.e("DebridPlayback", "Unrestrict failed for link '$link' (Attempt $attempts): $msg")
                    
                    if (msg.contains("451") || unrestrictResult.exception.toString().contains("451")) {
                        return Error(Exception("Content Unavailable (Legal Restriction)"))
                    }
                    if (msg.contains("404")) {
                         return Error(Exception("Link 404: $link"))
                    }

                    if (attempts < maxRetries - 1) {
                         delay(1000L * (attempts + 1))
                    } else {
                        return Error(Exception("Unrestrict Failed: $msg"))
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
            links.firstOrNull()
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

    private fun isDirectStreamUrl(url: String): Boolean {
        val lowered = url.lowercase()
        if (!lowered.startsWith("http")) return false
        if (lowered.contains("mediafusion.elfhosted.com")) return true

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

    private fun isMediaFusionPlaybackUrl(url: String): Boolean {
        val lowered = url.lowercase()
        return lowered.contains("mediafusion.elfhosted.com/playback")
    }
}
