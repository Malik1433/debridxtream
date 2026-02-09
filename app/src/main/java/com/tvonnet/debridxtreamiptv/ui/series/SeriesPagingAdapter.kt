package com.tvonnet.debridxtreamiptv.ui.series

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.paging.PagingDataAdapter
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.RecyclerView
import com.bumptech.glide.Glide
import com.tvonnet.debridxtreamiptv.R
import com.tvonnet.debridxtreamiptv.data.model.XtreamSeriesInfo

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

    // ... existing ViewHolder creation ...
    
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
    private val tvSeparator = itemView.findViewById<android.widget.TextView>(R.id.tv_series_separator)
    private val tvRating = itemView.findViewById<android.widget.TextView>(R.id.tv_series_rating)
    private val ivPoster = itemView.findViewById<android.widget.ImageView>(R.id.iv_series_poster)
    private val ivFavoriteIndicator = itemView.findViewById<android.widget.ImageView>(R.id.iv_favorite_indicator)
    private val tvNewBadge = itemView.findViewById<android.widget.TextView>(R.id.tv_series_new_badge)

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

        tvSeparator.visibility = if (tvYear.visibility == android.view.View.VISIBLE && tvRating.visibility == android.view.View.VISIBLE) {
            android.view.View.VISIBLE
        } else {
            android.view.View.GONE
        }

        ivFavoriteIndicator?.visibility = if (isFavorite) {
            android.view.View.VISIBLE
        } else {
            android.view.View.GONE
        }

        tvNewBadge?.visibility = android.view.View.GONE

        val posterUrl = series.cover
        if (!posterUrl.isNullOrBlank()) {
            Glide.with(itemView.context)
                .load(posterUrl)
                .placeholder(R.drawable.tv_card_placeholder)
                .error(R.drawable.tv_card_placeholder)
                .centerCrop()
                .into(ivPoster)
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
    }
}
