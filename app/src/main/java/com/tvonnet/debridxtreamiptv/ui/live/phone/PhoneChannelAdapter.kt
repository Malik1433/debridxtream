package com.tvonnet.debridxtreamiptv.ui.live.phone

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.ProgressBar
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.core.view.isVisible
import androidx.paging.PagingDataAdapter
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.RecyclerView
import com.bumptech.glide.Glide
import com.tvonnet.debridxtreamiptv.R
import com.tvonnet.debridxtreamiptv.data.local.entity.EpgEntity
import com.tvonnet.debridxtreamiptv.data.model.toAbsoluteUrl
import com.tvonnet.debridxtreamiptv.data.model.XtreamStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * The PHONE channel row: 88dp, logo tile, name, now/next and a progress line.
 *
 * Paged like the TV list, because a category here can hold two thousand channels and the
 * handoff's answer to that is the same one the app already had — [androidx.paging].
 *
 * The row's geometry never changes. A channel with no EPG keeps its full height and degrades its
 * CONTENT only: a missing guide is the common case, not an error, and a list whose rows resize as
 * EPG trickles in jumps under the thumb that is scrolling it.
 */
class PhoneChannelAdapter(
    private val actions: Actions,
    private val facts: Facts,
) : PagingDataAdapter<XtreamStream, PhoneChannelAdapter.VH>(DIFF) {

    /** What a row can do. */
    data class Actions(
        val onPlay: (XtreamStream) -> Unit,
        val onGuide: (XtreamStream) -> Unit,
        val onOverflow: (XtreamStream) -> Unit,
        val onToggleFavourite: (XtreamStream) -> Unit,
    )

    /** What a row needs to know, asked at bind time so it is never stale. */
    data class Facts(
        val epg: (XtreamStream) -> Pair<EpgEntity?, EpgEntity?>,
        val isFavourite: (XtreamStream) -> Boolean,
        val playingStreamId: () -> String?,
        /** Xtream serves some logos as a path relative to the portal, not a URL. */
        val serverUrl: () -> String,
    )

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) = VH(
        LayoutInflater.from(parent.context).inflate(R.layout.item_phone_channel_row, parent, false)
    )

    override fun onBindViewHolder(holder: VH, position: Int) {
        val channel = getItem(position) ?: return
        holder.bind(channel)
    }

    inner class VH(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val row: View = itemView.findViewById(R.id.phone_ch_row)
        private val logo: ImageView = itemView.findViewById(R.id.phone_ch_logo)
        private val logoFallback: TextView = itemView.findViewById(R.id.phone_ch_logo_fallback)
        private val name: TextView = itemView.findViewById(R.id.phone_ch_name)
        private val number: TextView = itemView.findViewById(R.id.phone_ch_number)
        private val now: TextView = itemView.findViewById(R.id.phone_ch_now)
        private val next: TextView = itemView.findViewById(R.id.phone_ch_next)
        private val progress: ProgressBar = itemView.findViewById(R.id.phone_ch_progress)
        private val star: ImageView = itemView.findViewById(R.id.phone_ch_star)
        private val overflow: ImageView = itemView.findViewById(R.id.phone_ch_overflow)

        fun bind(channel: XtreamStream) {
            val ctx = itemView.context
            name.text = channel.name.orEmpty()
            // Not every playlist numbers its channels. Where it does not, num arrives as 0 for
            // every row — printing that is worse than printing nothing.
            val channelNumber = channel.num?.takeIf { it > 0 }
            number.text = channelNumber?.toString().orEmpty()

            bindLogo(channel)
            bindEpg(channel)

            val favourite = facts.isFavourite(channel)
            star.setImageResource(
                if (favourite) R.drawable.ic_live_star_filled else R.drawable.ic_live_star
            )
            star.setColorFilter(
                ContextCompat.getColor(
                    ctx,
                    if (favourite) R.color.phone_amber else R.color.phone_text_muted
                )
            )

            // The row playing in the dock is marked so the user can see where they are in a list
            // they are scrolling past it.
            val playing = facts.playingStreamId() != null && facts.playingStreamId() == channel.stream_id
            row.setBackgroundResource(
                if (playing) R.drawable.bg_phone_row_playing else R.drawable.bg_phone_row_default
            )
            name.setTextColor(
                ContextCompat.getColor(
                    ctx,
                    if (playing) R.color.phone_cyan else R.color.phone_text_primary
                )
            )

            row.setOnClickListener { actions.onPlay(channel) }
            // Long-press is a shortcut for the star, never the only route to it.
            row.setOnLongClickListener { actions.onToggleFavourite(channel); true }
            star.setOnClickListener { actions.onToggleFavourite(channel) }
            overflow.setOnClickListener { actions.onOverflow(channel) }
        }

        private fun bindLogo(channel: XtreamStream) {
            val url = channel.stream_icon
                ?.takeIf { it.isNotBlank() }
                .toAbsoluteUrl(facts.serverUrl())
            if (url == null) {
                // No image: show the channel NUMBER. A generic TV glyph would say nothing the
                // user cannot already see, and the number is how they find this row again.
                logo.setImageDrawable(null)
                logo.isVisible = false
                logoFallback.isVisible = true
                // The channel NUMBER, per the handoff — and where the playlist has no numbers,
                // the channel's own initials rather than a generic glyph or a lie like "0".
                logoFallback.text = channel.num?.takeIf { it > 0 }?.toString()
                    ?: initialsOf(channel.name)
                return
            }
            logo.isVisible = true
            logoFallback.isVisible = false
            // fitCenter, never centerCrop: these marks are wide and transparent, and cropping one
            // to fill the tile cuts the name out of the logo.
            Glide.with(logo).load(url).fitCenter().into(logo)
        }

        private fun bindEpg(channel: XtreamStream) {
            val ctx = itemView.context
            val (current, upcoming) = facts.epg(channel)
            if (current == null) {
                now.text = ctx.getString(R.string.phone_no_guide_data)
                now.setTextColor(ContextCompat.getColor(ctx, R.color.phone_text_muted))
                progress.progress = 0
                progress.isEnabled = false
                next.text = ""
                // Not just a null listener: a View stays clickable once setOnClickListener has
                // made it so, and a row with no guide was swallowing the tap that should have
                // played the channel. The affordance has to be turned off, not just emptied.
                now.setOnClickListener(null)
                now.isClickable = false
                return
            }
            now.text = "${clock(current.start)} ${current.title.orEmpty()}"
            now.setTextColor(ContextCompat.getColor(ctx, R.color.phone_text_primary))
            progress.isEnabled = true
            progress.progress = percentThrough(current)
            next.text = upcoming?.let { ctx.getString(R.string.phone_next_badge) + " " + clock(it.start) }
                ?: ""
            // The now/next line is the guide's affordance — a channel without EPG never shows it.
            now.isClickable = true
            now.setOnClickListener { actions.onGuide(channel) }
        }
    }

    /** "|WC| BEIN SPORTS 1 HD FR" -> "BS": the country tag is not what tells channels apart. */
    private fun initialsOf(name: String?): String {
        val cleaned = name.orEmpty().replace(Regex("\\|[^|]*\\|"), " ").trim()
        return cleaned.split(' ')
            .filter { it.isNotBlank() }
            .take(2)
            .mapNotNull { word -> word.firstOrNull { it.isLetterOrDigit() }?.uppercaseChar() }
            .joinToString("")
            .ifBlank { "TV" }
    }

    private fun percentThrough(programme: EpgEntity): Int {
        val span = programme.stop - programme.start
        if (span <= 0) return 0
        val elapsed = System.currentTimeMillis() - programme.start
        return ((elapsed * 100) / span).toInt().coerceIn(0, 100)
    }

    private fun clock(epochMillis: Long): String = TIME.format(Date(epochMillis))

    private companion object {
        /** ROOT and 24h: this sits beside other mono readouts and must not change width. */
        val TIME = SimpleDateFormat("HH:mm", Locale.ROOT)

        val DIFF = object : DiffUtil.ItemCallback<XtreamStream>() {
            override fun areItemsTheSame(old: XtreamStream, new: XtreamStream) =
                old.stream_id == new.stream_id

            override fun areContentsTheSame(old: XtreamStream, new: XtreamStream) = old == new
        }
    }
}
