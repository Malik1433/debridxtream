package com.tvonnet.debridxtreamiptv.player.stabilized

/**
 * Every time the picture stopped when the viewer did not ask it to, and for how long (2026-09-28).
 *
 * The QoE `rebuffer_count` only sees Media3's own rebuffers. A network drop is a player ERROR, and
 * the app then rebuilds the player - so on every Fire TV outage test it read 0 while the screen
 * had plainly stopped three times. This meter lives in the screen's session, so it spans those
 * rebuilds: an interruption opens when playback stops with the viewer still wanting it to play,
 * and closes when it plays again, whatever path brought it back.
 *
 * Not interruptions: a pause (playWhenReady false), and the stop that follows the viewer's own
 * zap or seek ([onUserChange]). Pure; the clock is injected.
 */
internal class PlaybackInterruptionMeter(private val nowMs: () -> Long) {

    var count = 0
        private set
    var stoppedMs = 0L
        private set
    var longestMs = 0L
        private set
    private var playingMs = 0L
    private var playingSinceMs = NONE
    private var interruptedSinceMs = NONE
    private var userChangePending = false

    fun onPlaying() {
        val now = nowMs()
        closeInterruption(now)
        userChangePending = false
        if (playingSinceMs == NONE) playingSinceMs = now
    }

    /** @param viewerStillWantsPlay the player's playWhenReady: false means a pause, not an interruption. */
    fun onStopped(viewerStillWantsPlay: Boolean) {
        val now = nowMs()
        closePlaying(now)
        if (!viewerStillWantsPlay || userChangePending || interruptedSinceMs != NONE) return
        count++
        interruptedSinceMs = now
    }

    /** The viewer zapped or sought: the stop that follows is theirs. */
    fun onUserChange() {
        closeInterruption(nowMs())
        userChangePending = true
    }

    /** `interruptions count=… stopped_ms=… longest_ms=… playing_ms=… per_hour=…` for the harvest. */
    fun logLine(mode: String): String {
        val now = nowMs()
        closeInterruption(now)
        closePlaying(now)
        val perHour = if (playingMs > 0) count * HOUR_MS / playingMs else 0
        return "interruptions mode=$mode count=$count stopped_ms=$stoppedMs longest_ms=$longestMs " +
            "playing_ms=$playingMs per_hour=$perHour"
    }

    private fun closePlaying(now: Long) {
        if (playingSinceMs == NONE) return
        playingMs += now - playingSinceMs
        playingSinceMs = NONE
    }

    private fun closeInterruption(now: Long) {
        if (interruptedSinceMs == NONE) return
        val lasted = now - interruptedSinceMs
        stoppedMs += lasted
        if (lasted > longestMs) longestMs = lasted
        interruptedSinceMs = NONE
    }

    private companion object {
        const val NONE = Long.MIN_VALUE
        const val HOUR_MS = 3_600_000L
    }
}
