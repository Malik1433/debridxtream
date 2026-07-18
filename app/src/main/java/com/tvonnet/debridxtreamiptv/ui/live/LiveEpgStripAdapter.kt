package com.tvonnet.debridxtreamiptv.ui.live

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ProgressBar
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.core.view.isVisible
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.tvonnet.debridxtreamiptv.R
import com.tvonnet.debridxtreamiptv.data.local.entity.EpgEntity
import com.tvonnet.debridxtreamiptv.ui.debrid.stremio.StremioFonts
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.TimeUnit

/**
 * The Live TV v2 "Program Guide" strip: today's programmes for the previewed channel, laid out
 * left-to-right with each card's width proportional to its duration (as in the design).
 *
 * Feed it de-overlapped programmes — overlapping XMLTV entries render as stacked ghost cards.
 */
class LiveEpgStripAdapter(
    private val onProgramClick: (EpgEntity) -> Unit = {},
    private val onKey: ((position: Int, keyCode: Int, event: android.view.KeyEvent) -> Boolean)? = null
) : ListAdapter<EpgEntity, LiveEpgStripAdapter.EpgCardViewHolder>(DIFF) {

    private val timeFormatter = SimpleDateFormat("HH:mm", Locale.getDefault())

    init {
        // Lets updatePreservingFocus restore focus to the same programme after a refresh.
        setHasStableIds(true)
    }

    override fun getItemId(position: Int): Long = getItem(position).start

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): EpgCardViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_livev2_epg_card, parent, false)
        return EpgCardViewHolder(view, timeFormatter)
    }

    override fun onBindViewHolder(holder: EpgCardViewHolder, position: Int) {
        holder.bind(getItem(position), onProgramClick, onKey)
    }

    class EpgCardViewHolder(
        itemView: View,
        private val timeFormatter: SimpleDateFormat
    ) : RecyclerView.ViewHolder(itemView) {

        private val tvTime: TextView? = itemView.findViewById(R.id.tv_epg_card_time)
        private val tvOnAir: TextView? = itemView.findViewById(R.id.tv_epg_card_onair)
        private val tvTitle: TextView? = itemView.findViewById(R.id.tv_epg_card_title)
        private val pbProgress: ProgressBar? = itemView.findViewById(R.id.pb_epg_card_progress)

        fun bind(
            program: EpgEntity,
            onProgramClick: (EpgEntity) -> Unit,
            onKey: ((Int, Int, android.view.KeyEvent) -> Boolean)?
        ) {
            val context = itemView.context
            val now = System.currentTimeMillis()
            val isNow = now in program.start until program.stop
            val isPast = program.stop <= now

            tvTime?.text = context.getString(
                R.string.livev2_epg_time_range,
                timeFormatter.format(Date(program.start)),
                timeFormatter.format(Date(program.stop))
            )
            tvTitle?.text = program.title?.takeIf { it.isNotBlank() }
                ?: context.getString(R.string.epg_no_info)
            tvOnAir?.isVisible = isNow

            // Design buckets card width by length: long programmes get the wider card.
            val durationMin = TimeUnit.MILLISECONDS.toMinutes(
                (program.stop - program.start).coerceAtLeast(0L)
            )
            val density = context.resources.displayMetrics.density
            val widthDp = if (durationMin >= 60) WIDE_CARD_WIDTH_DP else NARROW_CARD_WIDTH_DP
            itemView.layoutParams = itemView.layoutParams?.apply {
                width = (widthDp * density).toInt()
            }

            // The current programme is emphasised; already-aired ones recede (the design mock
            // always starts at "now" — real EPG carries an hour of lookback).
            itemView.isActivated = isNow
            tvTitle?.setTextColor(
                ContextCompat.getColor(
                    context,
                    if (isNow) R.color.stremio_text_primary else R.color.stremio_text_secondary
                )
            )
            tvTime?.setTextColor(
                ContextCompat.getColor(
                    context,
                    if (isNow) R.color.stremio_cyan else R.color.stremio_text_faint
                )
            )
            itemView.alpha = if (isPast) 0.55f else 1f
            tvTitle?.setTextSize(
                android.util.TypedValue.COMPLEX_UNIT_SP,
                if (isNow) NOW_TITLE_SP else TITLE_SP
            )
            // Outfit is a downloadable Google Font: the synchronous ResourcesCompat.getFont
            // throws Resources$NotFoundException on this device, so go through the shared
            // async/cached loader.
            tvTitle?.let {
                StremioFonts.apply(it, if (isNow) R.font.outfit_bold else R.font.outfit_semibold)
            }

            if (isNow) {
                val duration = (program.stop - program.start).coerceAtLeast(1L)
                val elapsed = (now - program.start).coerceIn(0L, duration)
                pbProgress?.progress = ((elapsed * 100) / duration).toInt()
                pbProgress?.isVisible = true
            } else {
                pbProgress?.isVisible = false
            }

            itemView.setOnClickListener { onProgramClick(program) }
            itemView.setOnKeyListener { _, keyCode, event ->
                val position = bindingAdapterPosition
                position != RecyclerView.NO_POSITION && onKey?.invoke(position, keyCode, event) == true
            }
        }

        private companion object {
            /** Design: 220px under an hour, 290px at or over — halved for this stage. */
            const val NARROW_CARD_WIDTH_DP = 110f
            const val WIDE_CARD_WIDTH_DP = 145f
            const val TITLE_SP = 7f
            const val NOW_TITLE_SP = 8f
        }
    }

    private companion object {
        val DIFF = object : DiffUtil.ItemCallback<EpgEntity>() {
            override fun areItemsTheSame(oldItem: EpgEntity, newItem: EpgEntity) =
                oldItem.start == newItem.start && oldItem.channelId == newItem.channelId

            override fun areContentsTheSame(oldItem: EpgEntity, newItem: EpgEntity) =
                oldItem == newItem
        }
    }
}
