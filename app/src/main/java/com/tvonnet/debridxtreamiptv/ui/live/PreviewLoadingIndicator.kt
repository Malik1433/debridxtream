package com.tvonnet.debridxtreamiptv.ui.live

import android.view.View
import android.widget.TextView
import androidx.core.view.isVisible
import com.tvonnet.debridxtreamiptv.R

/**
 * What the Live preview shows between a channel click and its first frame (owner, 2026-09-28).
 *
 * Fire TV QA on a Fire TV Stick 4K: a click could take 2.6 s, 4.3 s, once over 18 s, and all that
 * time the panel still read "Preview a channel to get program details" with no spinner - the
 * viewer could not tell the click had landed. Now the channel's name and "Connecting…" appear on
 * the click itself, and if the provider is slow the line says so rather than looking stuck.
 *
 * Owns the layout's existing `preview_loading_container` (spinner + text); nothing else shows or
 * hides it.
 */
internal class PreviewLoadingIndicator(root: View) {

    private val container: View? = root.findViewById(R.id.preview_loading_container)
    private val text: TextView? = root.findViewById(R.id.preview_loading_text)

    private val slowLine = Runnable {
        text?.setText(R.string.live_preview_slow)
    }

    fun show(channelName: String?) {
        val box = container ?: return
        box.removeCallbacks(slowLine)
        text?.text = if (channelName.isNullOrBlank()) {
            box.context.getString(R.string.live_preview_loading_state)
        } else {
            box.context.getString(R.string.live_preview_connecting, channelName)
        }
        box.isVisible = true
        box.postDelayed(slowLine, SLOW_AFTER_MS)
    }

    /** First frame, an error, or the player went away: the panel speaks for itself again. */
    fun hide() {
        container?.removeCallbacks(slowLine)
        container?.isVisible = false
    }

    private companion object {
        /** Most channels start inside ~2 s; past this the viewer deserves to hear why it is waiting. */
        const val SLOW_AFTER_MS = 6_000L
    }
}
