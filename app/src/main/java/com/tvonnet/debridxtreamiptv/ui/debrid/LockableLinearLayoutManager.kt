package com.tvonnet.debridxtreamiptv.ui.debrid

import android.content.Context
import android.view.View
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView

/**
 * Custom LayoutManager that prevents focus from escaping the RecyclerView
 * when the user navigates past the last item (Focus Trap).
 */
class LockableLinearLayoutManager(context: Context) : LinearLayoutManager(context, HORIZONTAL, false) {

    override fun onFocusSearchFailed(
        focused: View,
        focusDirection: Int,
        recycler: RecyclerView.Recycler,
        state: RecyclerView.State
    ): View? {
        // Trap Focus:
        // If the user presses RIGHT (FOCUS_RIGHT), and we can't find a next view within this list...
        if (focusDirection == View.FOCUS_RIGHT) {
            
            // Check if we are really at the end.
            val pos = getPosition(focused)
            val count = itemCount
            
            // If we are at the last item (or effectively the last item before simple loading logic)
            // Just returning 'focused' tells the system: "The next focus is ME".
            // So focus stays right here. Same effect as "Jumping to self".
            // This prevents the search from bubbling up to the parent Vertical RecyclerView.
            if (pos != RecyclerView.NO_POSITION) {
                 return focused
            }
        }
        
        // Otherwise invoke standard behavior (which might bubble up)
        return super.onFocusSearchFailed(focused, focusDirection, recycler, state)
    }
}
