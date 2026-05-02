package com.tvonnet.debridxtreamiptv.utils

import android.animation.ValueAnimator
import android.util.TypedValue
import android.view.View
import android.view.animation.DecelerateInterpolator
import androidx.interpolator.view.animation.FastOutSlowInInterpolator

/**
 * SidebarFocusHelper
 * 
 * Standardized utility to handle the "Alive" sidebar expansion/collapsing animation
 * across different fragments in the DebridXtream player.
 * 
 * Features:
 * - Smooth width animation
 * - Alpha transitions for title areas
 * - Focus-loss grace period to prevent flickering during fast navigation
 */
object SidebarFocusHelper {

    private const val COLLAPSED_WIDTH_DP = 80f
    private const val EXPANDED_WIDTH_DP = 260f
    private const val ANIMATION_DURATION = 300L
    private const val FOCUS_GRACE_PERIOD_MS = 100L

    /**
     * Attaches expansion logic to a sidebar container.
     * 
     * @param sidebarContainer The view whose width will be animated (typically a Layout or RecyclerView)
     * @param focusTrigger The view to monitor for focus (usually the RecyclerView itself)
     * @param titleArea Optional title view/container to fade in/out during expansion
     */
    fun attachStandardSidebarAnimation(
        sidebarContainer: View,
        focusTrigger: View,
        titleArea: View? = null,
        onExpandedChanged: ((Boolean) -> Unit)? = null
    ) {
        var animator: ValueAnimator? = null
        val collapsedWidth = dpToPx(sidebarContainer, COLLAPSED_WIDTH_DP)
        val expandedWidth = dpToPx(sidebarContainer, EXPANDED_WIDTH_DP)
        var isExpanded = sidebarContainer.layoutParams.width != collapsedWidth

        val performAnimation = { expand: Boolean ->
            if (sidebarContainer.isAttachedToWindow) {
                val startWidth = sidebarContainer.width
                val endWidth = if (expand) expandedWidth else collapsedWidth
                if (expand != isExpanded) {
                    isExpanded = expand
                    onExpandedChanged?.invoke(expand)
                }
    
                if (startWidth != endWidth) {
                    animator?.cancel()
                    animator = ValueAnimator.ofInt(startWidth, endWidth).apply {
                        duration = ANIMATION_DURATION
                        interpolator = FastOutSlowInInterpolator()
                        addUpdateListener { anim ->
                            val width = anim.animatedValue as Int
                            val params = sidebarContainer.layoutParams
                            params.width = width
                            sidebarContainer.layoutParams = params
                            sidebarContainer.requestLayout()
                        }
                        start()
                    }
    
                    titleArea?.animate()
                        ?.alpha(if (expand) 1f else 0f)
                        ?.setDuration(ANIMATION_DURATION)
                        ?.setInterpolator(FastOutSlowInInterpolator())
                        ?.start()
                }
            }
        }

        // Use GlobalFocusChangeListener for robust tracking across children
        val focusListener = android.view.ViewTreeObserver.OnGlobalFocusChangeListener { _, newFocus ->
            if (!focusTrigger.isAttachedToWindow) return@OnGlobalFocusChangeListener
            
            val isInside = isDescendantOf(newFocus, focusTrigger)
            
            if (isInside) {
                performAnimation(true)
            } else {
                // Focus left the sidebar. Use grace period to see if it refocuses (e.g. moving between items)
                focusTrigger.postDelayed({
                    if (!focusTrigger.isAttachedToWindow) return@postDelayed
                    val currentFocus = focusTrigger.findFocus()
                    if (!isDescendantOf(currentFocus, focusTrigger)) {
                        performAnimation(false)
                    }
                }, FOCUS_GRACE_PERIOD_MS)
            }
        }
        
        focusTrigger.viewTreeObserver.addOnGlobalFocusChangeListener(focusListener)
        
        focusTrigger.addOnAttachStateChangeListener(object : View.OnAttachStateChangeListener {
            override fun onViewAttachedToWindow(v: View) {}
            override fun onViewDetachedFromWindow(v: View) {
                // Ensure we don't leak or fire after detached
                if (v.viewTreeObserver.isAlive) {
                    v.viewTreeObserver.removeOnGlobalFocusChangeListener(focusListener)
                }
                animator?.cancel()
            }
        })
    }

    private fun isDescendantOf(view: View?, ancestor: View): Boolean {
        var current: View? = view
        while (current != null) {
            if (current === ancestor) return true
            val parent = current.parent
            current = if (parent is View) parent else null
        }
        return false
    }

    private fun dpToPx(view: View, dp: Float): Int {
        return TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_DIP,
            dp,
            view.resources.displayMetrics
        ).toInt()
    }
}
