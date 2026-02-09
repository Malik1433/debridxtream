package com.tvonnet.debridxtreamiptv.features.seriesv2.ui

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.paging.PagingDataAdapter
import androidx.recyclerview.widget.RecyclerView.Adapter.StateRestorationPolicy
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.RecyclerView
import com.bumptech.glide.Glide
import com.bumptech.glide.load.resource.drawable.DrawableTransitionOptions
import com.tvonnet.debridxtreamiptv.databinding.ItemEpisodeV2Binding
import com.tvonnet.debridxtreamiptv.features.seriesv2.data.model.EpisodeEntityV2

class EpisodesAdapterV2(
    private val onEpisodeClick: (EpisodeEntityV2) -> Unit
) : PagingDataAdapter<EpisodeEntityV2, EpisodesAdapterV2.EpisodeViewHolder>(EpisodeDiffCallback) {

    init {
        stateRestorationPolicy = StateRestorationPolicy.PREVENT_WHEN_EMPTY
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): EpisodeViewHolder {
        val binding = ItemEpisodeV2Binding.inflate(LayoutInflater.from(parent.context), parent, false)
        return EpisodeViewHolder(binding)
    }

    override fun onBindViewHolder(holder: EpisodeViewHolder, position: Int) {
        val item = getItem(position)
        if (item != null) {
            holder.bind(item)
        }
    }

    var seriesCoverUrl: String? = null

    inner class EpisodeViewHolder(private val binding: ItemEpisodeV2Binding) : RecyclerView.ViewHolder(binding.root) {
        
        init {
            binding.root.setOnClickListener {
                val item = getItem(bindingAdapterPosition)
                if (item != null) onEpisodeClick(item)
            }
            
            // Clean Focus Handling
            binding.vFocusBorder.setBackgroundResource(com.tvonnet.debridxtreamiptv.R.drawable.border_focus_white)
            binding.vFocusBorder.visibility = android.view.View.INVISIBLE
            
            binding.root.setOnFocusChangeListener { _, hasFocus ->
                // Animate Scale
                binding.root.animate()
                    .scaleX(if (hasFocus) 1.05f else 1.0f)
                    .scaleY(if (hasFocus) 1.05f else 1.0f)
                    .duration = 150
                
                // Toggle Border Visibility
                binding.vFocusBorder.visibility = if (hasFocus) android.view.View.VISIBLE else android.view.View.INVISIBLE
            }
        }

        fun bind(episode: EpisodeEntityV2) {
            binding.tvEpisodeNumber.text = "S${episode.seasonNumber}:E${episode.episodeNumber}"
            
            // Fix: Clean Title
            val rawTitle = episode.title
            val cleanTitle = if (rawTitle.isNullOrEmpty() || rawTitle.equals("null", ignoreCase = true)) {
                "Episode ${episode.episodeNumber}"
            } else {
                rawTitle
            }
            binding.tvTitle.text = cleanTitle
            
            // Plot is removed in horizontal card to save space, or use tvPlot if visible
            binding.tvPlot.text = episode.plot ?: ""
            binding.tvDuration.text = formatDuration(episode.duration)

            // Fix: Fallback Image Logic
            val imageUrl = if (!episode.thumbnail.isNullOrEmpty()) episode.thumbnail else seriesCoverUrl
            
            Glide.with(binding.root.context)
                .load(imageUrl)
                .transition(DrawableTransitionOptions.withCrossFade())
                .placeholder(android.R.drawable.ic_menu_gallery)
                .error(android.R.drawable.ic_menu_gallery)
                .into(binding.ivEpisodeThumb)
        }
        
        private fun formatDuration(seconds: Long): String {
            if (seconds <= 0) return ""
            val m = seconds / 60
            return "${m}m"
        }
    }

    object EpisodeDiffCallback : DiffUtil.ItemCallback<EpisodeEntityV2>() {
        override fun areItemsTheSame(oldItem: EpisodeEntityV2, newItem: EpisodeEntityV2): Boolean {
            return oldItem.episodeId == newItem.episodeId
        }

        override fun areContentsTheSame(oldItem: EpisodeEntityV2, newItem: EpisodeEntityV2): Boolean {
            return oldItem == newItem
        }
    }
}
