package com.tvonnet.debridxtreamiptv.player.stabilized

import com.tvonnet.debridxtreamiptv.data.model.ContentType

/**
 * The playback-error taxonomy (Phase P16 — the second half of the scope §8 had
 * blocked, reopened by the owner on 2026-07-22).
 *
 * Only the CLASSIFICATION lives here. Every reaction stays in [PlayerActivity]:
 * the audio-sink recovery, the debrid re-resolve, the release/re-init and the
 * terminal-failure routing are landmine territory and were not touched.
 */

/** HTTP codes that never recover by retrying — the listing is gone or refuses us. */
private val TERMINAL_HTTP_CODES = setOf(400, 401, 403, 404, 405, 410, 451)

/**
 * Fail fast for non-live sources on a definitive HTTP error.
 *
 * Before this rule existed, plain IPTV/Xtream playback had no terminal-HTTP path at
 * all (only direct-debrid did), so a dead listing — a provider answering 405/500 for
 * an unavailable movie id — looped the "RECONNECTING…" banner until the whole retry
 * budget drained. Live TV is excluded: there a 4xx/5xx is usually a transient blip.
 * 429 is excluded too — it gets a cool-off instead.
 */
internal fun isTerminalHttpForNonLive(
    responseCode: Int?,
    contentType: ContentType?,
    playbackSource: PlaybackSource
): Boolean {
    if (contentType == ContentType.LIVE_TV) return false
    val code = responseCode ?: return false
    val terminal4xx = code in TERMINAL_HTTP_CODES
    // A 5xx from an Xtream panel is a dead listing, not a transient CDN blip
    // (debrid CDNs and live streams keep retrying).
    val terminalIptv5xx = playbackSource == PlaybackSource.IPTV && code in 500..599
    return terminal4xx || terminalIptv5xx
}

/**
 * HTTP 429 on plain IPTV/Xtream: the provider's WAF is rate-limiting this IP, so fast
 * retries only deepen the ban and the caller must cool off instead. Direct-debrid
 * treats 429 as terminal separately.
 */
internal fun isRateLimitCoolOff(responseCode: Int?, directDebridPlayback: Boolean): Boolean =
    responseCode == 429 && !directDebridPlayback

/** Live TV gets its own (larger) retry allowance. */
internal fun maxRetriesFor(
    contentType: ContentType?,
    liveMaxRetries: Int,
    defaultMaxRetries: Int
): Int = if (contentType == ContentType.LIVE_TV) liveMaxRetries else defaultMaxRetries
