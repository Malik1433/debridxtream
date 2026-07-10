package com.tvonnet.debridxtreamiptv.player.stabilized

import android.graphics.Bitmap
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.media3.exoplayer.ExoPlayer

/**
 * Hands ONE live ExoPlayer between the EPG guide's mini preview and the
 * fullscreen [PlayerActivity], so the stream keeps playing across the
 * transition (no reconnect / rebuffer in either direction).
 *
 * Ownership: whoever calls [adopt] owns the player. If an offered player is
 * never adopted (e.g. the target screen never started), a failsafe timeout
 * releases it so audio can't keep playing in the background.
 */
object LiveSharedPlayer {

    private const val TAG = "LiveSharedPlayer"
    private const val ADOPT_TIMEOUT_MS = 8_000L

    private val handler = Handler(Looper.getMainLooper())
    private var player: ExoPlayer? = null

    var streamUrl: String? = null
        private set
    var streamId: String? = null
        private set

    /**
     * Snapshot of the LAST rendered video frame at hand-off time. The adopting
     * screen shows it as a cover until its own surface renders the first frame,
     * so the transition never flashes black.
     */
    private var frame: Bitmap? = null

    private val timeoutRunnable = Runnable { release("adopt_timeout") }

    /** Park a RUNNING player for the next screen to adopt. */
    fun offer(p: ExoPlayer, url: String?, id: String?, frameBitmap: Bitmap? = null) {
        if (player !== p) release("replaced")
        player = p
        streamUrl = url
        streamId = id
        frame = frameBitmap
        handler.removeCallbacks(timeoutRunnable)
        handler.postDelayed(timeoutRunnable, ADOPT_TIMEOUT_MS)
        Log.i(TAG, "offer: id=$id frame=${frameBitmap != null}")
    }

    /** One-shot: the hand-off frame snapshot (cleared on take). */
    fun takeFrame(): Bitmap? {
        val f = frame
        frame = null
        return f
    }

    /**
     * Take ownership of the parked player. When [expectedUrl] is given it must
     * match what was offered (guards against adopting a stale stream).
     */
    fun adopt(expectedUrl: String? = null): ExoPlayer? {
        val p = player ?: return null
        if (expectedUrl != null && streamUrl != null && expectedUrl != streamUrl) {
            Log.i(TAG, "adopt: url mismatch, not adopting")
            return null
        }
        handler.removeCallbacks(timeoutRunnable)
        player = null
        streamUrl = null
        streamId = null
        Log.i(TAG, "adopt: handed over")
        return p
    }

    fun release(reason: String = "unspecified") {
        handler.removeCallbacks(timeoutRunnable)
        player?.let {
            Log.i(TAG, "release: $reason")
            it.stop()
            it.release()
        }
        player = null
        streamUrl = null
        streamId = null
        frame = null
    }
}
