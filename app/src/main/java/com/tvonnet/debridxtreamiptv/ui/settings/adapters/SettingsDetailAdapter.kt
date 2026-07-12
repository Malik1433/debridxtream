package com.tvonnet.debridxtreamiptv.ui.settings.adapters

import android.graphics.drawable.GradientDrawable
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.core.view.isVisible
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.tvonnet.debridxtreamiptv.R
import com.tvonnet.debridxtreamiptv.databinding.ItemSettingsSelectionBinding
import com.tvonnet.debridxtreamiptv.databinding.ItemSettingsToggleBinding

sealed class SettingItem(val id: String) {
    data class Toggle(
        val key: String,
        val title: String,
        val description: String,
        val isChecked: Boolean,
        val onToggle: (Boolean) -> Unit
    ) : SettingItem(key)

    data class Selection(
        val key: String,
        val title: String,
        val currentValue: String,
        val onClick: () -> Unit
    ) : SettingItem(key)

    data class Action(
        val key: String,
        val title: String,
        val description: String,
        val onClick: () -> Unit
    ) : SettingItem(key)
}

/** Detail rows — rebuilt to "Settings Screen.dc.html": accent icon tile, custom toggle pill, value/chevron. */
class SettingsDetailAdapter : ListAdapter<SettingItem, RecyclerView.ViewHolder>(SettingDiffCallback()) {

    /** Active category accent — set before submitList so rows pick up the category colour. */
    var accent: Int = 0xFF00F0FF.toInt()

    init { setHasStableIds(true) }

    override fun getItemId(position: Int): Long = getItem(position).id.hashCode().toLong()

    companion object {
        private const val TYPE_TOGGLE = 1
        private const val TYPE_SELECTION = 2

        private fun tint(accent: Int, alpha: Int) = (accent and 0x00FFFFFF) or (alpha shl 24)

        fun iconFor(key: String): Int = when (key) {
            "autoplay" -> R.drawable.ic_play
            "logout_account", "sign_out" -> R.drawable.ic_logout
            "software_audio", "audio" -> R.drawable.ic_player_volume
            "preferred_audio_lang" -> R.drawable.ic_player_language
            "subtitles" -> R.drawable.ic_player_subtitles
            "live_tv_style", "resume_last_live", "epg_zoom", "epg_density", "epg_genre_tint",
            "epg_auto_sync", "epg_sync_interval", "epg_sync_now", "refresh_iptv", "home_custom_live" -> R.drawable.ic_live_tv
            "scale", "home_custom_movie", "home_custom_series" -> R.drawable.ic_movie
            "home_custom_movie_row" -> R.drawable.ic_home
            "manage_stremio_addons", "manage_debrid" -> R.drawable.ic_weather_cloud
            "version" -> R.drawable.ic_person
            else -> R.drawable.ic_settings
        }
    }

    override fun getItemViewType(position: Int): Int = when (getItem(position)) {
        is SettingItem.Toggle -> TYPE_TOGGLE
        else -> TYPE_SELECTION
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        val inflater = LayoutInflater.from(parent.context)
        return when (viewType) {
            TYPE_TOGGLE -> ToggleViewHolder(ItemSettingsToggleBinding.inflate(inflater, parent, false))
            else -> SelectionViewHolder(ItemSettingsSelectionBinding.inflate(inflater, parent, false))
        }
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        val item = getItem(position)
        when (holder) {
            is ToggleViewHolder -> holder.bind(item as SettingItem.Toggle, accent)
            is SelectionViewHolder -> when (item) {
                is SettingItem.Selection -> holder.bindSelection(item, accent)
                is SettingItem.Action -> holder.bindAction(item, accent)
                else -> {}
            }
        }
    }

    // ── shared row styling ──
    private object RowStyle {
        fun rowBg(root: View, focused: Boolean): GradientDrawable {
            val d = root.resources.displayMetrics.density
            return GradientDrawable().apply {
                cornerRadius = 5f * d
                if (focused) { setColor(0x0F00F0FF); setStroke((1 * d).toInt(), 0x3800F0FF) }
                else { setColor(0x660C111B.toInt()); setStroke((1 * d).toInt(), 0x0AFFFFFF) }
            }
        }

        fun iconTile(icon: View, accent: Int) {
            val d = icon.resources.displayMetrics.density
            icon.background = GradientDrawable().apply {
                cornerRadius = 4f * d
                setColor(tint(accent, 0x14))
                setStroke((1 * d).toInt(), tint(accent, 0x2E))
            }
        }
    }

    class ToggleViewHolder(private val binding: ItemSettingsToggleBinding) :
        RecyclerView.ViewHolder(binding.root) {

        private val d = binding.root.resources.displayMetrics.density

        fun bind(item: SettingItem.Toggle, accent: Int) {
            binding.tvTitle.text = item.title
            binding.tvDescription.text = item.description
            binding.tvDescription.isVisible = item.description.isNotBlank()
            binding.ivIcon.setImageResource(iconFor(item.key))
            binding.ivIcon.setColorFilter(accent)
            RowStyle.iconTile(binding.ivIcon, accent)
            styleToggle(item.isChecked, accent)
            binding.root.background = RowStyle.rowBg(binding.root, false)
            binding.root.setOnFocusChangeListener { v, has -> v.background = RowStyle.rowBg(v, has) }
            binding.root.setOnClickListener {
                val next = !item.isChecked
                styleToggle(next, accent)
                item.onToggle(next)
            }
        }

        private fun styleToggle(on: Boolean, accent: Int) {
            binding.vTogglePill.background = GradientDrawable().apply {
                cornerRadius = 7f * d
                setColor(if (on) tint(accent, 0x40) else 0x0FFFFFFF)
                setStroke((1 * d).toInt(), if (on) tint(accent, 0x66) else 0x1AFFFFFF)
            }
            binding.vToggleDot.background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(if (on) accent else 0xFF475569.toInt())
            }
            binding.vToggleDot.translationX = if (on) 14 * d else 2 * d
        }
    }

    class SelectionViewHolder(private val binding: ItemSettingsSelectionBinding) :
        RecyclerView.ViewHolder(binding.root) {

        private fun common(key: String, title: String, accent: Int) {
            binding.tvTitle.text = title
            binding.ivIcon.setImageResource(iconFor(key))
            binding.ivIcon.setColorFilter(accent)
            RowStyle.iconTile(binding.ivIcon, accent)
            binding.root.background = RowStyle.rowBg(binding.root, false)
            binding.root.setOnFocusChangeListener { v, has -> v.background = RowStyle.rowBg(v, has) }
        }

        fun bindSelection(item: SettingItem.Selection, accent: Int) {
            common(item.key, item.title, accent)
            binding.tvDescription.isVisible = false
            binding.tvStatus.isVisible = true
            binding.tvStatus.text = item.currentValue
            binding.tvStatus.setTextColor(accent)
            binding.root.setOnClickListener { item.onClick() }
        }

        fun bindAction(item: SettingItem.Action, accent: Int) {
            common(item.key, item.title, accent)
            binding.tvDescription.isVisible = item.description.isNotBlank()
            binding.tvDescription.text = item.description
            binding.tvStatus.isVisible = false
            binding.root.setOnClickListener { item.onClick() }
        }
    }
}

class SettingDiffCallback : DiffUtil.ItemCallback<SettingItem>() {
    override fun areItemsTheSame(oldItem: SettingItem, newItem: SettingItem): Boolean =
        oldItem.id == newItem.id

    override fun areContentsTheSame(oldItem: SettingItem, newItem: SettingItem): Boolean =
        oldItem == newItem
}
