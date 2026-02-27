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
        itemView.setTag(R.id.tag_vod_id, movie.stream_id?.toString())
        tvName.text = movie.name ?: "Unknown Movie"
        
        // Week 13: Show/hide favorite heart icon
        ivFavoriteIndicator?.visibility = if (isFavorite) {
            android.view.View.VISIBLE
        } else {
            android.view.View.GONE
        }

        // Bind new metadata (Rating, Year, Quality) if views exist
        // Note: VodPagingAdapter might be using item_channel_card or item_movie_card.
        // Step 47 said: .inflate(R.layout.item_channel_card, ...)
        // Wait, VodPagingAdapter uses item_channel_card? 
        // VodFragment uses item_movie_card. 
        // If VodPagingAdapter uses item_channel_card, then my changes to item_movie_card won't affect it unless I change the layout file it uses.
        // But VodFragment uses "VodAdapter" (inner class) which uses R.layout.item_movie_card.
        // So VodPagingAdapter might be for a different view (maybe "All Movies" vs "Category"?).
        // If VodFragment uses "VodAdapter", then VodPagingAdapter might be unused or for different context.
        // I will NOT update VodPagingAdapter logic to rely on item_movie_card IDs if it uses item_channel_card.
        // I'll leave it as is or update it if I confirm it's used and needs the new design.
        // Given the request "same result", focusing on VodFragment (which seems to be the main browser) is priority.
        // I'll skip updating VodPagingAdapter for now to avoid breaking it if it uses a different layout.
        
        // Phase 2.4: Load poster with optimized GlideUtils
        val resolved = com.tvonnet.debridxtreamiptv.util.GlobalConfig.resolveIconUrl(movie.stream_icon)
        // Diagnostic Toast
        
        com.tvonnet.debridxtreamiptv.util.GlideUtils.loadMoviePoster(ivLogo, resolved, useRoundedCorners = true)
        
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

