package com.tvonnet.debridxtreamiptv.ui.debrid.stremio

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.core.view.isVisible
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.tvonnet.debridxtreamiptv.R
import com.tvonnet.debridxtreamiptv.ui.debrid.DebridContentItem
import com.tvonnet.debridxtreamiptv.ui.debrid.DebridRow

/** Vertical list of Debrid catalog rows, each a Stremio-styled horizontal poster row. */
internal class StremioRowAdapter(
    private val onItemClick: (DebridContentItem) -> Unit,
    private val onSeeAll: (DebridRow) -> Unit,
    /**
     * The phone's hero + provider strip, as the FIRST ITEM of this list (design frame 1a).
     *
     * It has to scroll with the rows, and it has to do that without turning the rails into a
     * NestedScrollView full of un-recycled posters. A header item is how a RecyclerView says
     * "this belongs to the page, not above it". Null on the television, whose hero is a fixed
     * band by design.
     */
    private val header: View? = null,
) : RecyclerView.Adapter<RecyclerView.ViewHolder>() {

    private var rows: List<DebridRow> = emptyList()
    private val pool = RecyclerView.RecycledViewPool()

    init { setHasStableIds(true) }

    fun submit(list: List<DebridRow>) { rows = list; notifyDataSetChanged() }

    private val headerCount: Int get() = if (header != null) 1 else 0

    override fun getItemCount() = rows.size + headerCount

    // Stable ids let RecyclerView keep the focused ViewHolder across a rebind (CW refresh on resume)
    // instead of detaching it and dropping D-pad focus.
    override fun getItemId(position: Int): Long =
        if (isHeader(position)) HEADER_ID else rows[position - headerCount].id.hashCode().toLong()

    private fun isHeader(position: Int) = header != null && position == 0

    override fun getItemViewType(position: Int) = if (isHeader(position)) TYPE_HEADER else TYPE_ROW

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        if (viewType == TYPE_HEADER) {
            // Detached from any previous parent: a header View outlives the ViewHolder that shows
            // it, and re-attaching a view that still has a parent throws.
            (header?.parent as? ViewGroup)?.removeView(header)
            return HeaderVH(header!!)
        }
        val v = LayoutInflater.from(parent.context).inflate(R.layout.item_stremio_row, parent, false)
        return VH(v)
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        if (holder is VH) holder.bind(rows[position - headerCount])
    }

    /** The header carries its own bindings; this holder only parks it in the list. */
    class HeaderVH(view: View) : RecyclerView.ViewHolder(view)

    private companion object {
        const val TYPE_HEADER = 0
        const val TYPE_ROW = 1
        const val HEADER_ID = -2L
    }

    inner class VH(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val title: TextView = itemView.findViewById(R.id.row_title)
        private val seeAll: View = itemView.findViewById(R.id.row_see_all)
        // Phone only: the count beside the row title, and the dashed card at the rail's end.
        private val count: TextView? = itemView.findViewById(R.id.row_count)
        private val isPhone = !itemView.resources.getBoolean(R.bool.ui_uses_dpad_focus)
        private val rv: RecyclerView = itemView.findViewById(R.id.rv_stremio_row_items)
        private var boundRow: DebridRow? = null
        private val posterAdapter = StremioRowPosterAdapter(
            onClick = onItemClick,
            onSeeAll = if (isPhone) {
                { boundRow?.takeIf { it.hasSeeAll }?.let(onSeeAll) }
            } else {
                null
            },
        )

        init {
            rv.layoutManager = LinearLayoutManager(itemView.context, LinearLayoutManager.HORIZONTAL, false)
            rv.adapter = posterAdapter
            rv.setRecycledViewPool(pool)
            rv.itemAnimator = null
            StremioFocus.attach(seeAll, 1.05f)
        }

        fun bind(row: DebridRow) {
            boundRow = row
            title.text = StremioRowTitles.displayTitle(title.context, row)
            // On the phone the header carries the COUNT — how much is in this rail is the question
            // a header answers — and it opens the full list only when there is one to open.
            count?.text = row.items.count { it.type != "see_all" }.toString()
            seeAll.isVisible = if (isPhone) true else row.hasSeeAll
            seeAll.isClickable = row.hasSeeAll
            seeAll.setOnClickListener { if (row.hasSeeAll) onSeeAll(row) }
            posterAdapter.submit(row.items.filter { it.type != "see_all" })
        }
    }
}
