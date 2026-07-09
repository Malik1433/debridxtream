package com.tvonnet.debridxtreamiptv.player.stabilized

import android.view.LayoutInflater
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.core.view.isVisible
import androidx.recyclerview.widget.RecyclerView
import com.tvonnet.debridxtreamiptv.R
import com.tvonnet.debridxtreamiptv.util.GlideUtils

/**
 * Channel rows for the live-player surf drawer (Live Player - Spec.md §7).
 * Rows carry the ZapChannel list so OK-tune maps 1:1 onto the zap index.
 */
class LiveSurfChannelAdapter(
    private val onChannelClick: (Int) -> Unit
) : RecyclerView.Adapter<LiveSurfChannelAdapter.Holder>() {

    private var channels: List<ZapChannel> = emptyList()
    private var playingIndex: Int = -1
    private var favoriteIds: Set<String> = emptySet()
    private var nowTitles: Map<String, String> = emptyMap()

    fun submit(
        channels: List<ZapChannel>,
        playingIndex: Int,
        favoriteIds: Set<String>,
        nowTitles: Map<String, String> = emptyMap()
    ) {
        this.channels = channels
        this.playingIndex = playingIndex
        this.favoriteIds = favoriteIds
        this.nowTitles = nowTitles
        notifyDataSetChanged()
    }

    fun updatePlayingIndex(index: Int) {
        val old = playingIndex
        playingIndex = index
        if (old in channels.indices) notifyItemChanged(old)
        if (index in channels.indices) notifyItemChanged(index)
    }

    override fun getItemCount(): Int = channels.size

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_live_surf_channel, parent, false)
        return Holder(view)
    }

    override fun onBindViewHolder(holder: Holder, position: Int) {
        holder.bind(channels[position], position)
    }

    inner class Holder(itemView: android.view.View) : RecyclerView.ViewHolder(itemView) {
        private val num: TextView = itemView.findViewById(R.id.surf_num)
        private val logo: FrameLayout = itemView.findViewById(R.id.surf_logo)
        private val logoText: TextView = itemView.findViewById(R.id.surf_logo_text)
        private val logoImg: ImageView = itemView.findViewById(R.id.surf_logo_img)
        private val name: TextView = itemView.findViewById(R.id.surf_name)
        private val playing: TextView = itemView.findViewById(R.id.surf_playing)
        private val now: TextView = itemView.findViewById(R.id.surf_now)
        private val fav: ImageView = itemView.findViewById(R.id.surf_fav)

        fun bind(channel: ZapChannel, position: Int) {
            val ctx = itemView.context
            val isPlaying = position == playingIndex

            num.text = String.format("%03d", position + 1)
            num.setTextColor(
                ContextCompat.getColor(
                    ctx,
                    if (isPlaying) R.color.neon_cyan else R.color.live_osd_text_faint
                )
            )

            name.text = channel.name
            playing.isVisible = isPlaying
            itemView.isActivated = isPlaying

            val nowTitle = nowTitles[channel.streamId]
            now.text = nowTitle
            now.isVisible = !nowTitle.isNullOrBlank()

            logoText.text = LivePlayerOsdManager.channelInitials(channel.name)
            logo.background = LivePlayerOsdManager.channelTileGradient(channel.name, 5.5f * ctx.resources.displayMetrics.density)
            if (!channel.logoUrl.isNullOrBlank()) {
                logoImg.isVisible = true
                GlideUtils.loadChannelLogo(logoImg, channel.logoUrl)
            } else {
                logoImg.isVisible = false
            }

            fav.isVisible = favoriteIds.contains(channel.streamId)

            itemView.setOnClickListener { onChannelClick(position) }
        }
    }
}
