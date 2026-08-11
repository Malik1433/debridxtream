package com.tvonnet.debridxtreamiptv.ui.live.guide

import android.graphics.drawable.GradientDrawable
import android.view.View
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import com.tvonnet.debridxtreamiptv.R
import com.tvonnet.debridxtreamiptv.databinding.FragmentLiveTvGuideBinding
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * C7: the detail pane beside the guide — channel tile, programme title, times, progress and the
 * ON AIR / UP NEXT / ENDED status.
 *
 * It is driven purely by *focus*, not by selection: moving through the grid repaints this panel
 * without touching playback. That separation is what makes the guide feel like a TV guide rather
 * than a channel zapper, so nothing here may tune, persist or launch anything.
 *
 * Bodies moved verbatim from `LiveTvGuideFragment`.
 */
internal class GuideDetailPanel(
    private val fragment: Fragment,
    private val binding: FragmentLiveTvGuideBinding,
) {
    private val clockFmt = SimpleDateFormat("HH:mm", Locale.getDefault())

    fun update(channel: GuideChannel, program: GuideProgram?) {
        bindChannelTile(channel)
        if (program == null) {
            bindNoProgram(channel)
            return
        }
        bindProgram(program, System.currentTimeMillis())
    }

    private fun bindChannelTile(channel: GuideChannel) {
        val genreColor = color(channel.genre.colorRes)
        binding.previewTile.background = GradientDrawable().apply {
            cornerRadius = dp(16).toFloat()
            colors = intArrayOf(withAlpha(genreColor, 90), withAlpha(genreColor, 30))
            orientation = GradientDrawable.Orientation.TL_BR
            setStroke(dp(1), withAlpha(color(R.color.epg_cyan), 51))
        }
        binding.tvTileInitials.text = channel.initials()
        binding.tvTileName.text = channel.name

        binding.tvBadgeQuality.visibility = if (channel.quality != null) View.VISIBLE else View.GONE
        binding.tvBadgeQuality.text = channel.quality ?: ""
    }

    /** No EPG for this channel — show the channel itself rather than an empty pane. */
    private fun bindNoProgram(channel: GuideChannel) {
        binding.tvBadgeLive.visibility = View.GONE
        binding.tvStatus.text = ""
        binding.tvTitle.text = channel.name
        binding.tvMeta.text = channel.genre.label
        binding.tvDesc.text = ""
        binding.progressTrack.visibility = View.GONE
        binding.btnPrimary.text = binding.btnPrimary.context.getString(R.string.movie_detail_add_to_favorites)
    }

    private fun bindProgram(program: GuideProgram, now: Long) {
        val isNow = program.isNow(now)
        val ended = program.hasEnded(now)
        binding.tvBadgeLive.visibility = if (isNow) View.VISIBLE else View.GONE
        binding.tvStatus.text = when {
            isNow -> "ON AIR NOW"
            ended -> "ENDED"
            else -> "UP NEXT"
        }
        binding.tvStatus.setTextColor(color(when {
            isNow -> R.color.epg_status_onair
            ended -> R.color.epg_status_ended
            else -> R.color.epg_status_upnext
        }))
        binding.tvTitle.text = program.title
        binding.tvMeta.text =
            "${program.genre.label}   ${clockFmt.format(Date(program.startMs))} - ${clockFmt.format(Date(program.stopMs))}"
        binding.tvDesc.text = program.description ?: ""

        bindProgress(program, now, isNow)
        binding.btnPrimary.text = if (ended || isNow) "Add to Favorites" else "Set Reminder"
    }

    private fun bindProgress(program: GuideProgram, now: Long, isNow: Boolean) {
        if (!isNow) {
            binding.progressTrack.visibility = View.GONE
            return
        }
        binding.progressTrack.visibility = View.VISIBLE
        val frac = ((now - program.startMs).toFloat() / (program.stopMs - program.startMs)).coerceIn(0f, 1f)
        // Width is only known after layout, so the fill is sized in a post().
        binding.progressTrack.post {
            val lp = binding.progressFill.layoutParams
            lp.width = (binding.progressTrack.width * frac).toInt()
            binding.progressFill.layoutParams = lp
        }
    }

    /** The clock in the guide header — same formatter, so the two never disagree by a minute. */
    fun formatNow(): String = clockFmt.format(Date())

    private fun dp(v: Int) = (v * fragment.resources.displayMetrics.density).toInt()
    private fun color(res: Int) = ContextCompat.getColor(fragment.requireContext(), res)
    private fun withAlpha(c: Int, a: Int) = android.graphics.Color.argb(
        a, android.graphics.Color.red(c), android.graphics.Color.green(c), android.graphics.Color.blue(c)
    )
}
