package com.tvonnet.debridxtreamiptv.utils

import android.view.View
import androidx.core.view.OneShotPreDrawListener
import androidx.recyclerview.widget.RecyclerView

/**
 * Focus-preserving list updates for Android TV (D-pad only, no touch).
 *
 * The dominant TV focus bug across this app (see docs/reports/FOCUS_AUDIT_MASTER_PLAN.md,
 * pattern CC-1): adapters call `notifyDataSetChanged()` / `submitList()` together with
 * `scrollToPosition(0)` / `requestFocus()` on *every* async data emit or periodic tick
 * (e.g. a 30-second EPG refresh), ignoring where the user's focus currently is. A
 * background refresh then yanks focus away from whatever the user was doing.
 *
 * This helper mirrors the Home screen's proven approach (captureContentFocusSnapshot /
 * restoreContentFocusAfterDataUpdate) in one reusable place:
 *
 *  - If the user is **not** focused inside this RecyclerView, it runs the data update and
 *    touches nothing else — focus and scroll stay exactly where the user left them.
 *  - If the user **was** focused inside it, it restores focus after the update to the same
 *    item by **stable id** (requires `adapter.setHasStableIds(true)`); if that item is gone,
 *    it falls back to the nearest surviving index; if the list is now empty, it leaves focus
 *    alone rather than yanking to a default.
 *
 * Usage — replace the ad-hoc `adapter.submitX(); rv.scrollToPosition(0); rv.requestFocus()`
 * with:
 *
 *     recyclerView.updatePreservingFocus { adapter.submitItems(newItems) }
 *
 * Notes:
 *  - For stable-id restore to work the adapter should have stable ids. Without them the
 *    helper still prevents focus-steal and falls back to index-based restore.
 *  - The update is expected to be applied synchronously inside [applyUpdate] (the common
 *    `notifyDataSetChanged()` case). For async DiffUtil `submitList`, pass the restore via
 *    the submit callback instead, or keep the synchronous `notify*` path.
 */
fun RecyclerView.updatePreservingFocus(applyUpdate: () -> Unit) {
    val hadFocus = hasFocus()
    var focusedId = RecyclerView.NO_ID
    var focusedIndex = RecyclerView.NO_POSITION

    if (hadFocus) {
        focusedChild?.let { child ->
            findContainingViewHolder(child)?.let { holder ->
                focusedIndex = holder.bindingAdapterPosition
                focusedId = holder.itemId // valid only when the adapter has stable ids
            }
        }
    }

    applyUpdate()

    // User was elsewhere (sidebar, chips, another list) — never steal focus or scroll.
    if (!hadFocus) return
    if (focusedId == RecyclerView.NO_ID && focusedIndex == RecyclerView.NO_POSITION) return

    // MUST run after the layout pass, not on a bare post(). Device-proven (B2.2): for the common
    // synchronous notifyDataSetChanged() the posted runnable lands BEFORE the traversal, so the
    // removed row is still attached, "focus is already inside" reads true, we bail — and the
    // framework then drops focus to row 0. That is exactly the CC-1 jump this helper exists to
    // prevent, so the restore has to wait for the new layout.
    OneShotPreDrawListener.add(this) {
        val a = adapter ?: return@add
        val count = a.itemCount
        if (count <= 0) return@add                  // nothing to focus; leave as-is

        var target = RecyclerView.NO_POSITION
        if (focusedId != RecyclerView.NO_ID && a.hasStableIds()) {
            var i = 0
            while (i < count) {
                if (a.getItemId(i) == focusedId) { target = i; break }
                i++
            }
        }
        if (target == RecyclerView.NO_POSITION) {
            if (focusedIndex == RecyclerView.NO_POSITION) return@add
            target = focusedIndex.coerceIn(0, count - 1) // focused item vanished → nearest surviving row
        }
        // Only correct focus when it is not already on the right item — when RecyclerView's own
        // recovery got it right there is nothing to do, and we must not fight it.
        if (isFocusOnItemAt(target)) return@add
        focusItemAt(target)
    }
}

/** True when the currently focused view is (inside) the item at [position]. */
private fun RecyclerView.isFocusOnItemAt(position: Int): Boolean {
    val focused = findFocus() ?: return false
    val item = findContainingItemView(focused) ?: return false
    return getChildAdapterPosition(item) == position
}

/** True if the current focus lives inside this RecyclerView's view subtree. */
fun RecyclerView.isFocusInsideThis(): Boolean {
    val focused = findFocus() ?: return false
    var v: View? = focused
    while (v != null) {
        if (v === this) return true
        v = v.parent as? View
    }
    return false
}

private fun RecyclerView.focusItemAt(position: Int) {
    val lm = layoutManager ?: return
    lm.findViewByPosition(position)?.let { if (it.requestFocus()) return }
    scrollToPosition(position)
    post { layoutManager?.findViewByPosition(position)?.requestFocus() }
}
