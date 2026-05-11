package com.tvonnet.debridxtreamiptv.ui.home

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.RecyclerView
import com.bumptech.glide.Glide
import com.tvonnet.debridxtreamiptv.R
import com.tvonnet.debridxtreamiptv.data.model.RecentLiveChannelItem
import com.tvonnet.debridxtreamiptv.util.GlobalConfig
import com.tvonnet.debridxtreamiptv.util.loadPosterOrPlaceholder

class RecentLiveChannelsAdapter(
    private var items: List<RecentLiveChannelItem>,
    private val onItemClick: (RecentLiveChannelItem) -> Unit,
    private val onItemFocused: (Int, RecentLiveChannelItem) -> Unit = { _, _ -> }
) : RecyclerView.Adapter<RecentLiveChannelsAdapter.RecentLiveViewHolder>() {

    init {
        setHasStableIds(true)
    }
    
    fun updateItems(newItems: List<RecentLiveChannelItem>) {
        android.util.Log.e("HISTORY_DEBUG", "RecentLiveChannelsAdapter: updateItems called with ${newItems.size} items")
        if (items == newItems) {
            android.util.Log.e("HISTORY_DEBUG", "RecentLiveChannelsAdapter: Items are the same, skipping update")
            return
        }
        val oldItems = items
        val diff = DiffUtil.calculateDiff(object : DiffUtil.Callback() {
            override fun getOldListSize(): Int = oldItems.size

            override fun getNewListSize(): Int = newItems.size

            override fun areItemsTheSame(oldItemPosition: Int, newItemPosition: Int): Boolean {
                return stableIdFor(oldItems[oldItemPosition]) == stableIdFor(newItems[newItemPosition])
            }

            override fun areContentsTheSame(oldItemPosition: Int, newItemPosition: Int): Boolean {
                return oldItems[oldItemPosition] == newItems[newItemPosition]
            }
        })
        items = newItems
        diff.dispatchUpdatesTo(this)
    }
    
    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecentLiveViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_recent_live_card, parent, false)
        return RecentLiveViewHolder(view)
    }
    
    override fun onBindViewHolder(holder: RecentLiveViewHolder, position: Int) {
        android.util.Log.e("HISTORY_DEBUG", "RecentLiveChannelsAdapter: onBindViewHolder position=$position")
        holder.bind(items[position], onItemClick, onItemFocused)
    }
    
    override fun getItemCount() = items.size

    override fun getItemId(position: Int): Long {
        return stableIdFor(items[position])
    }

    fun getStableItemIdAt(position: Int): Long? {
        return items.getOrNull(position)?.let { stableIdFor(it) }
    }

    fun findPositionByStableId(stableId: Long): Int {
        return items.indexOfFirst { stableIdFor(it) == stableId }
    }

    companion object {
        fun stableIdFor(item: RecentLiveChannelItem): Long {
            val identity = buildString {
                append(item.channelId)
                append(':')
                append(item.epgChannelId ?: "")
                append(':')
                append(item.channelName)
            }
            var hash = 1125899906842597L
            identity.forEach { char ->
                hash = 31L * hash + char.code
            }
            return hash
        }
    }
    
    class RecentLiveViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val ivChannelLogo: ImageView = itemView.findViewById(R.id.iv_channel_logo)
        private val tvChannelName: TextView = itemView.findViewById(R.id.tv_channel_name)
        
        fun bind(
            item: RecentLiveChannelItem,
            onClick: (RecentLiveChannelItem) -> Unit,
            onFocused: (Int, RecentLiveChannelItem) -> Unit
        ) {
            tvChannelName.text = item.channelName
            
            // Load channel logo using enhanced extension for logging and cinematic style
            ivChannelLogo.loadPosterOrPlaceholder(item.channelLogo)
            
            itemView.setOnClickListener {
                onClick(item)
            }

            itemView.setOnFocusChangeListener { _, hasFocus ->
                if (!hasFocus) return@setOnFocusChangeListener
                val position = bindingAdapterPosition
                if (position != RecyclerView.NO_POSITION) {
                    onFocused(position, item)
                }
            }
        }
    }
}

