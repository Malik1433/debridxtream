package com.tvonnet.debridxtreamiptv.player.stabilized

/**
 * What a finished playback session tells Crashlytics (roadmap G2, 2026-09-03) — pure.
 *
 * Every session sets a handful of custom keys, so the NEXT crash or non-fatal from this
 * device arrives with "how was playback going" attached. Only an *unhealthy* session also
 * files a non-fatal of its own — otherwise ten thousand fine sessions would bury the ones
 * worth reading. Unhealthy = it kept rebuffering, or it never showed a frame, or it errored.
 * Numbers and a mode word only; never a title, a URL, or an account.
 */
object SessionSummaryPolicy {

    const val REBUFFER_UNHEALTHY = 3
    const val MIN_SESSION_SEC_FOR_NO_FRAME = 20L

    data class Summary(
        val mode: String,
        val sessionSec: Long,
        val rebufferCount: Int,
        val droppedFrames: Int,
        val errorCount: Int,
        val firstFrameSeen: Boolean,
        val ttffMs: Long?,
    )

    fun isUnhealthy(s: Summary): Boolean =
        s.errorCount > 0 ||
            s.rebufferCount >= REBUFFER_UNHEALTHY ||
            (!s.firstFrameSeen && s.sessionSec >= MIN_SESSION_SEC_FOR_NO_FRAME)

    /** The custom keys, all primitives; stable names so dashboards can filter on them. */
    fun keys(s: Summary): Map<String, Any> = linkedMapOf(
        "pb_mode" to s.mode,
        "pb_session_sec" to s.sessionSec,
        "pb_rebuffers" to s.rebufferCount,
        "pb_dropped_frames" to s.droppedFrames,
        "pb_errors" to s.errorCount,
        "pb_first_frame" to s.firstFrameSeen,
        "pb_ttff_ms" to (s.ttffMs ?: -1L),
    )

    /** The one-line message of the non-fatal; grouped by Crashlytics on the exception type. */
    fun message(s: Summary): String =
        "playback_session mode=${s.mode} sec=${s.sessionSec} rebuffers=${s.rebufferCount} " +
            "dropped=${s.droppedFrames} errors=${s.errorCount} firstFrame=${s.firstFrameSeen} ttff=${s.ttffMs ?: -1}"
}
