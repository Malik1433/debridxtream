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
import com.tvonnet.debridxtreamiptv.data.model.XtreamSeriesInfo
import com.tvonnet.debridxtreamiptv.utils.MagneticFocusHelper

/**
 * Week 9: Search Series Results Adapter
 */
class SearchSeriesAdapter(
    private val onSeriesClick: (XtreamSeriesInfo) -> Unit
) : ListAdapter<XtreamSeriesInfo, SearchSeriesAdapter.ViewHolder>(DiffCallback()) {
    
    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_search_result, parent, false)
        return ViewHolder(view, onSeriesClick)
    }
    
    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        holder.bind(getItem(position))
    }
    
    class ViewHolder(
        itemView: View,
        private val onSeriesClick: (XtreamSeriesInfo) -> Unit
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
        
        fun bind(series: XtreamSeriesInfo) {
            tvName.text = series.name ?: "Unknown Series"
            
            // Show additional info if available
            val typeText = buildString {
                append("Series")
                series.releaseDate?.let { date ->
                    if (date.isNotEmpty()) {
                        append(" • $date")
                    }
                }
                series.episodes?.let { episodes ->
                    val totalEpisodes = episodes.values.sumOf { it.size }
                    if (totalEpisodes > 0) {
                        append(" • $totalEpisodes Episodes")
                    }
                }
            }
            tvType.text = typeText
            
            // Load cover with Glide
            val resolved = com.tvonnet.debridxtreamiptv.util.GlobalConfig.resolveIconUrl(series.cover)
            // Diagnostic Toast

            com.tvonnet.debridxtreamiptv.util.GlideUtils.loadMoviePoster(ivIcon, resolved, useRoundedCorners = true)
            
            itemView.setOnClickListener {
                onSeriesClick(series)
            }
        }
    }
    
    private class DiffCallback : DiffUtil.ItemCallback<XtreamSeriesInfo>() {
        override fun areItemsTheSame(oldItem: XtreamSeriesInfo, newItem: XtreamSeriesInfo): Boolean {
            return oldItem.series_id == newItem.series_id
        }
        
        override fun areContentsTheSame(oldItem: XtreamSeriesInfo, newItem: XtreamSeriesInfo): Boolean {
            return oldItem == newItem
        }
    }
}
