package com.tvonnet.debridxtreamiptv.ui.home

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.tvonnet.debridxtreamiptv.R
import com.tvonnet.debridxtreamiptv.util.loadPosterOrPlaceholder
import com.tvonnet.debridxtreamiptv.data.model.FeaturedItem

class FeaturedAdapter(
    private var items: List<FeaturedItem>,
    private val onItemClick: (FeaturedItem) -> Unit
) : RecyclerView.Adapter<FeaturedAdapter.FeaturedViewHolder>() {
    
    init {
        setHasStableIds(true)
    }

    override fun getItemId(position: Int): Long {
        return items[position].contentId.hashCode().toLong()
    }
    
    fun updateItems(newItems: List<FeaturedItem>) {
        items = newItems
        notifyDataSetChanged()
    }
    
    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): FeaturedViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_featured_card, parent, false)
        return FeaturedViewHolder(view)
    }
    
    override fun onBindViewHolder(holder: FeaturedViewHolder, position: Int) {
        holder.bind(items[position], onItemClick)
    }
    
    override fun getItemCount() = items.size
    
    class FeaturedViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val ivFeaturedImage: ImageView = itemView.findViewById(R.id.iv_featured_image)
        private val tvFeaturedTitle: TextView = itemView.findViewById(R.id.tv_featured_title)
        
        fun bind(item: FeaturedItem, onClick: (FeaturedItem) -> Unit) {
            tvFeaturedTitle.text = item.title
            
            // Load backdrop image (guard blank URLs)
            ivFeaturedImage.loadPosterOrPlaceholder(item.backdropUrl)
            
            itemView.setOnClickListener {
                onClick(item)
            }
        }
    }
}
