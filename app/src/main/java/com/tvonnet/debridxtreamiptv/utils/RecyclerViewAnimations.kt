package com.tvonnet.debridxtreamiptv.utils

import androidx.recyclerview.widget.DefaultItemAnimator
import androidx.recyclerview.widget.RecyclerView
import com.tvonnet.debridxtreamiptv.R

/**
 * RecyclerView Animations Utility - Week 13: UI Polish
 * 
 * Purpose:
 * - Provide smooth item animations for RecyclerViews
 * - Enhance user experience
 * - Add polish to list/grid transitions
 * 
 * Usage:
 * ```kotlin
 * recyclerView.itemAnimator = RecyclerViewAnimations.createDefaultAnimator()
 * ```
 */
object RecyclerViewAnimations {
    
    /**
     * Create default item animator with smooth animations
     * - Slide up on add
     * - Fade out on remove
     * - Smooth change animations
     */
    fun createDefaultAnimator(): RecyclerView.ItemAnimator {
        return DefaultItemAnimator().apply {
            addDuration = 300 // ms
            removeDuration = 300 // ms
            moveDuration = 300 // ms
            changeDuration = 300 // ms
        }
    }
    
    /**
     * Apply animations to RecyclerView
     * Sets up item animator and layout animations
     */
    fun applyAnimations(recyclerView: RecyclerView) {
        recyclerView.itemAnimator = createDefaultAnimator()
        
        // Week 13: Layout animation for initial items
        val layoutAnimation = android.view.animation.AnimationUtils.loadLayoutAnimation(
            recyclerView.context,
            R.anim.layout_animation_slide_up
        )
        recyclerView.layoutAnimation = layoutAnimation
    }
    
    /**
     * Create custom item animator with specific durations
     */
    fun createCustomAnimator(
        addDuration: Long = 300,
        removeDuration: Long = 300,
        moveDuration: Long = 300,
        changeDuration: Long = 300
    ): RecyclerView.ItemAnimator {
        return DefaultItemAnimator().apply {
            this.addDuration = addDuration
            this.removeDuration = removeDuration
            this.moveDuration = moveDuration
            this.changeDuration = changeDuration
        }
    }
}

