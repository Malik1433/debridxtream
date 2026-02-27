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
import com.tvonnet.debridxtreamiptv.data.model.XtreamVodInfo
import com.tvonnet.debridxtreamiptv.utils.MagneticFocusHelper

/**
 * Week 9: Search VOD Results Adapter
 */
class SearchVodAdapter(
    private val onVodClick: (XtreamVodInfo) -> Unit
) : ListAdapter<XtreamVodInfo, SearchVodAdapter.ViewHolder>(DiffCallback()) {
    
    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_search_result, parent, false)
        return ViewHolder(view, onVodClick)
    }
    
    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        holder.bind(getItem(position))
    }
    
    class ViewHolder(
        itemView: View,
        private val onVodClick: (XtreamVodInfo) -> Unit
    ) : RecyclerView.ViewHolder(itemView) {
        
        private val ivIcon: ImageView = itemView.findViewById(R.id.iv_icon)
        private val tvName: TextView = itemView.findViewById(R.id.tv_name)
        private val tvType: TextView = itemView.findViewById(R.id.tv_type)

        init {
            itemView.onFocusChangeListener = View.OnFocusChangeListener { v, hasFocus ->
                v.isSelected = hasFocus
                v.isActivated = hasFocus
            }
            MagneticFocusHelper.attach(itemView, scale = 1.08f)
        }
        
        fun bind(vod: XtreamVodInfo) {
            tvName.text = vod.name ?: "Unknown Movie"
            
            // Show additional info if available
            val typeText = buildString {
                append("Movie")
                vod.releaseDate?.let { date ->
                    if (date.isNotEmpty()) {
                        append(" • $date")
                    }
                }
                vod.rating_imdb?.let { rating ->
                    if (rating.isNotEmpty()) {
                        append(" • ⭐ $rating")
                    }
                }
            }
            tvType.text = typeText
            
            // Load poster with Glide
            val posterUrl = com.tvonnet.debridxtreamiptv.util.GlobalConfig.resolveIconUrl(vod.stream_icon) ?: vod.cover
            // Diagnostic Toast

            com.tvonnet.debridxtreamiptv.util.GlideUtils.loadMoviePoster(ivIcon, posterUrl, useRoundedCorners = true)
            itemView.setOnClickListener {
                onVodClick(vod)
            }
        }
    }
    
    private class DiffCallback : DiffUtil.ItemCallback<XtreamVodInfo>() {
        override fun areItemsTheSame(oldItem: XtreamVodInfo, newItem: XtreamVodInfo): Boolean {
            return oldItem.stream_id == newItem.stream_id
        }
        
        override fun areContentsTheSame(oldItem: XtreamVodInfo, newItem: XtreamVodInfo): Boolean {
            return oldItem == newItem
        }
    }
}
