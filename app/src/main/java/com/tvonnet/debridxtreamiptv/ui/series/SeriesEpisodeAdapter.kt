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
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_episode_row, parent, false)
        return EpisodeViewHolder(view)
    }

    override fun onBindViewHolder(holder: EpisodeViewHolder, position: Int) {
        val item = getItem(position)
        holder.bind(item, item.id == selectedEpisodeId)
    }

    inner class EpisodeViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val ivThumb: ImageView = itemView.findViewById(R.id.iv_episode_thumb)
        private val tvTitle: TextView = itemView.findViewById(R.id.tv_episode_title)
        private val tvMeta: TextView = itemView.findViewById(R.id.tv_episode_meta)

        fun bind(item: EpisodeUiModel, isSelected: Boolean) {
            tvTitle.text = item.title

            val context = itemView.context
            val episodeLabel = item.episodeNumber?.let { episodeNumber ->
                if (item.durationMinutes != null) {
                    val durationText = context.getString(
                        R.string.series_detail_episode_duration_minutes,
                        item.durationMinutes
                    )
                    context.getString(
                        R.string.series_detail_episode_meta_format,
                        episodeNumber,
                        durationText
                    )
                } else {
                    context.getString(R.string.series_detail_episode_meta_no_duration, episodeNumber)
                }
            }

            val description = item.description?.takeIf { it.isNotBlank() }
            val metaParts = buildList {
                episodeLabel?.let { add(it) }
                description?.let { add(it) }
            }
            tvMeta.text = metaParts.joinToString(separator = " �?� ")

            val thumbnail = item.thumbnailUrl?.takeIf { it.isNotBlank() }
            if (thumbnail == null) {
                Glide.with(itemView).clear(ivThumb)
                ivThumb.setImageResource(R.drawable.tv_card_placeholder)
            } else {
                Glide.with(itemView)
                    .load(thumbnail)
                    .placeholder(R.drawable.tv_card_placeholder)
                    .error(R.drawable.tv_card_placeholder)
                    .centerCrop()
                    .into(ivThumb)
            }

            // Playback Status
            val ivWatched: android.widget.ImageView = itemView.findViewById(R.id.iv_watched_indicator)
            val pbResume: android.widget.ProgressBar = itemView.findViewById(R.id.pb_resume_progress)

            if (item.isWatched) {
                ivWatched.visibility = View.VISIBLE
                pbResume.visibility = View.GONE
            } else if (item.resumePosition > 0 && item.durationMinutes != null && item.durationMinutes > 0) {
                ivWatched.visibility = View.GONE
                pbResume.visibility = View.VISIBLE
                val durationSecs = item.durationMinutes * 60L
                val progress = ((item.resumePosition.toDouble() / durationSecs.toDouble()) * 100).toInt()
                pbResume.progress = progress
            } else {
                ivWatched.visibility = View.GONE
                pbResume.visibility = View.GONE
            }

            itemView.isSelected = isSelected

            itemView.setOnClickListener {
                onEpisodeClick(item)
            }

            itemView.setOnFocusChangeListener { _, hasFocus ->
                if (hasFocus) {
                    onEpisodeFocused(item)
                }
            }
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

