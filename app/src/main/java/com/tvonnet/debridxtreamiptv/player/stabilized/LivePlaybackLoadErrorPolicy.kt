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
        val http = loadErrorInfo.exception as? HttpDataSource.InvalidResponseCodeException
        val retryAfterSec = http?.headerFields?.entries
            ?.firstOrNull { it.key?.equals("Retry-After", ignoreCase = true) == true }
            ?.value?.firstOrNull()?.trim()?.toLongOrNull()

        // The decision itself is a pure function so the max_connections=1 protections (fail-fast on a
        // dead source, hard cool-off on 429) are unit-tested — see LiveRetryDelayTest.
        val delayMs = liveRetryDelayMsFor(http?.responseCode, retryAfterSec, loadErrorInfo.errorCount)

        // Jitter only the exponential-backoff case; never smear a fail-fast or a cool-off.
        return if (delayMs == C.TIME_UNSET || http?.responseCode == 429) {
            delayMs
        } else {
            delayMs + (0..400).random()
        }
    }
}
