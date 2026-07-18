package com.tvonnet.debridxtreamiptv.ui.live

import android.view.LayoutInflater
import android.view.KeyEvent
import android.view.ViewGroup
import androidx.paging.PagingDataAdapter
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.RecyclerView
import com.bumptech.glide.Glide
import com.tvonnet.debridxtreamiptv.R
import com.tvonnet.debridxtreamiptv.data.local.entity.EpgEntity
import com.tvonnet.debridxtreamiptv.data.model.XtreamStream
import com.tvonnet.debridxtreamiptv.util.GlideUtils
import com.tvonnet.debridxtreamiptv.utils.MagneticFocusHelper
import java.util.Locale

/**
 * Paging3 adapter for Live TV channels
 * Efficiently handles large lists with automatic pagination
 * 
 * NEW DESIGN: Now uses enhanced card layout with metadata
 * Week 11: Added EPG integration
 */
class ChannelPagingAdapter(
    private val onChannelClick: (XtreamStream) -> Unit,
    private val onChannelLongClick: ((XtreamStream) -> Unit)? = null,
    private val epgProvider: ((XtreamStream) -> Pair<EpgEntity?, EpgEntity?>)? = null, // (stream) -> (current, next)
    private val favoriteChecker: ((String) -> Boolean)? = null, // Week 12: (streamId) -> isFavorite
    private val onChannelFocused: ((XtreamStream, Int) -> Unit)? = null,
    private val onChannelKey: ((Int, Int, KeyEvent) -> Boolean)? = null
) : PagingDataAdapter<XtreamStream, ChannelPagingViewHolder>(CHANNEL_COMPARATOR) {

// PagingDataAdapter handles stable IDs efficiently via DiffUtil.
    // Explicit getItemId override is disabled here due to library finality.
    
    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ChannelPagingViewHolder {
        val layoutId = when {
            USE_HORIZONTAL_CARD -> R.layout.item_channel_horizontal
            USE_NEW_CARD -> R.layout.item_channel_card_new
            else -> R.layout.item_channel_card
        }
        val view = LayoutInflater.from(parent.context)
            .inflate(layoutId, parent, false)
        return ChannelPagingViewHolder(view, USE_NEW_CARD, USE_HORIZONTAL_CARD)
    }
    
    override fun onBindViewHolder(holder: ChannelPagingViewHolder, position: Int) {
        val channel = getItem(position)
        if (channel != null) {
            // Fetch EPG data if provider is available
            val epgData = epgProvider?.invoke(channel)
            // Week 12: Check if channel is favorited
            val isFavorite = channel.stream_id?.toString()?.let { favoriteChecker?.invoke(it) } ?: false
            holder.bind(channel, onChannelClick, onChannelLongClick, epgData, isFavorite, onChannelFocused, onChannelKey)
            prefetchLogos(holder.itemView.context, position)
        }
    }

    override fun onViewRecycled(holder: ChannelPagingViewHolder) {
        super.onViewRecycled(holder)
        // Reset any stale focus/glint animation state before the view is reused.
        com.tvonnet.debridxtreamiptv.utils.FocusGlintHelper.forceReset(holder.itemView)
    }

    private fun prefetchLogos(context: android.content.Context, position: Int) {
        for (offset in 1..PREFETCH_DISTANCE) {
            val forwardIndex = position + offset
            if (forwardIndex >= itemCount && position - offset < 0) break
            val forward = safePeek(forwardIndex)
            if (forward?.stream_icon != null) {
                GlideUtils.preloadImage(context, forward.stream_icon)
            }
            val backIndex = position - offset
            val backward = safePeek(backIndex)
            if (backward?.stream_icon != null) {
                GlideUtils.preloadImage(context, backward.stream_icon)
            }
        }
    }

    private fun safePeek(index: Int): XtreamStream? {
        if (index < 0 || index >= itemCount) return null
        return try {
            peek(index)
        } catch (_: IndexOutOfBoundsException) {
            null
        }
    }
    
    companion object {
        // Toggle to use new enhanced card layout
        private const val USE_NEW_CARD = true
        private const val USE_HORIZONTAL_CARD = true  // Modern 3-column horizontal cards
        private const val PREFETCH_DISTANCE = 6
        
        private val CHANNEL_COMPARATOR = object : DiffUtil.ItemCallback<XtreamStream>() {
            override fun areItemsTheSame(oldItem: XtreamStream, newItem: XtreamStream): Boolean {
                return oldItem.stream_id == newItem.stream_id
            }
            
            override fun areContentsTheSame(oldItem: XtreamStream, newItem: XtreamStream): Boolean {
                return oldItem == newItem
            }
        }
    }
}

