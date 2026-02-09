package com.tvonnet.debridxtreamiptv.ui.vod

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.paging.PagingDataAdapter
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.RecyclerView
import com.bumptech.glide.Glide
import com.tvonnet.debridxtreamiptv.R
import com.tvonnet.debridxtreamiptv.data.model.XtreamVodInfo
import com.tvonnet.debridxtreamiptv.util.GlideUtils

/**
 * Paging3 adapter for VOD (Movies)
 * Efficiently handles large movie lists with automatic pagination
 * Week 13: Added favorite indicator support
 */
class VodPagingAdapter(
    private val onMovieClick: (XtreamVodInfo) -> Unit,
    private val favoriteChecker: ((String) -> Boolean)? = null,
    private val onMovieLongClick: ((XtreamVodInfo) -> Unit)? = null
) : PagingDataAdapter<XtreamVodInfo, VodPagingViewHolder>(VOD_COMPARATOR) {
    
    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VodPagingViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_channel_card, parent, false)
        return VodPagingViewHolder(view)
    }
    
    override fun onBindViewHolder(holder: VodPagingViewHolder, position: Int) {
        val movie = getItem(position)
        if (movie != null) {
            val isFavorite = movie.stream_id?.let { favoriteChecker?.invoke(it) } ?: false
            holder.bind(movie, isFavorite, onMovieClick, onMovieLongClick)
        }
    }
    
    companion object {
        private val VOD_COMPARATOR = object : DiffUtil.ItemCallback<XtreamVodInfo>() {
            override fun areItemsTheSame(oldItem: XtreamVodInfo, newItem: XtreamVodInfo): Boolean {
                return oldItem.stream_id == newItem.stream_id
            }
            
            override fun areContentsTheSame(oldItem: XtreamVodInfo, newItem: XtreamVodInfo): Boolean {
                return oldItem == newItem
            }
        }
    }
}

class VodPagingViewHolder(itemView: android.view.View) : RecyclerView.ViewHolder(itemView) {
    
    private val tvName = itemView.findViewById<android.widget.TextView>(R.id.tv_channel_name)
    private val ivLogo = itemView.findViewById<android.widget.ImageView>(R.id.iv_channel_logo)
    private val ivFavoriteIndicator = itemView.findViewById<android.widget.ImageView>(R.id.iv_favorite_indicator)
    
    fun bind(
        movie: XtreamVodInfo, 
        isFavorite: Boolean,
        onClick: (XtreamVodInfo) -> Unit,
        onLongClick: ((XtreamVodInfo) -> Unit)? = null
    ) {
        tvName.text = movie.name ?: "Unknown Movie"
        
        // Week 13: Show/hide favorite heart icon
        ivFavoriteIndicator?.visibility = if (isFavorite) {
            android.view.View.VISIBLE
        } else {
            android.view.View.GONE
        }
        
        // Phase 2.4: Load poster with optimized GlideUtils
        val posterUrl = movie.stream_icon
        GlideUtils.loadMoviePoster(ivLogo, posterUrl, useRoundedCorners = true)
        
        itemView.setOnClickListener {
            onClick(movie)
        }
        
        // Week 13: Long press to add/remove favorites
        itemView.setOnLongClickListener {
            onLongClick?.invoke(movie)
            true
        }
    }
}

