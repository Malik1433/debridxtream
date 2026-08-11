package com.tvonnet.debridxtreamiptv.ui.settings.adapters

import android.graphics.drawable.GradientDrawable
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.tvonnet.debridxtreamiptv.R
import com.tvonnet.debridxtreamiptv.databinding.ItemSettingsCategoryBinding
import com.tvonnet.debridxtreamiptv.ui.settings.SettingCategory

/** Category rail — rebuilt to "Settings Screen.dc.html": accent icon tile, active bar, sub caption. */
class SettingsCategoryAdapter(
    private val onCategorySelected: (SettingCategory) -> Unit,
    /** Tier gating: NORMAL (IPTV-only) devices must not see the Stremio Addons category. */
    private val showDebridCategory: Boolean = true
) : RecyclerView.Adapter<SettingsCategoryAdapter.ViewHolder>() {

    /**
     * Title and subtitle are string RESOURCES, not literals: this is a companion-object lookup with
     * no Context, and the settings rail is the one screen that must be readable in whatever
     * language the user just picked. Each call site resolves them from its own context.
     */
    data class CatMeta(val titleRes: Int, val subRes: Int, val accent: Int, val iconRes: Int)

    private val categories = SettingCategory.values()
        .filter { showDebridCategory || it != SettingCategory.ADDONS }
    private var selectedCategory = SettingCategory.PLAYBACK

    init { setHasStableIds(true) }

    override fun getItemId(position: Int): Long = categories[position].ordinal.toLong()

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

        private val d = binding.root.resources.displayMetrics.density

        init {
            binding.root.setOnClickListener {
                onCategorySelected(categories[bindingAdapterPosition])
            }
        }

        fun bind(category: SettingCategory, isSelected: Boolean) {
            val m = metaFor(category)
            binding.tvTitle.setText(m.titleRes)
            binding.tvSub.setText(m.subRes)
            binding.ivIcon.setImageResource(m.iconRes)
            style(category, isSelected, binding.root.isFocused)
            binding.root.setOnFocusChangeListener { _, has -> style(category, category == selectedCategory, has) }
        }

        private fun style(category: SettingCategory, selected: Boolean, focused: Boolean) {
            val m = metaFor(category)
            binding.vCatBar.visibility = if (selected) View.VISIBLE else View.INVISIBLE
            binding.vCatBar.background = bar(m.accent)
            binding.ivIcon.background = roundRect(
                5f,
                tint(m.accent, if (selected) 0x1A else 0x0A),
                tint(m.accent, if (selected) 0x33 else 0x10), 1
            )
            binding.ivIcon.setColorFilter(if (selected) m.accent else 0xFF64748B.toInt())
            binding.tvTitle.setTextColor(if (selected) 0xFFF1F5F9.toInt() else 0xFF94A3B8.toInt())
            binding.tvSub.setTextColor(if (selected) 0xFF475569.toInt() else 0xFF3D4F60.toInt())
            binding.root.background = when {
                focused -> roundRect(6f, 0x1200F0FF, 0x5900F0FF, 1)
                selected -> roundRect(6f, 0xB30C111B.toInt(), 0x14FFFFFF, 1)
                else -> null
            }
        }

        private fun bar(accent: Int) = GradientDrawable().apply {
            cornerRadius = 1 * d; setColor(accent)
        }

        private fun roundRect(radiusDp: Float, fill: Int, strokeColor: Int, strokeDp: Int) =
            GradientDrawable().apply {
                cornerRadius = radiusDp * d
                setColor(fill)
                if (strokeDp > 0) setStroke((strokeDp * d).toInt(), strokeColor)
            }
    }

    companion object {
        private fun tint(accent: Int, alpha: Int) = (accent and 0x00FFFFFF) or (alpha shl 24)

        fun metaFor(c: SettingCategory): CatMeta = when (c) {
            SettingCategory.PLAYBACK ->
                CatMeta(R.string.s_cat_playback, R.string.s_cat_playback_sub, 0xFFFF3366.toInt(), R.drawable.ic_play)
            SettingCategory.LIVE_TV ->
                CatMeta(R.string.s_cat_live_tv, R.string.s_cat_live_tv_sub, 0xFF00FF88.toInt(), R.drawable.ic_live_tv)
            SettingCategory.HOME ->
                CatMeta(R.string.s_cat_home, R.string.s_cat_home_sub, 0xFFFFAA00.toInt(), R.drawable.ic_home)
            SettingCategory.ADDONS ->
                CatMeta(R.string.s_cat_addons, R.string.s_cat_addons_sub, 0xFF00F0FF.toInt(), R.drawable.ic_weather_cloud)
            SettingCategory.DATA ->
                CatMeta(R.string.s_cat_data, R.string.s_cat_data_sub, 0xFFA78BFA.toInt(), R.drawable.ic_settings)
            SettingCategory.ABOUT ->
                CatMeta(R.string.s_cat_about, R.string.s_cat_about_sub, 0xFF64748B.toInt(), R.drawable.ic_person)
            SettingCategory.ACCOUNT ->
                CatMeta(R.string.s_cat_account, R.string.s_cat_account_sub, 0xFFFF3355.toInt(), R.drawable.ic_logout)
        }

        fun descFor(c: SettingCategory): Int = when (c) {
            SettingCategory.PLAYBACK -> R.string.s_cat_playback_desc
            SettingCategory.LIVE_TV -> R.string.s_cat_live_tv_desc
            SettingCategory.HOME -> R.string.s_cat_home_desc
            SettingCategory.ADDONS -> R.string.s_cat_addons_desc
            SettingCategory.DATA -> R.string.s_cat_data_desc
            SettingCategory.ABOUT -> R.string.s_cat_about_desc
            SettingCategory.ACCOUNT -> R.string.s_cat_account_desc
        }
    }
}
