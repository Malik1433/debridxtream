package com.tvonnet.debridxtreamiptv.ui.live

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.bumptech.glide.Glide
import com.tvonnet.debridxtreamiptv.R
import com.tvonnet.debridxtreamiptv.data.model.XtreamStream

class ChannelAdapter(
    private val channels: List<XtreamStream>,
    private val onChannelClick: (XtreamStream) -> Unit
) : RecyclerView.Adapter<ChannelViewHolder>() {
    
    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ChannelViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_channel_card, parent, false)
        return ChannelViewHolder(view)
    }
    
    override fun onBindViewHolder(holder: ChannelViewHolder, position: Int) {
        holder.bind(channels[position], onChannelClick)
    }
    
    override fun getItemCount() = channels.size
}

class ChannelViewHolder(itemView: android.view.View) : RecyclerView.ViewHolder(itemView) {
    private val ivLogo = itemView.findViewById<android.widget.ImageView>(R.id.iv_channel_logo)
    private val tvName = itemView.findViewById<android.widget.TextView>(R.id.tv_channel_name)
    
    fun bind(channel: XtreamStream, onClick: (XtreamStream) -> Unit) {
        tvName.text = channel.name ?: "Unknown"
        
        // Load image with Glide, handle null/empty URLs gracefully
        // Load image with GlideUtils (Phase 2.4)
        com.tvonnet.debridxtreamiptv.util.GlideUtils.loadChannelLogo(
            ivLogo,
            channel.stream_icon,
            com.tvonnet.debridxtreamiptv.R.drawable.app_logo,
            com.tvonnet.debridxtreamiptv.R.drawable.app_logo
        )
        
        itemView.setOnClickListener {
            onClick(channel)
        }
    }
}

