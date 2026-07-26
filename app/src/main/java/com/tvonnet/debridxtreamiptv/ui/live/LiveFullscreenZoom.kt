package com.tvonnet.debridxtreamiptv.ui.live

import android.graphics.Bitmap
import android.os.Handler
import android.os.Looper
import android.view.TextureView
import android.view.View
import android.view.animation.DecelerateInterpolator
import android.widget.ImageView
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView

/**
 * The smooth zoom transition for the classic Live browse fullscreen: a full-size [container] (bridge)
 * holding a TextureView [bridgePlayer] and a [cover] ImageView. Scaling/translating the container
 * (pivot 0,0) between the preview-tile rect and fullscreen makes the preview appear to zoom in/out.
 *
 * **Why the bridge is never GONE (root cause of the old "frozen while zooming"):** a GONE view is not
 * laid out and has NO SurfaceTexture, so attaching the player to it blocked video output until the view
 * became visible and its surface was created — i.e. exactly during the grow. The bridge is therefore kept
 * VISIBLE-but-invisible (alpha ~0, parked over the tile) so its surface is always warm, and the one
 * surface switch is done and **awaited BEFORE the animation starts** ([growLive] waits for the bridge's
 * first rendered frame behind a still cover, then animates). Nothing is moving during that wait, so the
 * switch is imperceptible — and the grow itself is always LIVE video (a TextureView keeps rendering
 * through View transforms). Mirrors the EPG guide's zero-freeze behaviour.
 *
 * Robust by construction: every entry point degrades to an immediate continuation if the views are not
 * laid out, the first-frame wait is timeout-bounded, and [hide] always re-parks the bridge invisible so
 * it can never be left covering the UI.
 */
class LiveFullscreenZoom(
    private val root: View,
    private val tile: View,
    private val container: View,
    private val bridgePlayer: PlayerView,
    private val cover: ImageView,
) {
    private companion object {
        const val DURATION_MS = 300L
        /** Cap on the pre-grow surface-switch wait; the grow starts anyway if the frame is late. */
        const val FIRST_FRAME_TIMEOUT_MS = 400L
        /** Effectively invisible, but still drawn — which is what keeps the TextureView's surface warm. */
        const val PARKED_ALPHA = 0.004f
    }

    private val handler = Handler(Looper.getMainLooper())

    /** [left, top, width, height] of the preview tile relative to [root]. */
    private fun tileRectInRoot(): FloatArray {
        val tileLoc = IntArray(2); tile.getLocationInWindow(tileLoc)
        val rootLoc = IntArray(2); root.getLocationInWindow(rootLoc)
        return floatArrayOf(
            (tileLoc[0] - rootLoc[0]).toFloat(),
            (tileLoc[1] - rootLoc[1]).toFloat(),
            tile.width.toFloat().coerceAtLeast(1f),
            tile.height.toFloat().coerceAtLeast(1f)
        )
    }

    private fun ready(): Boolean = root.width > 0 && root.height > 0 && tile.width > 0 && tile.height > 0

    private fun parkAtTile() {
        val r = tileRectInRoot()
        container.pivotX = 0f
        container.pivotY = 0f
        container.scaleX = r[2] / root.width
        container.scaleY = r[3] / root.height
        container.translationX = r[0]
        container.translationY = r[1]
    }

    /**
     * Park the bridge invisibly over the preview tile so its TextureView surface is created and stays
     * warm. Call once the layout has been measured (retries itself until then).
     */
    fun prewarm() {
        if (!ready()) {
            root.post { if (root.isAttachedToWindow) prewarm() }
            return
        }
        container.animate().cancel()
        parkAtTile()
        container.alpha = PARKED_ALPHA
        container.visibility = View.VISIBLE
        cover.visibility = View.GONE
        cover.setImageDrawable(null)
    }

    /**
     * Grow the preview to fullscreen with LIVE video.
     *
     * [coverFrame] (the preview's last frame) masks the surface switch: it is shown at tile size, the
     * player is attached to the warm bridge surface, and only once the bridge renders its first frame
     * (or [FIRST_FRAME_TIMEOUT_MS] elapses) is the cover dropped and the grow animated — so the moving
     * part is always live. Falls back to an immediate [onGrown] if the layout is not ready.
     */
    fun growLive(player: ExoPlayer, coverFrame: Bitmap?, onGrown: () -> Unit) {
        if (!ready()) { onGrown(); return }
        container.animate().cancel()
        parkAtTile()
        container.alpha = 1f
        container.visibility = View.VISIBLE
        if (coverFrame != null) {
            cover.setImageBitmap(coverFrame)
            cover.visibility = View.VISIBLE
        }
        bridgePlayer.player = player

        onceFirstFrame(player) {
            // Live frames are flowing into the bridge now: drop the cover and grow LIVE.
            cover.visibility = View.GONE
            cover.setImageDrawable(null)
            container.animate().cancel()
            container.animate()
                .scaleX(1f).scaleY(1f).translationX(0f).translationY(0f)
                .setDuration(DURATION_MS).setInterpolator(DecelerateInterpolator())
                .withEndAction { onGrown() }
                .start()
        }
    }

    /** Run [action] on the first frame rendered on the CURRENT surface, or after a timeout. */
    private fun onceFirstFrame(player: ExoPlayer, action: () -> Unit) {
        var done = false
        val listener = object : Player.Listener {
            override fun onRenderedFirstFrame() {
                if (done) return
                done = true
                player.removeListener(this)
                action()
            }
        }
        player.addListener(listener)
        handler.postDelayed({
            if (done) return@postDelayed
            done = true
            runCatching { player.removeListener(listener) }
            action()
        }, FIRST_FRAME_TIMEOUT_MS)
    }

    /** Instantly cover the whole screen with [frame] (used on return, before shrinking). */
    fun showFullscreenCover(frame: Bitmap?) {
        if (frame == null) return
        container.animate().cancel()
        bridgePlayer.player = null
        cover.setImageBitmap(frame)
        cover.visibility = View.VISIBLE
        container.pivotX = 0f
        container.pivotY = 0f
        container.scaleX = 1f
        container.scaleY = 1f
        container.translationX = 0f
        container.translationY = 0f
        container.alpha = 1f
        container.visibility = View.VISIBLE
    }

    /** Shrink the fullscreen cover back onto the preview tile, then [hide] to reveal the live preview. */
    fun shrink() {
        if (container.alpha < 1f || !ready()) { hide(); return }
        val r = tileRectInRoot()
        container.pivotX = 0f
        container.pivotY = 0f
        container.animate().cancel()
        container.animate()
            .scaleX(r[2] / root.width).scaleY(r[3] / root.height)
            .translationX(r[0]).translationY(r[1])
            .setDuration(DURATION_MS).setInterpolator(DecelerateInterpolator())
            .withEndAction { hide() }
            .start()
    }

    /**
     * Drop the player + cover and re-park the bridge invisibly over the tile (keeping its surface warm
     * for the next transition). Safe to call anytime — never leaves the UI covered.
     */
    fun hide() {
        container.animate().cancel()
        bridgePlayer.player = null
        cover.visibility = View.GONE
        cover.setImageDrawable(null)
        container.alpha = PARKED_ALPHA
        if (ready()) parkAtTile() else container.visibility = View.GONE
    }
}
