package com.tvonnet.debridxtreamiptv.ui.debrid.stremio

import android.graphics.drawable.GradientDrawable
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.bumptech.glide.Glide
import com.tvonnet.debridxtreamiptv.R

internal data class StremioSearchResult(
    val title: String,
    val meta: String,
    val type: String,
    val typeBg: Int,
    val posterUrl: String?,
    val onClick: () -> Unit
)

internal class StremioSearchResultAdapter : RecyclerView.Adapter<StremioSearchResultAdapter.VH>() {

    private var items: List<StremioSearchResult> = emptyList()

    fun submit(list: List<StremioSearchResult>) { items = list; notifyDataSetChanged() }

    override fun getItemCount() = items.size

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val v = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_stremio_search_result, parent, false)
        return VH(v)
    }

    override fun onBindViewHolder(holder: VH, position: Int) = holder.bind(items[position], position)

    inner class VH(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val card: View = itemView.findViewById(R.id.sr_card)
        private val image: ImageView = itemView.findViewById(R.id.sr_image)
        private val overlay: View = itemView.findViewById(R.id.sr_overlay)
        private val type: TextView = itemView.findViewById(R.id.sr_type)
        private val title: TextView = itemView.findViewById(R.id.sr_title)
        private val meta: TextView = itemView.findViewById(R.id.sr_meta)
        private val density = itemView.resources.displayMetrics.density

        fun bind(r: StremioSearchResult, position: Int) {
            card.background = StremioGradients.cardBg(StremioPalette.forIndex(position), 5f, density)
            overlay.background = StremioGradients.topFade(0xF706090E.toInt(), 0x1A06090E, 0.5f, 5f, density)
            title.text = r.title
            meta.text = r.meta
            type.text = r.type
            type.background = GradientDrawable().apply { cornerRadius = 1.5f * density; setColor(r.typeBg) }
            Glide.with(image).load(r.posterUrl).into(image)
            card.setOnClickListener { r.onClick() }
            card.setOnFocusChangeListener { v, has ->
                v.z = if (has) 8f else 0f
            }
        }
    }
}
