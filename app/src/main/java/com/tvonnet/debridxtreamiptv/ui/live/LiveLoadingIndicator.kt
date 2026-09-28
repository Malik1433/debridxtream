package com.tvonnet.debridxtreamiptv.ui.live

import android.view.View
import android.widget.TextView
import androidx.core.view.isVisible
import com.tvonnet.debridxtreamiptv.R

/**
 * What a Live screen shows between a channel change and its first frame (owner, 2026-09-28): the
 * guide's preview on a click, and the fullscreen player on a zap.
 *
 * Fire TV QA on a Fire TV Stick 4K: a click could take 2.6 s, 4.3 s, once over 18 s, and all that
 * time the panel still read "Preview a channel to get program details" with no spinner - the
 * viewer could not tell the click had landed. Fullscreen zapping had the same gap: the zap
 * backdrop covers the player's own spinner. Now the channel's name and "Connecting…" appear at
 * once, and if the provider is slow the line says so rather than looking stuck.
 *
 * Owns one container (spinner + text); nothing else shows or hides it.
 */
internal class LiveLoadingIndicator(private val container: View?, private val text: TextView?) {

    /** The guide preview's `preview_loading_container`. */
    constructor(previewRoot: View) : this(
        previewRoot.findViewById(R.id.preview_loading_container),
        previewRoot.findViewById(R.id.preview_loading_text),
    )

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
