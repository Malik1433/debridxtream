package com.tvonnet.debridxtreamiptv.ui.debrid.stremio

import android.view.View
import com.tvonnet.debridxtreamiptv.util.FocusEffects

/**
 * Makes any focusable visibly react to D-pad focus (scale + lift), reusing the app's shared
 * [FocusEffects]. Chains onto any existing OnFocusChangeListener so callers can still react.
 */
internal object StremioFocus {

    fun attach(view: View, scale: Float = 1.08f, extra: ((View, Boolean) -> Unit)? = null) {
        val existing = view.onFocusChangeListener
        view.setOnFocusChangeListener { v, hasFocus ->
            FocusEffects.applyCinematicFocus(v, hasFocus, scale)
            v.z = if (hasFocus) 12f else 0f
            existing?.onFocusChange(v, hasFocus)
            extra?.invoke(v, hasFocus)
        }
    }
}
