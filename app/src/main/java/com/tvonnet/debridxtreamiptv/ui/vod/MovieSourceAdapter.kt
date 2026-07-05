package com.tvonnet.debridxtreamiptv.ui.vod

import android.view.LayoutInflater
import android.view.ViewGroup
import android.widget.TextView
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
    private val onSourceClicked: (MovieSource) -> Unit,
    private val onNavigateUpFromFirstRow: () -> Boolean = { false }
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
            // Col 1: Provider Badge
            bindProviderBadge(source)

            // Col 2: Language Flags
            bindLanguages(source)

            // Col 3: Source Title / Release Name
            binding.tvReleaseName.text = source.label

            // Col 4: Quality Badge
            val qualityLabel = source.quality
                ?.trim()
                ?.takeIf { it.isNotBlank() && it.lowercase(Locale.US) != "unknown" }
                ?.uppercase(Locale.US)
            binding.tvQuality.isVisible = !qualityLabel.isNullOrBlank()
            if (!qualityLabel.isNullOrBlank()) {
                binding.tvQuality.text = qualityLabel
                when {
                    qualityLabel.contains("4K") || qualityLabel.contains("2160") -> {
                        binding.tvQuality.setBackgroundResource(R.drawable.cin_pill_4k_gold)
                        binding.tvQuality.setTextColor(binding.root.context.getColor(R.color.black))
                    }
                    qualityLabel.contains("1080") -> {
                        binding.tvQuality.setBackgroundResource(R.drawable.cin_pill_1080p_blue)
                        binding.tvQuality.setTextColor(binding.root.context.getColor(R.color.white))
                    }
                    else -> {
                        binding.tvQuality.setBackgroundResource(R.drawable.cin_pill_generic)
                        binding.tvQuality.setTextColor(binding.root.context.getColor(R.color.white))
                    }
                }
            }

            // Col 5: Type Badge
            val typeLabel = when {
                source.sourceType == "IPTV" -> "IPTV"
                source.cacheStatus == DebridCacheStatus.NOT_CACHED -> "TORRENT"
                else -> "DIRECT"
            }
            binding.tvType.text = typeLabel
            if (typeLabel == "DIRECT") {
                binding.tvType.setBackgroundResource(R.drawable.cin_pill_cached)
                binding.tvType.setTextColor(binding.root.context.getColor(R.color.white))
            } else {
                binding.tvType.setBackgroundResource(R.drawable.cin_pill_uncached)
                binding.tvType.setTextColor(binding.root.context.getColor(R.color.white))
            }

            // Col 6: Size
            val sizeLabel = source.sizeBytes
                ?.takeIf { it > 0 }
                ?.let { formatSizeLabel(it) }
            binding.tvFilesize.isVisible = !sizeLabel.isNullOrBlank()
            if (!sizeLabel.isNullOrBlank()) {
                binding.tvFilesize.text = sizeLabel
            }

            val isSelectedItem = source.stream.stream_id == selectedStreamId
            binding.root.isSelected = isSelectedItem

            // Focus management
            FocusGlintHelper.updateListener(binding.root) { _, hasFocus ->
                val stillSelected = source.stream.stream_id == selectedStreamId
                binding.root.isSelected = stillSelected
                
                binding.ivPlay.isVisible = hasFocus
                binding.ivPlay.alpha = if (hasFocus) 1.0f else 0.0f
                
                if (hasFocus) {
                    onSourceFocused(source)
                }
            }
            
            FocusGlintHelper.forceReset(binding.root)
            
            if (binding.root.isFocused) {
                FocusGlintHelper.attach(binding.root)
                binding.ivPlay.isVisible = true
                binding.ivPlay.alpha = 1.0f
            } else {
                binding.ivPlay.isVisible = false
            }

            binding.root.setOnClickListener {
                onSourceClicked(source)
            }
            
            binding.root.setOnKeyListener { v, keyCode, event ->
                if (event.action == android.view.KeyEvent.ACTION_DOWN) {
                    val position = bindingAdapterPosition
                    if (position == RecyclerView.NO_POSITION) return@setOnKeyListener false
                    
                    val rv = v.parent as? RecyclerView ?: return@setOnKeyListener false
                    val adapter = rv.adapter ?: return@setOnKeyListener false
                    
                    when (keyCode) {
                        android.view.KeyEvent.KEYCODE_DPAD_CENTER,
                        android.view.KeyEvent.KEYCODE_ENTER -> {
                            onSourceClicked(source)
                            return@setOnKeyListener true
                        }
                        android.view.KeyEvent.KEYCODE_DPAD_DOWN -> {
                            if (position < adapter.itemCount - 1) {
                                val nextPosition = position + 1
                                rv.scrollToPosition(nextPosition)
                                rv.post {
                                    val nextHolder = rv.findViewHolderForAdapterPosition(nextPosition)
                                    nextHolder?.itemView?.requestFocus()
                                }
                                return@setOnKeyListener true
                            } else {
                                return@setOnKeyListener true
                            }
                        }
                        android.view.KeyEvent.KEYCODE_DPAD_UP -> {
                            if (position > 0) {
                                val prevPosition = position - 1
                                rv.scrollToPosition(prevPosition)
                                rv.post {
                                    val prevHolder = rv.findViewHolderForAdapterPosition(prevPosition)
                                    prevHolder?.itemView?.requestFocus()
                                }
                                return@setOnKeyListener true
                            }
                            onNavigateUpFromFirstRow()
                            return@setOnKeyListener true
                        }
                    }
                }
                false
            }
        }

        private fun bindProviderBadge(source: MovieSource) {
            val label = source.label.uppercase(Locale.US)
            val provider = source.provider?.uppercase(Locale.US) ?: ""
            val sourceName = source.sourceName?.uppercase(Locale.US) ?: ""

            // Design palette: RD cyan, AIO amber, STRM green, generic glass
            val darkText = 0xFF0E0D15.toInt()
            val (badgeText, badgeBg, textColor) = when {
                provider.contains("IPTV") || label == "IPTV" ->
                    Triple("IPTV", R.drawable.cin_src_badge_xtream, 0xFFE8B94A.toInt())
                sourceName.contains("AIO") || label.contains("AIOSTREAMS") ->
                    Triple("AIO", R.drawable.cin_src_badge_aio_amber, darkText)
                sourceName.contains("STREMTHRU") || sourceName.contains("STRM") || label.contains("STREMTHRU") ->
                    Triple("STRM", R.drawable.cin_src_badge_strm_green, darkText)
                label.contains("RD") || label.contains("REAL-DEBRID") || provider.contains("REALDEBRID") ->
                    Triple("RD", R.drawable.cin_src_badge_rd_cyan, darkText)
                label.contains("AD") || label.contains("ALLDEBRID") || provider.contains("ALLDEBRID") ->
                    Triple("AD", R.drawable.cin_src_badge_aio_amber, darkText)
                label.contains("PM") || label.contains("PREMIUMIZE") || provider.contains("PREMIUMIZE") ->
                    Triple("PM", R.drawable.cin_src_badge_prem, 0xFFF1F5F9.toInt())
                else -> Triple("SRC", R.drawable.cin_src_badge_generic, 0xFFF1F5F9.toInt())
            }

            binding.tvProvider.text = badgeText
            binding.tvProvider.setBackgroundResource(badgeBg)
            binding.tvProvider.setTextColor(textColor)
        }

        private fun bindLanguages(source: MovieSource) {
            binding.llLanguages.removeAllViews()
            val languages = source.languages ?: listOf("multi")
            
            languages.distinct().forEach { langCode ->
                val chip = LayoutInflater.from(binding.root.context).inflate(
                    R.layout.item_lang_chip, binding.llLanguages, false
                )
                val tvFlag = chip.findViewById<TextView>(R.id.tv_flag)
                val tvCode = chip.findViewById<TextView>(R.id.tv_code)
                
                val flagParts = getFlagEmoji(langCode).split(" ")
                val emoji = flagParts.firstOrNull() ?: "🌐"
                val code = if (flagParts.size > 1) flagParts[1] else "UNK"
                
                tvFlag.text = emoji
                tvCode.text = code
                binding.llLanguages.addView(chip)
            }
        }

        private fun getFlagEmoji(languageCode: String): String {
            return when (languageCode.lowercase()) {
                "en", "eng", "english" -> "🇺🇸 EN"
                "fr", "fre", "french" -> "🇫🇷 FR"
                "de", "ger", "german" -> "🇩🇪 DE"
                "es", "spa", "spanish" -> "🇪🇸 ES"
                "it", "ita", "italian" -> "🇮🇹 IT"
                "pt", "por", "portuguese" -> "🇵🇹 PT"
                "ru", "rus", "russian" -> "🇷🇺 RU"
                "hi", "hin", "hindi" -> "🇮🇳 HI"
                "ja", "jpn", "japanese" -> "🇯🇵 JA"
                "ko", "kor", "korean" -> "🇰🇷 KO"
                "zh", "chi", "chinese" -> "🇨🇳 ZH"
                "ar", "ara", "arabic" -> "🇸🇦 AR"
                "tr", "tur", "turkish" -> "🇹🇷 TR"
                "pl", "pol", "polish" -> "🇵🇱 PL"
                "nl", "dut", "dutch" -> "🇳🇱 NL"
                "multi" -> "🌎 MULTI"
                else -> "🌐 UNK"
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
