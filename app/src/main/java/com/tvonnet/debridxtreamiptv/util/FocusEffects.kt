package com.tvonnet.debridxtreamiptv.util

import android.animation.TimeInterpolator
import android.view.View
import android.view.animation.AccelerateDecelerateInterpolator

object FocusEffects {

    private val FOCUS_INTERPOLATOR: TimeInterpolator = AccelerateDecelerateInterpolator()
    private const val ANIMATION_DURATION = 200L
    private const val DEFAULT_SCALE = 1.1f

    /**
     * Applies a cinematic focus animation with scaling and elevation.
     * Optional 3D tilt can be added if supported by the layout.
     */
    fun applyCinematicFocus(view: View, hasFocus: Boolean, scale: Float = DEFAULT_SCALE) {
        if (hasFocus) {
            view.animate()
                .scaleX(scale)
                .scaleY(scale)
                .translationZ(8f) // Lift up
                .setDuration(ANIMATION_DURATION)
                .setInterpolator(FOCUS_INTERPOLATOR)
                .start()
            
            // Show glow/border if it exists as a separate view with tag "focus_glow"
            view.findViewWithTag<View>("focus_glow")?.animate()
                ?.alpha(1f)
                ?.setDuration(ANIMATION_DURATION)
                ?.start()
                
        } else {
            view.animate()
                .scaleX(1f)
                .scaleY(1f)
                .translationZ(0f) // Reset lift
                .rotationX(0f) // Reset tilt
                .rotationY(0f)
                .setDuration(ANIMATION_DURATION)
                .setInterpolator(FOCUS_INTERPOLATOR)
                .start()

            // Hide glow
            view.findViewWithTag<View>("focus_glow")?.animate()
                ?.alpha(0f)
                ?.setDuration(ANIMATION_DURATION)
                ?.start()
        }
    }

    /**
     * Applies a subtle 3D tilt effect based on focus vs normal state.
     * Note: Full motion tilt requires touch/mouse events, this is a static focus tilt.
     */
    fun apply3DTilt(view: View, hasFocus: Boolean) {
        if (hasFocus) {
            // Slight tilt up to simulate looking at it
            view.animate()
                .rotationX(-2f)
                .setDuration(ANIMATION_DURATION)
                .start()
        } else {
            view.animate()
                .rotationX(0f)
                .setDuration(ANIMATION_DURATION)
                .start()
        }
    }
}
