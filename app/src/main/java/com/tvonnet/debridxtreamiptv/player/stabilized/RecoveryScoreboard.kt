package com.tvonnet.debridxtreamiptv.player.stabilized

/**
 * Recovery scoreboard (2026-09-27): how often each self-healing path ran on one player screen, and
 * whether playback actually came back afterwards.
 *
 * The player carries many recoveries (stall, freeze, audio escape, audio-sink, retry, debrid
 * refresh, black-video) and until now nothing said which of them work. Two audio fixes in a row
 * were aimed at the wrong cause for lack of exactly this number.
 *
 * It listens to the diagnostic events those paths already emit (see
 * [com.tvonnet.debridxtreamiptv.debug.PlaybackDiagnosticsRecorder.recoverySink]), so not one line of
 * the recovery code itself changed. An attempt is:
 *  - **ok** when a frame renders again within [RESOLVE_WINDOW_MS] (every re-init path ends in one);
 *  - **failed** on a terminal failure / exhausted verdict, or when the window passes silently;
 *  - **handed off** when the screen returns to the source list to try the next source;
 *  - **abandoned** when the viewer leaves while it is still pending.
 * Chained attempts (a stall that triggers a retry) are each counted, per kind.
 *
 * Pure: the clock is injected, nothing here touches Android. Synchronized, because the recorder is
 * also called from background threads (source loading), even though recovery events are main-thread.
 */
internal class RecoveryScoreboard(private val nowMs: () -> Long) {

    enum class Kind(val key: String) {
        STALL("stall"),
        VIDEO_FREEZE("freeze"),
        AUDIO_ESCAPE("audio_escape"),
        AUDIO_SINK("audio_sink"),
        RETRY("retry"),
        DEBRID_REFRESH("debrid_refresh"),
        BLACK_VIDEO("black_video"),
        UNEXPECTED_PAUSE("unexpected_pause"),
        LIVE_HOLD("live_hold"),
    }

    data class Tally(var tries: Int = 0, var ok: Int = 0, var failed: Int = 0, var handedOff: Int = 0, var abandoned: Int = 0)

    private val tallies = LinkedHashMap<Kind, Tally>()
    private val pending = ArrayList<Pair<Kind, Long>>()

    /** Everything recorded so far, in first-seen order. */
    @Synchronized
    fun tallies(): Map<Kind, Tally> = LinkedHashMap(tallies)

    @Synchronized
    fun onEvent(eventType: String) {
        expire()
        ATTEMPTS[eventType]?.let { kind ->
            tally(kind).tries++
            pending += kind to nowMs()
            return
        }
        when (eventType) {
            FIRST_FRAME -> resolveAll { it.ok++ }
            UNEXPECTED_PAUSE_OK -> resolve(Kind.UNEXPECTED_PAUSE) { it.ok++ }
            UNEXPECTED_PAUSE_FAILED -> resolve(Kind.UNEXPECTED_PAUSE) { it.failed++ }
            in FAILURES -> resolveAll { it.failed++ }
            RETURN_TO_SOURCES -> resolveAll { it.handedOff++ }
        }
    }

    /** The viewer left: whatever is still pending was neither proven nor disproven. */
    @Synchronized
    fun close() {
        expire()
        resolveAll { it.abandoned++ }
    }

    /** `recovery scoreboard mode=vod stall=1/1 retry=2/1/0/0/1 …` as tries/ok/failed/handedOff/abandoned. */
    @Synchronized
    fun logLine(mode: String): String =
        "recovery scoreboard mode=$mode " + if (tallies.isEmpty()) {
            "none"
        } else {
            tallies.entries.joinToString(" ") { (k, t) ->
                "${k.key}=${t.tries}/${t.ok}/${t.failed}/${t.handedOff}/${t.abandoned}"
            } + " (tries/ok/failed/handed_off/abandoned)"
        }

    private fun tally(kind: Kind) = tallies.getOrPut(kind) { Tally() }

