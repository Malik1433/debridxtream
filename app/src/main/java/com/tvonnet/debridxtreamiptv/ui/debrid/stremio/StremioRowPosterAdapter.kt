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
import com.tvonnet.debridxtreamiptv.ui.debrid.DebridContentItem
import com.tvonnet.debridxtreamiptv.util.MediaTitleCleaner
import com.tvonnet.debridxtreamiptv.ui.debrid.DebridItemType

/** Horizontal poster row for a Debrid catalog row, in the Stremio card style. */
internal class StremioRowPosterAdapter(
    private val onClick: (DebridContentItem) -> Unit
) : RecyclerView.Adapter<StremioRowPosterAdapter.VH>() {

    private var items: List<DebridContentItem> = emptyList()

    init { setHasStableIds(true) }

    fun submit(list: List<DebridContentItem>) { items = list; notifyDataSetChanged() }

    override fun getItemCount() = items.size

    // Stable ids keep the focused poster attached across a rebind so D-pad focus isn't lost.
    override fun getItemId(position: Int) = items[position].id.hashCode().toLong()

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val v = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_stremio_popular_poster, parent, false)
        return VH(v)
    }

    override fun onBindViewHolder(holder: VH, position: Int) = holder.bind(items[position], position)

    inner class VH(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val card: View = itemView.findViewById(R.id.poster_card)
        private val image: ImageView = itemView.findViewById(R.id.poster_image)
        private val overlay: View = itemView.findViewById(R.id.poster_overlay)
        private val quality: TextView = itemView.findViewById(R.id.poster_quality)
        private val title: TextView = itemView.findViewById(R.id.poster_title)
        private val year: TextView = itemView.findViewById(R.id.poster_year)
        private val progressWrap: View = itemView.findViewById(R.id.poster_progress_wrap)
        private val progressFill: View = itemView.findViewById(R.id.poster_progress_fill)
        private val density = itemView.resources.displayMetrics.density

        // No scale on big row posters — the cyan focus ring + lift is enough, and scaling would make
        // the RecyclerView scroll to refit, pushing the row header up under the hero clip (header cut).
        init { StremioFocus.attach(card, 1.0f) }

        fun bind(item: DebridContentItem, position: Int) {
            card.background = StremioGradients.cardBg(StremioPalette.forIndex(position), 6f, density)
            overlay.background = StremioGradients.topFade(0xF706090E.toInt(), 0x1406090E, 0.5f, 6f, density)
            title.text = MediaTitleCleaner.clean(item.title)
            val fallback = if (DebridItemType.isSeries(item.type)) "Series" else "Movie"
            val upcoming = ReleaseInfo.isUpcoming(item.releaseDate)
            year.text = ReleaseInfo.metaLabel(item.year, item.releaseDate, fallback)
            // Highlight an unreleased title's date in amber so it reads as "coming soon".
            year.setTextColor(if (upcoming) 0xFFFFAA00.toInt() else 0xFF64748B.toInt())

            val rating = item.rating?.trim().orEmpty()
            val hasRating = rating.isNotEmpty() && rating != "0" && rating != "0.0"
            quality.isVisible = hasRating
            if (hasRating) {
                quality.text = rating
                quality.background = GradientDrawable().apply {
                    cornerRadius = 2f * density; setColor(0xFFFFAA00.toInt())
                }
            }

            Glide.with(image).load(item.posterUrl ?: item.backdropUrl)
                .format(com.bumptech.glide.load.DecodeFormat.PREFER_RGB_565).into(image) // IMG-1

            val showProgress = item.isContinueWatching && (item.durationMs ?: 0L) > 0L
            progressWrap.isVisible = showProgress
            if (showProgress) {
                val pct = ((item.resumePositionMs ?: 0L) * 100 / (item.durationMs ?: 1L)).toInt().coerceIn(0, 100)
                card.post {
                    progressFill.layoutParams = progressFill.layoutParams.apply {
                        width = (card.width * pct / 100f).toInt()
                    }
                }
            }

            card.setOnClickListener { onClick(item) }
        }
    }
}
