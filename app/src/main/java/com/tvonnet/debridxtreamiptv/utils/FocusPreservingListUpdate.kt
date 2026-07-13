package com.tvonnet.debridxtreamiptv.utils

import android.view.View
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

    post {
        val a = adapter ?: return@post
        val count = a.itemCount
        if (count <= 0) return@post                 // nothing to focus; leave as-is
        if (isFocusInsideThis()) return@post          // RecyclerView already recovered focus

        var target = RecyclerView.NO_POSITION
        if (focusedId != RecyclerView.NO_ID && a.hasStableIds()) {
            var i = 0
            while (i < count) {
                if (a.getItemId(i) == focusedId) { target = i; break }
                i++
            }
        }
        if (target == RecyclerView.NO_POSITION) {
            if (focusedIndex == RecyclerView.NO_POSITION) return@post
            target = focusedIndex.coerceIn(0, count - 1) // focused item vanished → nearest surviving row
        }
        focusItemAt(target)
    }
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