    private fun resolveAll(outcome: (Tally) -> Unit) {
        pending.forEach { (kind, _) -> outcome(tally(kind)) }
        pending.clear()
    }

    private fun resolve(kind: Kind, outcome: (Tally) -> Unit) {
        val hit = pending.filter { it.first == kind }
        hit.forEach { outcome(tally(kind)) }
        pending.removeAll(hit.toSet())
    }

    private fun expire() {
        val now = nowMs()
        val stale = pending.filter { now - it.second > RESOLVE_WINDOW_MS }
        stale.forEach { (kind, _) -> tally(kind).failed++ }
        pending.removeAll(stale.toSet())
    }

    companion object {
        const val RESOLVE_WINDOW_MS = 60_000L
        const val FIRST_FRAME = "first_frame_rendered"
        const val RETURN_TO_SOURCES = "return_to_sources"
        const val UNEXPECTED_PAUSE_RESUME = "unexpected_pause_resume"
        const val UNEXPECTED_PAUSE_OK = "unexpected_pause_resume_ok"
        const val UNEXPECTED_PAUSE_FAILED = "unexpected_pause_resume_failed"
        const val LIVE_HOLD_RETRY = "live_hold_retry"

        private val ATTEMPTS = mapOf(
            "stall_triggered" to Kind.STALL,
            "video_freeze_detected" to Kind.VIDEO_FREEZE,
            "audio_wedge_escape" to Kind.AUDIO_ESCAPE,
            "audio_wedge_escape_released" to Kind.AUDIO_ESCAPE,
            "audio_sink_recovery" to Kind.AUDIO_SINK,
            "retry_triggered" to Kind.RETRY,
            "direct_debrid_refresh_started" to Kind.DEBRID_REFRESH,
            "black_video_tunneling_retry" to Kind.BLACK_VIDEO,
            UNEXPECTED_PAUSE_RESUME to Kind.UNEXPECTED_PAUSE,
            LIVE_HOLD_RETRY to Kind.LIVE_HOLD,
        )
        private val FAILURES = setOf("terminal_failure", "audio_sink_exhausted", "audio_wedge_both_routes")
    }
}

/**
 * Hands one screen's scoreboard to the outside world when the screen goes: always one logcat line
 * (the on-device harvest reads it), and - only with the viewer's diagnostics consent (G3) - one
 * `playback_recovery` analytics event per kind that ran. Counts and a mode only: no URL, title or id.
 */
internal object RecoveryScoreboardReporter {

    fun flush(
        context: android.content.Context,
        scoreboard: RecoveryScoreboard,
        mode: String,
        interruptions: PlaybackInterruptionMeter? = null,
    ) {
        scoreboard.close()
        android.util.Log.i("PlayerActivity", scoreboard.logLine(mode))
        interruptions?.let { android.util.Log.i("PlayerActivity", it.logLine(mode)) }
        if (!com.tvonnet.debridxtreamiptv.util.DiagnosticsConsent.isEnabled(context)) return
        val analytics = com.google.firebase.analytics.FirebaseAnalytics.getInstance(context.applicationContext)
        // The number that says whether live buffering got better in the field: stops per hour watched.
        interruptions?.let { m ->
            analytics.logEvent("playback_interruptions", android.os.Bundle().apply {
                putString("mode", mode)
                putInt("count", m.count)
                putLong("stopped_ms", m.stoppedMs)
                putLong("longest_ms", m.longestMs)
            })
        }
        scoreboard.tallies().forEach { (kind, t) ->
            analytics.logEvent("playback_recovery", android.os.Bundle().apply {
                putString("kind", kind.key)
                putString("mode", mode)
                putInt("tries", t.tries)
                putInt("ok", t.ok)
                putInt("failed", t.failed)
                putInt("handed_off", t.handedOff)
                putInt("abandoned", t.abandoned)
            })
        }
    }
}
