package com.tvonnet.debridxtreamiptv.ui.debrid.stremio

import android.view.View
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.tvonnet.debridxtreamiptv.R
import com.tvonnet.debridxtreamiptv.ui.debrid.DebridRow

/** Home tab: vertical scrolling list of Debrid catalog rows (Stremio style). */
internal class StremioHomeSection(
    private val fragment: StremioHomeFragment,
    private val root: View,
    private val actions: StremioDebridActions
) {
    private val ctx = root.context
    private val rowsRv: RecyclerView = root.findViewById(R.id.homeRows)

    private val rowAdapter = StremioRowAdapter(
        onItemClick = { actions.click(it) },
        onSeeAll = { actions.seeAll(it) }
    )

    init {
        rowsRv.layoutManager = LinearLayoutManager(ctx)
        rowsRv.adapter = rowAdapter
        rowsRv.itemAnimator = null
        rowsRv.setHasFixedSize(false)
    }

    fun bind(rows: List<DebridRow>) {
        rowAdapter.submit(rows)
    }

    /** First focusable in this tab, for hero↔content wiring. */
    fun firstFocusable(): View = rowsRv
}
