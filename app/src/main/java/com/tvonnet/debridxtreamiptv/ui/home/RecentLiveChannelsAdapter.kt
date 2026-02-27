package com.tvonnet.debridxtreamiptv.ui.home

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.bumptech.glide.Glide
import com.tvonnet.debridxtreamiptv.R
import android.widget.Toast
import com.tvonnet.debridxtreamiptv.data.model.RecentLiveChannelItem
import com.tvonnet.debridxtreamiptv.util.GlobalConfig
import com.tvonnet.debridxtreamiptv.util.loadPosterOrPlaceholder

class RecentLiveChannelsAdapter(
    private var items: List<RecentLiveChannelItem>,
    private val onItemClick: (RecentLiveChannelItem) -> Unit
) : RecyclerView.Adapter<RecentLiveChannelsAdapter.RecentLiveViewHolder>() {
    
    fun updateItems(newItems: List<RecentLiveChannelItem>) {
        items = newItems
        notifyDataSetChanged()
    }
    
    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecentLiveViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_recent_live_card, parent, false)
        return RecentLiveViewHolder(view)
    }
    
    override fun onBindViewHolder(holder: RecentLiveViewHolder, position: Int) {
        holder.bind(items[position], onItemClick)
    }
    
    override fun getItemCount() = items.size
    
    class RecentLiveViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val ivChannelLogo: ImageView = itemView.findViewById(R.id.iv_channel_logo)
        private val tvChannelName: TextView = itemView.findViewById(R.id.tv_channel_name)
        
        fun bind(item: RecentLiveChannelItem, onClick: (RecentLiveChannelItem) -> Unit) {
            tvChannelName.text = item.channelName
            
            val resolvedUrl = GlobalConfig.resolveIconUrl(item.channelLogo)
            // Debug Toast as requested
            
            // Load channel logo using enhanced extension for logging and cinematic style
            ivChannelLogo.loadPosterOrPlaceholder(resolvedUrl)
            
            itemView.setOnClickListener {
                onClick(item)
            }
        }
    }
}

