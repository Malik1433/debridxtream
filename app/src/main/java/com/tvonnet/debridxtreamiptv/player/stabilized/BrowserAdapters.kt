package com.tvonnet.debridxtreamiptv.player.stabilized

import android.view.KeyEvent
import android.graphics.Typeface
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.ProgressBar
import android.widget.TextView
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.tvonnet.debridxtreamiptv.R
import com.tvonnet.debridxtreamiptv.data.model.XtreamCategory
import com.tvonnet.debridxtreamiptv.data.model.XtreamStream
import com.tvonnet.debridxtreamiptv.util.GlideUtils

// Parsed Code Logic — the short code shown beside a category name in the sidebar:
// a recognised lang/country tag wins, then the branded shortcuts, then word initials.
private val CATEGORY_CODE_REGEX =
    Regex("""(?i)[\|\[\(\s]*(MULTI|EN|FR|IT|ES|DE|RU|TR|AR|NL|PT|PL|UK|US|IN|PK|CA|AU|BR)[\|\]\)\s]*""")

internal fun categorySidebarCode(rawName: String): String {
    val parsedCode = CATEGORY_CODE_REGEX.find(rawName)?.groupValues?.get(1)?.uppercase(java.util.Locale.ROOT)
    return when {
        parsedCode != null -> parsedCode
        rawName.contains("Favorite", true) -> "FAV"
        rawName.contains("Netflix", true) -> "NFLX"
        rawName.contains("Amazon", true) -> "PRME"
        rawName.contains("Disney", true) -> "DSNY"
        else -> {
            val words = rawName.split(" ", "|", "/", "-").filter { it.isNotBlank() }
            if (words.size >= 2) {
                words.take(3).mapNotNull { it.firstOrNull()?.uppercaseChar() }.joinToString("")
            } else {
                rawName.take(3).uppercase(java.util.Locale.ROOT).trim()
            }
        }
    }
}

/** The HD/4K badge pair for a channel row — 4K wins, HD only when not 4K. */
internal data class BrowserChannelBadges(val is4k: Boolean, val isHd: Boolean)

internal fun browserChannelBadges(name: String): BrowserChannelBadges {
    val is4k = name.contains("4k", ignoreCase = true) || name.contains("uhd", ignoreCase = true)
    val isHd = !is4k && (name.contains("hd", ignoreCase = true) || name.contains("fhd", ignoreCase = true))
    return BrowserChannelBadges(is4k, isHd)
}

class BrowserCategoryAdapter(
    private val onCategoryClick: (XtreamCategory) -> Unit,
    private val onRightPressed: (() -> Unit)? = null
) : ListAdapter<XtreamCategory, BrowserCategoryAdapter.ViewHolder>(CategoryDiffCallback) {

    private var selectedCategoryId: String? = null

    fun setSelectedId(id: String?) {
        val old = selectedCategoryId
        selectedCategoryId = id
        if (old == id) return
        val list = currentList
        val oldIndex = if (old != null) list.indexOfFirst { it.category_id == old } else -1
        val newIndex = if (id != null) list.indexOfFirst { it.category_id == id } else -1
        if (oldIndex != -1) notifyItemChanged(oldIndex)
        if (newIndex != -1) notifyItemChanged(newIndex)
    }

    fun getSelectedPosition(): Int {
        val id = selectedCategoryId ?: return -1
        return currentList.indexOfFirst { it.category_id == id }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_category_sidebar, parent, false)
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val category = getItem(position)
        val isSelected = category.category_id == selectedCategoryId
        holder.bind(category, isSelected)
    }

    inner class ViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val tvName: TextView = itemView.findViewById(R.id.tv_category_name)
        private val tvCode: TextView = itemView.findViewById(R.id.tv_category_code)
        private val indicator: View = itemView.findViewById(R.id.v_active_indicator)

        fun bind(category: XtreamCategory, isSelected: Boolean) {
            itemView.isFocusable = true
            itemView.isFocusableInTouchMode = true

            val rawName = category.category_name ?: "Unknown"
            tvName.text = rawName
            tvName.visibility = View.VISIBLE

            tvCode.text = categorySidebarCode(rawName)
            tvCode.visibility = View.VISIBLE

            // Active indicator
            indicator.visibility = if (isSelected) View.VISIBLE else View.INVISIBLE
            indicator.alpha = if (isSelected) 1f else 0f

            // Initial visual styling based on current state
            val hasFocus = itemView.hasFocus()
            itemView.scaleX = if (hasFocus) 1.05f else 1.0f
            itemView.scaleY = if (hasFocus) 1.05f else 1.0f
            applyStyle(hasFocus, isSelected)

            itemView.setOnClickListener {
                onCategoryClick(category)
            }

            itemView.setOnKeyListener { _, keyCode, event ->
                if (event.action == KeyEvent.ACTION_DOWN && keyCode == KeyEvent.KEYCODE_DPAD_RIGHT) {
                    onRightPressed?.invoke()
                    onRightPressed != null
                } else {
                    false
                }
            }

            itemView.setOnFocusChangeListener { v, focus ->
                v.animate()
                    .scaleX(if (focus) 1.05f else 1.0f)
                    .scaleY(if (focus) 1.05f else 1.0f)
                    .setDuration(120)
                    .start()
                applyStyle(focus, isSelected)
            }
        }

        // The three visual states, verbatim: focused glass, selected emerald, plain row.
        private fun applyStyle(focus: Boolean, isSelected: Boolean) {
            if (focus) {
                itemView.setBackgroundResource(R.drawable.bg_sidebar_item_focused_glass)
                tvName.setTextColor(itemView.context.getColor(R.color.white))
                tvCode.setTextColor(itemView.context.getColor(R.color.white))
                tvName.setTypeface(null, Typeface.BOLD)
            } else if (isSelected) {
                itemView.setBackgroundResource(R.color.sidebar_emerald_soft_bg)
                tvName.setTextColor(itemView.context.getColor(R.color.white))
                tvCode.setTextColor(itemView.context.getColor(R.color.white))
                tvName.setTypeface(null, Typeface.BOLD)
            } else {
                itemView.setBackgroundResource(android.R.color.transparent)
                tvName.setTextColor(itemView.context.getColor(R.color.white_opacity_70))
                tvCode.setTextColor(itemView.context.getColor(R.color.brand_cyan))
                tvName.setTypeface(null, Typeface.NORMAL)
            }
        }
    }

    object CategoryDiffCallback : DiffUtil.ItemCallback<XtreamCategory>() {
        override fun areItemsTheSame(oldItem: XtreamCategory, newItem: XtreamCategory): Boolean =
            oldItem.category_id == newItem.category_id

        override fun areContentsTheSame(oldItem: XtreamCategory, newItem: XtreamCategory): Boolean =
            oldItem == newItem
    }
}

