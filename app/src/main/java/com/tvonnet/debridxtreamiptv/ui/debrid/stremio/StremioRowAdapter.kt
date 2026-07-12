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
    private val onSeeAll: (DebridRow) -> Unit
) : RecyclerView.Adapter<StremioRowAdapter.VH>() {

    private var rows: List<DebridRow> = emptyList()
    private val pool = RecyclerView.RecycledViewPool()

    init { setHasStableIds(true) }

    fun submit(list: List<DebridRow>) { rows = list; notifyDataSetChanged() }

    override fun getItemCount() = rows.size

    // Stable ids let RecyclerView keep the focused ViewHolder across a rebind (CW refresh on resume)
    // instead of detaching it and dropping D-pad focus.
    override fun getItemId(position: Int) = rows[position].id.hashCode().toLong()

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val v = LayoutInflater.from(parent.context).inflate(R.layout.item_stremio_row, parent, false)
        return VH(v)
    }

    override fun onBindViewHolder(holder: VH, position: Int) = holder.bind(rows[position])

    inner class VH(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val title: TextView = itemView.findViewById(R.id.row_title)
        private val seeAll: View = itemView.findViewById(R.id.row_see_all)
        private val rv: RecyclerView = itemView.findViewById(R.id.rv_stremio_row_items)
        private val posterAdapter = StremioRowPosterAdapter(onItemClick)

        init {
            rv.layoutManager = LinearLayoutManager(itemView.context, LinearLayoutManager.HORIZONTAL, false)
            rv.adapter = posterAdapter
            rv.setRecycledViewPool(pool)
            rv.itemAnimator = null
            StremioFocus.attach(seeAll, 1.05f)
        }

        fun bind(row: DebridRow) {
            title.text = row.title
            seeAll.isVisible = row.hasSeeAll
            seeAll.setOnClickListener { onSeeAll(row) }
            posterAdapter.submit(row.items.filter { it.type != "see_all" })
        }
    }
}
