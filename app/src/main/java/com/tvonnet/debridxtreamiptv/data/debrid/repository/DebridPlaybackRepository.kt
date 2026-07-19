package com.tvonnet.debridxtreamiptv.data.debrid.repository

import android.content.Context
import com.tvonnet.debridxtreamiptv.data.Result
import com.tvonnet.debridxtreamiptv.data.Result.Error
import com.tvonnet.debridxtreamiptv.data.Result.Success
import com.tvonnet.debridxtreamiptv.data.debrid.model.DebridFailureClassifier
import com.tvonnet.debridxtreamiptv.data.debrid.source.RealDebridRemoteDataSource
import com.tvonnet.debridxtreamiptv.debug.PlaybackDiagnosticsRecorder
import com.tvonnet.debridxtreamiptv.util.SensitiveLogRedactor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

enum class AddonProxyReadiness {
    READY,
    UNCERTAIN,
    TERMINAL
}

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
    fun requiresDirectProxyReadinessCheck(
        url: String?,
        provider: String? = null,
        sourceName: String? = null,
        sourceType: String? = null
    ): Boolean {
        return !url.isNullOrBlank() && (
            isAddonProxyPlaybackUrl(url) ||
                isAddonProxySource(provider) ||
                isAddonProxySource(sourceName) ||
                isAddonProxySource(sourceType)
            )
    }

    suspend fun isAddonProxyPlaybackReady(
        url: String,
        headers: Map<String, String>? = null,
        provider: String? = null,
        sourceName: String? = null,
        sourceType: String? = null,
        diagnosticsContext: Context? = null
    ): Result<Boolean> {
        return when (
            val readiness = getAddonProxyPlaybackReadiness(
                url,
                headers,
                provider,
                sourceName,
                sourceType,
                diagnosticsContext
            )
        ) {
            is Success -> Success(readiness.data == AddonProxyReadiness.READY)
            is Error -> readiness
            is Result.Loading -> readiness
        }
    }

    suspend fun getAddonProxyPlaybackReadiness(
        url: String,
        headers: Map<String, String>? = null,
        provider: String? = null,
        sourceName: String? = null,
        sourceType: String? = null,
        diagnosticsContext: Context? = null
    ): Result<AddonProxyReadiness> {
        if (!requiresDirectProxyReadinessCheck(url, provider, sourceName, sourceType)) {
            diagnosticsContext?.let { context ->
                PlaybackDiagnosticsRecorder.record(
                    context,
                    "readiness_check_finished",
                    PlaybackDiagnosticsRecorder.urlFields(context, url) +
                        PlaybackDiagnosticsRecorder.headerFields(headers) +
                        mapOf(
                            "provider" to provider,
                            "sourceName" to sourceName,
                            "sourceType" to sourceType,
                            "required" to false,
                            "ready" to true,
                            "readinessOutcome" to AddonProxyReadiness.READY.name,
                            "reasonCode" to "NOT_REQUIRED"
                        )
                )
            }
            return Success(AddonProxyReadiness.READY)
        }

        diagnosticsContext?.let { context ->
            PlaybackDiagnosticsRecorder.record(
                context,
                "readiness_check_started",
                PlaybackDiagnosticsRecorder.urlFields(context, url) +
                    PlaybackDiagnosticsRecorder.headerFields(effectiveAddonProxyPlaybackHeaders(headers)) +
                    mapOf(
                        "provider" to provider,
                        "sourceName" to sourceName,
                        "sourceType" to sourceType,
                        "required" to true
                    )
            )
        }

        return try {
            val client = okHttpClient.newBuilder()
                .followRedirects(false)
                .followSslRedirects(false)
                .build()
            val request = Request.Builder()
                .url(url)
                .head()
                .applyPlaybackHeaders(headers)
                .build()
            val (readiness, statusCode, wasRedirect) = withContext(Dispatchers.IO) {
                client.newCall(request).execute().use { response ->
                    val result = if (response.isRedirect) {
                        isReadyAddonProxyRedirect(client, response, headers)
                    } else {
                        classifyAddonProxyResponse(response)
                    }
                    Triple(result, response.code, response.isRedirect)
                }
            }
            diagnosticsContext?.let { context ->
                PlaybackDiagnosticsRecorder.record(
                    context,
                    "readiness_check_finished",
                    PlaybackDiagnosticsRecorder.urlFields(context, url) +
                        PlaybackDiagnosticsRecorder.headerFields(effectiveAddonProxyPlaybackHeaders(headers)) +
                        mapOf(
                            "provider" to provider,
                            "sourceName" to sourceName,
                            "sourceType" to sourceType,
                            "required" to true,
                            "ready" to (readiness == AddonProxyReadiness.READY),
                            "readinessOutcome" to readiness.name,
                            "httpStatusCode" to statusCode,
                            "isRedirect" to wasRedirect,
                            "reasonCode" to readiness.name
                        )
                )
            }
            Success(readiness)
        } catch (e: Exception) {
            diagnosticsContext?.let { context ->
                PlaybackDiagnosticsRecorder.record(
                    context,
                    "readiness_check_finished",
                    PlaybackDiagnosticsRecorder.urlFields(context, url) +
                        PlaybackDiagnosticsRecorder.headerFields(effectiveAddonProxyPlaybackHeaders(headers)) +
                        mapOf(
                            "provider" to provider,
                            "sourceName" to sourceName,
                            "sourceType" to sourceType,
                            "required" to true,
                            "ready" to false,
                            "readinessOutcome" to AddonProxyReadiness.UNCERTAIN.name,
                            "reasonCode" to PlaybackDiagnosticsRecorder.sanitizeReason(e::class.java.simpleName)
                        )
                )
            }
            Success(AddonProxyReadiness.UNCERTAIN)
        }
    }

    fun effectiveAddonProxyPlaybackHeaders(headers: Map<String, String>?): Map<String, String> {
        val normalized = LinkedHashMap<String, String>()
        var hasUserAgent = false
        var hasAccept = false
        headers.orEmpty().forEach { (name, value) ->
            if (name.isBlank() || value.isBlank()) return@forEach
            if (name.equals("User-Agent", ignoreCase = true)) hasUserAgent = true
            if (name.equals("Accept", ignoreCase = true)) hasAccept = true
            normalized[name] = value
        }
        if (!hasUserAgent) normalized["User-Agent"] = "Stremio/1.0"
        if (!hasAccept) normalized["Accept"] = "*/*"
        return normalized
    }

    private fun Request.Builder.applyPlaybackHeaders(headers: Map<String, String>?): Request.Builder {
        effectiveAddonProxyPlaybackHeaders(headers).forEach { (name, value) ->
            header(name, value)
        }
        return this
    }

    private fun classifyAddonProxyResponse(response: Response): AddonProxyReadiness {
        if (response.isRedirect) {
            return AddonProxyReadiness.UNCERTAIN
        }
        if (isTerminalAddonProxyStatus(response.code)) {
            return AddonProxyReadiness.TERMINAL
        }
        if (response.code == 204) {
            return AddonProxyReadiness.UNCERTAIN
        }
        if (!response.isSuccessful) {
            return AddonProxyReadiness.UNCERTAIN
        }

        val contentType = response.header("Content-Type")?.lowercase()
        if (contentType != null && isAddonProxyErrorContentType(contentType)) {
            return AddonProxyReadiness.TERMINAL
        }

        val contentLength = response.header("Content-Length")?.toLongOrNull()
        if (contentLength != null && contentLength in 1..4096 && contentType?.contains("video") != true) {
            return AddonProxyReadiness.UNCERTAIN
        }

        return AddonProxyReadiness.READY
    }

    private fun isReadyAddonProxyRedirect(
        client: OkHttpClient,
        response: Response,
        headers: Map<String, String>?
    ): AddonProxyReadiness {
        val location = response.header("Location") ?: return AddonProxyReadiness.UNCERTAIN
        if (isAddonProxyErrorTarget(location)) return AddonProxyReadiness.TERMINAL
        val redirectUrl = response.request.url.resolve(location) ?: return AddonProxyReadiness.UNCERTAIN
        val probeClient = client.newBuilder()
            .followRedirects(true)
            .followSslRedirects(true)
            .callTimeout(5, TimeUnit.SECONDS)
            .build()
        val request = Request.Builder()
            .url(redirectUrl)
            .get()
            .header("Range", "bytes=0-0")
            .applyPlaybackHeaders(headers)
            .build()
        return try {
            probeClient.newCall(request).execute().use { probeResponse ->
                classifyAddonProxyResponse(probeResponse)
            }
        } catch (_: Exception) {
            AddonProxyReadiness.UNCERTAIN
        }
    }

    private fun isTerminalAddonProxyStatus(code: Int): Boolean {
        return code == 401 ||
            code == 403 ||
            code == 404 ||
            code == 410 ||
            code == 451
    }

    private fun isAddonProxyErrorTarget(location: String?): Boolean {
        val value = location?.lowercase() ?: return false
        return value.contains("torrent_not_downloaded") ||
            value.contains("not_downloaded") ||
            value.contains("not-downloaded") ||
            value.contains("not-ready") ||
            value.contains("not_ready") ||
            value.contains("notready") ||
            value.contains("notcached") ||
            value.contains("not_cached") ||
            value.contains("not-cached") ||
            value.contains("api_exception") ||
            value.contains("api-exception") ||
            value.contains("api exception") ||
            value.contains("exception") ||
            value.contains("rate_limit") ||
            value.contains("rate-limit") ||
            value.contains("rate limit") ||
            value.contains("limit_reached") ||
            value.contains("limit-reached") ||
            value.contains("limit reached") ||
            value.contains("quota") ||
            value.contains("provider_error") ||
            value.contains("provider-error") ||
            value.contains("provider error") ||
            value.contains("invalid_token") ||
            value.contains("invalid-token") ||
            value.contains("invalid token") ||
            value.contains("error=") ||
            value.contains("error.mp4") ||
            value.contains("/error")
    }

    private fun isAddonProxyErrorContentType(contentType: String): Boolean {
        return contentType.contains("application/json") ||
            contentType.contains("text/html") ||
            contentType.contains("text/plain")
    }

    private fun isAddonProxySource(value: String?): Boolean {
        if (value.isNullOrBlank()) return false
        return value.contains("mediafusion", ignoreCase = true) ||
            value.contains("aio streams", ignoreCase = true) ||
            value.contains("aiostreams", ignoreCase = true) ||
            value.equals("aio", ignoreCase = true)
    }

    private data class CachedResolution(val url: String, val expiresAtMs: Long)

    private val resolvedLinkCache = java.util.concurrent.ConcurrentHashMap<String, CachedResolution>()

    private companion object {
        const val RESOLVED_LINK_TTL_MS = 15 * 60 * 1000L
    }

    /**
     * Resolve a magnet/infoHash to a streamable URL via Real-Debrid
     * Flow: Add magnet → Poll until ready → Select file → Unrestrict link
     *
     * Successful resolutions are cached for [RESOLVED_LINK_TTL_MS] so replays/episode
     * restarts skip the full add-magnet→poll→unrestrict chain. Pass [bypassCache]=true
     * on retries so a dead link is never served back from cache.
     */
    suspend fun resolveDebridUrl(
        infoHash: String?,
        magnet: String?,
        seasonNumber: Int? = null,
        episodeNumber: Int? = null,
        episodeTitle: String? = null,
        allowDirectStreamUrlPassthrough: Boolean = true,
        bypassCache: Boolean = false
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

        val resolutionCacheKey = buildString {
            append(sourceKey)
            episodeHint?.let { append(":s").append(it.season).append("e").append(it.episode) }
        }
        if (bypassCache) {
            resolvedLinkCache.remove(resolutionCacheKey)
        } else {
            resolvedLinkCache[resolutionCacheKey]?.let { cached ->
                if (cached.expiresAtMs > System.currentTimeMillis()) {
                    android.util.Log.d(
                        "DebridPlayback",
                        "Using cached resolved link for ${SensitiveLogRedactor.describeHash(infoHash)}"
                    )
                    return Success(cached.url)
                }
                resolvedLinkCache.remove(resolutionCacheKey)
            }
        }

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
        val maxAttempts = 18 // 0.5s+1s+2s then 4s intervals ≈ 63s total budget
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
                            delay(pollDelayMs(attempts))
                        }
                        "error", "dead", "magnet_error" -> {
                            return Error(Exception("Torrent failed with status: $status"))
                        }
                        else -> {
                            delay(pollDelayMs(attempts))
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
        val result = unrestrictLinkWithRetry(videoLink, 3, sourceKey)
        if (result is Success) {
            resolvedLinkCache[resolutionCacheKey] =
                CachedResolution(result.data, System.currentTimeMillis() + RESOLVED_LINK_TTL_MS)
        }
        return result
    }

    // Exponential poll intervals: cached torrents become ready within the first
    // 1-2 polls, so start fast (500ms) and back off to 4s for slow downloads.
    private fun pollDelayMs(attempt: Int): Long =
        if (attempt >= 3) 4000L else 500L shl attempt

    private suspend fun unrestrictLinkWithRetry(link: String, maxRetries: Int, sourceKey: String?): Result<String> {
        var attempts = 0
        while (attempts < maxRetries) {
            realDebridRateLimiter.awaitPermit()
            val unrestrictResult = realDebridRemote.unrestrictLink(link)
            when (unrestrictResult) {
                is Success -> {
                    // A4: guard BLANK too, not just null — a "" download used to become
                    // Success("") and get cached for 15 min (poisoned instant-empty repeats).
                    val streamUrl = unrestrictResult.data.downloadUrl?.takeIf { it.isNotBlank() }
                        ?: unrestrictResult.data.streamingUrl?.takeIf { it.isNotBlank() }
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
            // A3: hinted torrent where no filename matches SxxEyy (absolute numbering,
            // unusual naming). Returning null here failed the WHOLE resolution with
            // "episode not found" even though a playable video exists — fall back to
            // the largest selected video file (single-episode torrents: always right;
            // season packs: best-effort instead of a guaranteed dead-end).
            val largest = scoredLinks.maxByOrNull { it.sizeBytes }
            largest?.let { links.getOrNull(it.index) } ?: links.firstOrNull()
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
            lower.endsWith(".webm") ||
            // A2: .m4v was accepted by isDirectStreamUrl but rejected here — a
            // .m4v-only torrent stalled in waiting_files_selection until timeout.
            lower.endsWith(".m4v")
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
        // Full string, not a truncated prefix: two different magnets sharing the first
        // N chars must never collapse to the same rate-limit/resolution-cache key.
        return hash ?: magnetLink?.trim()?.lowercase()?.takeIf { it.isNotBlank() }
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
