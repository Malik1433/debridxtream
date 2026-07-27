package com.tvonnet.debridxtreamiptv.player.stabilized

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.ProgressBar
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.core.view.isVisible
import androidx.recyclerview.widget.RecyclerView
import android.graphics.drawable.Drawable
import com.bumptech.glide.Glide
import com.bumptech.glide.request.target.CustomTarget
import com.bumptech.glide.request.transition.Transition
import com.tvonnet.debridxtreamiptv.R
import com.tvonnet.debridxtreamiptv.util.GlideUtils

/** Current-EPG snapshot for one surf row (Live Player v2 rich rows). */
data class SurfRowEpg(val nowTitle: String?, val progress: Int, val leftLabel: String?)

/**
 * Rich channel rows for the Live Player v2 surf drawer (spec: 92px rows with
 * category color-bar, logo, now-playing + progress, quality badge, fav star).
 * Rows carry the ZapChannel list so OK-tune maps 1:1 onto the zap index.
 */
class LiveSurfChannelAdapter(
    private val onChannelClick: (Int) -> Unit,
    private val onChannelFocus: (Int) -> Unit
) : RecyclerView.Adapter<LiveSurfChannelAdapter.Holder>() {

    private var channels: List<ZapChannel> = emptyList()
    private var playingIndex: Int = -1
    private var favoriteIds: Set<String> = emptySet()
    private var epg: Map<String, SurfRowEpg> = emptyMap()
    private var categoryColor: Int = 0

    fun submit(
        channels: List<ZapChannel>,
        playingIndex: Int,
        favoriteIds: Set<String>,
        epg: Map<String, SurfRowEpg>,
        categoryColor: Int
    ) {
        this.channels = channels
        this.playingIndex = playingIndex
        this.favoriteIds = favoriteIds
        this.epg = epg
        this.categoryColor = categoryColor
        notifyDataSetChanged()
    }

    override fun getItemCount(): Int = channels.size

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_live_surf_channel, parent, false)
        return Holder(view)
    }

    override fun onBindViewHolder(holder: Holder, position: Int) = holder.bind(channels[position], position)

    inner class Holder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val catBar: View = itemView.findViewById(R.id.surf_cat_bar)
        private val num: TextView = itemView.findViewById(R.id.surf_num)
        private val wash: View = itemView.findViewById(R.id.surf_wash)
        private val logo: FrameLayout = itemView.findViewById(R.id.surf_logo)
        private val logoText: TextView = itemView.findViewById(R.id.surf_logo_text)
        private val logoImg: ImageView = itemView.findViewById(R.id.surf_logo_img)
        private val name: TextView = itemView.findViewById(R.id.surf_name)
        private val playing: TextView = itemView.findViewById(R.id.surf_playing)
        private val now: TextView = itemView.findViewById(R.id.surf_now)
        private val progress: ProgressBar = itemView.findViewById(R.id.surf_progress)
        private val left: TextView = itemView.findViewById(R.id.surf_left)
        private val quality: TextView = itemView.findViewById(R.id.surf_quality)
        private val fav: ImageView = itemView.findViewById(R.id.surf_fav)

        fun bind(channel: ZapChannel, position: Int) {
            val ctx = itemView.context
            val isPlaying = position == playingIndex

            catBar.setBackgroundColor(categoryColor)
            catBar.alpha = if (isPlaying) 1f else 0.5f

            num.text = String.format(java.util.Locale.US, "%03d", position + 1)
            num.setTextColor(
                ContextCompat.getColor(ctx, if (isPlaying) R.color.neon_cyan else R.color.live_osd_text_faint)
            )

            name.text = channel.name
            playing.isVisible = isPlaying
            itemView.isActivated = isPlaying

            bindLogoAndWash(channel, isPlaying)

            val rowEpg = epg[channel.streamId]
            now.text = rowEpg?.nowTitle
            now.isVisible = !rowEpg?.nowTitle.isNullOrBlank()
            if (rowEpg != null && rowEpg.progress > 0) {
                progress.isVisible = true
                progress.progress = rowEpg.progress
                left.isVisible = !rowEpg.leftLabel.isNullOrBlank()
                left.text = rowEpg.leftLabel
            } else {
                progress.isVisible = false
                left.isVisible = false
            }

            val q = LiveChannelVisuals.detectQuality(channel.name)
            if (q == null) {
                quality.isVisible = false
            } else {
                quality.isVisible = true
                quality.text = q
                quality.setBackgroundResource(
                    if (q.contains("4K")) R.drawable.bg_live_badge_quality_4k
                    else R.drawable.bg_live_badge_quality_hd
                )
            }

            fav.isVisible = favoriteIds.contains(channel.streamId)

            itemView.setOnClickListener { onChannelClick(position) }
            itemView.setOnFocusChangeListener { _, hasFocus -> if (hasFocus) onChannelFocus(position) }
        }

        /**
         * The logo goes in as a Drawable rather than straight into the ImageView, because the row's
         * colour wash is derived from the artwork itself.
         *
         * The wash is painted TWICE on purpose: once immediately with the name-hash fallback so the
         * row is never colourless while the logo is in flight, and again with the real brand colour
         * once it arrives. Recycled rows are reset first, or a row keeps the previous channel's
         * logo and colour until its own load finishes.
         */
        private fun bindLogoAndWash(channel: ZapChannel, isPlaying: Boolean) {
            val ctx = itemView.context
            val fallback = LiveChannelVisuals.accentColor(channel.name)

            logoText.text = LiveChannelVisuals.channelInitials(channel.name)
            logoText.isVisible = true
            logoImg.isVisible = false
            logoImg.setImageDrawable(null)
            Glide.with(ctx).clear(logoTarget)
            applyWash(fallback, isPlaying)

            val url = com.tvonnet.debridxtreamiptv.util.GlobalConfig.resolveIconUrl(channel.logoUrl)
            if (url.isNullOrBlank()) return
            pendingLogoUrl = url
            pendingFocused = isPlaying
            pendingFallback = fallback
            Glide.with(ctx).load(url).dontAnimate().into(logoTarget)
        }

        private fun applyWash(color: Int, focused: Boolean) {
            wash.background = ChannelAccentWash.forRow(
                color, focused, itemView.resources.displayMetrics.density
            )
        }

        private var pendingLogoUrl: String? = null
        private var pendingFocused = false

        /**
         * The same fallback bind() already painted. Reusing it matters for a monochrome logo like
         * ZDF neo: the palette finds no brand colour, and recomputing the fallback from a different
         * input here would make the row visibly change colour the moment the logo loads.
         */
        private var pendingFallback = 0

        private val logoTarget = object : CustomTarget<Drawable>() {
            override fun onResourceReady(resource: Drawable, transition: Transition<in Drawable>?) {
                logoImg.setImageDrawable(resource)
                logoImg.isVisible = true
                logoText.isVisible = false
                applyWash(
                    ChannelAccentPalette.accentFor(pendingLogoUrl, resource, pendingFallback),
                    pendingFocused
                )
            }

            override fun onLoadCleared(placeholder: Drawable?) {
                logoImg.setImageDrawable(null)
                logoImg.isVisible = false
                logoText.isVisible = true
            }

            override fun onLoadFailed(errorDrawable: Drawable?) {
                // Keep the initials and the fallback wash bind() already applied.
                logoImg.isVisible = false
                logoText.isVisible = true
            }
        }
    }
}
