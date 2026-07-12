package com.tvonnet.debridxtreamiptv.ui.debrid.stremio

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.bumptech.glide.Glide
import com.bumptech.glide.load.resource.bitmap.RoundedCorners
import com.tvonnet.debridxtreamiptv.R

internal data class StremioTop10Row(
    val rank: String,
    val rankColor: Int,
    val title: String,
    val meta: String,
    val trend: String,
    val trendColor: Int,
    val posterUrl: String?,
    val upcoming: Boolean,
    val onClick: () -> Unit
)

internal class StremioDiscoverTop10Adapter(
    private val onClick: (StremioTop10Row) -> Unit
) : RecyclerView.Adapter<StremioDiscoverTop10Adapter.VH>() {

    private var items: List<StremioTop10Row> = emptyList()

    fun submit(list: List<StremioTop10Row>) { items = list; notifyDataSetChanged() }

    override fun getItemCount() = items.size

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val v = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_stremio_discover_top10, parent, false)
        return VH(v)
    }

    override fun onBindViewHolder(holder: VH, position: Int) = holder.bind(items[position], position)

    inner class VH(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val row: View = itemView.findViewById(R.id.top10_row)
        private val rank: TextView = itemView.findViewById(R.id.top10_rank)
        private val thumb: ImageView = itemView.findViewById(R.id.top10_thumb)
        private val title: TextView = itemView.findViewById(R.id.top10_title)
        private val meta: TextView = itemView.findViewById(R.id.top10_meta)
        private val trend: TextView = itemView.findViewById(R.id.top10_trend)
        private val density = itemView.resources.displayMetrics.density

        fun bind(r: StremioTop10Row, position: Int) {
            rank.text = r.rank
            rank.setTextColor(r.rankColor)
            title.text = r.title
            meta.text = r.meta
            meta.setTextColor(if (r.upcoming) 0xFFFFAA00.toInt() else 0xFF475569.toInt())
            trend.text = r.trend
            trend.setTextColor(r.trendColor)
            thumb.background = StremioGradients.cardBg(StremioPalette.forIndex(position), 3f, density, 0x00000000)
            Glide.with(thumb).load(r.posterUrl)
                .transform(RoundedCorners((3f * density).toInt()))
                .into(thumb)
            row.setOnClickListener { onClick(r) }
        }
    }
}