class ChannelPagingViewHolder(
    itemView: android.view.View,
    private val useNewCard: Boolean,
    private val useHorizontalCard: Boolean = false
) : RecyclerView.ViewHolder(itemView) {

    private val ivLogo = if (useHorizontalCard) {
        itemView.findViewById<android.widget.ImageView>(R.id.iv_channel_logo_horizontal)
    } else {
        itemView.findViewById<android.widget.ImageView>(R.id.iv_channel_logo)
    }

    // Horizontal card fields
    private val tvChannelNameHorizontal = if (useHorizontalCard) {
        itemView.findViewById<android.widget.TextView>(R.id.tv_channel_name_horizontal)
    } else null
    private val tvEpgNow = if (useHorizontalCard) {
        itemView.findViewById<android.widget.TextView>(R.id.tv_epg_now)
    } else null
    private val pbEpgProgress = if (useHorizontalCard) {
        itemView.findViewById<android.widget.ProgressBar>(R.id.pb_epg_progress)
    } else null
    private val badgeHd = if (useHorizontalCard) {
        itemView.findViewById<android.widget.TextView>(R.id.badge_hd)
    } else null
    private val badge4k = if (useHorizontalCard) {
        itemView.findViewById<android.widget.TextView>(R.id.badge_4k)
    } else null
    private val badgeEpg = if (useHorizontalCard) {
        itemView.findViewById<android.widget.TextView>(R.id.badge_epg)
    } else null
    private val badgePay = if (useHorizontalCard) {
        itemView.findViewById<android.widget.TextView>(R.id.badge_pay)
    } else null
    private val favoriteIndicator = if (useHorizontalCard) {
        itemView.findViewById<android.widget.ImageView>(R.id.iv_favorite_indicator)
    } else null
    // v2 row: one quality chip instead of the old separate HD/4K badges, plus the channel number.
    private val tvChannelNumber = if (useHorizontalCard) {
        itemView.findViewById<android.widget.TextView>(R.id.tv_channel_number)
    } else null
    private val badgeQuality = if (useHorizontalCard) {
        itemView.findViewById<android.widget.TextView>(R.id.badge_quality)
    } else null

    // New card fields
    private val tvChannelNumberBadge = if (useNewCard && !useHorizontalCard) {
        itemView.findViewById<android.widget.TextView>(R.id.tv_channel_number_badge)
    } else null

    private val tvChannelNumberName = if (useNewCard && !useHorizontalCard) {
        itemView.findViewById<android.widget.TextView>(R.id.tv_channel_number_name)
    } else null

    // Old card field
    private val tvName = if (!useNewCard && !useHorizontalCard) {
        itemView.findViewById<android.widget.TextView>(R.id.tv_channel_name)
    } else null

    fun bind(
        channel: XtreamStream,
        onClick: (XtreamStream) -> Unit,
        onLongClick: ((XtreamStream) -> Unit)? = null,
        epgData: Pair<EpgEntity?, EpgEntity?>? = null,
        isFavorite: Boolean = false,
        onFocused: ((XtreamStream, Int) -> Unit)? = null,
        onKey: ((Int, Int, KeyEvent) -> Boolean)? = null
    ) {
        itemView.onFocusChangeListener = android.view.View.OnFocusChangeListener { _, hasFocus ->
            if (hasFocus) onFocused?.invoke(channel, bindingAdapterPosition)
        }
        itemView.setOnKeyListener { _, keyCode, event ->
            val position = bindingAdapterPosition
            if (position == RecyclerView.NO_POSITION) {
                false
            } else {
                onKey?.invoke(position, keyCode, event) == true
            }
        }
        when {
            useHorizontalCard -> bindHorizontalCard(channel, onClick, onLongClick, epgData, isFavorite)
            useNewCard -> bindNewCard(channel, onClick, onLongClick)
            else -> bindOldCard(channel, onClick, onLongClick)
        }
    }

    private fun bindHorizontalCard(
        channel: XtreamStream,
        onClick: (XtreamStream) -> Unit,
        onLongClick: ((XtreamStream) -> Unit)? = null,
        epgData: Pair<EpgEntity?, EpgEntity?>? = null,
        isFavorite: Boolean = false
    ) {
        // NOTE: deliberately NOT using FocusGlintHelper here. Its applyFocus3D walks every
        // ancestor and sets clipChildren=false up to the root so a scaled card's glow isn't
        // cut — but that let channel rows scrolling off the top paint over the header chips.
        // v2 rows don't scale (PREMIUM_SCALE=1.0) and their focus treatment is the cyan border
        // drawn inside the row (livev2_row_bg selector) + glow, exactly as the design shows, so
        // the glint isn't needed and clipping stays on.

        val channelName = channel.name ?: "Unknown Channel"

        // Channel name
        tvChannelNameHorizontal?.text = channelName

        val nowProgram = epgData?.first
        val nowTitle = nowProgram?.title?.takeIf { it.isNotBlank() }
            ?: itemView.context.getString(R.string.live_preview_no_epg)
        tvEpgNow?.text = nowTitle
        tvEpgNow?.visibility = android.view.View.VISIBLE

        if (nowProgram != null) {
            val duration = (nowProgram.stop - nowProgram.start).coerceAtLeast(1L)
            val elapsed = (System.currentTimeMillis() - nowProgram.start)
                .coerceAtLeast(0)
                .coerceAtMost(duration)
            val progress = ((elapsed * 100) / duration).toInt()
            pbEpgProgress?.progress = progress
            pbEpgProgress?.visibility = android.view.View.VISIBLE
        } else {
            pbEpgProgress?.visibility = android.view.View.GONE
        }

        val is4k = channelName.contains("4k", ignoreCase = true) ||
            channelName.contains("uhd", ignoreCase = true)
        val isHd = !is4k && channelName.contains("hd", ignoreCase = true)
        val hasEpg = epgData?.first != null || epgData?.second != null
        val isPay = channelName.contains("ppv", ignoreCase = true) ||
            channelName.contains("pay", ignoreCase = true) ||
            channelName.contains("$")

        badge4k?.visibility = if (is4k) android.view.View.VISIBLE else android.view.View.GONE
        badgeHd?.visibility = if (isHd) android.view.View.VISIBLE else android.view.View.GONE
        badgeEpg?.visibility = if (hasEpg) android.view.View.VISIBLE else android.view.View.GONE
        badgePay?.visibility = if (isPay) android.view.View.VISIBLE else android.view.View.GONE
        favoriteIndicator?.visibility = if (isFavorite) android.view.View.VISIBLE else android.view.View.GONE

        // v2: channel number + a single quality chip (gold 4K / grey HD, hidden when neither).
        // Many providers send num=0 for every channel, so fall back to the row's position.
        val channelNumber = channel.num?.takeIf { it > 0 }
            ?: bindingAdapterPosition.takeIf { it != RecyclerView.NO_POSITION }?.plus(1)
        tvChannelNumber?.text = channelNumber
            ?.let { String.format(Locale.US, "%03d", it) }
            .orEmpty()
        badgeQuality?.apply {
            when {
                is4k -> {
                    text = "4K"
                    setBackgroundResource(R.drawable.bg_live_badge_quality_4k)
                    visibility = android.view.View.VISIBLE
                }
                isHd -> {
                    text = "HD"
                    setBackgroundResource(R.drawable.bg_live_badge_quality_hd)
                    visibility = android.view.View.VISIBLE
                }
                else -> visibility = android.view.View.GONE
            }
        }

        // Load channel logo
        loadChannelImage(channel.stream_icon)

        itemView.setOnClickListener {
            onClick(channel)
        }

        // Long press for favorites
        itemView.setOnLongClickListener {
            onLongClick?.invoke(channel)
            true
        }

    }

    private fun bindNewCard(channel: XtreamStream, onClick: (XtreamStream) -> Unit, onLongClick: ((XtreamStream) -> Unit)? = null) {
        val channelName = channel.name ?: "Unknown Channel"

        // Channel name only (clean and simple)
        tvChannelNumberName?.text = channelName

        // Load channel logo with Glide
        loadChannelImage(channel.stream_icon)

        itemView.setOnClickListener {
            onClick(channel)
        }

        // Long press for favorites
        itemView.setOnLongClickListener {
            onLongClick?.invoke(channel)
            true
        }

        com.tvonnet.debridxtreamiptv.utils.FocusGlintHelper.attach(itemView)
    }

    private fun bindOldCard(channel: XtreamStream, onClick: (XtreamStream) -> Unit, onLongClick: ((XtreamStream) -> Unit)? = null) {
        tvName?.text = channel.name ?: "Unknown"

        // Load channel logo with Glide
        loadChannelImage(channel.stream_icon)

        itemView.setOnClickListener {
            onClick(channel)
        }

        // Long press for favorites
        itemView.setOnLongClickListener {
            onLongClick?.invoke(channel)
            true
        }

        com.tvonnet.debridxtreamiptv.utils.FocusGlintHelper.attach(itemView)
    }

    private fun loadChannelImage(imageUrl: String?) {
        val resolved = com.tvonnet.debridxtreamiptv.util.GlobalConfig.resolveIconUrl(imageUrl)
        // Diagnostic Toast

        if (ivLogo != null) {
            if (!resolved.isNullOrBlank()) {
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
            } else {
                ivLogo.setImageResource(com.tvonnet.debridxtreamiptv.R.drawable.app_logo)
            }
        }
    }
}
