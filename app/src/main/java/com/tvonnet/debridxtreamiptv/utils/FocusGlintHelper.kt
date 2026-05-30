package com.tvonnet.debridxtreamiptv.utils

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.RectF
import android.graphics.Shader
import android.view.View
import android.view.ViewGroupOverlay
import androidx.core.view.animation.PathInterpolatorCompat

/**
 * 2026 OTT Best-In-Class Focus Animation Helper.
 * Implements a premium "Apple TV/Netflix" inspired 3D Parallax Scale + Sweeping Shine (Glint).
 */
object FocusGlintHelper {

    // Ultra-smooth friction curve: Fast out, incredibly slow and fluid in
    private val premiumInterpolator = PathInterpolatorCompat.create(0.2f, 1.0f, 0.2f, 1.0f)
    private const val PREMIUM_SCALE = 1.0f
    private const val ANIMATION_DURATION = 250L
    private val TAG_GLINT_RUNNABLE = com.tvonnet.debridxtreamiptv.R.id.tag_focus_glint_runnable


    /**
     * Attaches the 2026 Focus system to a view. 
     * Safe to call multiple times because it checks the listener type.
     */
    fun attach(view: View) {
        val currentListener = view.onFocusChangeListener
        if (currentListener is GlintFocusChangeListener) {
            return
        }
        view.onFocusChangeListener = GlintFocusChangeListener(currentListener)
    }

    private class GlintFocusChangeListener(var originalListener: View.OnFocusChangeListener?) : View.OnFocusChangeListener {
        override fun onFocusChange(v: View, hasFocus: Boolean) {
            originalListener?.onFocusChange(v, hasFocus)
            if (hasFocus) {
                applyFocus3D(v)
            } else {
                removeFocus3D(v)
            }
        }
    }
    
    /**
     * Optional: Call this in the adapter's bind if you update the listener.
     */
    fun updateListener(view: View, newListener: View.OnFocusChangeListener) {
        val current = view.onFocusChangeListener
        if (current is GlintFocusChangeListener) {
            current.originalListener = newListener
        } else {
            view.onFocusChangeListener = GlintFocusChangeListener(newListener)
        }
    }

