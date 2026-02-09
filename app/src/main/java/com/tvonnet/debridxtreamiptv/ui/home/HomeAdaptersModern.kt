package com.tvonnet.debridxtreamiptv.ui.home

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.bumptech.glide.Glide
import com.tvonnet.debridxtreamiptv.R
import com.tvonnet.debridxtreamiptv.data.model.FeaturedItem
import com.tvonnet.debridxtreamiptv.data.model.NewAddedItem
import com.tvonnet.debridxtreamiptv.data.model.RecentLiveChannelItem
import com.tvonnet.debridxtreamiptv.data.model.ContinueWatchingItem
import com.tvonnet.debridxtreamiptv.data.model.FavoriteItem
import com.tvonnet.debridxtreamiptv.util.loadPosterOrPlaceholder

// ============================================================================================
// FEATURED ADAPTER (Cinematic)
// ============================================================================================
class FeaturedAdapterModern(
    private var items: List<FeaturedItem>,
    private val onItemClick: (FeaturedItem) -> Unit
) : RecyclerView.Adapter<FeaturedAdapterModern.ViewHolder>() {

    class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val ivBackdrop: ImageView = view.findViewById(R.id.iv_backdrop)
        val tvTitle: TextView = view.findViewById(R.id.tv_title)
        val tvDescription: TextView? = view.findViewById(R.id.tv_description)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_featured_card_cinematic, parent, false)
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val item = items[position]
        holder.tvTitle.text = item.title
        holder.tvDescription?.text = item.description ?: ""
        
        // Use backdrop if available, else poster
        val imageUrl = item.backdropUrl ?: item.posterUrl
        holder.ivBackdrop.loadPosterOrPlaceholder(imageUrl)

        holder.itemView.setOnClickListener { onItemClick(item) }

        // Cinematic Focus Animation
        holder.itemView.onFocusChangeListener = View.OnFocusChangeListener { v, hasFocus ->
            if (hasFocus) {
                v.animate()
                    .scaleX(1.12f) // Increased scale
                    .scaleY(1.12f)
                    .translationZ(16f) // Higher Z
                    .setDuration(250)
                    .setInterpolator(android.view.animation.OvershootInterpolator())
                    .start()
            } else {
                v.animate()
                    .scaleX(1f)
                    .scaleY(1f)
                    .translationZ(0f)
                    .setDuration(200)
                    .start()
            }
        }
    }

    override fun getItemCount() = items.size

    fun updateItems(newItems: List<FeaturedItem>) {
        items = newItems
        notifyDataSetChanged()
    }
}

// ============================================================================================
// RECENT LIVE ADAPTER (Cinematic - Circular)
// ============================================================================================
class RecentLiveAdapterModern(
    private var items: List<RecentLiveChannelItem>,
    private val onItemClick: (RecentLiveChannelItem) -> Unit
) : RecyclerView.Adapter<RecentLiveAdapterModern.ViewHolder>() {

    class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val ivLogo: ImageView = view.findViewById(R.id.iv_logo)
        val tvName: TextView = view.findViewById(R.id.tv_name)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_recent_live_cinematic, parent, false)
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val item = items[position]
        holder.tvName.text = item.channelName
        // Use GlideUtils for channel logo with app fallback
        com.tvonnet.debridxtreamiptv.util.GlideUtils.loadChannelLogo(
            holder.ivLogo,
            item.channelLogo,
            com.tvonnet.debridxtreamiptv.R.drawable.app_logo,
            com.tvonnet.debridxtreamiptv.R.drawable.app_logo
        )
        holder.itemView.setOnClickListener { onItemClick(item) }
        
        // Circular Focus Animation
        holder.itemView.onFocusChangeListener = View.OnFocusChangeListener { v, hasFocus ->
            if (hasFocus) {
                v.animate()
                    .scaleX(1.15f) // Larger scale for small circular items
                    .scaleY(1.15f)
                    .translationZ(12f)
                    .setDuration(200)
                    .setInterpolator(android.view.animation.OvershootInterpolator())
                    .start()
            } else {
                v.animate()
                    .scaleX(1f)
                    .scaleY(1f)
                    .translationZ(0f)
                    .setDuration(200)
                    .start()
            }
        }
    }

    override fun getItemCount() = items.size

    fun updateItems(newItems: List<RecentLiveChannelItem>) {
        items = newItems
        notifyDataSetChanged()
    }
}

// ============================================================================================
// POSTER ADAPTER (Cinematic - Movies/Series/Favorites)
// ============================================================================================
class PosterAdapterModern<T>(
    private var items: List<T>,
    private val onItemClick: (T) -> Unit
) : RecyclerView.Adapter<PosterAdapterModern.ViewHolder>() {

    class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val ivPoster: ImageView = view.findViewById(R.id.iv_poster)
        val tvBadge: TextView = view.findViewById(R.id.tv_badge)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_poster_cinematic, parent, false)
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val item = items[position]
        
        // Handle different item types
        when (item) {
            is NewAddedItem -> {
                holder.ivPoster.loadPosterOrPlaceholder(item.posterUrl)
                holder.tvBadge.visibility = View.VISIBLE
                holder.tvBadge.text = "NEW"
            }
            is ContinueWatchingItem -> {
                holder.ivPoster.loadPosterOrPlaceholder(item.posterUrl)
                holder.tvBadge.visibility = View.GONE
                // TODO: Add progress bar logic if needed in future
            }
            is FavoriteItem -> {
                holder.ivPoster.loadPosterOrPlaceholder(item.posterUrl)
                holder.tvBadge.visibility = View.GONE
            }
        }

        holder.itemView.setOnClickListener { onItemClick(item) }

        // Poster Focus Animation
        holder.itemView.onFocusChangeListener = View.OnFocusChangeListener { v, hasFocus ->
            if (hasFocus) {
                v.animate()
                    .scaleX(1.15f) // Drastic scale for posters
                    .scaleY(1.15f)
                    .translationZ(16f)
                    .setDuration(200)
                    .setInterpolator(android.view.animation.OvershootInterpolator())
                    .start()
            } else {
                v.animate()
                    .scaleX(1f)
                    .scaleY(1f)
                    .translationZ(0f)
                    .setDuration(200)
                    .start()
            }
        }
    }

    override fun getItemCount() = items.size

    fun updateItems(newItems: List<T>) {
        items = newItems
        notifyDataSetChanged()
    }
}
