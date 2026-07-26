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

    /**
     * A read-only fact (version, signed-in user). It renders like a value row but takes no focus
     * and shows no chevron — on a D-pad, a row you can land on but cannot act on is a dead end.
     */
    data class Info(
        val key: String,
        val title: String,
        val value: String
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
            "logout_account" -> R.drawable.ic_logout
            "software_audio" -> R.drawable.ic_player_volume
            "preferred_audio_lang" -> R.drawable.ic_player_language
            "live_tv_style", "resume_last_live", "epg_zoom", "epg_density", "epg_genre_tint",
            "epg_auto_sync", "epg_sync_interval", "epg_sync_now", "home_custom_live" -> R.drawable.ic_live_tv
            "home_custom_movie", "home_custom_series" -> R.drawable.ic_movie
            "manage_stremio_addons", "manage_debrid" -> R.drawable.ic_weather_cloud
            "refresh_iptv", "clear_cache" -> R.drawable.ic_settings
            "version", "build", "account_user", "account_server" -> R.drawable.ic_person
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
                is SettingItem.Info -> holder.bindInfo(item, accent)
                else -> {}
            }
        }
    }

    // ── shared row styling ──
    private object RowStyle {
        // Radii/strokes scale with the 10-foot row sizes in the layouts; a 5dp corner on a 74dp
        // row reads as a square from the sofa, and a 1px focus stroke is invisible.
        fun rowBg(root: View, focused: Boolean): GradientDrawable {
            val d = root.resources.displayMetrics.density
            return GradientDrawable().apply {
                cornerRadius = 10f * d
                if (focused) { setColor(0x1400F0FF); setStroke((2 * d).toInt(), 0x5C00F0FF) }
                else { setColor(0x660C111B.toInt()); setStroke((1 * d).toInt(), 0x0AFFFFFF) }
            }
        }

        fun iconTile(icon: View, accent: Int) {
            val d = icon.resources.displayMetrics.density
            icon.background = GradientDrawable().apply {
                cornerRadius = 9f * d
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
                cornerRadius = PILL_HEIGHT_DP / 2f * d
                setColor(if (on) tint(accent, 0x40) else 0x0FFFFFFF)
                setStroke((1 * d).toInt(), if (on) tint(accent, 0x66) else 0x1AFFFFFF)
            }
            binding.vToggleDot.background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(if (on) accent else 0xFF475569.toInt())
            }
            // Geometry must track item_settings_toggle.xml: the dot slides between the two insets.
            binding.vToggleDot.translationX =
                if (on) (PILL_WIDTH_DP - DOT_SIZE_DP - PILL_INSET_DP) * d else PILL_INSET_DP * d
        }

        private companion object {
            const val PILL_WIDTH_DP = 56f
            const val PILL_HEIGHT_DP = 30f
            const val DOT_SIZE_DP = 22f
            const val PILL_INSET_DP = 4f
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
            // Rows are recycled between actionable and read-only kinds — restore the default.
            binding.root.isFocusable = true
            binding.root.isFocusableInTouchMode = true
            binding.root.isClickable = true
            binding.ivChevron.isVisible = true
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

        fun bindInfo(item: SettingItem.Info, accent: Int) {
            common(item.key, item.title, accent)
            binding.tvDescription.isVisible = false
            binding.tvStatus.isVisible = true
            binding.tvStatus.text = item.value
            binding.tvStatus.setTextColor(0xFF94A3B8.toInt())
            binding.ivChevron.isVisible = false
            binding.root.setOnClickListener(null)
            binding.root.isFocusable = false
            binding.root.isFocusableInTouchMode = false
            binding.root.isClickable = false
            binding.root.background = RowStyle.rowBg(binding.root, false)
        }
    }
}

class SettingDiffCallback : DiffUtil.ItemCallback<SettingItem>() {
    override fun areItemsTheSame(oldItem: SettingItem, newItem: SettingItem): Boolean =
        oldItem.id == newItem.id

    override fun areContentsTheSame(oldItem: SettingItem, newItem: SettingItem): Boolean =
        oldItem == newItem
}
