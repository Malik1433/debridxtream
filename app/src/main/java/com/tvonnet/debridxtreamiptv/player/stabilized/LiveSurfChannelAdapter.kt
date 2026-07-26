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

            logoText.text = LivePlayerOsdManager.channelInitials(channel.name)
            // FIX 3: the logo tile's neutral background is now static (set once in
            // the layout XML) — no per-channel hashed-color gradient here anymore.
            if (!channel.logoUrl.isNullOrBlank()) {
                logoImg.isVisible = true
                // Explicit null placeholder/error: on a broken/missing logo URL, fall
                // back to the initials text underneath instead of a generic poster
                // placeholder icon sitting inside the tile.
                GlideUtils.loadChannelLogo(logoImg, channel.logoUrl, null, null)
            } else {
                logoImg.isVisible = false
            }

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

            val q = LivePlayerOsdManager.detectQuality(channel.name)
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
    }
}
