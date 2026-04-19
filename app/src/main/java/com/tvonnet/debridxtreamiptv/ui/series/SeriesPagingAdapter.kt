package com.tvonnet.debridxtreamiptv.ui.series

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.paging.PagingDataAdapter
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.RecyclerView
import com.bumptech.glide.Glide
import com.tvonnet.debridxtreamiptv.R
import com.tvonnet.debridxtreamiptv.data.model.XtreamSeriesInfo
import android.animation.ValueAnimator
import android.view.animation.OvershootInterpolator

/**
 * Paging3 adapter for Series
 * Efficiently handles large series lists with automatic pagination
 * Week 13: Added favorite indicator support
 */
class SeriesPagingAdapter(
    private val onSeriesClick: (XtreamSeriesInfo, android.view.View) -> Unit,
    private val favoriteChecker: ((String) -> Boolean)? = null,
    private val onSeriesLongClick: ((XtreamSeriesInfo) -> Unit)? = null
) : PagingDataAdapter<XtreamSeriesInfo, SeriesPagingViewHolder>(SERIES_COMPARATOR) {

// PagingDataAdapter handles stable IDs efficiently via DiffUtil.
    // Explicit getItemId override is disabled here due to library finality.
    
    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): SeriesPagingViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_series_card, parent, false)
        return SeriesPagingViewHolder(view)
    }

    override fun onBindViewHolder(holder: SeriesPagingViewHolder, position: Int) {
        val series = getItem(position)
        if (series != null) {
            val isFavorite = series.series_id?.let { favoriteChecker?.invoke(it) } ?: false
            holder.bind(series, isFavorite, onSeriesClick, onSeriesLongClick)
        }
    }

    override fun onViewRecycled(holder: SeriesPagingViewHolder) {
        super.onViewRecycled(holder)
        holder.clearAnimations()
    }
    // ...
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
    // ... existing fields ...
    private val tvTitle = itemView.findViewById<android.widget.TextView>(R.id.tv_series_title)
    private val tvYear = itemView.findViewById<android.widget.TextView>(R.id.tv_series_year)
    private val tvRating = itemView.findViewById<android.widget.TextView>(R.id.tv_series_rating)
    private val ivPoster = itemView.findViewById<android.widget.ImageView>(R.id.iv_series_poster)
    private val ivFavoriteIndicator = itemView.findViewById<android.widget.ImageView>(R.id.iv_favorite_indicator)
    private val glowFocus = itemView.findViewById<android.view.View>(R.id.glow_focus)
    
    private var pulseAnimator: ValueAnimator? = null
    private val focusScale = 1.15f

    fun bind(
        series: XtreamSeriesInfo,
        isFavorite: Boolean,
        onClick: (XtreamSeriesInfo, android.view.View) -> Unit,
        onLongClick: ((XtreamSeriesInfo) -> Unit)? = null
    ) {
        // ... previous bind logic ...
        itemView.setTag(R.id.tag_series_id, series.series_id)
        tvTitle.text = series.name ?: "Unknown Series"

        val releaseYear = series.releaseDate?.takeIf { it.isNotBlank() }
        val ratingValue = series.rating?.takeIf { it.isNotBlank() }

        if (releaseYear != null) {
            tvYear.text = releaseYear
            tvYear.visibility = android.view.View.VISIBLE
        } else {
            tvYear.visibility = android.view.View.GONE
        }

        if (ratingValue != null) {
            tvRating.text = ratingValue
            tvRating.visibility = android.view.View.VISIBLE
        } else {
            tvRating.visibility = android.view.View.GONE
        }

        ivFavoriteIndicator?.visibility = if (isFavorite) {
            android.view.View.VISIBLE
        } else {
            android.view.View.GONE
        }

        val resolved = com.tvonnet.debridxtreamiptv.util.GlobalConfig.resolveIconUrl(series.cover)
        // Diagnostic Toast

        if (!resolved.isNullOrBlank()) {
            com.tvonnet.debridxtreamiptv.util.GlideUtils.loadMoviePoster(ivPoster, resolved, useRoundedCorners = true)
        } else {
            ivPoster.setImageResource(R.drawable.tv_card_placeholder)
        }
        
        // Set unique transition name
        ivPoster.transitionName = "series_poster_${series.series_id}"

        itemView.setOnClickListener {
            onClick(series, ivPoster)
        }

        itemView.setOnLongClickListener {
            onLongClick?.invoke(series)
            true
        }

        itemView.setOnFocusChangeListener { v, hasFocus ->
            if (hasFocus) {
                // Focus: Scale up with pop and pulse glow
                v.z = 20f
                v.animate()
                    .scaleX(focusScale)
                    .scaleY(focusScale)
                    .setInterpolator(OvershootInterpolator(1.2f))
                    .setDuration(250)
                    .start()

                glowFocus?.alpha = 1f
                pulseAnimator?.cancel()
                pulseAnimator = ValueAnimator.ofFloat(0.5f, 1f).apply {
                    duration = 800
                    repeatMode = ValueAnimator.REVERSE
                    repeatCount = ValueAnimator.INFINITE
                    addUpdateListener { animator ->
                        glowFocus?.alpha = animator.animatedValue as Float
                    }
                    start()
                }

                tvTitle.isSelected = true
            } else {
                // Unfocus: Scale down and hide glow
                v.z = 0f
                v.animate()
                    .scaleX(1.0f)
                    .scaleY(1.0f)
                    .setInterpolator(android.view.animation.DecelerateInterpolator())
                    .setDuration(200)
                    .start()

                pulseAnimator?.cancel()
                glowFocus?.alpha = 0f
                tvTitle.isSelected = false
            }
        }
    }

    fun clearAnimations() {
        pulseAnimator?.cancel()
        pulseAnimator = null
        itemView.animate()?.cancel()
        glowFocus?.alpha = 0f
        itemView.scaleX = 1.0f
        itemView.scaleY = 1.0f
        itemView.z = 0f
    }
}
