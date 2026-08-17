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
    private val onClick: (DebridContentItem) -> Unit,
    /**
     * The phone's "See all" — a dashed card at the END of the rail (design frame 1a). Null on the
     * television, where the header chip is already on the D-pad's way down and a trailing card
     * would only lengthen the ladder.
     */
    private val onSeeAll: (() -> Unit)? = null,
) : RecyclerView.Adapter<RecyclerView.ViewHolder>() {

    private var items: List<DebridContentItem> = emptyList()

    init { setHasStableIds(true) }

    fun submit(list: List<DebridContentItem>) { items = list; notifyDataSetChanged() }

    private val hasSeeAll: Boolean get() = onSeeAll != null && items.isNotEmpty()

    override fun getItemCount() = items.size + if (hasSeeAll) 1 else 0

    // Stable ids keep the focused poster attached across a rebind so D-pad focus isn't lost.
    override fun getItemId(position: Int): Long =
        if (position < items.size) items[position].id.hashCode().toLong() else SEE_ALL_ID

    override fun getItemViewType(position: Int): Int =
        if (position < items.size) TYPE_POSTER else TYPE_SEE_ALL

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        val inflater = LayoutInflater.from(parent.context)
        if (viewType == TYPE_SEE_ALL) {
            return SeeAllVH(inflater.inflate(R.layout.item_stremio_see_all_card, parent, false))
        }
        return VH(inflater.inflate(R.layout.item_stremio_popular_poster, parent, false))
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        when (holder) {
            is SeeAllVH -> holder.itemView.setOnClickListener { onSeeAll?.invoke() }
            is VH -> holder.bind(items[position], position)
        }
    }

    /** The rail-end door. Nothing to bind but the click. */
    inner class SeeAllVH(itemView: View) : RecyclerView.ViewHolder(itemView)

    inner class VH(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val card: View = itemView.findViewById(R.id.poster_card)

        // The phone card puts its title BELOW the art, so the gradient and the fade belong to the
        // art frame rather than to the whole card. The television has no such frame and falls back
        // to the card itself, which is exactly what it did before.
        private val art: View = itemView.findViewById(R.id.poster_art) ?: card
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
            val titleOverArt = art === card
            art.background = StremioGradients.cardBg(StremioPalette.forIndex(position), 6f, density)
            // No text over the art on a phone, so no fade to make it readable — the fade would only
            // darken the one thing that identifies the title.
            overlay.background = if (titleOverArt) {
                StremioGradients.topFade(0xF706090E.toInt(), 0x1406090E, 0.5f, 6f, density)
            } else {
                null
            }
            title.text = MediaTitleCleaner.clean(item.title)
            val fallback = if (DebridItemType.isSeries(item.type)) "Series" else "Movie"
            val upcoming = ReleaseInfo.isUpcoming(item.releaseDate)
            year.text = ReleaseInfo.metaLabel(item.year, item.releaseDate, fallback)
            // Highlight an unreleased title's date in amber so it reads as "coming soon".
            year.setTextColor(if (upcoming) 0xFFFFAA00.toInt() else 0xFF64748B.toInt())

            bindRating(item)

            Glide.with(image).load(item.posterUrl ?: item.backdropUrl)
                .format(com.bumptech.glide.load.DecodeFormat.PREFER_RGB_565).into(image) // IMG-1

            bindProgress(item)

            card.setOnClickListener { onClick(item) }
        }

        /** The TMDB score, in the badge corner. Blank scores are hidden rather than shown as 0.0. */
        private fun bindRating(item: DebridContentItem) {
            val rating = item.rating?.trim().orEmpty()
            val hasRating = rating.isNotEmpty() && rating != "0" && rating != "0.0"
            quality.isVisible = hasRating
            if (!hasRating) return
            quality.text = rating
            quality.background = GradientDrawable().apply {
                cornerRadius = 2f * density
                setColor(0xFFFFAA00.toInt())
            }
        }

        private fun bindProgress(item: DebridContentItem) {
            val showProgress = item.isContinueWatching && (item.durationMs ?: 0L) > 0L
            progressWrap.isVisible = showProgress
            if (showProgress) {
                val pct = ((item.resumePositionMs ?: 0L) * 100 / (item.durationMs ?: 1L)).toInt().coerceIn(0, 100)
                art.post {
                    progressFill.layoutParams = progressFill.layoutParams.apply {
                        width = (art.width * pct / 100f).toInt()
                    }
                }
            }
        }
    }

    private companion object {
        const val TYPE_POSTER = 0
        const val TYPE_SEE_ALL = 1
        const val SEE_ALL_ID = -1L
    }
}
