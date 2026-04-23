package com.tvonnet.debridxtreamiptv.utils

import android.animation.ValueAnimator
import android.view.View
import androidx.interpolator.view.animation.FastOutSlowInInterpolator
import com.tvonnet.debridxtreamiptv.R

/**
 * SidebarFocusHelper
 * 
 * Standardized utility to handle the "Alive" sidebar expansion/collapsing animation
 * across different fragments in the DebridXtream player.
 */
object SidebarFocusHelper {

    private const val ANIMATION_DURATION = 300L
    private const val FOCUS_GRACE_PERIOD_MS = 100L
    
    private var currentAnimator: ValueAnimator? = null
    private var isExpandedState = false
    private var isLockedState = false

    fun attachStandardSidebarAnimation(
        sidebarContainer: View,
        focusTrigger: View,
        titleArea: View? = null,
        onExpandedChanged: ((Boolean) -> Unit)? = null
    ) {
        val collapsedWidth = sidebarContainer.resources.getDimensionPixelSize(R.dimen.sidebar_width_collapsed)
        val expandedWidth = sidebarContainer.resources.getDimensionPixelSize(R.dimen.sidebar_width_expanded)
        isExpandedState = sidebarContainer.layoutParams.width == expandedWidth

        val performAnimation = { expand: Boolean ->
            if (expand == isExpandedState || isLockedState) { /* do nothing */ } else {
                isExpandedState = expand
                onExpandedChanged?.invoke(expand)

                currentAnimator?.cancel()
                val startWidth = sidebarContainer.width
                val endWidth = if (expand) expandedWidth else collapsedWidth
                
                currentAnimator = ValueAnimator.ofInt(startWidth, endWidth).apply {
                    duration = ANIMATION_DURATION
                    interpolator = FastOutSlowInInterpolator()
                    addUpdateListener { anim ->
                        val width = anim.animatedValue as Int
                        val params = sidebarContainer.layoutParams
                        params.width = width
                        sidebarContainer.layoutParams = params
                    }
                    start()
                }

                titleArea?.animate()
                    ?.alpha(if (expand) 1f else 0f)
                    ?.setDuration(ANIMATION_DURATION)
                    ?.setInterpolator(FastOutSlowInInterpolator())
                    ?.withStartAction { if (expand) titleArea.visibility = View.VISIBLE }
                    ?.withEndAction { if (!expand) titleArea.visibility = View.GONE }
                    ?.start()
            }
        }

        // Use GlobalFocusChangeListener for robust tracking across children
        focusTrigger.viewTreeObserver.addOnGlobalFocusChangeListener { _, newFocus ->
            val isInside = isDescendantOf(newFocus, focusTrigger) || newFocus === focusTrigger
            
            if (isInside) {
                performAnimation(true)
            } else {
                // Focus left the sidebar. Use grace period to see if it refocuses
                focusTrigger.postDelayed({
                    val currentFocus = focusTrigger.findFocus()
                    if (!isDescendantOf(currentFocus, focusTrigger) && currentFocus !== focusTrigger && !isLockedState) {
                        performAnimation(false)
                    }
                }, FOCUS_GRACE_PERIOD_MS)
            }
        }
    }

    /**
     * Lock or unlock the sidebar in its current state (prevent auto-collapse).
     */
    fun setLocked(locked: Boolean) {
        isLockedState = locked
    }

    /**
     * Explicitly close the sidebar regardless of focus or lock (e.g., from a back button click).
     */
    fun collapse(sidebarContainer: View, titleArea: View? = null, onExpandedChanged: ((Boolean) -> Unit)? = null) {
        val collapsedWidth = sidebarContainer.resources.getDimensionPixelSize(R.dimen.sidebar_width_collapsed)
        if (isExpandedState) {
            isExpandedState = false
            isLockedState = false // Force unlock on manual collapse
            onExpandedChanged?.invoke(false)
            
            currentAnimator?.cancel()
            currentAnimator = ValueAnimator.ofInt(sidebarContainer.width, collapsedWidth).apply {
                duration = ANIMATION_DURATION
                interpolator = FastOutSlowInInterpolator()
                addUpdateListener { anim ->
                    val params = sidebarContainer.layoutParams
                    params.width = anim.animatedValue as Int
                    sidebarContainer.layoutParams = params
                }
                start()
            }

            titleArea?.animate()
                ?.alpha(0f)
                ?.setDuration(ANIMATION_DURATION)
                ?.setInterpolator(FastOutSlowInInterpolator())
                ?.withEndAction { titleArea.visibility = View.GONE }
                ?.start()
        }
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
}
