package com.tvonnet.debridxtreamiptv.utils

import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup

/**
 * A utility class to create strict focus traps for side menus, dialogs, and bottom sheets.
 * Ensures that directional navigation (D-Pad) cannot escape the boundaries of the provided container,
 * either by consuming the event or looping focus back around.
 */
object FocusTrapHelper {

    /**
     * Attaches a strict focus trap to the given container.
     * 
     * @param container The ViewGroup (modal, menu, dialog) to trap focus inside.
     * @param loopFocus If true, reaching the edge will loop focus to the opposite side.
     *                  If false, reaching the edge will simply consume the event (hard stop).
     */
    fun attachFocusTrap(container: ViewGroup, loopFocus: Boolean = true) {
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

            // Ask the system where it WANTS to go next
            val nextFocus = currentFocus.focusSearch(direction)

            // If the system found nothing, or found a view OUTSIDE our container, we trap it.
            if (nextFocus == null || !isChildOf(container, nextFocus)) {
                if (loopFocus) {
                    val loopedView = findLoopedFocus(container, currentFocus, direction)
                    if (loopedView != null && loopedView != currentFocus) {
                        loopedView.requestFocus()
                        return@setOnKeyListener true // Event handled, focus looped
                    }
                }
                
                // If loop is false, or we couldn't find a loop target, 
                // we consume the event to create a "brick wall" hard stop.
                return@setOnKeyListener true 
            }

            // Normal navigation INSIDE the container is allowed to proceed
            false
        }
    }

    private fun isChildOf(parent: ViewGroup, child: View): Boolean {
        var currentParent = child.parent
        while (currentParent != null) {
            if (currentParent === parent) return true
            currentParent = currentParent.parent
        }
        return false
    }

    /**
     * Finds the furthest focusable view in the OPPOSITE direction to create a loop effect.
     * e.g., if trapped at the top and pressing UP, find the bottom-most focusable view.
     */
    private fun findLoopedFocus(container: ViewGroup, current: View, direction: Int): View? {
        // The search direction for the loop is the OPPOSITE of the movement direction
        val searchDirection = when (direction) {
            View.FOCUS_UP -> View.FOCUS_DOWN
            View.FOCUS_DOWN -> View.FOCUS_UP
            View.FOCUS_LEFT -> View.FOCUS_RIGHT
            View.FOCUS_RIGHT -> View.FOCUS_LEFT
            else -> return null
        }

        val focusables = container.getFocusables(searchDirection)
        if (focusables.isEmpty()) return null

        var furthestView: View? = null
        var extremeValue = when (searchDirection) {
            View.FOCUS_UP, View.FOCUS_LEFT -> Int.MAX_VALUE // Looking for smallest coordinate
            View.FOCUS_DOWN, View.FOCUS_RIGHT -> Int.MIN_VALUE // Looking for largest coordinate
            else -> 0
        }

        for (candidate in focusables) {
            if (candidate === current) continue

            val loc = IntArray(2)
            candidate.getLocationOnScreen(loc)

            when (searchDirection) {
                View.FOCUS_UP -> { // Finding the top-most view
                    if (loc[1] < extremeValue) {
                        extremeValue = loc[1]
                        furthestView = candidate
                    }
                }
                View.FOCUS_DOWN -> { // Finding the bottom-most view
                    if (loc[1] > extremeValue) {
                        extremeValue = loc[1]
                        furthestView = candidate
                    }
                }
                View.FOCUS_LEFT -> { // Finding the left-most view
                    if (loc[0] < extremeValue) {
                        extremeValue = loc[0]
                        furthestView = candidate
                    }
                }
                View.FOCUS_RIGHT -> { // Finding the right-most view
                    if (loc[0] > extremeValue) {
                        extremeValue = loc[0]
                        furthestView = candidate
                    }
                }
            }
        }

        return furthestView
    }
}
