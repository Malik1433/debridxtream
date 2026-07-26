package com.tvonnet.debridxtreamiptv.player.stabilized

import android.os.Handler
import android.os.Looper
import android.widget.TextView
import androidx.media3.common.Player
import java.util.concurrent.TimeUnit

/**
 * The 1 Hz developer overlay lifted out of [PlayerActivity] (Phase P8).
 *
 * The Activity supplies a [PlayerDebugSnapshot] on demand; a null snapshot means
 * "nothing to render" (overlay disabled, no player, or the Activity is going away)
 * and stops the loop, exactly like the original runnable's early returns.
 */
internal data class PlayerDebugSnapshot(
    val playbackState: Int,
    val playWhenReady: Boolean,
    val positionMs: Long,
    val bufferedPositionMs: Long,
    val switchCount: Int,
    val playbackSource: PlaybackSource,
    val url: String?
)

/** The four overlay lines. Pure, so the formatting is unit-testable. */
internal fun buildDebugOverlayText(snapshot: PlayerDebugSnapshot): String {
    val stateStr = when (snapshot.playbackState) {
        Player.STATE_IDLE -> "IDLE"
        Player.STATE_BUFFERING -> "BUFFER"
        Player.STATE_READY -> "READY"
        Player.STATE_ENDED -> "ENDED"
        else -> "UNKNOWN"
    }
    val posStr = formatClock(snapshot.positionMs)
    val bufStr = formatClock(snapshot.bufferedPositionMs)
    val urlRaw = snapshot.url ?: "Null"
    val shortUrl = if (urlRaw.length > 55) urlRaw.substring(0, 52) + "..." else urlRaw

    return buildString {
        append("State: $stateStr | Playing: ${snapshot.playWhenReady}\n")
        append("Pos: $posStr | Buf: $bufStr\n")
        append("Switches: ${snapshot.switchCount} | Src: ${snapshot.playbackSource}\n")
        append("URL: $shortUrl")
    }
}

private fun formatClock(millis: Long): String = String.format(
    "%02d:%02d",
    TimeUnit.MILLISECONDS.toMinutes(millis),
    TimeUnit.MILLISECONDS.toSeconds(millis) -
        TimeUnit.MINUTES.toSeconds(TimeUnit.MILLISECONDS.toMinutes(millis))
)

internal class PlayerDebugOverlay(
    private val target: () -> TextView?,
    private val snapshot: () -> PlayerDebugSnapshot?
) {

    private val handler = Handler(Looper.getMainLooper())

    private val runnable = object : Runnable {
        override fun run() {
            if (!refresh()) return
            handler.postDelayed(this, 1000)
        }
    }

    /** Restart the 1 Hz loop, but only while there is something to render. */
    fun start() {
        handler.removeCallbacks(runnable)
        if (snapshot() != null) {
            handler.post(runnable)
        }
    }

    fun stop() = handler.removeCallbacks(runnable)

    /** Renders one frame; returns false when there is nothing to render any more. */
    fun refresh(): Boolean {
        val current = snapshot() ?: return false
        try {
            target()?.text = buildDebugOverlayText(current)
        } catch (e: Exception) {
            // The overlay must never take playback down with it — but log why it failed.
            android.util.Log.w("PlayerDebugOverlay", "Debug overlay update failed", e)
        }
        return true
    }

    /** Appends a player error under the current overlay text. */
    fun appendError(message: String?) {
        val view = target() ?: return
        view.text = view.text?.toString() + "\nERR: $message"
    }
}
