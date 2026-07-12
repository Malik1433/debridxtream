package com.tvonnet.debridxtreamiptv.player.stabilized

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ProgressBar
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.core.view.isVisible
import androidx.recyclerview.widget.RecyclerView
import com.tvonnet.debridxtreamiptv.R

/** One row of the bottom-OSD "ON AIR NOW" mini-EPG (prev / current / next). */
data class OnAirRow(
    val name: String,
    val num: String,
    val nowTitle: String?,
    val logoUrl: String?,
    val isActive: Boolean,
    val progress: Int
)

class LiveOnAirAdapter : RecyclerView.Adapter<LiveOnAirAdapter.Holder>() {

    private var rows: List<OnAirRow> = emptyList()

    fun submit(rows: List<OnAirRow>) {
        this.rows = rows
        notifyDataSetChanged()
    }

    override fun getItemCount(): Int = rows.size

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_live_onair, parent, false)
        return Holder(view)
    }

    override fun onBindViewHolder(holder: Holder, position: Int) = holder.bind(rows[position])

    inner class Holder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val bar: View = itemView.findViewById(R.id.onair_bar)
        private val logo: FrameLayout = itemView.findViewById(R.id.onair_logo)
        private val logoText: TextView = itemView.findViewById(R.id.onair_logo_text)
        private val name: TextView = itemView.findViewById(R.id.onair_name)
        private val live: TextView = itemView.findViewById(R.id.onair_live)
        private val now: TextView = itemView.findViewById(R.id.onair_now)
        private val progress: ProgressBar = itemView.findViewById(R.id.onair_progress)
        private val num: TextView = itemView.findViewById(R.id.onair_num)

        fun bind(row: OnAirRow) {
            val ctx = itemView.context
            val density = ctx.resources.displayMetrics.density

            logoText.text = LivePlayerOsdManager.channelInitials(row.name)
            logo.background = LivePlayerOsdManager.channelTileGradient(row.name, 4.5f * density)
            name.text = row.name
            num.text = row.num
            now.text = row.nowTitle
            now.isVisible = !row.nowTitle.isNullOrBlank()

            live.isVisible = row.isActive
            bar.setBackgroundColor(
                ContextCompat.getColor(ctx, if (row.isActive) R.color.neon_cyan else android.R.color.transparent)
            )
            progress.isVisible = row.isActive
            if (row.isActive) progress.progress = row.progress

            val alpha = if (row.isActive) 1f else 0.6f
            logo.alpha = alpha
            name.setTextColor(
                ContextCompat.getColor(ctx, if (row.isActive) R.color.live_osd_text_primary else R.color.live_osd_text_muted)
            )
            now.alpha = alpha
            num.alpha = alpha
        }
    }
}
