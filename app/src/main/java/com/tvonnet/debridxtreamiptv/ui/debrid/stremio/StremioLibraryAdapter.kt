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

internal data class StremioLibraryCard(
    val title: String,
    val year: String,
    val status: String,
    val statusColor: Int,
    val statusBg: Int,
    val hasProgress: Boolean,
    val progress: Int,
    val posterUrl: String?,
    val onClick: (() -> Unit)?
)

internal class StremioLibraryAdapter : RecyclerView.Adapter<StremioLibraryAdapter.VH>() {

    private var items: List<StremioLibraryCard> = emptyList()

    fun submit(list: List<StremioLibraryCard>) { items = list; notifyDataSetChanged() }

    override fun getItemCount() = items.size

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val v = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_stremio_library_card, parent, false)
        return VH(v)
    }

    override fun onBindViewHolder(holder: VH, position: Int) = holder.bind(items[position], position)

    inner class VH(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val card: View = itemView.findViewById(R.id.lib_card)
        private val image: ImageView = itemView.findViewById(R.id.lib_image)
        private val overlay: View = itemView.findViewById(R.id.lib_overlay)
        private val status: TextView = itemView.findViewById(R.id.lib_status)
        private val title: TextView = itemView.findViewById(R.id.lib_title)
        private val year: TextView = itemView.findViewById(R.id.lib_year)
        private val progressWrap: View = itemView.findViewById(R.id.lib_progress_wrap)
        private val progressFill: View = itemView.findViewById(R.id.lib_progress_fill)
        private val density = itemView.resources.displayMetrics.density

        fun bind(c: StremioLibraryCard, position: Int) {
            card.background = StremioGradients.cardBg(StremioPalette.forIndex(position), 5f, density)
            overlay.background = StremioGradients.topFade(0xF706090E.toInt(), 0x00000000, 0.55f, 5f, density)
            title.text = c.title
            year.text = c.year
            status.text = c.status
            status.setTextColor(c.statusColor)
            status.background = GradientDrawable().apply { cornerRadius = 1.5f * density; setColor(c.statusBg) }
            Glide.with(image).load(c.posterUrl)
                .format(com.bumptech.glide.load.DecodeFormat.PREFER_RGB_565).into(image) // IMG-1

            progressWrap.isVisible = c.hasProgress
            if (c.hasProgress) card.post {
                progressFill.layoutParams = progressFill.layoutParams.apply {
                    width = (progressWrap.width * c.progress.coerceIn(0, 100) / 100f).toInt()
                }
            }
            card.setOnClickListener { c.onClick?.invoke() }
            card.setOnFocusChangeListener { v, has ->
                v.animate().translationY(if (has) -1.5f * density else 0f).setDuration(150).start()
                v.z = if (has) 8f else 0f
            }
        }
    }
}
