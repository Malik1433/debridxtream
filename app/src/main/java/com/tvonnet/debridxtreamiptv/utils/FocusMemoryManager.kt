package com.tvonnet.debridxtreamiptv.utils

import android.view.View
import androidx.recyclerview.widget.RecyclerView
import java.util.WeakHashMap

/**
 * A utility to track and restore focus memory when navigating away and coming "Back" to a screen.
 * Particularly useful for deep RecyclerView hierarchies or Fragment replacements.
 */
object FocusMemoryManager {

    // WeakHashMap prevents memory leaks if the views/containers are destroyed
    private val memoryMap = WeakHashMap<View, Int>()

    /**
     * Call this when navigating *away* from a view container (like an Activity or Fragment).
     * It finds the currently focused view inside the container and saves its ID.
     */
    fun saveFocus(container: View) {
        val focusedView = container.findFocus()
        if (focusedView != null && focusedView.id != View.NO_ID) {
            memoryMap[container] = focusedView.id
        }
    }

    /**
     * Call this when returning *back* to a view container (e.g., in onResume or onViewCreated).
     * It looks up the previously saved View ID and attempts to request focus on it.
     */
    fun restoreFocus(container: View): Boolean {
        val savedId = memoryMap[container]
        if (savedId != null) {
            val targetView = container.findViewById<View>(savedId)
            if (targetView != null && targetView.isFocusable) {
                targetView.requestFocus()
                return true
            }
        }
        return false
    }

    /**
     * A broader helper that automatically applies the correct state restoration policy 
     * to a RecyclerView adapter to ensure it retains its scroll position.
     * 
     * Usage: adapter.applyFocusMemory()
     */
    fun RecyclerView.Adapter<*>.applyFocusMemory() {
        this.stateRestorationPolicy = RecyclerView.Adapter.StateRestorationPolicy.PREVENT_WHEN_EMPTY
    }
}
