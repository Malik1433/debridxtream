package com.tvonnet.debridxtreamiptv.data.debrid.repository

import com.tvonnet.debridxtreamiptv.data.Result
import com.tvonnet.debridxtreamiptv.data.Result.Error
import com.tvonnet.debridxtreamiptv.data.Result.Success
import com.tvonnet.debridxtreamiptv.data.debrid.model.DebridFailureClassifier
import com.tvonnet.debridxtreamiptv.data.debrid.model.RealDebridTorrentInfoResponse
import com.tvonnet.debridxtreamiptv.data.debrid.source.RealDebridRemoteDataSource
import com.tvonnet.debridxtreamiptv.util.SensitiveLogRedactor
import kotlinx.coroutines.delay

/**
 * C6: magnet / infoHash → a URL the player can open, via Real-Debrid.
 *
 * The chain is add-magnet → poll until the torrent is ready → select the wanted file → unrestrict,
 * and every step here exists because a provider misbehaved at it:
 *  - **Poll backoff is front-loaded** (500ms, 1s, 2s, then 4s): a *cached* torrent is ready within
 *    one or two polls, so a flat interval made every cached play feel slow. The 18-attempt budget
 *    is ~63s.
 *  - **Re-add once** when the torrent reports "downloaded" but the wanted episode is not among the
 *    selected files — Real-Debrid remembers a previous partial selection, and deleting/re-adding is
 *    the only way to clear it.
 *  - **A4 blank guard on unrestrict**: an empty `download` used to become `Success("")` and get
 *    cached for 15 minutes, so the source failed instantly and repeatedly.
 *  - **Every RD call goes through the rate limiter**, and terminal failures are recorded so the
 *    source goes on cooldown rather than being retried into a ban.
 *
 * Bodies moved verbatim from `DebridPlaybackRepository`.
 */
