package com.tvonnet.debridxtreamiptv.ui.live.guide

import android.os.Handler
import android.view.View
import android.view.animation.DecelerateInterpolator
import android.view.animation.OvershootInterpolator
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import com.tvonnet.debridxtreamiptv.databinding.FragmentLiveTvGuideBinding

/**
 * C7: the video surface that lives between the mini preview tile and fullscreen.
 *
 * There is exactly **one** player view on this screen. It is parked over the preview tile at rest
 * and *transformed* — never re-parented, never re-created — to fill the screen. A `TextureView`
 * keeps rendering through view transforms, so the whole grow shows live video: no snapshot, no
 * surface switch, no freeze.
 *
 * The rules that keep it from going black (each one cost a debugging session on the classic Live
 * screen; see the shared-handoff notes):
 *  - **transform, don't re-parent.** Scale + translate around a top-left pivot; the geometry then
 *    matches the tile exactly, and the surface is never torn down.
 *  - **cover before the one unavoidable switch.** Coming back from the player there IS a real
 *    surface swap, so the last frame is painted over the bridge first and only lifted once our own
 *    surface reports a frame ([onceFirstFrame]).
 *  - **[onceFirstFrame] always fires.** It has a timeout fallback, because a first frame that never
 *    arrives would otherwise leave the UI stuck fullscreen with no way back.
 *
 * Bodies moved verbatim from `LiveTvGuideFragment`.
 */
internal class GuideVideoBridge(
    private val binding: FragmentLiveTvGuideBinding,
    private val handler: Handler,
    private val isViewAlive: () -> Boolean,
) {
    private companion object {
        const val GROW_DURATION_MS = 360L
        const val SHRINK_DURATION_MS = 320L
        const val COVER_FADE_MS = 150L
    }

    /** Returns [left, top, width, height] of the preview tile relative to the fragment root. */
    private fun tileRectInRoot(): FloatArray {
        val tileLoc = IntArray(2); binding.previewTile.getLocationInWindow(tileLoc)
        val rootLoc = IntArray(2); binding.root.getLocationInWindow(rootLoc)
        return floatArrayOf(
            (tileLoc[0] - rootLoc[0]).toFloat(),
            (tileLoc[1] - rootLoc[1]).toFloat(),
            binding.previewTile.width.toFloat().coerceAtLeast(1f),
            binding.previewTile.height.toFloat().coerceAtLeast(1f)
        )
    }

    /**
     * Park the persistent video bridge exactly over the preview tile (its "mini" rest state) and
     * drop any cover snapshot. Retries once layout is done.
     *
     * [stillParked] is re-checked on the retry because layout can complete *after* the user has
     * already gone fullscreen — without it, a late retry would yank a fullscreen video back to
     * tile size.
     */
    fun parkAtTile(stillParked: () -> Boolean) {
        val fc = binding.fullscreenContainer
        val root = binding.root
        if (root.width == 0 || binding.previewTile.width == 0) {
            root.post { if (isViewAlive() && stillParked()) parkAtTile(stillParked) }
            return
        }
        val r = tileRectInRoot()
        fc.animate().cancel()
        fc.pivotX = 0f
        fc.pivotY = 0f
        fc.scaleX = r[2] / root.width.toFloat()
        fc.scaleY = r[3] / root.height.toFloat()
        fc.translationX = r[0]
        fc.translationY = r[1]
        fc.visibility = View.VISIBLE
        hideCover()
    }

    /** Water-drop grow from the tile to fullscreen; [onDone] runs when the video fills the screen. */
    fun growToFullscreen(onDone: () -> Unit) {
        binding.fullscreenContainer.animate()
            .scaleX(1f).scaleY(1f).translationX(0f).translationY(0f)
            .setDuration(GROW_DURATION_MS).setInterpolator(OvershootInterpolator(0.5f))
            .withEndAction { if (isViewAlive()) onDone() }
            .start()
    }

    /** Shrink the (live) fullscreen video back onto the tile. */
    fun shrinkToTile(onDone: () -> Unit) {
        val root = binding.root
        val r = tileRectInRoot()
        binding.fullscreenContainer.animate()
            .scaleX(r[2] / root.width.toFloat()).scaleY(r[3] / root.height.toFloat())
            .translationX(r[0]).translationY(r[1])
            .setDuration(SHRINK_DURATION_MS).setInterpolator(DecelerateInterpolator())
            .withEndAction { if (isViewAlive()) onDone() }
            .start()
    }

    fun showFullscreenContainer() {
        binding.fullscreenContainer.visibility = View.VISIBLE
    }

    // ── The cover snapshot ─────────────────────────────────────────────────────

    /** Snapshot the last rendered frame of a TextureView-based PlayerView. */
    fun capturePreviewFrame(): android.graphics.Bitmap? =
        runCatching {
            (binding.previewPlayerView.videoSurfaceView as? android.view.TextureView)?.getBitmap()
        }.getOrNull()

    /** Show the hand-off frame snapshot over the bridge video. */
    fun showCover(bitmap: android.graphics.Bitmap?) {
        if (bitmap == null) return
        binding.fullscreenCover.setImageBitmap(bitmap)
        binding.fullscreenCover.alpha = 1f
        binding.fullscreenCover.visibility = View.VISIBLE
    }

    fun fadeOutCover() {
        binding.fullscreenCover.animate().alpha(0f).setDuration(COVER_FADE_MS).withEndAction {
            if (isViewAlive()) hideCover()
        }.start()
    }

    private fun hideCover() {
        binding.fullscreenCover.visibility = View.GONE
        binding.fullscreenCover.setImageDrawable(null)
        binding.fullscreenCover.alpha = 1f
    }

    /**
     * Run [action] once the player renders its first frame on the CURRENT surface — with a timeout
     * fallback so the UI can never get stuck.
     */
    fun onceFirstFrame(player: ExoPlayer, timeoutMs: Long, action: () -> Unit) {
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
            if (!done) {
                done = true
                player.removeListener(listener)
                action()
            }
        }, timeoutMs)
    }
}
