package com.tvonnet.debridxtreamiptv.player.stabilized

import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.core.view.isVisible
import androidx.recyclerview.widget.RecyclerView
import com.tvonnet.debridxtreamiptv.R
import com.tvonnet.debridxtreamiptv.util.GlideUtils

/** One programme block on a guide row, positioned as fractions of the window. */
data class GuideShow(
    val title: String,
    val timeLabel: String,
    val leftFrac: Float,
    val widthFrac: Float,
    val isCurrent: Boolean,
    /** Filler covering a gap with no EPG data — dimmed, non-navigable. */
    val isFiller: Boolean = false
)

/** One channel row of the in-player TV Guide grid. */
data class GuideRow(
    val name: String,
    val num: String,
    val logoUrl: String?,
    val isActiveChannel: Boolean,
    val nowLineFrac: Float,
    val shows: List<GuideShow>
)

/**
 * Renders the in-player TV Guide grid (Live Player v2). Focus is driven by the
 * OSD (position-based, not native), so the adapter just styles the focused
 * row/show and lays each programme block out by air-time.
 */
class LiveGuideAdapter : RecyclerView.Adapter<LiveGuideAdapter.Holder>() {

    private var rows: List<GuideRow> = emptyList()
    private var timelineWidthPx: Int = 0
    private var focusedRow: Int = 0
    private var focusedShow: Int = 0

    fun submit(rows: List<GuideRow>, timelineWidthPx: Int, focusedRow: Int, focusedShow: Int) {
        this.rows = rows
        this.timelineWidthPx = timelineWidthPx
        this.focusedRow = focusedRow
        this.focusedShow = focusedShow
        notifyDataSetChanged()
    }

    override fun getItemCount(): Int = rows.size

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_live_guide_row, parent, false)
        return Holder(view)
    }

    override fun onBindViewHolder(holder: Holder, position: Int) =
        holder.bind(rows[position], position)

    inner class Holder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val col: View = itemView.findViewById(R.id.guide_ch_col)
        private val logo: FrameLayout = itemView.findViewById(R.id.guide_ch_logo)
        private val logoText: TextView = itemView.findViewById(R.id.guide_ch_logo_text)
        private val logoImg: ImageView = itemView.findViewById(R.id.guide_ch_logo_img)
        private val name: TextView = itemView.findViewById(R.id.guide_ch_name)
        private val num: TextView = itemView.findViewById(R.id.guide_ch_num)
        private val timeline: FrameLayout = itemView.findViewById(R.id.guide_row_timeline)

        fun bind(row: GuideRow, position: Int) {
            val ctx = itemView.context
            val density = ctx.resources.displayMetrics.density
            val isFocusedRow = position == focusedRow

            logoText.text = LiveChannelVisuals.channelInitials(row.name)
            logo.background = LiveChannelVisuals.channelTileGradient(row.name, 6f * density)
            if (!row.logoUrl.isNullOrBlank()) {
                logoImg.isVisible = true
                logoText.isVisible = false
                GlideUtils.loadChannelLogo(logoImg, row.logoUrl)
            } else {
                logoImg.isVisible = false
                logoText.isVisible = true
            }
            name.text = row.name
            num.text = row.num
            name.setTextColor(
                ContextCompat.getColor(
                    ctx,
                    when {
                        row.isActiveChannel -> R.color.neon_cyan
                        isFocusedRow -> R.color.live_osd_text_primary
                        else -> R.color.live_osd_text_soft
                    }
                )
            )
            itemView.setBackgroundColor(
                if (isFocusedRow) 0x1400F0FF else Color.TRANSPARENT
            )

            timeline.removeAllViews()
            val width = timelineWidthPx
            if (width <= 0) return
            val vMargin = (3f * density).toInt()

            row.shows.forEachIndexed { si, show ->
                val leftPx = (show.leftFrac * width).toInt().coerceAtLeast(0)
                val wPx = (show.widthFrac * width).toInt().coerceAtLeast((10f * density).toInt())
                val focused = isFocusedRow && si == focusedShow

                val block = LayoutInflater.from(ctx)
                    .inflate(R.layout.item_live_guide_show, timeline, false)
                val title = block.findViewById<TextView>(R.id.guide_show_title)
                val time = block.findViewById<TextView>(R.id.guide_show_time)
                title.text = show.title
                time.isVisible = show.timeLabel.isNotEmpty()
                time.text = show.timeLabel
                if (show.isFiller) {
                    title.setTextColor(ContextCompat.getColor(ctx, R.color.live_osd_text_faint))
                } else if (focused || show.isCurrent) {
                    title.setTextColor(ContextCompat.getColor(ctx, R.color.live_osd_text_primary))
                    time.setTextColor(ContextCompat.getColor(ctx, R.color.neon_cyan))
                }
                block.background = blockBackground(density, show.isCurrent, focused, show.isFiller)

                val lp = FrameLayout.LayoutParams(wPx, FrameLayout.LayoutParams.MATCH_PARENT)
                lp.leftMargin = leftPx
                lp.topMargin = vMargin
                lp.bottomMargin = vMargin
                timeline.addView(block, lp)
            }

            // per-row NOW line
            if (row.nowLineFrac in 0f..1f) {
                val line = View(ctx)
                line.setBackgroundColor(0x5900F0FF)
                val lp = FrameLayout.LayoutParams((2f * density).toInt(), FrameLayout.LayoutParams.MATCH_PARENT)
                lp.leftMargin = (row.nowLineFrac * width).toInt()
                timeline.addView(line, lp)
            }
        }

        private fun blockBackground(density: Float, isCurrent: Boolean, focused: Boolean, isFiller: Boolean): GradientDrawable {
            val fill = when {
                isFiller -> 0x03FFFFFF
                focused -> 0x2400F0FF
                isCurrent -> 0x1400F0FF
                else -> 0x06FFFFFF
            }
            val strokeColor = when {
                isFiller -> 0x0AFFFFFF
                focused -> 0xFF00F0FF.toInt()
                isCurrent -> 0x4000F0FF
                else -> 0x12FFFFFF
            }
            val strokeW = if (focused) (2f * density).toInt() else (1f * density).toInt()
            return GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = 4.5f * density
                setColor(fill)
                setStroke(strokeW, strokeColor)
            }
        }
    }
}
