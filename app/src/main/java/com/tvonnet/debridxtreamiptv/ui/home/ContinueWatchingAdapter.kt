package com.tvonnet.debridxtreamiptv.ui.home

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.ProgressBar
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.tvonnet.debridxtreamiptv.R
import android.widget.Toast
import com.tvonnet.debridxtreamiptv.data.model.ContinueWatchingItem
import com.tvonnet.debridxtreamiptv.util.GlobalConfig
import com.tvonnet.debridxtreamiptv.util.loadPosterOrPlaceholder

class ContinueWatchingAdapter(
    private var items: List<ContinueWatchingItem>,
    private val onItemClick: (ContinueWatchingItem) -> Unit
) : RecyclerView.Adapter<ContinueWatchingAdapter.ContinueWatchingViewHolder>() {
    
    fun updateItems(newItems: List<ContinueWatchingItem>) {
        items = newItems
        notifyDataSetChanged()
    }
    
    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ContinueWatchingViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_continue_watching_card, parent, false)
        return ContinueWatchingViewHolder(view)
    }
    
    override fun onBindViewHolder(holder: ContinueWatchingViewHolder, position: Int) {
        holder.bind(items[position], onItemClick)
    }
    
    override fun getItemCount() = items.size
    
    class ContinueWatchingViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val ivContinuePoster: ImageView = itemView.findViewById(R.id.iv_continue_poster)
        private val tvContinueTitle: TextView = itemView.findViewById(R.id.tv_continue_title)
        private val progressWatch: ProgressBar = itemView.findViewById(R.id.progress_watch)
        private val tvContinueProgress: TextView = itemView.findViewById(R.id.tv_continue_progress)
        
        fun bind(item: ContinueWatchingItem, onClick: (ContinueWatchingItem) -> Unit) {
            tvContinueTitle.text = formatTitle(item)
            tvContinueProgress.text = item.formattedProgress
            progressWatch.progress = item.progressPercentage
            
            val resolvedUrl = GlobalConfig.resolveIconUrl(item.posterUrl)
            // Debug Toast as requested
            
            // Load poster image (guard blank URLs)
            ivContinuePoster.loadPosterOrPlaceholder(resolvedUrl)
            
            itemView.setOnClickListener {
                onClick(item)
            }
        }

        private fun formatTitle(item: ContinueWatchingItem): String {
            val baseTitle = item.seriesTitle?.takeIf { it.isNotBlank() } ?: item.title
            val seasonNumber = item.seasonNumber
            val episodeNumber = item.episodeNumber
            return if (item.contentType == com.tvonnet.debridxtreamiptv.data.model.ContentType.EPISODE &&
                seasonNumber != null &&
                episodeNumber != null
            ) {
                val episodeLabel = String.format("S%02dE%02d", seasonNumber, episodeNumber)
                "$baseTitle - $episodeLabel"
            } else {
                baseTitle
            }
        }
    }
}
