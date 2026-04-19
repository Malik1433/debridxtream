package com.tvonnet.debridxtreamiptv.ui.settings.adapters

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.RecyclerView
import com.tvonnet.debridxtreamiptv.R
import com.tvonnet.debridxtreamiptv.databinding.ItemSettingsCategoryBinding
import com.tvonnet.debridxtreamiptv.ui.settings.SettingCategory

class SettingsCategoryAdapter(
    private val onCategorySelected: (SettingCategory) -> Unit
) : RecyclerView.Adapter<SettingsCategoryAdapter.ViewHolder>() {

    private val categories = SettingCategory.values()
    private var selectedCategory = SettingCategory.GENERAL

    init {
        setHasStableIds(true)
    }

    override fun getItemId(position: Int): Long {
        return categories[position].ordinal.toLong()
    }

    fun setSelectedCategory(category: SettingCategory) {
        val oldIndex = categories.indexOf(selectedCategory)
        val newIndex = categories.indexOf(category)
        selectedCategory = category
        if (oldIndex != -1) notifyItemChanged(oldIndex)
        notifyItemChanged(newIndex)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val binding = ItemSettingsCategoryBinding.inflate(
            LayoutInflater.from(parent.context), parent, false
        )
        return ViewHolder(binding)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val category = categories[position]
        holder.bind(category, category == selectedCategory)
    }

    override fun getItemCount(): Int = categories.size

    inner class ViewHolder(private val binding: ItemSettingsCategoryBinding) :
        RecyclerView.ViewHolder(binding.root) {

        init {
            binding.root.setOnClickListener {
                onCategorySelected(categories[bindingAdapterPosition])
            }
        }

        fun bind(category: SettingCategory, isSelected: Boolean) {
            binding.tvTitle.text = getCategoryTitle(category)
            
            val iconRes = getCategoryIcon(category)
            binding.ivIcon.setImageResource(iconRes)

            binding.root.isSelected = isSelected

            if (isSelected) {
                // binding.root.isSelected handles the background via selector_menu_item
                binding.tvTitle.setTextColor(ContextCompat.getColor(binding.root.context, R.color.color_accent))
                binding.ivIcon.setColorFilter(ContextCompat.getColor(binding.root.context, R.color.color_accent))
            } else {
                binding.tvTitle.setTextColor(ContextCompat.getColor(binding.root.context, R.color.white))
                binding.ivIcon.setColorFilter(ContextCompat.getColor(binding.root.context, R.color.white))
            }
        }

        private fun getCategoryTitle(category: SettingCategory): String {
            return when (category) {
                SettingCategory.GENERAL -> "General"
                SettingCategory.IPTV_EPG -> "IPTV & EPG"
                SettingCategory.HOME -> "Home Screen"
                SettingCategory.PLAYER -> "Player"
                SettingCategory.VISUALS -> "Visuals"
                SettingCategory.DEBRID -> "Real-Debrid"
                SettingCategory.ABOUT -> "About"
                SettingCategory.LOGOUT -> "Logout"
            }
        }

        private fun getCategoryIcon(category: SettingCategory): Int {
            return when (category) {
                SettingCategory.GENERAL -> R.drawable.ic_settings
                SettingCategory.IPTV_EPG -> R.drawable.ic_live_tv
                SettingCategory.HOME -> R.drawable.ic_home
                SettingCategory.PLAYER -> R.drawable.ic_live_tv
                SettingCategory.VISUALS -> R.drawable.ic_movie
                SettingCategory.DEBRID -> R.drawable.ic_weather_cloud
                SettingCategory.ABOUT -> R.drawable.ic_person
                SettingCategory.LOGOUT -> R.drawable.ic_logout
            }
        }
    }
}
