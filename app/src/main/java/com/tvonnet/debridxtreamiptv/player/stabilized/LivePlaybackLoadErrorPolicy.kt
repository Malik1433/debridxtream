package com.tvonnet.debridxtreamiptv.player.stabilized

import androidx.media3.common.C
import androidx.media3.datasource.HttpDataSource
import androidx.media3.exoplayer.upstream.DefaultLoadErrorHandlingPolicy
import androidx.media3.exoplayer.upstream.LoadErrorHandlingPolicy

/**
 * Shared playback load-error policy (LP-ADOPT).
 *
 * Extracted so the EPG guide's preview player ([com.tvonnet.debridxtreamiptv.ui.live.PreviewPlayerPanel])
 * is built with the SAME error handling as the fullscreen [PlayerActivity]. That
 * lets the guide→fullscreen warm hand-off ADOPT the already-running preview
 * instance (no release→reconnect, so no 403 storm on max_connections=1 accounts)
 * while still carrying the 429 cool-off / fail-fast behaviour a live session needs.
 *
 * - Permanently-broken sources (401/403/404/410/416/451) → fail fast (no retry) so
 *   app-level fallback kicks in quickly.
 * - HTTP 429 (provider WAF rate-limit) → cool off hard: honor `Retry-After` if
 *   present, else a fixed 20s. Standard fast backoff only deepens the ban.
 * - Everything else → capped exponential backoff (1s..8s) with jitter.
 *
 * Keep this in lockstep with any equivalent logic in [PlayerActivity].
 */
class LivePlaybackLoadErrorPolicy : DefaultLoadErrorHandlingPolicy() {
    override fun getRetryDelayMsFor(loadErrorInfo: LoadErrorHandlingPolicy.LoadErrorInfo): Long {
        val responseCode =
            (loadErrorInfo.exception as? HttpDataSource.InvalidResponseCodeException)?.responseCode
        if (responseCode == 401 || responseCode == 403 || responseCode == 404 ||
            responseCode == 410 || responseCode == 416 || responseCode == 451
        ) {
            return C.TIME_UNSET
        }
        if (responseCode == 429) {
            val headers =
                (loadErrorInfo.exception as? HttpDataSource.InvalidResponseCodeException)?.headerFields
            val retryAfterSec = headers?.entries
                ?.firstOrNull { it.key?.equals("Retry-After", ignoreCase = true) == true }
                ?.value?.firstOrNull()?.trim()?.toLongOrNull()
            return retryAfterSec?.let { (it * 1000L).coerceIn(1000L, 60_000L) } ?: HTTP_429_COOLOFF_MS
        }
        val exponentialMs = 1000L shl (loadErrorInfo.errorCount - 1).coerceIn(0, 3)
        return exponentialMs + (0..400).random()
    }

    companion object {
        private const val HTTP_429_COOLOFF_MS = 20_000L
    }
}
