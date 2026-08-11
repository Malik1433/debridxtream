package com.tvonnet.debridxtreamiptv.ui.search

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.bumptech.glide.Glide
import com.tvonnet.debridxtreamiptv.R
import com.tvonnet.debridxtreamiptv.data.model.XtreamStream
import com.tvonnet.debridxtreamiptv.utils.MagneticFocusHelper

/**
 * Week 9: Search Live TV Results Adapter
 */
class SearchLiveAdapter(
    private val onStreamClick: (XtreamStream) -> Unit
) : ListAdapter<XtreamStream, SearchLiveAdapter.ViewHolder>(DiffCallback()) {
    
    init {
        setHasStableIds(true)
    }
    
    override fun getItemId(position: Int): Long {
        return getItem(position).stream_id.hashCode().toLong()
    }
    
    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_search_result, parent, false)
        return ViewHolder(view, onStreamClick)
    }
    
    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        holder.bind(getItem(position))
    }

    override fun onViewRecycled(holder: ViewHolder) {
        super.onViewRecycled(holder)
        com.tvonnet.debridxtreamiptv.utils.FocusGlintHelper.forceReset(holder.itemView)
    }
    
    class ViewHolder(
        itemView: View,
        private val onStreamClick: (XtreamStream) -> Unit
    ) : RecyclerView.ViewHolder(itemView) {
        
        private val ivIcon: ImageView = itemView.findViewById(R.id.iv_icon)
        private val tvName: TextView = itemView.findViewById(R.id.tv_name)
        private val tvType: TextView = itemView.findViewById(R.id.tv_type)

        init {
            itemView.onFocusChangeListener = View.OnFocusChangeListener { v, hasFocus ->
                v.isSelected = hasFocus
                v.isActivated = hasFocus
            }
            com.tvonnet.debridxtreamiptv.utils.FocusGlintHelper.attach(itemView)
        }
        
        fun bind(stream: XtreamStream) {
            tvName.text = stream.name ?: "Unknown Channel"
            tvType.text = tvType.context.getString(R.string.nav_live_tv)
            
            // Load icon with Glide
            val resolved = com.tvonnet.debridxtreamiptv.util.GlobalConfig.resolveIconUrl(stream.stream_icon)
            // Diagnostic Toast

            com.tvonnet.debridxtreamiptv.util.GlideUtils.loadChannelLogo(ivIcon, resolved)
            
            itemView.setOnClickListener {
                onStreamClick(stream)
            }
        }
    }
    
    private class DiffCallback : DiffUtil.ItemCallback<XtreamStream>() {
        override fun areItemsTheSame(oldItem: XtreamStream, newItem: XtreamStream): Boolean {
            return oldItem.stream_id == newItem.stream_id
        }
        
        override fun areContentsTheSame(oldItem: XtreamStream, newItem: XtreamStream): Boolean {
            return oldItem == newItem
        }
    }
}
