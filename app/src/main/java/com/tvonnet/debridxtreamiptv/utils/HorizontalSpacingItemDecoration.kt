package com.tvonnet.debridxtreamiptv.utils

import android.graphics.Rect
import android.view.View
import androidx.recyclerview.widget.RecyclerView

/**
 * HorizontalSpacingItemDecoration - consistent spacing for linear horizontal lists
 */
class HorizontalSpacingItemDecoration(private val spacing: Int) : RecyclerView.ItemDecoration() {
    override fun getItemOffsets(
        outRect: Rect,
        view: View,
        parent: RecyclerView,
        state: RecyclerView.State
    ) {
        val position = parent.getChildAdapterPosition(view)
        if (position == RecyclerView.NO_POSITION) return

        // Add spacing to the right of every item
        outRect.right = spacing
        
        // Add spacing to the left of the first item to maintain symmetry if needed
        if (position == 0) {
            outRect.left = spacing
        }
        
        // Vertical spacing to ensure glow isn't clipped
        outRect.top = spacing / 4
        outRect.bottom = spacing / 4
    }
}
