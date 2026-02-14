package com.tvonnet.debridxtreamiptv.utils

import android.graphics.Rect
import android.view.View
import androidx.recyclerview.widget.RecyclerView

/**
 * GridSpacingItemDecoration - enforceable spacing for GridLayoutManager
 * 
 * Purpose: 
 * Ensures consistent gaps between items in a grid, regardless of item margins.
 * Especially useful for TV UIs where spacing must be precise for focus effects.
 */
class GridSpacingItemDecoration(
    private val spanCount: Int,
    private val spacing: Int, // in pixels/dp
    private val includeEdge: Boolean
) : RecyclerView.ItemDecoration() {

    override fun getItemOffsets(
        outRect: Rect,
        view: View,
        parent: RecyclerView,
        state: RecyclerView.State
    ) {
        val position = parent.getChildAdapterPosition(view) // item position
        if (position == RecyclerView.NO_POSITION) return

        val column = position % spanCount // item column

        if (includeEdge) {
            outRect.left = spacing - column * spacing / spanCount
            outRect.right = (column + 1) * spacing / spanCount

            if (position < spanCount) { // top edge
                outRect.top = spacing
            }
            outRect.bottom = spacing // item bottom
        } else {
            outRect.left = column * spacing / spanCount
            outRect.right = spacing - (column + 1) * spacing / spanCount
            if (position >= spanCount) {
                outRect.top = spacing // item top
            }
        }
    }
}