internal class DebridLinkResolver(
    private val realDebridRemote: RealDebridRemoteDataSource,
    private val debridAccountRepository: DebridAccountRepository,
    private val realDebridRateLimiter: RealDebridRateLimiter,
) {
    private companion object {
        const val RESOLVED_LINK_TTL_MS = 15 * 60 * 1000L
        const val MAX_POLL_ATTEMPTS = 18 // 0.5s+1s+2s then 4s intervals ≈ 63s total budget
        const val UNRESTRICT_MAX_RETRIES = 3
        val READY_STATUSES = listOf("downloaded", "ready")
        val DEAD_STATUSES = listOf("error", "dead", "magnet_error")
    }

    private data class CachedResolution(val url: String, val expiresAtMs: Long)

    private val resolvedLinkCache = java.util.concurrent.ConcurrentHashMap<String, CachedResolution>()

    /**
     * Resolve a magnet/infoHash to a streamable URL via Real-Debrid.
     *
     * Successful resolutions are cached for [RESOLVED_LINK_TTL_MS] so replays/episode restarts skip
     * the full add-magnet→poll→unrestrict chain. Pass [bypassCache]=true on retries so a dead link
     * is never served back from cache.
     */
    @Suppress("LongParameterList") // mirrors the public repository API, which callers depend on
    suspend fun resolve(
        infoHash: String?,
        magnet: String?,
        seasonNumber: Int?,
        episodeNumber: Int?,
        episodeTitle: String?,
        allowDirectStreamUrlPassthrough: Boolean,
        bypassCache: Boolean
    ): Result<String> {
        if (infoHash.isNullOrBlank() && magnet.isNullOrBlank()) {
            return Error(Exception("No infoHash or magnet provided"))
        }

        directStreamOutcome(infoHash, magnet, allowDirectStreamUrlPassthrough)?.let { return it }

        val magnetLink = magnetLinkFor(infoHash, magnet)
        val sourceKey = DebridUrlRules.normalizeSourceKey(infoHash, magnetLink)
        val episodeHint = episodeHintOf(seasonNumber, episodeNumber, episodeTitle)
        val cacheKey = resolutionCacheKey(sourceKey, episodeHint)

        cachedLink(cacheKey, bypassCache, infoHash)?.let { return Success(it) }
        checkCooldowns(sourceKey)?.let { return it }
        checkAuth()?.let { return it }

        // An http(s) link that is not a .torrent is already a file RD can unrestrict directly.
        if (isUnrestrictableHttpLink(magnetLink)) {
            android.util.Log.d(
                "DebridPlayback",
                "HTTP link requires Real-Debrid unrestrict: ${SensitiveLogRedactor.describeUrl(magnetLink)}"
            )
            return unrestrictLinkWithRetry(magnetLink, UNRESTRICT_MAX_RETRIES, sourceKey)
        }

        return resolveViaTorrent(TorrentContext(magnetLink, sourceKey, episodeHint), cacheKey)
    }

    /** Everything that stays fixed for one resolution attempt. */
    private data class TorrentContext(
        val magnetLink: String,
        val sourceKey: String?,
        val episodeHint: EpisodeHint?,
    )

    // ── preflight ──────────────────────────────────────────────────────────────

    private fun episodeHintOf(season: Int?, episode: Int?, title: String?): EpisodeHint? =
        if (season != null && episode != null) EpisodeHint(season, episode, title) else null

    /** An addon URL that is already playable, minus `.torrent` files which still need RD. */
    private fun directStreamUrlOf(magnet: String?): String? = magnet?.takeIf {
        DebridUrlRules.isDirectStreamUrl(it) && !it.endsWith(".torrent", ignoreCase = true)
    }

    /**
     * @return the passthrough result, the "needs fresh metadata" refusal, or null to keep going.
     *
     * A resume replays a stored URL that may have expired, so history passes
     * `allowDirectStreamUrlPassthrough = false` and the link is re-resolved from the infoHash.
     */
    private fun directStreamOutcome(
        infoHash: String?,
        magnet: String?,
        allowPassthrough: Boolean
    ): Result<String>? {
        val directStreamUrl = directStreamUrlOf(magnet) ?: return null
        if (allowPassthrough) {
            android.util.Log.d(
                "DebridPlayback",
                "Direct addon URL detected, using fresh passthrough: ${SensitiveLogRedactor.describeUrl(directStreamUrl)}"
            )
            return Success(directStreamUrl)
        }
        if (infoHash.isNullOrBlank()) {
            return Error(Exception("Direct addon URL requires fresh metadata"))
        }
        return null
    }

    private fun isUnrestrictableHttpLink(link: String): Boolean =
        link.startsWith("http", ignoreCase = true) && !link.endsWith(".torrent", ignoreCase = true)

    /**
     * A `.torrent` URL or a direct addon URL still carries the infoHash, and RD wants a magnet —
     * so rebuild one rather than handing it the http link.
     */
    private fun magnetLinkFor(infoHash: String?, magnet: String?): String {
        val isTorrentUrl = !magnet.isNullOrBlank() &&
            magnet.startsWith("http", ignoreCase = true) &&
            magnet.endsWith(".torrent", ignoreCase = true)
        val rebuildFromHash = !infoHash.isNullOrBlank() &&
            (isTorrentUrl || directStreamUrlOf(magnet) != null)
        return when {
            rebuildFromHash -> "magnet:?xt=urn:btih:$infoHash"
            !magnet.isNullOrBlank() -> magnet
            else -> "magnet:?xt=urn:btih:$infoHash"
        }
    }

    /** Episode-scoped: a season pack resolves to a different link per episode. */
    private fun resolutionCacheKey(sourceKey: String?, hint: EpisodeHint?): String = buildString {
        append(sourceKey)
        hint?.let { append(":s").append(it.season).append("e").append(it.episode) }
    }

    private fun cachedLink(cacheKey: String, bypassCache: Boolean, infoHash: String?): String? {
        if (bypassCache) {
            resolvedLinkCache.remove(cacheKey)
            return null
        }
        val cached = resolvedLinkCache[cacheKey] ?: return null
        if (cached.expiresAtMs > System.currentTimeMillis()) {
            android.util.Log.d(
                "DebridPlayback",
                "Using cached resolved link for ${SensitiveLogRedactor.describeHash(infoHash)}"
            )
            return cached.url
        }
        resolvedLinkCache.remove(cacheKey)
        return null
    }

    private fun checkCooldowns(sourceKey: String?): Error? {
        realDebridRateLimiter.globalCooldown()?.let { cooldown ->
            android.util.Log.w("DebridPlayback", "Skipping RD playback during global cooldown")
            return Error(cooldown)
        }
        realDebridRateLimiter.sourceCooldown(sourceKey)?.let { cooldown ->
            android.util.Log.w("DebridPlayback", "Skipping RD source on cooldown: ${cooldown.type}")
            return Error(cooldown)
        }
        return null
    }

    private suspend fun checkAuth(): Error? {
        debridAccountRepository.getCachedAuthState()
            ?: return Error(
                com.tvonnet.debridxtreamiptv.data.debrid.model.NotAuthenticatedException(
                    "Real-Debrid configuration missing"
                )
            )
        val refreshResult = debridAccountRepository.refreshAuthStateIfNeeded()
        if (refreshResult is Error && shouldForceReauth(refreshResult.exception)) {
            return Error(
                com.tvonnet.debridxtreamiptv.data.debrid.model.NotAuthenticatedException(
                    "Real-Debrid session expired"
                )
            )
        }
        return null
    }

    // ── the torrent path ─────────────────────────────────────────────────────

    private suspend fun resolveViaTorrent(ctx: TorrentContext, cacheKey: String): Result<String> {
        realDebridRateLimiter.awaitPermit()
        val addResult = realDebridRemote.addMagnet(ctx.magnetLink)
        val torrentId = when (addResult) {
            is Success -> addResult.data.id
            is Error -> return handleDebridFailure("add magnet", ctx.sourceKey, addResult.exception)
            else -> null
        } ?: return Error(Exception("No torrent ID returned"))

        val polled = when (val outcome = pollUntilReady(torrentId, ctx)) {
            is PollOutcome.Failed -> return outcome.error
            is PollOutcome.Ready -> outcome
        }

        val torrentInfo = polled.info
        if (torrentInfo == null || torrentInfo.status?.lowercase() !in READY_STATUSES) {
            return Error(Exception("Torrent not ready after $MAX_POLL_ATTEMPTS attempts"))
        }

        val videoLink = polled.videoLink
            ?: TorrentFileMatcher.pickVideoLink(torrentInfo, ctx.episodeHint)
            ?: return Error(Exception("No links available in torrent for requested episode"))

        val result = unrestrictLinkWithRetry(videoLink, UNRESTRICT_MAX_RETRIES, ctx.sourceKey)
        if (result is Success) {
            resolvedLinkCache[cacheKey] =
                CachedResolution(result.data, System.currentTimeMillis() + RESOLVED_LINK_TTL_MS)
        }
        return result
    }

    private sealed interface PollOutcome {
        /** The loop finished; [info] and [videoLink] are whatever it ended up with. */
        data class Ready(val info: RealDebridTorrentInfoResponse?, val videoLink: String?) : PollOutcome

        data class Failed(val error: Error) : PollOutcome
    }

    /** What the "downloaded" branch decided. */
    private sealed interface ReadyStep {
        data class Play(val link: String) : ReadyStep
        data class ReAdded(val torrentId: String) : ReadyStep
        data class Failed(val error: Error) : ReadyStep
    }

    /** Poll RD until the torrent is downloaded, selecting files and re-adding once if needed. */
    private suspend fun pollUntilReady(initialTorrentId: String, ctx: TorrentContext): PollOutcome {
        var torrentId = initialTorrentId
        var attempts = 0
        var torrentInfo: RealDebridTorrentInfoResponse? = null
        var hasReAddedTorrent = false

        while (attempts < MAX_POLL_ATTEMPTS) {
            realDebridRateLimiter.awaitPermit()
            val infoResult = realDebridRemote.getTorrentInfo(torrentId)
            if (infoResult is Error) {
                return PollOutcome.Failed(handleDebridFailure("get torrent info", ctx.sourceKey, infoResult.exception))
            }
            if (infoResult !is Success) {
                return PollOutcome.Failed(Error(Exception("Unknown error during torrent info fetch")))
            }

            torrentInfo = infoResult.data
            val status = torrentInfo.status?.lowercase()
            if (status in READY_STATUSES) {
                when (val step = onTorrentDownloaded(torrentId, ctx, hasReAddedTorrent, torrentInfo)) {
                    is ReadyStep.Play -> return PollOutcome.Ready(torrentInfo, step.link)
                    is ReadyStep.Failed -> return PollOutcome.Failed(step.error)
                    is ReadyStep.ReAdded -> {
                        torrentId = step.torrentId
                        hasReAddedTorrent = true
                        attempts = 0
                        continue
                    }
                }
            }
            if (status in DEAD_STATUSES) {
                return PollOutcome.Failed(Error(Exception("Torrent failed with status: $status")))
            }
            if (status == "waiting_files_selection") {
                selectFiles(torrentId, torrentInfo, ctx)?.let { return PollOutcome.Failed(it) }
            }
            delay(pollDelayMs(attempts))
            attempts++
        }
        return PollOutcome.Ready(torrentInfo, null)
    }

    /**
     * The torrent says it is downloaded. Either the wanted episode is among the selected files, or
     * Real-Debrid is remembering an earlier partial selection and the torrent has to be deleted and
     * re-added to clear it — which is done at most once per resolution.
     */
    private suspend fun onTorrentDownloaded(
        torrentId: String,
        ctx: TorrentContext,
        hasReAddedTorrent: Boolean,
        torrentInfo: RealDebridTorrentInfoResponse,
    ): ReadyStep {
        TorrentFileMatcher.pickVideoLink(torrentInfo, ctx.episodeHint)?.let { return ReadyStep.Play(it) }
        if (hasReAddedTorrent) {
            return ReadyStep.Failed(Error(Exception("Desired episode not found in torrent after re-adding")))
        }
        android.util.Log.w("DebridPlayback", "Episode not found in selected files. Deleting and re-adding torrent.")
        realDebridRateLimiter.awaitPermit()
        realDebridRemote.deleteTorrent(torrentId)
        realDebridRateLimiter.awaitPermit()
        val reAddResult = realDebridRemote.addMagnet(ctx.magnetLink)
        if (reAddResult is Success) {
            return reAddResult.data.id?.let { ReadyStep.ReAdded(it) }
                ?: ReadyStep.Failed(Error(Exception("No torrent ID returned on re-add")))
        }
        val error = (reAddResult as? Error)?.exception
            ?: Exception("Failed to re-add magnet after deletion")
        return ReadyStep.Failed(handleDebridFailure("re-add magnet", ctx.sourceKey, error))
    }

    /** @return an [Error] to abort with, or null to keep polling. */
    private suspend fun selectFiles(
        torrentId: String,
        torrentInfo: RealDebridTorrentInfoResponse,
        ctx: TorrentContext,
    ): Error? {
        val fileIds = TorrentFileMatcher.selectFileIds(torrentInfo.files, ctx.episodeHint)
        if (fileIds.isEmpty()) return null
        realDebridRateLimiter.awaitPermit()
        val selectResult = realDebridRemote.selectFiles(torrentId, fileIds.joinToString(","))
        if (selectResult is Error) {
            return handleDebridFailure("select files", ctx.sourceKey, selectResult.exception)
        }
        return null
    }

    // Exponential poll intervals: cached torrents become ready within the first
    // 1-2 polls, so start fast (500ms) and back off to 4s for slow downloads.
    private fun pollDelayMs(attempt: Int): Long =
        if (attempt >= 3) 4000L else 500L shl attempt

    // ── unrestrict ─────────────────────────────────────────────────────────────

    private suspend fun unrestrictLinkWithRetry(link: String, maxRetries: Int, sourceKey: String?): Result<String> {
        var attempts = 0
        while (attempts < maxRetries) {
            realDebridRateLimiter.awaitPermit()
            when (val unrestrictResult = realDebridRemote.unrestrictLink(link)) {
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
                    if (attempts >= maxRetries - 1) return Error(failure)
                    delay(1000L * (attempts + 1))
                }
                else -> {
                    if (attempts >= maxRetries - 1) return Error(Exception("Unknown state during unrestrict"))
                    delay(1000L * (attempts + 1))
                }
            }
            attempts++
        }
        return Error(Exception("Failed to unrestrict link after retries"))
    }

    private fun handleDebridFailure(operation: String, sourceKey: String?, error: Exception): Error {
        val failure = DebridFailureClassifier.classify(error)
        android.util.Log.e("DebridPlayback", "Real-Debrid $operation failed: ${failure.type}")
        realDebridRateLimiter.recordFailure(sourceKey, failure)
        return Error(failure)
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
}
