package com.tvonnet.debridxtreamiptv.ui.debrid.stremio

import android.graphics.drawable.GradientDrawable
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.core.view.isVisible
import androidx.recyclerview.widget.RecyclerView
import com.bumptech.glide.Glide
import com.tvonnet.debridxtreamiptv.R

internal data class StremioSearchResult(
    val title: String,
    val sub: String,
    val typeLabel: String,
    val typeColor: Int,
    val typeBg: Int,
    val typeBorder: Int,
    val isLive: Boolean,
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
        private val live: View = itemView.findViewById(R.id.sr_live)
        private val title: TextView = itemView.findViewById(R.id.sr_title)
        private val meta: TextView = itemView.findViewById(R.id.sr_meta)
        private val d = itemView.resources.displayMetrics.density

        fun bind(r: StremioSearchResult, position: Int) {
            // rounded poster: placeholder gradient doubles as the clip outline
            image.background = GradientDrawable().apply {
                cornerRadius = 4 * d
                colors = intArrayOf(StremioPalette.forIndex(position), 0xFF0B0F17.toInt())
                orientation = GradientDrawable.Orientation.TL_BR
            }
            image.clipToOutline = true
            image.setImageDrawable(null)
            Glide.with(image).load(r.posterUrl).into(image)

            // bottom-up scrim so the type badge stays legible
            overlay.background = GradientDrawable(
                GradientDrawable.Orientation.BOTTOM_TOP,
                intarr(0xE606090E.toInt(), 0x0006090E)
            )

            title.text = r.title
            meta.text = r.sub
            type.text = r.typeLabel
            type.setTextColor(r.typeColor)
            type.background = GradientDrawable().apply {
                cornerRadius = 1.5f * d
                setColor(r.typeBg)
                setStroke((1 * d).toInt(), r.typeBorder)
            }
            live.isVisible = r.isLive

            card.setOnClickListener { r.onClick() }
            card.setOnFocusChangeListener { v, has ->
                v.z = if (has) 8f else 0f
                v.animate().translationY(if (has) -3 * d else 0f).setDuration(140).start()
            }
        }

        private fun intarr(vararg c: Int) = c
    }
}
