package com.tvonnet.debridxtreamiptv.player.stabilized

import android.os.SystemClock
import android.util.Log
import android.widget.Toast
import androidx.media3.common.Player
import com.tvonnet.debridxtreamiptv.R
import com.tvonnet.debridxtreamiptv.data.model.ContentType
import com.tvonnet.debridxtreamiptv.debug.PlaybackDiagnosticsRecorder

/**
 * F1 Phase 2 (docs/reports/F1_UNEXPECTED_PAUSE_DESIGN.md, owner chose auto-resume 2026-09-27): a VOD
 * that pauses by itself must not sit at PAUSED forever where no watchdog looks.
 *
 * Only a pause nobody asked for is "unexpected", and every rule below exists to keep the guard away
 * from a pause somebody DID ask for:
 *  - television only: on a phone a touch on the pause button arrives with no key event, so the
 *    viewer's own pause could not be told apart (CLAUDE.md: two platforms, two rulebooks);
 *  - never Live TV (it has its own recovery) and never while the screen is not resumed (the app
 *    going to the background pauses on purpose);
 *  - a key pressed just before it = the viewer's pause; a MediaSession command just before it =
 *    Alexa / HDMI-CEC / another app, a deliberate pause; audio-focus loss = another app is talking;
 *  - resume ONCE; a second unexpected pause within [REPEAT_WINDOW_MS] shows a hint instead of
 *    fighting whatever keeps pausing it.
 *
 * Pure decision logic; [reactToUnexpectedPause] is the Android side.
 */
internal class PlayerUnexpectedPauseGuard(private val nowMs: () -> Long) {

    enum class Decision { IGNORE, RESUME, SHOW_HINT }

    private var lastResumeAtMs = NEVER

    /** Everything [onPause] weighs - one field per rule in the class doc. Ages are -1 when never. */
    data class Pause(
        val reason: Int,
        val isTelevision: Boolean,
        val isLive: Boolean,
        val isScreenResumed: Boolean,
        val lastKeyAgeMs: Long,
        val systemCommandAgeMs: Long,
    )

    fun onPause(pause: Pause): Decision = with(pause) {
        if (!isTelevision || isLive || !isScreenResumed) return Decision.IGNORE
        if (reason !in UNEXPECTED_REASONS) return Decision.IGNORE
        if (reason == Player.PLAY_WHEN_READY_CHANGE_REASON_USER_REQUEST &&
            (recent(lastKeyAgeMs, USER_KEY_WINDOW_MS) || recent(systemCommandAgeMs, SYSTEM_COMMAND_WINDOW_MS))
        ) {
            return Decision.IGNORE
        }
        val now = nowMs()
        if (lastResumeAtMs != NEVER && now - lastResumeAtMs < REPEAT_WINDOW_MS) return Decision.SHOW_HINT
        lastResumeAtMs = now
        return Decision.RESUME
    }

    private fun recent(ageMs: Long, windowMs: Long) = ageMs in 0..windowMs

    companion object {
        const val RESUME_DELAY_MS = 2_000L
        const val VERIFY_DELAY_MS = 3_000L
        const val USER_KEY_WINDOW_MS = 1_500L
        const val SYSTEM_COMMAND_WINDOW_MS = 1_500L
        const val REPEAT_WINDOW_MS = 60_000L
        private const val NEVER = Long.MIN_VALUE

        // USER_REQUEST stays in: a pause from our own code or a session command both arrive as it,
        // and the key / system-command windows above are what tell those apart.
        private val UNEXPECTED_REASONS = setOf(
            Player.PLAY_WHEN_READY_CHANGE_REASON_USER_REQUEST,
            Player.PLAY_WHEN_READY_CHANGE_REASON_AUDIO_BECOMING_NOISY,
            6, // PLAY_WHEN_READY_CHANGE_REASON_SUPPRESSED_TOO_LONG
        )
    }
}

/**
 * The guard's Android side, called from the player listener when playWhenReady goes false. Waits
 * [PlayerUnexpectedPauseGuard.RESUME_DELAY_MS], re-checks that nothing changed (same player, still
 * paused, screen still resumed, no key pressed since), resumes, and records the attempt and its
 * outcome for the recovery scoreboard.
 */
internal fun reactToUnexpectedPause(activity: BasePlayerFragment, session: PlayerSessionState, reason: Int) {
    val now = SystemClock.elapsedRealtime()
    val decision = activity.unexpectedPauseGuard.onPause(PlayerUnexpectedPauseGuard.Pause(
        reason = reason,
        isTelevision = activity.isTelevision,
        isLive = session.contentType == ContentType.LIVE_TV,
        isScreenResumed = activity.isResumed,
        lastKeyAgeMs = if (session.lastKeyAtMs > 0L) now - session.lastKeyAtMs else -1L,
        systemCommandAgeMs = PlayerMediaSessionManager.lastSystemCommandAtMs.let { if (it > 0L) now - it else -1L },
    ))
    when (decision) {
        PlayerUnexpectedPauseGuard.Decision.IGNORE -> Unit
        PlayerUnexpectedPauseGuard.Decision.SHOW_HINT -> {
            Log.w("PlayerActivity", "unexpected pause again within 60 s - not resuming, asking the viewer")
            Toast.makeText(activity.requireContext(), R.string.c_unexpected_pause_hint, Toast.LENGTH_LONG).show()
        }
        PlayerUnexpectedPauseGuard.Decision.RESUME -> scheduleResume(activity, session, now)
    }
}

private fun scheduleResume(activity: BasePlayerFragment, session: PlayerSessionState, pausedAtMs: Long) {
    val pausedPlayer = activity.player ?: return
    activity.retryHandler.postDelayed({
        val stillPaused = activity.player === pausedPlayer && !pausedPlayer.playWhenReady && activity.isResumed
        if (!stillPaused || session.lastKeyAtMs > pausedAtMs) return@postDelayed
        Log.w("PlayerActivity", "unexpected pause - resuming once (F1 Phase 2)")
        PlaybackDiagnosticsRecorder.record(activity.requireContext(), RecoveryScoreboard.UNEXPECTED_PAUSE_RESUME)
        pausedPlayer.play()
        activity.retryHandler.postDelayed({
            if (activity.player !== pausedPlayer || !activity.isAdded) return@postDelayed
            val playing = pausedPlayer.playWhenReady && pausedPlayer.playbackState != Player.STATE_IDLE
            PlaybackDiagnosticsRecorder.record(
                activity.requireContext(),
                if (playing) RecoveryScoreboard.UNEXPECTED_PAUSE_OK else RecoveryScoreboard.UNEXPECTED_PAUSE_FAILED
            )
        }, PlayerUnexpectedPauseGuard.VERIFY_DELAY_MS)
    }, PlayerUnexpectedPauseGuard.RESUME_DELAY_MS)
}
