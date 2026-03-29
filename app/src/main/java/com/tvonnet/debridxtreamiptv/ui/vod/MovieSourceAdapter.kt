package com.tvonnet.debridxtreamiptv.ui.vod

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.core.view.isVisible
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.tvonnet.debridxtreamiptv.data.repository.MovieSource
import com.tvonnet.debridxtreamiptv.databinding.ItemMovieSourceBinding
import com.tvonnet.debridxtreamiptv.utils.FocusGlintHelper
import java.util.Locale

class MovieSourceAdapter(
    private val onSourceFocused: (MovieSource) -> Unit,
    private val onSourceClicked: (MovieSource) -> Unit
) : ListAdapter<MovieSource, MovieSourceAdapter.SourceViewHolder>(DiffCallback) {

    private var selectedStreamId: String? = null

    fun updateSelection(streamId: String?, notify: Boolean = true) {
        selectedStreamId = streamId
        if (notify) {
            notifyDataSetChanged()
        }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): SourceViewHolder {
        val binding = ItemMovieSourceBinding.inflate(
            LayoutInflater.from(parent.context),
            parent,
            false
        )
        return SourceViewHolder(binding)
    }

    override fun onBindViewHolder(holder: SourceViewHolder, position: Int) {
        android.util.Log.d("MovieSourceAdapter", "Binding item at position $position: ${getItem(position).label}")
        holder.bind(getItem(position))
    }

    override fun getItemCount(): Int {
        val count = super.getItemCount()
        android.util.Log.d("MovieSourceAdapter", "getItemCount: $count")
        return count
    }

    inner class SourceViewHolder(
        private val binding: ItemMovieSourceBinding
    ) : RecyclerView.ViewHolder(binding.root) {

        fun bind(source: MovieSource) {
            binding.tvSourceLabel.text = source.label
            
            // Set language flags
            val languages = source.languages ?: listOf("multi")
            val flags = languages.map { getFlagEmoji(it) }.distinct().joinToString(" ")
            binding.tvLanguageFlag.text = flags

            val qualityLabel = source.quality
                ?.trim()
                ?.takeIf { it.isNotBlank() && it.lowercase(Locale.US) != "unknown" }
                ?.uppercase(Locale.US)
            binding.tvBadgeQuality.isVisible = !qualityLabel.isNullOrBlank()
            if (!qualityLabel.isNullOrBlank()) {
                binding.tvBadgeQuality.text = qualityLabel
            }

            val sizeLabel = source.sizeBytes
                ?.takeIf { it > 0 }
                ?.let { formatSizeLabel(it) }
            binding.tvBadgeSize.isVisible = !sizeLabel.isNullOrBlank()
            if (!sizeLabel.isNullOrBlank()) {
                binding.tvBadgeSize.text = sizeLabel
            }

            val seedersLabel = source.seeders
                ?.takeIf { it > 0 }
                ?.let { buildSeedersLabel(it) }
            binding.tvBadgeSeeders.isVisible = !seedersLabel.isNullOrBlank()
            if (!seedersLabel.isNullOrBlank()) {
                binding.tvBadgeSeeders.text = seedersLabel
            }

            val cachedLabel = source.isCached?.let { if (it) "CACHED" else "UNCACHED" }
            binding.tvBadgeCached.isVisible = !cachedLabel.isNullOrBlank()
            if (!cachedLabel.isNullOrBlank()) {
                binding.tvBadgeCached.text = cachedLabel
            }

            binding.layoutBadges.isVisible =
                binding.tvBadgeQuality.isVisible ||
                binding.tvBadgeSize.isVisible ||
                binding.tvBadgeSeeders.isVisible ||
                binding.tvBadgeCached.isVisible

            val isSelectedItem = source.stream.stream_id == selectedStreamId
            binding.root.isSelected = isSelectedItem

            // Setup the focus listener via the helper to prevent overwriting/nesting
            com.tvonnet.debridxtreamiptv.utils.FocusGlintHelper.updateListener(binding.root) { _, hasFocus ->
                val stillSelected = source.stream.stream_id == selectedStreamId
                binding.root.isSelected = stillSelected
                if (hasFocus) {
                    onSourceFocused(source)
                }
            }
            
            // Reset focus state before binding to prevent recycled highlights
            com.tvonnet.debridxtreamiptv.utils.FocusGlintHelper.forceReset(binding.root)
            
            // If the view already has focus (e.g. after a data refresh), ensure effects are active
            if (binding.root.isFocused) {
                com.tvonnet.debridxtreamiptv.utils.FocusGlintHelper.attach(binding.root)
            }

            binding.root.setOnClickListener {
                onSourceClicked(source)
            }
        }

        private fun getFlagEmoji(languageCode: String): String {
            return when (languageCode.lowercase()) {
                "en", "eng", "english" -> "EN"
                "fr", "fre", "french" -> "FR"
                "de", "ger", "german" -> "DE"
                "es", "spa", "spanish" -> "ES"
                "it", "ita", "italian" -> "IT"
                "pt", "por", "portuguese" -> "PT"
                "ru", "rus", "russian" -> "RU"
                "hi", "hin", "hindi" -> "HI"
                "ja", "jpn", "japanese" -> "JA"
                "ko", "kor", "korean" -> "KO"
                "zh", "chi", "chinese" -> "ZH"
                "ar", "ara", "arabic" -> "AR"
                "tr", "tur", "turkish" -> "TR"
                "pl", "pol", "polish" -> "PL"
                "nl", "dut", "dutch" -> "NL"
                "multi" -> "MULTI"
                else -> "UNK"
            }
        }

        private fun formatSizeLabel(sizeBytes: Long): String {
            val gb = 1024.0 * 1024.0 * 1024.0
            val mb = 1024.0 * 1024.0
            return if (sizeBytes >= gb) {
                String.format(Locale.US, "%.1f GB", sizeBytes / gb)
            } else {
                String.format(Locale.US, "%.0f MB", sizeBytes / mb)
            }
        }

        private fun buildSeedersLabel(seeders: Int): String {
            val label = if (seeders == 1) "SEED" else "SEEDS"
            return "$seeders $label"
        }
    }

    override fun onAttachedToRecyclerView(recyclerView: RecyclerView) {
        super.onAttachedToRecyclerView(recyclerView)
        com.tvonnet.debridxtreamiptv.utils.FocusGlintHelper.attachScrollReset(recyclerView)
    }

    override fun onViewRecycled(holder: SourceViewHolder) {
        super.onViewRecycled(holder)
        com.tvonnet.debridxtreamiptv.utils.FocusGlintHelper.forceReset(holder.itemView)
    }

    companion object {
        private val DiffCallback = object : DiffUtil.ItemCallback<MovieSource>() {
            override fun areItemsTheSame(oldItem: MovieSource, newItem: MovieSource): Boolean {
                return oldItem.stream.stream_id == newItem.stream.stream_id
            }

            override fun areContentsTheSame(oldItem: MovieSource, newItem: MovieSource): Boolean {
                return oldItem == newItem
            }
        }
    }
}
