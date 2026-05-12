package com.tvonnet.debridxtreamiptv.ui.series

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

class SeriesEpisodeAdapter(
    private val onEpisodeClick: (EpisodeUiModel) -> Unit,
    private val onEpisodeFocused: (EpisodeUiModel) -> Unit
) : ListAdapter<EpisodeUiModel, SeriesEpisodeAdapter.EpisodeViewHolder>(DIFF_CALLBACK) {

    init {
        setHasStableIds(true)
    }

    override fun getItemId(position: Int): Long {
        return getItem(position).id.hashCode().toLong()
    }

    private var selectedEpisodeId: String? = null

    fun submitEpisodes(newItems: List<EpisodeUiModel>, selectedId: String?) {
        selectedEpisodeId = selectedId
        submitList(newItems)
    }

    fun setSelectedEpisode(episodeId: String?) {
        if (selectedEpisodeId == episodeId) return

        val previousId = selectedEpisodeId
        selectedEpisodeId = episodeId

        previousId?.let { notifyItemChangedById(it) }
        episodeId?.let { notifyItemChangedById(it) }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): EpisodeViewHolder {
        val binding = com.tvonnet.debridxtreamiptv.databinding.ItemEpisodeV2Binding.inflate(
            LayoutInflater.from(parent.context), parent, false
        )
        return EpisodeViewHolder(binding)
    }

    override fun onBindViewHolder(holder: EpisodeViewHolder, position: Int) {
        val item = getItem(position)
        holder.bind(item, item.id == selectedEpisodeId)
    }

    inner class EpisodeViewHolder(private val binding: com.tvonnet.debridxtreamiptv.databinding.ItemEpisodeV2Binding) : 
        RecyclerView.ViewHolder(binding.root) {
        
        init {
            binding.vFocusBorder.setBackgroundResource(R.drawable.bg_episode_focus_glow)
            binding.vFocusBorder.visibility = View.INVISIBLE

            binding.root.setOnClickListener {
                val item = getItem(bindingAdapterPosition)
                onEpisodeClick(item)
            }

            binding.root.setOnFocusChangeListener { v, hasFocus ->
                binding.vFocusBorder.visibility = if (hasFocus) View.VISIBLE else View.INVISIBLE
                if (hasFocus) {
                    val item = getItem(bindingAdapterPosition)
                    onEpisodeFocused(item)
                }
            }
        }

        fun bind(item: EpisodeUiModel, isSelected: Boolean) {
            binding.tvTitle.text = item.title
            binding.tvEpisodeNumber.text = "E${item.episodeNumber ?: 0}"

            val context = itemView.context
            val episodeLabel = item.episodeNumber?.let { "Episode $it" } ?: "Episode"
            val durationText = item.durationMinutes?.let { " • ${it} min" } ?: ""
            val plotSnippet = item.description?.take(40)?.let { " • $it..." } ?: ""
            
            binding.tvDuration.text = "$episodeLabel$durationText$plotSnippet"

            val thumbnail = item.thumbnailUrl?.takeIf { it.isNotBlank() }
            Glide.with(itemView)
                .load(thumbnail)
                .placeholder(R.drawable.tv_card_placeholder)
                .error(R.drawable.tv_card_placeholder)
                .centerCrop()
                .into(binding.ivEpisodeThumb)

            itemView.isSelected = isSelected
        }
    }

    private fun notifyItemChangedById(id: String) {
        val index = currentList.indexOfFirst { it.id == id }
        if (index != -1) {
            notifyItemChanged(index)
        }
    }

    companion object {
        private val DIFF_CALLBACK = object : DiffUtil.ItemCallback<EpisodeUiModel>() {
            override fun areItemsTheSame(oldItem: EpisodeUiModel, newItem: EpisodeUiModel): Boolean =
                oldItem.id == newItem.id

            override fun areContentsTheSame(oldItem: EpisodeUiModel, newItem: EpisodeUiModel): Boolean =
                oldItem == newItem
        }
    }
}

