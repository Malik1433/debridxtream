package com.tvonnet.debridxtreamiptv.utils

import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup

/**
 * A utility class to enforce strict orthogonal (Up/Down/Left/Right) 
 * 2D spatial navigation and prevent unpredictable diagonal jumping on D-Pads.
 */
object SpatialNavigationEngine {

    /**
     * Attaches an OnKeyListener to a ViewGroup (like RecyclerView or ConstraintLayout)
     * that intercepts D-Pad events and strictly forces nearest-neighbor focus finding
     * along the true X or Y axis, rejecting diagonal visual neighbors.
     */
    fun enforceStrictOrthogonalNavigation(container: ViewGroup) {
        container.setOnKeyListener { v, keyCode, event ->
            if (event.action != KeyEvent.ACTION_DOWN) return@setOnKeyListener false

            val currentFocus = container.findFocus() ?: return@setOnKeyListener false
            
            val direction = when (keyCode) {
                KeyEvent.KEYCODE_DPAD_UP -> View.FOCUS_UP
                KeyEvent.KEYCODE_DPAD_DOWN -> View.FOCUS_DOWN
                KeyEvent.KEYCODE_DPAD_LEFT -> View.FOCUS_LEFT
                KeyEvent.KEYCODE_DPAD_RIGHT -> View.FOCUS_RIGHT
                else -> return@setOnKeyListener false
            }

            // Let the system find the next focus first
            val systemNextFocus = currentFocus.focusSearch(direction)
            
            // If the system found nothing, or found something outside our container, let the system handle it loosely
            if (systemNextFocus == null || !container.isAncestorOf(systemNextFocus)) {
                return@setOnKeyListener false
            }

            // If the system found a valid view inside our container, let's verify if it's strictly orthogonal.
            if (isStrictlyOrthogonal(currentFocus, systemNextFocus, direction)) {
                // If it is strictly orthogonal, just request focus on it.
                // We consume the event by returning true, but we performed the action.
                systemNextFocus.requestFocus()
                return@setOnKeyListener true
            } else {
                // If it's a DIAGONAL jump, we actively block the system's choice by consuming the event 
                // but doing nothing (or we could try to find a *better* one, but strict blocking is safer to prevent wild jumps).
                
                // Optional Enhancement: We could do a custom manual search here for the *actual* closest strictly orthogonal view
                val customNextFocus = findNearestOrthogonalFocus(container, currentFocus, direction)
                if (customNextFocus != null && customNextFocus != currentFocus) {
                     customNextFocus.requestFocus()
                }
                
                return@setOnKeyListener true // Consume to prevent the diagonal jump
            }
        }
    }

    private fun View.isAncestorOf(child: View): Boolean {
        var parent = child.parent
        while (parent != null) {
            if (parent === this) return true
            parent = parent.parent
        }
        return false
    }

    private fun isStrictlyOrthogonal(current: View, next: View, direction: Int): Boolean {
        val currentLoc = IntArray(2)
        val nextLoc = IntArray(2)
        current.getLocationOnScreen(currentLoc)
        next.getLocationOnScreen(nextLoc)

        val currentMidX = currentLoc[0] + current.width / 2
        val currentMidY = currentLoc[1] + current.height / 2
        
        val nextMidX = nextLoc[0] + next.width / 2
        val nextMidY = nextLoc[1] + next.height / 2

        // A jump is diagonal if BOTH X and Y change significantly.
        // We define "significantly" as being outside the bounding box of the current view 
        // on the axis that ISN'T supposed to be moving.
        
        return when (direction) {
            View.FOCUS_UP, View.FOCUS_DOWN -> {
                // Moving Vertically. The X should remain roughly the same.
                // It's strictly vertical if the next view's X bounds overlap the current view's X bounds.
                val nextLeft = nextLoc[0]
                val nextRight = nextLoc[0] + next.width
                val currentLeft = currentLoc[0]
                val currentRight = currentLoc[0] + current.width
                
                // Check for horizontal overlapping
                (nextRight > currentLeft && nextLeft < currentRight)
            }
            View.FOCUS_LEFT, View.FOCUS_RIGHT -> {
                // Moving Horizontally. The Y should remain roughly the same.
                // It's strictly horizontal if the next view's Y bounds overlap the current view's Y bounds.
                val nextTop = nextLoc[1]
                val nextBottom = nextLoc[1] + next.height
                val currentTop = currentLoc[1]
                val currentBottom = currentLoc[1] + current.height
                
                // Check for vertical overlapping
                (nextBottom > currentTop && nextTop < currentBottom)
            }
            else -> true // Fallback
        }
    }
    
     private fun findNearestOrthogonalFocus(container: ViewGroup, current: View, direction: Int): View? {
        val focusables = container.getFocusables(direction)
        var nearest: View? = null
        var minDistance = Int.MAX_VALUE

        val currentLoc = IntArray(2)
        current.getLocationOnScreen(currentLoc)
        val currentMidX = currentLoc[0] + current.width / 2
        val currentMidY = currentLoc[1] + current.height / 2
        
        val currentLeft = currentLoc[0]
        val currentRight = currentLoc[0] + current.width
        val currentTop = currentLoc[1]
        val currentBottom = currentLoc[1] + current.height

        for (candidate in focusables) {
            if (candidate === current) continue

            val candidateLoc = IntArray(2)
            candidate.getLocationOnScreen(candidateLoc)
            val candidateMidX = candidateLoc[0] + candidate.width / 2
            val candidateMidY = candidateLoc[1] + candidate.height / 2
            
            val candidateLeft = candidateLoc[0]
            val candidateRight = candidateLoc[0] + candidate.width
            val candidateTop = candidateLoc[1]
            val candidateBottom = candidateLoc[1] + candidate.height

            val isOrthogonal = when (direction) {
                View.FOCUS_UP -> candidateMidY < currentMidY && (candidateRight > currentLeft && candidateLeft < currentRight)
                View.FOCUS_DOWN -> candidateMidY > currentMidY && (candidateRight > currentLeft && candidateLeft < currentRight)
                View.FOCUS_LEFT -> candidateMidX < currentMidX && (candidateBottom > currentTop && candidateTop < currentBottom)
                View.FOCUS_RIGHT -> candidateMidX > currentMidX && (candidateBottom > currentTop && candidateTop < currentBottom)
                else -> false
            }

            if (isOrthogonal) {
                // Calculate distance based on the PRIMARY axis of movement
                val distance = when (direction) {
                    View.FOCUS_UP, View.FOCUS_DOWN -> Math.abs(candidateMidY - currentMidY)
                    View.FOCUS_LEFT, View.FOCUS_RIGHT -> Math.abs(candidateMidX - currentMidX)
                    else -> Int.MAX_VALUE
                }

                if (distance < minDistance) {
                    minDistance = distance
                    nearest = candidate
                }
            }
        }
        return nearest
    }
}
