package com.tvonnet.debridxtreamiptv.ui.home

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import android.widget.Toast
import com.tvonnet.debridxtreamiptv.R
import com.tvonnet.debridxtreamiptv.util.loadPosterOrPlaceholder
import com.tvonnet.debridxtreamiptv.data.model.FavoriteItem

class FavoritesAdapter(
    private var items: List<FavoriteItem>,
    private val onItemClick: (FavoriteItem) -> Unit
) : RecyclerView.Adapter<FavoritesAdapter.FavoriteViewHolder>() {
    
    fun updateItems(newItems: List<FavoriteItem>) {
        items = newItems
        notifyDataSetChanged()
    }
    
    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): FavoriteViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_favorite_card, parent, false)
        return FavoriteViewHolder(view)
    }
    
    override fun onBindViewHolder(holder: FavoriteViewHolder, position: Int) {
        holder.bind(items[position], onItemClick)
    }
    
    override fun getItemCount() = items.size
    
    class FavoriteViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val ivFavoritePoster: ImageView = itemView.findViewById(R.id.iv_favorite_poster)
        private val tvFavoriteTitle: TextView = itemView.findViewById(R.id.tv_favorite_title)
        private val ivFavoriteIcon: ImageView = itemView.findViewById(R.id.iv_favorite_icon)
        
        fun bind(item: FavoriteItem, onClick: (FavoriteItem) -> Unit) {
            tvFavoriteTitle.text = item.title
            
            // Debug Toast as requested
            
            // Load poster image, fallback to placeholder when URL missing
            ivFavoritePoster.loadPosterOrPlaceholder(item.posterUrl)
            
            itemView.setOnClickListener {
                onClick(item)
            }
        }
    }
}
