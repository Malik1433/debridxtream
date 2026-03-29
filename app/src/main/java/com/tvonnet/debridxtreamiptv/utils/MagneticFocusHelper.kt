package com.tvonnet.debridxtreamiptv.utils

import android.view.View
import android.view.animation.AccelerateInterpolator
import android.view.animation.DecelerateInterpolator
import com.tvonnet.debridxtreamiptv.R

/**
 * Global App Helper to apply a unified, premium "Magnetic" focus visual effect to interactive views.
 * It strictly enforces a consistent scale-up and shadow-elevation state across the app.
 */
object MagneticFocusHelper {

    private const val DEFAULT_SCALE = 1.08f
    private const val DURATION = 150L
    private const val FOCUS_ELEVATION = 8f // standard global elevation for focused items

    /**
     * Attaches consistent focus styling (scale and elevation) to a view.
     */
    fun attach(view: View, scale: Float = DEFAULT_SCALE) {
        val previousListener = view.onFocusChangeListener
        view.onFocusChangeListener = View.OnFocusChangeListener { v, hasFocus ->
            previousListener?.onFocusChange(v, hasFocus)
            if (hasFocus) {
                applyFocusEffect(v, scale)
            } else {
                removeFocusEffect(v)
            }
        }
    }

    /**
     * Manually applies the magnetic focus scale, bobbing translation, and dynamic elevation effect.
     */
    fun applyFocusEffect(v: View, scale: Float = DEFAULT_SCALE) {
        // Cancel existing animator if any
        val oldAnimator = v.getTag(R.id.tag_focus_animator) as? android.animation.ValueAnimator
        oldAnimator?.cancel()

        // 1. Premium Initial Scale Up (Snappy OTT Pop)
        v.animate()
            .scaleX(scale)
            .scaleY(scale)
            .setDuration(200L) // Slightly longer for the overshoot to breathe
            .setInterpolator(android.view.animation.OvershootInterpolator(1.2f))
            .start()

        // 2. Set Outer Glow (Ambient/Spot Shadow Colors) if API 28+
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.P) {
            v.outlineAmbientShadowColor = android.graphics.Color.parseColor("#8800E5FF")
            v.outlineSpotShadowColor = android.graphics.Color.parseColor("#00E5FF")
        }

        // 3. Subtle Bobbing Animation +/- 2dp (Refined for TV)
        val maxBobDp = 2f
        val maxBobPx = android.util.TypedValue.applyDimension(
            android.util.TypedValue.COMPLEX_UNIT_DIP, maxBobDp, v.resources.displayMetrics
        )
        val maxElevationPx = android.util.TypedValue.applyDimension(
            android.util.TypedValue.COMPLEX_UNIT_DIP, 16f, v.resources.displayMetrics
        )

        // Using ValueAnimator from -8dp to +8dp
        val bobAnimator = android.animation.ValueAnimator.ofFloat(-maxBobPx, maxBobPx)
        bobAnimator.duration = 1250L // 2500ms total cycle
        bobAnimator.repeatCount = android.animation.ValueAnimator.INFINITE
        bobAnimator.repeatMode = android.animation.ValueAnimator.REVERSE
        // Smooth AccelerateDecelerate recreates the Sine wave curve
        bobAnimator.interpolator = android.view.animation.AccelerateDecelerateInterpolator()

        bobAnimator.addUpdateListener { anim ->
            val ty = anim.animatedValue as Float
            v.translationY = ty
            
            // Map ty from -maxBobPx to maxBobPx to dynamic elevation
            // ty = -maxBobPx (UP) -> max elevation
            // ty = maxBobPx (DOWN) -> minimum elevation (50% drop)
            val fraction = (ty + maxBobPx) / (2 * maxBobPx)
            v.elevation = maxElevationPx - (fraction * maxElevationPx * 0.5f)
        }
        
        v.setTag(R.id.tag_focus_animator, bobAnimator)
        bobAnimator.start()
    }

    /**
     * Manually removes the magnetic focus animations and resets states.
     */
    fun removeFocusEffect(v: View) {
        val oldAnimator = v.getTag(R.id.tag_focus_animator) as? android.animation.ValueAnimator
        if (oldAnimator != null) {
            oldAnimator.cancel()
            v.setTag(R.id.tag_focus_animator, null)
        }

        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.P) {
            v.outlineAmbientShadowColor = android.graphics.Color.BLACK
            v.outlineSpotShadowColor = android.graphics.Color.BLACK
        }

        v.animate()
            .scaleX(1f)
            .scaleY(1f)
            .translationY(0f)
            .setDuration(150L)
            .setInterpolator(AccelerateInterpolator())
            .withEndAction {
                v.elevation = 0f
            }
            .start()
    }
}
