package com.tvonnet.debridxtreamiptv.utils

import android.view.View
import android.view.animation.AccelerateInterpolator
import android.view.animation.DecelerateInterpolator

/**
 * Helper to apply "Magnetic" focus effects to views.
 * Scales up the view when focused and scales back down when focus is lost.
 */
object MagneticFocusHelper {

    private const val DEFAULT_SCALE = 1.05f
    private const val DURATION = 150L

    fun attach(view: View, scale: Float = DEFAULT_SCALE) {
        val previousListener = view.onFocusChangeListener
        view.onFocusChangeListener = View.OnFocusChangeListener { v, hasFocus ->
            previousListener?.onFocusChange(v, hasFocus)
            if (hasFocus) {
                v.animate()
                    .scaleX(scale)
                    .scaleY(scale)
                    .setDuration(DURATION)
                    .setInterpolator(DecelerateInterpolator())
                    .start()
                v.elevation = 4f // Lift up
            } else {
                v.animate()
                    .scaleX(1f)
                    .scaleY(1f)
                    .setDuration(DURATION)
                    .setInterpolator(AccelerateInterpolator())
                    .start()
                v.elevation = 0f // Flatten
            }
        }
    }
}
