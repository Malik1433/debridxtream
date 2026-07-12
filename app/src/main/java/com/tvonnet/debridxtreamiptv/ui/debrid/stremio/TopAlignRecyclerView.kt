package com.tvonnet.debridxtreamiptv.ui.debrid.stremio

import android.content.Context
import android.graphics.Rect
import android.util.AttributeSet
import android.view.KeyEvent
import android.view.View
import androidx.core.view.isVisible
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.tvonnet.debridxtreamiptv.R

/**
 * Vertical rows list for the Stremio home. It **owns all UP/DOWN D-pad traversal** so movement is
 * deterministic on a TV, instead of relying on FocusFinder geometry (which, with a pinned hero above
 * a list of nested horizontal RecyclerViews, made focus jump to a random poster / to the hero / vanish).
 *
 * Guarantees:
 *  - DOWN/UP move exactly one row and **preserve the horizontal column** (remembered index).
 *  - The focused row is **top-aligned** just under the pinned hero (`paddingTop`), so its header is
 *    never cut.
 *  - Entering the grid from the hero always lands on row 0 at the remembered column.
 *  - RIGHT past the last poster reaches the row's "See all"; LEFT from "See all" returns to it.
 */
internal class TopAlignRecyclerView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null, defStyle: Int = 0
) : RecyclerView(context, attrs, defStyle) {

    private var lastColumn = 0
    private var pendingRow = NO_POSITION

    // Suppress the default "reveal the focused descendant" scroll — we position rows ourselves, and
    // the default fights the top-align (partial/over-scroll = cut headers).
    override fun requestChildRectangleOnScreen(child: View, rect: Rect, immediate: Boolean): Boolean = false

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (event.action == KeyEvent.ACTION_DOWN) {
            when (event.keyCode) {
                KeyEvent.KEYCODE_DPAD_DOWN -> if (moveVertical(+1)) return true
                KeyEvent.KEYCODE_DPAD_UP -> if (moveVertical(-1)) return true
                KeyEvent.KEYCODE_DPAD_RIGHT -> if (moveToSeeAll()) return true
                KeyEvent.KEYCODE_DPAD_LEFT -> if (moveFromSeeAll()) return true
            }
        }
        return super.dispatchKeyEvent(event)
    }

    /** Move one row in [dir] (+1 down / -1 up), preserving the column. @return true if handled. */
    private fun moveVertical(dir: Int): Boolean {
        val focused = findFocus() ?: return false
        val rowView = findContainingItemView(focused) ?: return false
        val rowPos = getChildAdapterPosition(rowView)
        if (rowPos == NO_POSITION) return false

        rememberColumn(rowView, focused)

        val targetPos = rowPos + dir
        if (targetPos < 0) {                        // UP from row 0 → hero Play (fallback to default).
            val play = rootView?.findViewById<View>(R.id.heroPlayBtn)
            return play?.requestFocus() ?: false
        }
        if (targetPos >= (adapter?.itemCount ?: 0)) return true  // DOWN from last row → nowhere to go.

        focusRowColumn(targetPos, lastColumn)
        return true
    }

    /** RIGHT off the last poster of a row → its "See all" chip. */
    private fun moveToSeeAll(): Boolean {
        val focused = findFocus() ?: return false
        val rowView = findContainingItemView(focused) ?: return false
        val innerRv = rowView.findViewById<RecyclerView>(R.id.rv_stremio_row_items) ?: return false
        val posterView = innerRv.findContainingItemView(focused) ?: return false
        val col = innerRv.getChildAdapterPosition(posterView)
        val lastIdx = (innerRv.adapter?.itemCount ?: 0) - 1
        if (col == NO_POSITION || col != lastIdx) return false
        val seeAll = rowView.findViewById<View>(R.id.row_see_all) ?: return false
        if (!seeAll.isVisible || !seeAll.isFocusable) return false
        return seeAll.requestFocus()
    }

    /** LEFT from a "See all" chip → the last poster of the same row. */
    private fun moveFromSeeAll(): Boolean {
        val focused = findFocus() ?: return false
        if (focused.id != R.id.row_see_all) return false
        val rowView = findContainingItemView(focused) ?: return false
        val innerRv = rowView.findViewById<RecyclerView>(R.id.rv_stremio_row_items) ?: return false
        val lastIdx = (innerRv.adapter?.itemCount ?: 0) - 1
        if (lastIdx < 0) return false
        lastColumn = lastIdx
        innerRv.scrollToPosition(lastIdx)
        innerRv.post { focusPoster(innerRv, lastIdx) }
        return true
    }

    private fun rememberColumn(rowView: View, focused: View) {
        val innerRv = rowView.findViewById<RecyclerView>(R.id.rv_stremio_row_items) ?: return
        val posterView = innerRv.findContainingItemView(focused) ?: return
        val col = innerRv.getChildAdapterPosition(posterView)
        if (col != NO_POSITION) lastColumn = col
    }

    /** Top-align [rowPos] under the hero, then focus its poster at [column] (clamped). */
    private fun focusRowColumn(rowPos: Int, column: Int) {
        pendingRow = rowPos
        (layoutManager as? LinearLayoutManager)?.scrollToPositionWithOffset(rowPos, paddingTop)
        post {
            if (pendingRow != rowPos) return@post   // superseded by a later key press
            val rowView = findViewHolderForAdapterPosition(rowPos)?.itemView
                ?: layoutManager?.findViewByPosition(rowPos)
            val innerRv = rowView?.findViewById<RecyclerView>(R.id.rv_stremio_row_items)
            if (innerRv == null) { rowView?.requestFocus(); return@post }
            val n = innerRv.adapter?.itemCount ?: 0
            if (n == 0) { rowView.requestFocus(); return@post }
            val col = column.coerceIn(0, n - 1)
            innerRv.scrollToPosition(col)
            innerRv.post { if (pendingRow == rowPos) focusPoster(innerRv, col) }
        }
    }

    private fun focusPoster(innerRv: RecyclerView, col: Int) {
        val itemView = innerRv.findViewHolderForAdapterPosition(col)?.itemView ?: innerRv.getChildAt(0)
        (itemView?.findViewById<View>(R.id.poster_card) ?: itemView)?.requestFocus()
    }

    /** Deterministic entry from the hero: row 0 at the remembered column. */
    override fun onRequestFocusInDescendants(direction: Int, previouslyFocusedRect: Rect?): Boolean {
        if ((adapter?.itemCount ?: 0) <= 0) {
            return super.onRequestFocusInDescendants(direction, previouslyFocusedRect)
        }
        focusRowColumn(0, lastColumn)
        return true
    }
}
