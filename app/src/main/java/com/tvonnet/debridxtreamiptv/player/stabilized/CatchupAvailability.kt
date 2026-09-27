package com.tvonnet.debridxtreamiptv.player.stabilized

/**
 * Whether "resume where it stopped" may be offered after a Live outage - pure rules, C1 of
 * docs/reports/LIVE_CATCHUP_DESIGN.md.
 *
 * Offered only when every one holds: the outage was long enough to have missed something
 * ([MIN_OUTAGE_MS]); it is still inside the provider's 2-day archive; we know the provider's time
 * zone (the start is named in server time); the channel keeps recordings (EPG has_archive); and the
 * channel's recording has not already failed in this sitting (some tv_archive=1 channels answer 503
 * every time - C0 found one).
 */
internal object CatchupAvailability {

    const val MIN_OUTAGE_MS = 20_000L
    const val ARCHIVE_WINDOW_MS = 2 * 24 * 60 * 60_000L
    /** A recording that already played this long and then errors has run out, not failed. */
    const val NATURAL_END_AFTER_MS = 30_000L

    /**
     * C2-4 (Fire TV QA round 2): a recording asked for just after an outage only covers the minutes
     * the provider had recorded by then, and the provider often ends it by dropping the connection
     * rather than closing the file - ExoPlayer reports that as an error. After real playback it is
     * the end of the recording, not a broken channel, so it must not hide catch-up for the sitting.
     */
    fun isNaturalEnd(playedMs: Long?): Boolean = playedMs != null && playedMs >= NATURAL_END_AFTER_MS

    fun canOfferResume(
        outageStartMs: Long,
        nowMs: Long,
        serverZoneKnown: Boolean,
        channelHasArchive: Boolean?,
        failedThisSitting: Boolean,
    ): Boolean {
        val missed = nowMs - outageStartMs
        return missed >= MIN_OUTAGE_MS &&
            missed <= ARCHIVE_WINDOW_MS &&
            serverZoneKnown &&
            channelHasArchive == true &&
            !failedThisSitting
    }
}
