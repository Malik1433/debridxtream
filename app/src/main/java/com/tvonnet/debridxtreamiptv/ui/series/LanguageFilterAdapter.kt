package com.tvonnet.debridxtreamiptv.ui.series

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.tvonnet.debridxtreamiptv.databinding.ItemLanguageChipBinding
import com.tvonnet.debridxtreamiptv.utils.FocusGlintHelper

class LanguageFilterAdapter(
    private val onLanguageSelected: (String) -> Unit
) : ListAdapter<String, LanguageFilterAdapter.LanguageViewHolder>(DiffCallback) {

    private var selectedLanguage: String? = null

    fun updateSelection(language: String?) {
        selectedLanguage = language
        notifyDataSetChanged()
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

        private fun getFlagEmoji(languageCode: String): String {
            return when (languageCode.lowercase()) {
                "en", "eng", "english" -> "🇬🇧"
                "fr", "fre", "french" -> "🇫🇷"
                "de", "ger", "german" -> "🇩🇪"
                "es", "spa", "spanish" -> "🇪🇸"
                "it", "ita", "italian" -> "🇮🇹"
                "pt", "por", "portuguese" -> "🇵🇹"
                "ru", "rus", "russian" -> "🇷🇺"
                "hi", "hin", "hindi" -> "🇮🇳"
                "ja", "jpn", "japanese" -> "🇯🇵"
                "ko", "kor", "korean" -> "🇰🇷"
                "zh", "chi", "chinese" -> "🇨🇳"
                "ar", "ara", "arabic" -> "🇸🇦"
                "tr", "tur", "turkish" -> "🇹🇷"
                "pl", "pol", "polish" -> "🇵🇱"
                "nl", "dut", "dutch" -> "🇳🇱"
                "multi" -> "🌍"
                else -> "🌍"
            }
        }
    }

    override fun onAttachedToRecyclerView(recyclerView: RecyclerView) {
        super.onAttachedToRecyclerView(recyclerView)
        com.tvonnet.debridxtreamiptv.utils.FocusGlintHelper.attachScrollReset(recyclerView)
    }

    override fun onViewRecycled(holder: LanguageViewHolder) {
        super.onViewRecycled(holder)
        com.tvonnet.debridxtreamiptv.utils.FocusGlintHelper.forceReset(holder.itemView)
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
