package com.tvonnet.debridxtreamiptv.ui.home

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.bumptech.glide.Glide
import com.tvonnet.debridxtreamiptv.R
import com.tvonnet.debridxtreamiptv.data.model.RecentLiveChannelItem

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
            
            // Load channel logo
            Glide.with(itemView.context)
                .load(item.channelLogo)
                .placeholder(R.drawable.tv_card_placeholder)
                .error(R.drawable.tv_card_placeholder)
                .centerCrop()
                .into(ivChannelLogo)
            
            itemView.setOnClickListener {
                onClick(item)
            }
        }
    }
}

