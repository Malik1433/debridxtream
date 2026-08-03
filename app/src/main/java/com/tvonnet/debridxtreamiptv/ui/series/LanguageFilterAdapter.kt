package com.tvonnet.debridxtreamiptv.ui.series

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.tvonnet.debridxtreamiptv.databinding.ItemLanguageChipBinding
import com.tvonnet.debridxtreamiptv.utils.FocusGlintHelper

// The old when-chain as data; unknown codes (and "multi") fall back to the globe.
private val FLAG_EMOJI_BY_LANGUAGE = mapOf(
    "en" to "🇬🇧", "eng" to "🇬🇧", "english" to "🇬🇧",
    "fr" to "🇫🇷", "fre" to "🇫🇷", "french" to "🇫🇷",
    "de" to "🇩🇪", "ger" to "🇩🇪", "german" to "🇩🇪",
    "es" to "🇪🇸", "spa" to "🇪🇸", "spanish" to "🇪🇸",
    "it" to "🇮🇹", "ita" to "🇮🇹", "italian" to "🇮🇹",
    "pt" to "🇵🇹", "por" to "🇵🇹", "portuguese" to "🇵🇹",
    "ru" to "🇷🇺", "rus" to "🇷🇺", "russian" to "🇷🇺",
    "hi" to "🇮🇳", "hin" to "🇮🇳", "hindi" to "🇮🇳",
    "ja" to "🇯🇵", "jpn" to "🇯🇵", "japanese" to "🇯🇵",
    "ko" to "🇰🇷", "kor" to "🇰🇷", "korean" to "🇰🇷",
    "zh" to "🇨🇳", "chi" to "🇨🇳", "chinese" to "🇨🇳",
    "ar" to "🇸🇦", "ara" to "🇸🇦", "arabic" to "🇸🇦",
    "tr" to "🇹🇷", "tur" to "🇹🇷", "turkish" to "🇹🇷",
    "pl" to "🇵🇱", "pol" to "🇵🇱", "polish" to "🇵🇱",
    "nl" to "🇳🇱", "dut" to "🇳🇱", "dutch" to "🇳🇱",
)

class LanguageFilterAdapter(
    private val onLanguageSelected: (String) -> Unit
) : ListAdapter<String, LanguageFilterAdapter.LanguageViewHolder>(DiffCallback) {

    private var selectedLanguage: String? = null

    init {
        setHasStableIds(true)
    }

    override fun getItemId(position: Int): Long {
        return getItem(position).hashCode().toLong()
    }

    fun updateSelection(language: String?) {
        val old = selectedLanguage
        if (old == language) return
        
        selectedLanguage = language
        
        // Find and update affected positions only
        for (i in 0 until itemCount) {
            val item = getItem(i)
            if (item == old || item == language) {
                notifyItemChanged(i)
            }
        }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): LanguageViewHolder {
        val binding = ItemLanguageChipBinding.inflate(
            LayoutInflater.from(parent.context),
            parent,
            false
        )
        return LanguageViewHolder(binding)
    }

    override fun onBindViewHolder(holder: LanguageViewHolder, position: Int) {
        holder.bind(getItem(position))
    }

    override fun onViewRecycled(holder: LanguageViewHolder) {
        super.onViewRecycled(holder)
        com.tvonnet.debridxtreamiptv.utils.FocusGlintHelper.forceReset(holder.itemView)
    }

    inner class LanguageViewHolder(
        private val binding: ItemLanguageChipBinding
    ) : RecyclerView.ViewHolder(binding.root) {

        fun bind(language: String) {
            // Display flag + code (e.g., "🇬🇧 EN")
            binding.tvLanguageName.text = getLanguageLabel(language)
            
            val isSelected = language == selectedLanguage
            binding.root.isSelected = isSelected
            
            binding.root.setOnClickListener {
                onLanguageSelected(language)
            }
            
            // Setup the focus listener via the helper to prevent overwriting/nesting
            com.tvonnet.debridxtreamiptv.utils.FocusGlintHelper.updateListener(binding.root) { _, hasFocus ->
                // Focus state is automatically handled by the view system and the selector drawable.
            }

            // Reset focus state before binding to prevent recycled highlights
            com.tvonnet.debridxtreamiptv.utils.FocusGlintHelper.forceReset(binding.root)
            
            // If the view already has focus (e.g. after a data refresh), ensure effects are active
            if (binding.root.isFocused) {
                com.tvonnet.debridxtreamiptv.utils.FocusGlintHelper.attach(binding.root)
            }
        }

        private fun getLanguageLabel(code: String): String {
            val flag = getFlagEmoji(code)
            return "$flag ${code.uppercase()}"
        }

        private fun getFlagEmoji(languageCode: String): String =
            FLAG_EMOJI_BY_LANGUAGE[languageCode.lowercase()] ?: "🌍"
    }

    override fun onAttachedToRecyclerView(recyclerView: RecyclerView) {
        super.onAttachedToRecyclerView(recyclerView)
        com.tvonnet.debridxtreamiptv.utils.FocusGlintHelper.attachScrollReset(recyclerView)
    }


    companion object {
        private val DiffCallback = object : DiffUtil.ItemCallback<String>() {
            override fun areItemsTheSame(oldItem: String, newItem: String): Boolean {
                return oldItem == newItem
            }

            override fun areContentsTheSame(oldItem: String, newItem: String): Boolean {
                return oldItem == newItem
            }
        }
    }
}
