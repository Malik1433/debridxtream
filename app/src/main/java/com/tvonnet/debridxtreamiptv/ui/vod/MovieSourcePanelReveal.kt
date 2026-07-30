package com.tvonnet.debridxtreamiptv.ui.vod

import android.view.KeyEvent
import android.view.View
import androidx.core.view.isVisible

/**
 * The sources panel on the movie detail page: hidden until you ask for it, opened with RIGHT.
 *
 * It was permanently on screen first, which worked but left a list sitting over the backdrop on every
 * movie whether or not anyone wanted it. Opening on RIGHT keeps the page calm and — more usefully —
 * makes it behave like the series detail, where the stream panel already slides in from the right.
 * One gesture to learn instead of two conventions.
 *
 * Deliberately lighter than the series version: that panel overlays the episode strip, so it needs a
 * scrim and has to make the background unfocusable or D-pad focus escapes behind it. This one lives
 * in empty backdrop beside a fixed-width detail column and overlaps nothing, so it needs neither —
 * and skipping them avoids the focus trap that machinery exists to prevent.
 *
 * Closing is driven by focus rather than by keys: whenever focus ends up outside the panel it closes
 * itself. That covers LEFT out of the list, BACK, and anything else that moves focus, without each
 * one needing its own rule.
 */
class MovieSourcePanelReveal(
    private val panel: View,
    private val sourceList: View,
    private val openTriggers: List<View>,
    private val onReturnFocus: () -> Unit,
) {

    val isOpen: Boolean get() = panel.isVisible

    /** RIGHT from any of the action buttons opens the panel and moves focus into the list. */
    fun handleKeyEvent(event: KeyEvent): Boolean {
        if (event.action != KeyEvent.ACTION_DOWN) return false
        if (event.keyCode != KeyEvent.KEYCODE_DPAD_RIGHT) return false
        if (isOpen) return false
        if (openTriggers.none { it.hasFocus() }) return false
        open()
        return true
    }

    /** @return true when the press was used to close the panel, so BACK does not also exit. */
    fun handleBack(): Boolean {
        if (!isOpen) return false
        close()
        onReturnFocus()
        return true
    }

    fun open() {
        if (isOpen) return
        panel.visibility = View.VISIBLE
        panel.post {
            panel.translationX = panel.width.toFloat()
            panel.animate().translationX(0f).setDuration(OPEN_MS).start()
            // Focus after the panel is laid out; an empty list would refuse focus and strand the
            // user on a visible panel they cannot reach.
            if (!sourceList.requestFocus()) onReturnFocus()
        }
    }

    fun close() {
        if (!isOpen) return
        panel.animate().translationX(panel.width.toFloat()).setDuration(CLOSE_MS).withEndAction {
            panel.visibility = View.GONE
            panel.translationX = 0f
        }.start()
    }

    /**
     * Close as soon as focus leaves the panel. A global listener rather than per-view ones: focus can
     * leave from any row, and a listener on the container does not fire for its descendants.
     */
    fun attachAutoClose(root: View) {
        root.viewTreeObserver.addOnGlobalFocusChangeListener { _, newFocus ->
            if (isOpen && (newFocus == null || !isInsidePanel(newFocus))) close()
        }
    }

    private fun isInsidePanel(view: View): Boolean {
        var current: View? = view
        while (current != null) {
            if (current === panel) return true
            current = current.parent as? View
        }
        return false
    }

    private companion object {
        const val OPEN_MS = 220L
        const val CLOSE_MS = 180L
    }
}
