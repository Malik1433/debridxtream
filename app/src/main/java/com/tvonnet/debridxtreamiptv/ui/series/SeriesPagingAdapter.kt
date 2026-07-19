package com.tvonnet.debridxtreamiptv.ui.series

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.paging.PagingDataAdapter
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.RecyclerView
import com.tvonnet.debridxtreamiptv.R
import com.tvonnet.debridxtreamiptv.data.model.XtreamSeriesInfo

/**
 * Paging3 adapter for Series.
 * Card: item_series_card.xml — poster with an overlaid glass footer (title + ★ rating · year),
 * a NEW badge (year >= 2024) and a focus play-overlay. No seasons/episodes counts (the Xtream
 * list API has none), so that badge/segment is intentionally absent.
 */
class SeriesPagingAdapter(
    private val onSeriesClick: (XtreamSeriesInfo, android.view.View) -> Unit,
    private val favoriteChecker: ((String) -> Boolean)? = null,
    private val onSeriesLongClick: ((XtreamSeriesInfo) -> Unit)? = null,
    /** Returns (seasonCount, episodeCount) for a series_id, or null when unknown. */
    private val countsProvider: ((String) -> Pair<Int, Int>?)? = null
) : PagingDataAdapter<XtreamSeriesInfo, SeriesPagingViewHolder>(SERIES_COMPARATOR) {

    var onItemFocused: ((position: Int) -> Unit)? = null

    init {
        stateRestorationPolicy = StateRestorationPolicy.PREVENT_WHEN_EMPTY
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): SeriesPagingViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_series_card, parent, false)
        return SeriesPagingViewHolder(view)
    }

    override fun onBindViewHolder(holder: SeriesPagingViewHolder, position: Int) {
        val series = getItem(position)
        if (series != null) {
            val isFavorite = series.series_id?.let { favoriteChecker?.invoke(it) } ?: false
            val counts = series.series_id?.let { countsProvider?.invoke(it) }
            holder.bind(series, isFavorite, counts, onItemFocused, onSeriesClick, onSeriesLongClick)
        }
    }

    override fun onViewRecycled(holder: SeriesPagingViewHolder) {
        super.onViewRecycled(holder)
        holder.clearAnimations()
        holder.clearPoster() // IMG-14: cancel the in-flight Glide load for a recycled card
    }

    companion object {
        private val SERIES_COMPARATOR = object : DiffUtil.ItemCallback<XtreamSeriesInfo>() {
            override fun areItemsTheSame(oldItem: XtreamSeriesInfo, newItem: XtreamSeriesInfo): Boolean {
                return oldItem.series_id == newItem.series_id
            }

            override fun areContentsTheSame(oldItem: XtreamSeriesInfo, newItem: XtreamSeriesInfo): Boolean {
                return oldItem == newItem
            }
        }
    }
}

class SeriesPagingViewHolder(itemView: android.view.View) : RecyclerView.ViewHolder(itemView) {
    private val tvTitle = itemView.findViewById<TextView>(R.id.tv_series_title)
    private val flNewBadge = itemView.findViewById<View>(R.id.fl_new_badge)
    private val ivPoster = itemView.findViewById<ImageView>(R.id.iv_series_poster)
    private val ivFavoriteIndicator = itemView.findViewById<ImageView>(R.id.iv_favorite_indicator)
    private val glowFocus = itemView.findViewById<View>(R.id.glow_focus)

    init {
        // Same soft bloom as the movie cards, purple: rgba(167,139,250,0.35)
        com.tvonnet.debridxtreamiptv.util.FocusGlow.attachSelector(glowFocus, color = GLOW_PURPLE)
    }

    fun bind(
        series: XtreamSeriesInfo,
        isFavorite: Boolean,
        counts: Pair<Int, Int>?,
        onItemFocused: ((Int) -> Unit)?,
        onClick: (XtreamSeriesInfo, android.view.View) -> Unit,
        onLongClick: ((XtreamSeriesInfo) -> Unit)? = null
    ) {
        itemView.isFocusable = true
        itemView.isFocusableInTouchMode = true
        itemView.setTag(R.id.tag_series_id, series.series_id)

        tvTitle.text = com.tvonnet.debridxtreamiptv.util.MediaTitleCleaner.clean(series.name)
            .ifBlank { "Unknown Series" }

        // NEW badge (top-left, purple): release year >= 2024
        val releaseYear = series.releaseDate?.take(4)?.toIntOrNull()
        flNewBadge?.visibility = if (releaseYear != null && releaseYear >= 2024) View.VISIBLE else View.GONE

        // Favorite
        ivFavoriteIndicator?.visibility = if (isFavorite) View.VISIBLE else View.GONE

        // Poster
        val resolved = com.tvonnet.debridxtreamiptv.util.GlobalConfig.resolveIconUrl(series.cover)
        if (!resolved.isNullOrBlank()) {
            com.tvonnet.debridxtreamiptv.util.GlideUtils.loadMoviePoster(ivPoster, resolved, useRoundedCorners = true)
        } else {
            ivPoster.setImageResource(R.drawable.tv_card_placeholder)
        }

        // Shared-element transition name (used by the detail screen)
        ivPoster.transitionName = "series_poster_${series.series_id}"

        itemView.setOnClickListener { onClick(series, ivPoster) }
        itemView.setOnLongClickListener {
            onLongClick?.invoke(series)
            true
        }

        // Focus: identical to movie cards — shared cinematic focus util + z-lift.
        // Glow bloom is driven by duplicateParentState + FocusGlow selector.
        itemView.setOnFocusChangeListener { v, hasFocus ->
            com.tvonnet.debridxtreamiptv.util.FocusEffects.applyCinematicFocus(v, hasFocus, scale = 1.06f)
            if (hasFocus) {
                v.z = 20f
                val pos = bindingAdapterPosition
                if (pos != RecyclerView.NO_POSITION) {
                    onItemFocused?.invoke(pos)
                }
                tvTitle.isSelected = true
            } else {
                v.z = 0f
                tvTitle.isSelected = false
            }
        }
    }

    fun clearAnimations() {
        itemView.animate()?.cancel()
        itemView.scaleX = 1.0f
        itemView.scaleY = 1.0f
        itemView.z = 0f
    }

    fun clearPoster() {
        runCatching { com.bumptech.glide.Glide.with(itemView).clear(ivPoster) }
    }

    private companion object {
        // rgba(167,139,250,0.35)
        const val GLOW_PURPLE = 0x59A78BFA
    }
}

