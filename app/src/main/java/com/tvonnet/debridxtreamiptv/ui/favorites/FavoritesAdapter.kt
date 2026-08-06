package com.tvonnet.debridxtreamiptv.ui.favorites

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.bumptech.glide.Glide
import com.tvonnet.debridxtreamiptv.R
import com.tvonnet.debridxtreamiptv.data.local.entity.FavoriteEntity

/**
 * FavoritesAdapter - Week 10: Favorites System
 * 
 * RecyclerView adapter for displaying favorite items
 */
class FavoritesAdapter(
    private val onItemClick: (FavoriteEntity) -> Unit,
    private val onRemoveClick: (FavoriteEntity) -> Unit
) : ListAdapter<FavoriteEntity, FavoritesAdapter.FavoriteViewHolder>(FavoriteDiffCallback()) {
    
    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): FavoriteViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_favorite, parent, false)
        return FavoriteViewHolder(view)
    }
    
    override fun onBindViewHolder(holder: FavoriteViewHolder, position: Int) {
        holder.bind(getItem(position))
    }
    
    inner class FavoriteViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val ivThumbnail: ImageView = itemView.findViewById(R.id.iv_thumbnail)
        private val tvName: TextView = itemView.findViewById(R.id.tv_name)
        private val tvType: TextView = itemView.findViewById(R.id.tv_type)
        private val btnRemove: ImageButton = itemView.findViewById(R.id.btn_remove)
        
        fun bind(favorite: FavoriteEntity) {
            // Week 12: Set name from FavoriteEntity
            tvName.text = favorite.name
            
            // Set type badge
            tvType.text = when (favorite.type) {
                "live" -> "LIVE TV"
                "vod" -> "MOVIE"
                "series" -> "SERIES"
                else -> favorite.type.uppercase()
            }
            
            // Set type-specific placeholder icon
            val placeholderIcon = when (favorite.type) {
                "live" -> R.drawable.ic_live_tv
                "vod" -> R.drawable.ic_movie
                "series" -> R.drawable.ic_series
                else -> R.drawable.ic_movie
            }
            
            // Week 12: Load thumbnail from iconUrl if available
            val healed = com.tvonnet.debridxtreamiptv.util.GlobalConfig.resolveIconUrl(favorite.iconUrl)
            // Diagnostic Toast

            if (!healed.isNullOrBlank()) {
                com.tvonnet.debridxtreamiptv.util.GlideUtils.loadThumbnail(ivThumbnail, healed, placeholderIcon, placeholderIcon)
            } else {
                ivThumbnail.setImageResource(placeholderIcon)
            }
            
            // Click listeners
            itemView.setOnClickListener { onItemClick(favorite) }
            btnRemove.setOnClickListener { onRemoveClick(favorite) }
            
            // TV focus handling
            itemView.isFocusable = true
            itemView.isFocusableInTouchMode = itemView.resources.getBoolean(R.bool.ui_uses_dpad_focus)
        }
    }
    
    class FavoriteDiffCallback : DiffUtil.ItemCallback<FavoriteEntity>() {
        override fun areItemsTheSame(oldItem: FavoriteEntity, newItem: FavoriteEntity): Boolean {
            return oldItem.id == newItem.id
        }
        
        override fun areContentsTheSame(oldItem: FavoriteEntity, newItem: FavoriteEntity): Boolean {
            return oldItem == newItem
        }
    }
}

