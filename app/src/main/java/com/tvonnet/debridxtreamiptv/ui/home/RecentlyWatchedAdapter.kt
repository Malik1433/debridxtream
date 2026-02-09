package com.tvonnet.debridxtreamiptv.ui.home

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.tvonnet.debridxtreamiptv.R
import com.tvonnet.debridxtreamiptv.util.loadPosterOrPlaceholder
import com.tvonnet.debridxtreamiptv.data.model.RecentlyWatchedItem

class RecentlyWatchedAdapter(
    private var items: List<RecentlyWatchedItem>,
    private val onItemClick: (RecentlyWatchedItem) -> Unit
) : RecyclerView.Adapter<RecentlyWatchedAdapter.RecentlyWatchedViewHolder>() {
    
    fun updateItems(newItems: List<RecentlyWatchedItem>) {
        items = newItems
        notifyDataSetChanged()
    }
    
    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecentlyWatchedViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_favorite_card, parent, false) // Reusing favorite card layout
        return RecentlyWatchedViewHolder(view)
    }
    
    override fun onBindViewHolder(holder: RecentlyWatchedViewHolder, position: Int) {
        holder.bind(items[position], onItemClick)
    }
    
    override fun getItemCount() = items.size
    
    class RecentlyWatchedViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val ivFavoritePoster: ImageView = itemView.findViewById(R.id.iv_favorite_poster)
        private val tvFavoriteTitle: TextView = itemView.findViewById(R.id.tv_favorite_title)
        private val ivFavoriteIcon: ImageView = itemView.findViewById(R.id.iv_favorite_icon)
        
        fun bind(item: RecentlyWatchedItem, onClick: (RecentlyWatchedItem) -> Unit) {
            tvFavoriteTitle.text = item.title
            
            // Hide favorite icon for recently watched
            ivFavoriteIcon.visibility = View.GONE
            
            // Load poster image with placeholder fallback
            ivFavoritePoster.loadPosterOrPlaceholder(item.posterUrl)
            
            itemView.setOnClickListener {
                onClick(item)
            }
        }
    }
}
