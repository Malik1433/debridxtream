package com.tvonnet.debridxtreamiptv.player.stabilized

import android.app.Activity
import android.graphics.Bitmap
import android.graphics.Color
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.PixelCopy
import android.view.SurfaceView
import android.view.TextureView
import android.view.ViewGroup
import android.widget.ImageView
import androidx.media3.common.Player
import androidx.media3.ui.PlayerView

/**
 * The two bitmap halves of the Live guide <-> fullscreen handoff (Phase P12).
 *
 * Scope note: only the frame work lives here. The handoff *order* — capture, then
 * hand the player back, then finish — stays in [PlayerActivity.performBackExit],
 * because getting that order wrong is a known regression (adopt-before-takeFrame
 * was rejected). Moving the orchestration would relocate the coupling, not remove it.
 */

private const val TAG = "PlayerActivity"

/**
 * Snapshots the currently displayed video frame (TextureView directly, SurfaceView
 * via PixelCopy) so the guide can mask its surface warm-up. Always calls [onDone]
 * (with null on failure) — it must never block the exit.
 */
internal fun captureVideoFrame(playerView: PlayerView, onDone: (Bitmap?) -> Unit) {
    when (val v = playerView.videoSurfaceView) {
        is TextureView -> onDone(runCatching { v.getBitmap() }.getOrNull())
        is SurfaceView -> {
            val w = v.width
            val h = v.height
            if (w <= 0 || h <= 0) { onDone(null); return }
            val bitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
            try {
                PixelCopy.request(v, bitmap, { result ->
                    onDone(if (result == PixelCopy.SUCCESS) bitmap else null)
                }, Handler(Looper.getMainLooper()))
            } catch (e: Exception) {
                // Never block the exit on a failed snapshot — the guide just warms up unmasked.
                Log.w(TAG, "PixelCopy frame capture failed: ${e.javaClass.simpleName}", e)
                onDone(null)
            }
        }
        else -> onDone(null)
    }
}

/**
 * Shows the bridge frame handed over by the guide, removed as soon as our own
 * surface renders (or after [timeoutMs], whichever comes first).
 */
internal fun showAdoptedCoverFrame(
    activity: Activity,
    bitmap: Bitmap,
    player: Player?,
    handler: Handler,
    timeoutMs: Long = 800L
) {
    val cover = ImageView(activity).apply {
        setImageBitmap(bitmap)
        scaleType = ImageView.ScaleType.CENTER_CROP
        setBackgroundColor(Color.BLACK)
    }
    activity.addContentView(
        cover,
        ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
    )
    var removed = false
    val remove = {
        if (!removed) {
            removed = true
            cover.animate().alpha(0f).setDuration(150).withEndAction {
                (cover.parent as? ViewGroup)?.removeView(cover)
            }.start()
        }
    }
    if (player == null) { remove(); return }
    val listener = object : Player.Listener {
        override fun onRenderedFirstFrame() {
            player.removeListener(this)
            remove()
        }
    }
    player.addListener(listener)
    handler.postDelayed({
        player.removeListener(listener)
        remove()
    }, timeoutMs)
}
