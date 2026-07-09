package com.tvonnet.debridxtreamiptv.ui.series

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.bumptech.glide.Glide
import com.bumptech.glide.load.resource.drawable.DrawableTransitionOptions
import com.tvonnet.debridxtreamiptv.R
import com.tvonnet.debridxtreamiptv.data.model.ContinueWatchingItem
import com.tvonnet.debridxtreamiptv.util.GlobalConfig

/**
 * Horizontal "Continue Watching" rail for the IPTV VOD Series screen.
 * Purple mirror of [com.tvonnet.debridxtreamiptv.ui.vod.VodContinueWatchingAdapter],
 * rendered with the landscape card [R.layout.item_series_cw_card] (adds an S/E episode label).
 */
class SeriesContinueWatchingAdapter(
    private val onItemClick: (ContinueWatchingItem) -> Unit
) : ListAdapter<ContinueWatchingItem, SeriesContinueWatchingAdapter.CwViewHolder>(CW_DIFF) {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): CwViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_series_cw_card, parent, false)
        return CwViewHolder(view)
    }

    override fun onBindViewHolder(holder: CwViewHolder, position: Int) {
        holder.bind(getItem(position), onItemClick)
    }

    override fun onViewRecycled(holder: CwViewHolder) {
        super.onViewRecycled(holder)
        Glide.with(holder.itemView.context).clear(holder.ivThumbnail)
    }

    companion object {
        val CW_DIFF = object : DiffUtil.ItemCallback<ContinueWatchingItem>() {
            override fun areItemsTheSame(a: ContinueWatchingItem, b: ContinueWatchingItem) =
                a.contentId == b.contentId && a.contentType == b.contentType
            override fun areContentsTheSame(a: ContinueWatchingItem, b: ContinueWatchingItem) =
                a == b
        }
    }

    class CwViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        val ivThumbnail: ImageView = itemView.findViewById(R.id.iv_cw_thumbnail)
        private val tvTitle: TextView = itemView.findViewById(R.id.tv_cw_title)
        private val tvTimeLeft: TextView = itemView.findViewById(R.id.tv_cw_time_left)
        private val tvEpisode: TextView? = itemView.findViewById(R.id.tv_cw_episode)
        private val vProgress: View = itemView.findViewById(R.id.v_cw_progress)

        fun bind(item: ContinueWatchingItem, onClick: (ContinueWatchingItem) -> Unit) {
            tvTitle.text = com.tvonnet.debridxtreamiptv.util.MediaTitleCleaner
                .clean(item.seriesTitle ?: item.title)

            // Episode label (S/E) when the watch-history entry carries episode info
            val season = item.seasonNumber
            val episode = item.episodeNumber
            if (season != null && episode != null) {
                tvEpisode?.text = "S$season E$episode"
                tvEpisode?.visibility = View.VISIBLE
            } else {
                tvEpisode?.visibility = View.GONE
            }

            val remainingMs = (item.totalDuration - item.currentPosition).coerceAtLeast(0L)
            val remainingMinutes = (remainingMs / 60_000L).toInt()
            tvTimeLeft.text = when {
                item.totalDuration <= 0L -> "RESUME"
                remainingMinutes >= 60 -> "${remainingMinutes / 60}h ${remainingMinutes % 60}m LEFT"
                remainingMinutes > 0 -> "${remainingMinutes}m LEFT"
                else -> "ALMOST DONE"
            }

            val percent = item.progressPercentage.coerceIn(0, 100)
            vProgress.post {
                val track = vProgress.parent as? View ?: return@post
                val totalW = track.width
                if (totalW > 0) {
                    vProgress.layoutParams = vProgress.layoutParams.apply {
                        width = (totalW * percent / 100f).toInt().coerceAtLeast(if (percent > 0) 4 else 0)
                    }
                    vProgress.requestLayout()
                }
            }

            val url = GlobalConfig.resolveIconUrl(item.posterUrl ?: item.backdropUrl)
            if (!url.isNullOrBlank()) {
                Glide.with(itemView.context)
                    .load(url)
                    .transition(DrawableTransitionOptions.withCrossFade(200))
                    .into(ivThumbnail)
            } else {
                ivThumbnail.setImageResource(R.drawable.tv_card_placeholder)
            }

            itemView.setOnClickListener { onClick(item) }
        }
    }
}