class BrowserChannelAdapter(
    private val onChannelClick: (XtreamStream) -> Unit,
    private val onLeftPressedAtZero: (() -> Unit)? = null
) : ListAdapter<XtreamStream, BrowserChannelAdapter.ViewHolder>(ChannelDiffCallback) {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        // Use the new modern card layout
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_browser_channel_modern, parent, false)
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val channel = getItem(position)
        holder.bind(channel)
    }

    inner class ViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val ivLogo: ImageView = itemView.findViewById(R.id.iv_channel_logo_modern)
        private val tvName: TextView = itemView.findViewById(R.id.tv_channel_name_modern)
        private val tvPlaying: TextView = itemView.findViewById(R.id.tv_now_playing_modern)
        
        // Optional badges
        private val badgeHd: TextView? = itemView.findViewById(R.id.badge_hd_modern)
        private val badge4k: TextView? = itemView.findViewById(R.id.badge_4k_modern)

        fun bind(channel: XtreamStream) {
            tvName.text = channel.name ?: "Unknown Channel"
            tvPlaying.text = channel.currentProgramTitle ?: "No Information"

            loadLogo(com.tvonnet.debridxtreamiptv.util.GlobalConfig.resolveIconUrl(channel.stream_icon))

            // Basic badges
            val badges = browserChannelBadges(channel.name ?: "")
            badge4k?.visibility = if (badges.is4k) View.VISIBLE else View.GONE
            badgeHd?.visibility = if (badges.isHd) View.VISIBLE else View.GONE

            // Code-based focus animation with bounce effect
            itemView.onFocusChangeListener = View.OnFocusChangeListener { v, hasFocus ->
                animateBounceFocus(v, hasFocus)
            }

            itemView.setOnClickListener {
                onChannelClick(channel)
            }

            itemView.setOnKeyListener { _, keyCode, event -> handleChannelKey(keyCode, event) }
        }

        private fun loadLogo(resolved: String?) {
            if (resolved.isNullOrBlank()) {
                ivLogo.setImageResource(com.tvonnet.debridxtreamiptv.R.drawable.app_logo)
                return
            }
            val glideUrl = com.bumptech.glide.load.model.GlideUrl(
                resolved,
                com.bumptech.glide.load.model.LazyHeaders.Builder()
                    .addHeader("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36")
                    .addHeader("Accept", "image/avif,image/webp,image/apng,image/svg+xml,image/*,*/*;q=0.8")
                    .build()
            )
            com.bumptech.glide.Glide.with(itemView.context)
                .load(glideUrl)
                .apply(com.bumptech.glide.request.RequestOptions().transform(com.bumptech.glide.load.resource.bitmap.CenterCrop(), com.bumptech.glide.load.resource.bitmap.RoundedCorners(16)))
                .placeholder(com.tvonnet.debridxtreamiptv.R.drawable.app_logo)
                .error(com.tvonnet.debridxtreamiptv.R.drawable.app_logo)
                .into(ivLogo)
        }

        private fun animateBounceFocus(v: View, hasFocus: Boolean) {
            if (hasFocus) {
                v.animate()
                    .scaleX(1.1f)
                    .scaleY(1.1f)
                    .translationZ(10f)
                    .setDuration(200)
                    .setInterpolator(android.view.animation.OvershootInterpolator())
                    .start()
                // Optional: Add border or glow if needed via background change
            } else {
                v.animate()
                    .scaleX(1f)
                    .scaleY(1f)
                    .translationZ(0f)
                    .setDuration(200)
                    .setInterpolator(android.view.animation.AccelerateDecelerateInterpolator())
                    .start()
            }
        }

        // LEFT on the first card of a row (4-wide grid) steps back to the category list.
        private fun handleChannelKey(keyCode: Int, event: KeyEvent): Boolean {
            if (event.action != KeyEvent.ACTION_DOWN || keyCode != KeyEvent.KEYCODE_DPAD_LEFT) return false
            val pos = bindingAdapterPosition
            if (pos == RecyclerView.NO_POSITION || pos % 4 != 0) return false
            onLeftPressedAtZero?.invoke()
            return onLeftPressedAtZero != null
        }
    }

    object ChannelDiffCallback : DiffUtil.ItemCallback<XtreamStream>() {
        override fun areItemsTheSame(oldItem: XtreamStream, newItem: XtreamStream): Boolean =
            oldItem.stream_id == newItem.stream_id

        override fun areContentsTheSame(oldItem: XtreamStream, newItem: XtreamStream): Boolean =
            oldItem == newItem
    }
}
