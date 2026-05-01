package com.tvonnet.debridxtreamiptv.player.stabilized

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.bumptech.glide.Glide
import com.bumptech.glide.load.resource.bitmap.CenterCrop
import com.bumptech.glide.load.resource.bitmap.RoundedCorners
import com.bumptech.glide.request.RequestOptions
import com.tvonnet.debridxtreamiptv.R
import com.tvonnet.debridxtreamiptv.features.seriesv2.data.model.EpisodeEntityV2
import com.tvonnet.debridxtreamiptv.util.GlobalConfig

class PlayerEpisodeAdapter(
    private val onItemClick: (EpisodeEntityV2) -> Unit
) : ListAdapter<EpisodeEntityV2, PlayerEpisodeAdapter.EpisodeViewHolder>(DiffCallback()) {

    private var currentEpisodeId: String? = null

    init {
        setHasStableIds(true)
    }

    override fun getItemId(position: Int): Long {
        return getItem(position).episodeId.hashCode().toLong()
    }

    fun setCurrentEpisodeId(episodeId: String?) {
        val previousId = currentEpisodeId
        currentEpisodeId = episodeId
        
        if (previousId != currentEpisodeId) {
            val prevIndex = currentList.indexOfFirst { it.episodeId == previousId }
            val newIndex = currentList.indexOfFirst { it.episodeId == currentEpisodeId }
            
            if (prevIndex >= 0) notifyItemChanged(prevIndex)
            if (newIndex >= 0) notifyItemChanged(newIndex)
        }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): EpisodeViewHolder {
        val view = LayoutInflater.from(parent.context).inflate(R.layout.item_episode_v2, parent, false)
        return EpisodeViewHolder(view)
    }

    override fun onBindViewHolder(holder: EpisodeViewHolder, position: Int) {
        val episode = getItem(position)
        holder.bind(episode, currentEpisodeId)
    }

    inner class EpisodeViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val tvEpisodeNumber: TextView = itemView.findViewById(R.id.tv_episode_number)
        private val tvTitle: TextView = itemView.findViewById(R.id.tv_title)
        private val ivThumb: ImageView = itemView.findViewById(R.id.iv_episode_thumb)
        private val tvDuration: TextView = itemView.findViewById(R.id.tv_duration)
        private val tvPlot: TextView = itemView.findViewById(R.id.tv_plot)
        private val ivPlayIcon: ImageView = itemView.findViewById(R.id.iv_play_icon)
        private val vFocusBorder: View = itemView.findViewById(R.id.v_focus_border)

        init {
            itemView.setOnClickListener {
                val position = bindingAdapterPosition
                if (position != RecyclerView.NO_POSITION) {
                    val clickedEpisode = getItem(position)
                    if (clickedEpisode.episodeId != currentEpisodeId) {
                        onItemClick(clickedEpisode)
                    }
                }
            }

            itemView.setOnFocusChangeListener { view, hasFocus ->
                vFocusBorder.visibility = if (hasFocus) View.VISIBLE else View.INVISIBLE
                if (hasFocus) {
                    view.animate().scaleX(1.05f).scaleY(1.05f).setDuration(200).start()
                } else {
                    view.animate().scaleX(1.0f).scaleY(1.0f).setDuration(200).start()
                }
            }
        }

        fun bind(episode: EpisodeEntityV2, currentEpId: String?) {
            val isCurrent = episode.episodeId == currentEpId
            
            itemView.isSelected = isCurrent
            
            // Text color changes for current episode
            if (isCurrent) {
                tvTitle.setTextColor(itemView.context.getColor(R.color.gold_primary))
            } else {
                tvTitle.setTextColor(itemView.context.getColor(android.R.color.white))
            }

            tvEpisodeNumber.text = "Episode ${episode.episodeNumber}"
            tvTitle.text = episode.title ?: "Episode ${episode.episodeNumber}"
            
            if (!episode.plot.isNullOrBlank()) {
                tvPlot.visibility = View.VISIBLE
                tvPlot.text = episode.plot
            } else {
                tvPlot.visibility = View.GONE
            }

            ivPlayIcon.visibility = if (isCurrent) View.VISIBLE else View.GONE
            tvDuration.visibility = View.GONE

            val thumbUrl = episode.thumbnail
            if (!thumbUrl.isNullOrBlank()) {
                Glide.with(itemView.context)
                    .load(GlobalConfig.resolveIconUrl(thumbUrl))
                    .apply(RequestOptions().transform(CenterCrop(), RoundedCorners(8)))
                    .error(R.drawable.placeholder_poster)
                    .into(ivThumb)
            } else {
                ivThumb.setImageResource(R.drawable.placeholder_poster)
            }
        }
    }

    private class DiffCallback : DiffUtil.ItemCallback<EpisodeEntityV2>() {
        override fun areItemsTheSame(oldItem: EpisodeEntityV2, newItem: EpisodeEntityV2): Boolean {
            return oldItem.episodeId == newItem.episodeId
        }

        override fun areContentsTheSame(oldItem: EpisodeEntityV2, newItem: EpisodeEntityV2): Boolean {
            return oldItem == newItem
        }
    }
}