    /**
     * Forcibly removes any focus effects. Useful for RecyclerView's onViewRecycled.
     */
    fun forceReset(v: View) {
        v.animate()?.cancel()
        val oldAnimator = v.getTag(com.tvonnet.debridxtreamiptv.R.id.tag_focus_animator) as? Animator
        oldAnimator?.cancel()
        
        // Cancel pending glint
        val pending = v.getTag(TAG_GLINT_RUNNABLE) as? Runnable
        if (pending != null) {
            v.removeCallbacks(pending)
            v.setTag(TAG_GLINT_RUNNABLE, null)
        }
        
        v.setTag(com.tvonnet.debridxtreamiptv.R.id.tag_focus_animator, null)
        if (v is android.view.ViewGroup) {
            v.overlay.clear()
        } else {
            (v.parent as? android.view.View)?.overlay?.clear() // Just in case
            v.overlay.clear()
        }
        
        v.alpha = 1.0f
        v.scaleX = 1.0f
        v.scaleY = 1.0f
        v.translationZ = 0f
        
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.P) {
            v.outlineAmbientShadowColor = Color.BLACK
            v.outlineSpotShadowColor = Color.BLACK
        }
    }

    private fun applyFocus3D(v: View) {
        // Cancel any existing out-animations
        val oldAnimator = v.getTag(com.tvonnet.debridxtreamiptv.R.id.tag_focus_animator) as? Animator
        oldAnimator?.cancel()

        // 0. Disable clipping on parents to prevent edge cutoffs
        // Note: We removed v.bringToFront() because it reorders children and can break focus navigation logic (skipping items).
        // translationZ(16f) handles the 3D draw order layering naturally on API 21+.
        var parent = v.parent
        while (parent is android.view.ViewGroup) {
            parent.clipChildren = false
            parent.clipToPadding = false
            parent = parent.parent
        }

        // 1. 3D Scale & Elevation
        v.animate()
            .scaleX(PREMIUM_SCALE)
            .scaleY(PREMIUM_SCALE)
            .translationZ(4f) // Slight lift, no heavy shadow
            .setDuration(ANIMATION_DURATION)
            .setInterpolator(premiumInterpolator)
            .start()

        // 1.5. Focus Dimming (Dim siblings)
        val immediateParent = v.parent as? android.view.ViewGroup
        if (immediateParent != null) {
            for (i in 0 until immediateParent.childCount) {
                val sibling = immediateParent.getChildAt(i)
                if (sibling != v) {
                    sibling.animate()
                        .alpha(1.0f) // Do not dim siblings, keep fully readable
                        .setDuration(ANIMATION_DURATION)
                        .setInterpolator(premiumInterpolator)
                        .start()
                }
            }
        }

        // 2. The Sweeping Glint (Shine)
        val glintRunnable = Runnable {
            // CRITICAL: Ensure the view strongly retains focus before starting the animation
            if (!v.isFocused) return@Runnable

            val width = v.width.toFloat()
            val height = v.height.toFloat()
            if (width == 0f || height == 0f) return@Runnable

            val overlay = v.overlay
            val glintDrawable = GlintDrawable(width, height)
            overlay.add(glintDrawable)

            val sweepAnimator = ValueAnimator.ofFloat(-width * 1.5f, width * 1.5f)
            sweepAnimator.duration = 600L
            sweepAnimator.interpolator = premiumInterpolator
            sweepAnimator.addUpdateListener { anim ->
                val offset = anim.animatedValue as Float
                glintDrawable.updateOffset(offset)
            }
            sweepAnimator.addListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) {
                    overlay.remove(glintDrawable)
                    v.setTag(TAG_GLINT_RUNNABLE, null)
                }
            })
            
            v.setTag(com.tvonnet.debridxtreamiptv.R.id.tag_focus_animator, sweepAnimator)
            sweepAnimator.start()
        }
        
        v.setTag(TAG_GLINT_RUNNABLE, glintRunnable)
        v.post(glintRunnable)
    }

    private fun removeFocus3D(v: View) {
        val oldAnimator = v.getTag(com.tvonnet.debridxtreamiptv.R.id.tag_focus_animator) as? Animator
        oldAnimator?.cancel()
        v.setTag(com.tvonnet.debridxtreamiptv.R.id.tag_focus_animator, null)
        
        // Cancel pending glint
        val pending = v.getTag(TAG_GLINT_RUNNABLE) as? Runnable
        if (pending != null) {
            v.removeCallbacks(pending)
            v.setTag(TAG_GLINT_RUNNABLE, null)
        }
        
        v.overlay.clear()

        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.P) {
            v.outlineAmbientShadowColor = Color.BLACK
            v.outlineSpotShadowColor = Color.BLACK
        }

        v.animate()
            .scaleX(1f)
            .scaleY(1f)
            .translationZ(0f)
            .setDuration(250L) // slightly faster return
            .setInterpolator(premiumInterpolator)
            .start()

        // Restore sibling alpha
        val immediateParent = v.parent as? android.view.ViewGroup
        if (immediateParent != null) {
            for (i in 0 until immediateParent.childCount) {
                val sibling = immediateParent.getChildAt(i)
                if (sibling != v) {
                    sibling.animate()
                        .alpha(1.0f)
                        .setDuration(250L)
                        .setInterpolator(premiumInterpolator)
                        .start()
                }
            }
        }
    }

    /**
     * Custom lightweight Drawable that draws an angled specular highlight across the bounds.
     */
    private class GlintDrawable(private val w: Float, private val h: Float) : android.graphics.drawable.Drawable() {
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            xfermode = PorterDuffXfermode(PorterDuff.Mode.SRC_ATOP)
        }
        private var xOffset = -w
        private val rect = RectF(0f, 0f, w, h)
        // 12dp corner radius to match our bg_source_card_focused
        private val cornerRadius = 12f * android.content.res.Resources.getSystem().displayMetrics.density

        fun updateOffset(offset: Float) {
            xOffset = offset
            
            // Angled gradient: Transparent -> Bright White/Cyan -> Transparent
            // The gradient moves across the width of the view
            val colorTransparent = Color.TRANSPARENT
            val colorShine = Color.parseColor("#4DFFFFFF") // 30% White shine
            val colorCore = Color.parseColor("#8000E5FF")  // 50% Cyan Core

            // Create an angled shader (e.g., top-left to bottom-right of a thin strip)
            // We use local coordinates that shift with xOffset
            paint.shader = LinearGradient(
                xOffset, 0f, 
                xOffset + w * 0.5f, h, // Angle it slightly
                intArrayOf(colorTransparent, colorShine, colorCore, colorShine, colorTransparent),
                floatArrayOf(0f, 0.4f, 0.5f, 0.6f, 1f),
                Shader.TileMode.CLAMP
            )
            invalidateSelf()
        }

        override fun draw(canvas: Canvas) {
            canvas.drawRoundRect(rect, cornerRadius, cornerRadius, paint)
        }

        override fun setAlpha(alpha: Int) {}
        override fun setColorFilter(colorFilter: android.graphics.ColorFilter?) {}
        @Deprecated("Deprecated in Java", ReplaceWith("android.graphics.PixelFormat.TRANSLUCENT", "android.graphics.PixelFormat"))
        override fun getOpacity(): Int = android.graphics.PixelFormat.TRANSLUCENT
    }
    
    /**
     * Prevents "Directional Leak" by resetting all visible items during scroll.
     */
    fun attachScrollReset(rv: androidx.recyclerview.widget.RecyclerView) {
        rv.addOnScrollListener(object : androidx.recyclerview.widget.RecyclerView.OnScrollListener() {
            override fun onScrollStateChanged(recyclerView: androidx.recyclerview.widget.RecyclerView, newState: Int) {
                if (newState == androidx.recyclerview.widget.RecyclerView.SCROLL_STATE_DRAGGING || 
                    newState == androidx.recyclerview.widget.RecyclerView.SCROLL_STATE_SETTLING) {
                    for (i in 0 until recyclerView.childCount) {
                        val child = recyclerView.getChildAt(i)
                        if (!child.isFocused) {
                            forceReset(child)
                        }
                    }
                }
            }
        })
    }
}

