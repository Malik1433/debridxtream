package com.tvonnet.debridxtreamiptv.ui.browse.phone

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import androidx.core.view.isVisible
import androidx.recyclerview.widget.RecyclerView
import com.bumptech.glide.Glide
import com.tvonnet.debridxtreamiptv.R
import com.tvonnet.debridxtreamiptv.data.model.ContinueWatchingItem

/**
 * The Continue Watching rail at the top of Browse.
 *
 * The card, the row header and the rail are Home's — same layouts, same 208x117 geometry, same
 * scrim and progress bar — because a user who came to Movies to finish something should not have
 * to learn a second visual language for the thing they were already watching.
 *
 * When the list is empty the whole block is REMOVED: header, rail and all. Not a placeholder, not
 * a "nothing here yet" line. An empty adapter reports zero items and the grid simply starts
 * higher.
 */
class PhoneBrowseContinueRow(
    private val title: String,
    private val onItem: (ContinueWatchingItem) -> Unit,
) : RecyclerView.Adapter<PhoneBrowseContinueRow.RowHolder>() {

    private var items: List<ContinueWatchingItem> = emptyList()

    class RowHolder(view: View) : RecyclerView.ViewHolder(view)

    fun submit(list: List<ContinueWatchingItem>) {
        val had = items.isNotEmpty()
        items = list
        if (had && list.isEmpty()) notifyItemRemoved(0)
        else if (!had && list.isNotEmpty()) notifyItemInserted(0)
        else if (list.isNotEmpty()) notifyItemChanged(0)
    }

    override fun getItemCount(): Int = if (items.isEmpty()) 0 else 1

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) = RowHolder(
        LayoutInflater.from(parent.context).inflate(R.layout.item_phone_row, parent, false)
    )

    override fun onBindViewHolder(holder: RowHolder, position: Int) {
        val root = holder.itemView
        root.findViewById<TextView>(R.id.phone_row_title).text = title
        root.findViewById<TextView>(R.id.phone_row_count).text = items.size.toString()
        val rail = root.findViewById<LinearLayout>(R.id.phone_row_rail)
        rail.removeAllViews()
        val inflater = LayoutInflater.from(root.context)
        items.forEach { item ->
            val card = inflater.inflate(R.layout.item_phone_continue, rail, false)
            card.findViewById<TextView>(R.id.phone_cw_title).text = item.title
            card.findViewById<TextView>(R.id.phone_cw_time).text =
                "${clock(item.currentPosition)} / ${clock(item.totalDuration)}"
            card.findViewById<ProgressBar>(R.id.phone_cw_progress).progress =
                if (item.totalDuration > 0) {
                    ((item.currentPosition * 100) / item.totalDuration).toInt().coerceIn(0, 100)
                } else {
                    0
                }
            card.findViewById<TextView>(R.id.phone_cw_sub).isVisible = false
            Glide.with(card).load(item.backdropUrl ?: item.posterUrl).centerCrop()
                .into(card.findViewById<ImageView>(R.id.phone_cw_image))
            card.findViewById<View>(R.id.phone_cw_tile).setOnClickListener { onItem(item) }
            rail.addView(card)
        }
    }

    private fun clock(ms: Long): String {
        val total = ms / 1000
        val h = total / 3600
        val m = (total % 3600) / 60
        val s = total % 60
        return if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%d:%02d".format(m, s)
    }
}

/**
 * First paint, at the exact geometry the real cards will land in.
 *
 * The point of a skeleton here is not decoration: an Xtream category load takes seconds, and the
 * alternative is either a blank screen or a spinner that says nothing about what is coming.
 * Because these blocks are the size of the real cards, nothing reflows when the page arrives.
 */
class PhoneBrowseSkeleton : RecyclerView.Adapter<PhoneBrowseSkeleton.SkeletonHolder>() {

    private var showing = false

    class SkeletonHolder(view: View) : RecyclerView.ViewHolder(view)

    fun show(visible: Boolean) {
        if (showing == visible) return
        showing = visible
        notifyDataSetChanged()
    }

    override fun getItemCount(): Int = if (showing) CARDS else 0

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) = SkeletonHolder(
        LayoutInflater.from(parent.context)
            .inflate(R.layout.item_phone_poster_skeleton, parent, false)
    )

    override fun onBindViewHolder(holder: SkeletonHolder, position: Int) {
        // Varied label widths, so nine identical blocks do not read as a table.
        val line = holder.itemView.findViewById<View>(R.id.phone_sk_line2)
        line.layoutParams = line.layoutParams.apply {
            width = (holder.itemView.resources.displayMetrics.density * WIDTHS[position % WIDTHS.size]).toInt()
        }
    }

    private companion object {
        const val CARDS = 9
        val WIDTHS = listOf(60, 44, 72, 52, 66, 38, 58, 70, 48)
    }
}
