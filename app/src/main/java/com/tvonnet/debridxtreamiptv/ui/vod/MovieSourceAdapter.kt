package com.tvonnet.debridxtreamiptv.ui.vod

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.core.view.isVisible
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.tvonnet.debridxtreamiptv.R
import com.tvonnet.debridxtreamiptv.data.repository.DebridCacheStatus
import com.tvonnet.debridxtreamiptv.data.repository.MovieSource
import com.tvonnet.debridxtreamiptv.databinding.ItemMovieSourceBinding
import com.tvonnet.debridxtreamiptv.utils.FocusGlintHelper
import java.util.Locale

class MovieSourceAdapter(
    private val onSourceFocused: (MovieSource) -> Unit,
    private val onSourceClicked: (MovieSource) -> Unit
) : ListAdapter<MovieSource, MovieSourceAdapter.SourceViewHolder>(DiffCallback) {

    private var selectedStreamId: String? = null
    
    init {
        setHasStableIds(true)
    }
    
    override fun getItemId(position: Int): Long {
        return getItem(position).stream.stream_id.hashCode().toLong()
    }

    fun updateSelection(streamId: String?, notify: Boolean = true) {
        val oldId = selectedStreamId
        if (oldId == streamId) return
        
        selectedStreamId = streamId
        if (notify) {
            // Find positions and update selectively
            for (i in 0 until itemCount) {
                val item = getItem(i)
                if (item.stream.stream_id == oldId || item.stream.stream_id == streamId) {
                    notifyItemChanged(i)
                }
            }
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
        holder.bind(getItem(position))
    }

    inner class SourceViewHolder(
        private val binding: ItemMovieSourceBinding
    ) : RecyclerView.ViewHolder(binding.root) {

        fun bind(source: MovieSource) {
            binding.tvSourceLabel.text = source.label
            
            // Provider Badge
            bindProviderBadge(source)

            // Set language flags
            val languages = source.languages ?: listOf("multi")
            val flags = languages.map { getFlagEmoji(it) }.distinct().joinToString(" ")
            binding.tvLanguageFlag.text = flags

            // Quality Badge
            val qualityLabel = source.quality
                ?.trim()
                ?.takeIf { it.isNotBlank() && it.lowercase(Locale.US) != "unknown" }
                ?.uppercase(Locale.US)
            binding.tvBadgeQuality.isVisible = !qualityLabel.isNullOrBlank()
            if (!qualityLabel.isNullOrBlank()) {
                binding.tvBadgeQuality.text = qualityLabel
                // Apply color logic based on quality
                when {
                    qualityLabel.contains("4K") || qualityLabel.contains("2160") -> {
                        binding.tvBadgeQuality.setBackgroundResource(R.drawable.cin_pill_4k_gold)
                        binding.tvBadgeQuality.setTextColor(binding.root.context.getColor(R.color.black))
                    }
                    qualityLabel.contains("1080") -> {
                        binding.tvBadgeQuality.setBackgroundResource(R.drawable.cin_pill_1080p_blue)
                        binding.tvBadgeQuality.setTextColor(binding.root.context.getColor(R.color.white))
                    }
                    else -> {
                        binding.tvBadgeQuality.setBackgroundResource(R.drawable.cin_pill_generic)
                        binding.tvBadgeQuality.setTextColor(binding.root.context.getColor(R.color.white))
                    }
                }
            }

            // Size Badge
            val sizeLabel = source.sizeBytes
                ?.takeIf { it > 0 }
                ?.let { formatSizeLabel(it) }
            binding.tvBadgeSize.isVisible = !sizeLabel.isNullOrBlank()
            if (!sizeLabel.isNullOrBlank()) {
                binding.tvBadgeSize.text = sizeLabel
            }

            // Seeders Badge
            val seedersLabel = source.seeders
                ?.takeIf { it > 0 }
                ?.let { buildSeedersLabel(it) }
            binding.tvBadgeSeeders.isVisible = !seedersLabel.isNullOrBlank()
            if (!seedersLabel.isNullOrBlank()) {
                binding.tvBadgeSeeders.text = seedersLabel
            }

            // Cache confidence badge
            binding.tvBadgeCached.isVisible = true
            when (source.cacheStatus) {
                DebridCacheStatus.VERIFIED_CACHED -> {
                    binding.tvBadgeCached.text = "VERIFIED"
                    binding.tvBadgeCached.setBackgroundResource(R.drawable.cin_pill_cached)
                }
                DebridCacheStatus.DIRECT_STREAM -> {
                    binding.tvBadgeCached.text = "DIRECT"
                    binding.tvBadgeCached.setBackgroundResource(R.drawable.cin_pill_cached)
                }
                DebridCacheStatus.NOT_CACHED -> {
                    binding.tvBadgeCached.text = "UNCACHED"
                    binding.tvBadgeCached.setBackgroundResource(R.drawable.cin_pill_uncached)
                }
                DebridCacheStatus.UNKNOWN -> {
                    binding.tvBadgeCached.text = "UNKNOWN"
                    binding.tvBadgeCached.setBackgroundResource(R.drawable.cin_pill_generic)
                }
            }

            binding.layoutBadges.isVisible = true

            val isSelectedItem = source.stream.stream_id == selectedStreamId
            binding.root.isSelected = isSelectedItem

            // Focus management
            FocusGlintHelper.updateListener(binding.root) { _, hasFocus ->
                val stillSelected = source.stream.stream_id == selectedStreamId
                binding.root.isSelected = stillSelected
                
                // Show play icon only on focus for a cleaner look
                binding.ivPlayIcon.isVisible = hasFocus
                binding.ivPlayIcon.alpha = if (hasFocus) 1.0f else 0.0f
                
                if (hasFocus) {
                    onSourceFocused(source)
                }
            }
            
            FocusGlintHelper.forceReset(binding.root)
            
            if (binding.root.isFocused) {
                FocusGlintHelper.attach(binding.root)
                binding.ivPlayIcon.isVisible = true
                binding.ivPlayIcon.alpha = 1.0f
            } else {
                binding.ivPlayIcon.isVisible = false
            }

            binding.root.setOnClickListener {
                onSourceClicked(source)
            }
        }

        private fun bindProviderBadge(source: MovieSource) {
            val label = source.label?.uppercase(Locale.US) ?: ""
            val provider = source.provider?.uppercase(Locale.US) ?: ""
            
            val (badgeText, badgeBg) = when {
                label.contains("RD") || label.contains("REAL-DEBRID") || provider.contains("REALDEBRID") -> 
                    "RD" to R.drawable.cin_src_badge_real
                label.contains("AD") || label.contains("ALLDEBRID") || provider.contains("ALLDEBRID") -> 
                    "AD" to R.drawable.cin_src_badge_all
                label.contains("PM") || label.contains("PREMIUMIZE") || provider.contains("PREMIUMIZE") -> 
                    "PM" to R.drawable.cin_src_badge_prem
                else -> "SRC" to R.drawable.cin_src_badge_generic
            }
            
            binding.tvProviderBadge.text = badgeText
            binding.tvProviderBadge.setBackgroundResource(badgeBg)
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
        FocusGlintHelper.attachScrollReset(recyclerView)
    }

    override fun onViewRecycled(holder: SourceViewHolder) {
        super.onViewRecycled(holder)
        FocusGlintHelper.forceReset(holder.itemView)
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
