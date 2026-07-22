package com.tvonnet.debridxtreamiptv.player.stabilized

import android.os.Handler
import android.view.View
import androidx.core.view.isVisible

/**
 * The legacy in-player EPG strip: its mode, its pin state and its auto-hide
 * (Phase P18). Used only when the cinematic Live OSD is absent.
 *
 * Scope note: zapping/tuning stays in [PlayerActivity]; this owns nothing but the
 * overlay's own visibility rules. The Activity supplies [shouldBeVisible] because
 * that rule also depends on PiP, the OSD and the transport controller.
 */
internal enum class EpgOverlayMode { HIDDEN, COMPACT, EXPANDED }

/** The overlay counts as "pinned up" only while it is pinned AND actually showing. */
internal fun isEpgOverlayPinnedUp(pinned: Boolean, mode: EpgOverlayMode): Boolean =
    pinned && mode != EpgOverlayMode.HIDDEN

internal class PlayerEpgOverlay(
    private val handler: Handler,
    private val timeoutMs: Long,
    private val overlay: () -> View?,
    private val isLive: () -> Boolean,
    private val shouldBeVisible: () -> Boolean
) {

    var mode: EpgOverlayMode = EpgOverlayMode.HIDDEN
        private set

    var isPinned: Boolean = false
        private set

    private val autoHideRunnable = Runnable { maybeAutoHide() }

    fun isPinnedUp(): Boolean = isEpgOverlayPinnedUp(isPinned, mode)

    /**
     * Shows the strip. An unpinned show auto-hides after [timeoutMs]; a pinned one
     * stays until something hides it explicitly.
     */
    fun show(requestedMode: EpgOverlayMode, pinned: Boolean) {
        mode = if (requestedMode == EpgOverlayMode.HIDDEN) EpgOverlayMode.HIDDEN else EpgOverlayMode.COMPACT
        isPinned = pinned
        applyVisibility()
        handler.removeCallbacks(autoHideRunnable)
        if (!pinned) handler.postDelayed(autoHideRunnable, timeoutMs)
    }

    fun hide() {
        handler.removeCallbacks(autoHideRunnable)
        isPinned = false
        mode = EpgOverlayMode.HIDDEN
        applyVisibility()
    }

    /** OK/INFO toggle: pinned-and-showing hides, anything else shows pinned. */
    fun togglePinned() {
        if (isPinnedUp()) hide() else show(EpgOverlayMode.COMPACT, pinned = true)
    }

    /** Re-applies the Activity's visibility rule (PiP, OSD, controller changes). */
    fun applyVisibility() {
        setVisible(shouldBeVisible())
    }

    fun cancelAutoHide() = handler.removeCallbacks(autoHideRunnable)

    /** Auto-hide skips a pinned LIVE overlay — the user asked for it to stay. */
    private fun maybeAutoHide() {
        if (isLive() && isPinned) return
        hide()
    }

    private fun setVisible(visible: Boolean) {
        val view = overlay() ?: return
        if (visible) {
            if (view.isVisible) return
            view.alpha = 0f
            view.isVisible = true
            view.animate().alpha(1f).setDuration(160).start()
        } else {
            if (!view.isVisible) return
            view.animate().alpha(0f).setDuration(140).withEndAction { view.isVisible = false }.start()
        }
    }
}
