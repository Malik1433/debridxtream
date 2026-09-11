package com.tvonnet.debridxtreamiptv.ui.series

import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import com.tvonnet.debridxtreamiptv.R
import com.tvonnet.debridxtreamiptv.data.repository.MovieSource

/**
 * The strip above the episode's source list — "12 SOURCES / EPISODE · 4 CACHED · BEST 1080P 2.4 GB"
 * and the quality badge beside it.
 *
 * Lifted out of [SeriesDebridSourceController] (2026-09-08). That class owns source ACQUISITION —
 * which addon was asked, what came back, what is cached, which one plays next — and this is the one
 * thing in it that is purely presentation: it reads a source list and writes three views, and it is
 * the only reason the controller held those views at all. Splitting it also kept the controller
 * under the file-size ceiling when the summary text moved to string resources; that was the
 * occasion, not the reason.
 *
 * Bodies are the controller's verbatim, with `bestQuality`/`sizeLabel` unchanged — the only
 * difference is that the sentence now comes from `strings.xml` instead of being built in Kotlin.
 */
internal class SeriesSourceSummaryStrip(
    private val layout: LinearLayout,
    private val summary: TextView,
    private val qualityBadge: TextView,
) {

    fun render(sources: List<MovieSource>) {
        if (sources.isEmpty()) {
            layout.visibility = View.GONE
            return
        }
        layout.visibility = View.VISIBLE
        val cachedCount = sources.count { it.isCached == true }
        val bestQuality = when {
            sources.any { it.quality?.contains("4K", true) == true || it.quality?.contains("2160", true) == true } -> "4K"
            sources.any { it.quality?.contains("1080", true) == true } -> "1080P"
            sources.any { it.quality?.contains("720", true) == true } -> "720P"
            else -> ""
        }
        val bestSize = sources.filter { it.isCached == true }
            .mapNotNull { it.sizeBytes }
            .maxOrNull()
        val sizeLabel = if (bestSize != null) " ${formatSizeLabel(bestSize)}" else ""

        // Two whole sentences rather than one plus an appended clause: a translator needs to see
        // where "BEST 1080P 2.4 GB" sits in their own word order, not receive it glued on in Kotlin.
        val ctx = summary.context
        summary.text = if (bestQuality.isNotEmpty()) {
            ctx.getString(
                R.string.f_sources_per_episode_best,
                sources.size, cachedCount, bestQuality, sizeLabel
            )
        } else {
            ctx.getString(R.string.f_sources_per_episode, sources.size, cachedCount)
        }

        // Update quality badge
        if (bestQuality.isNotEmpty()) {
            qualityBadge.visibility = View.VISIBLE
            qualityBadge.text = bestQuality
        }
    }

    private fun formatSizeLabel(bytes: Long): String {
        return when {
            bytes >= 1_073_741_824L -> String.format(java.util.Locale.US, "%.1f GB", bytes / 1_073_741_824.0)
            bytes >= 1_048_576L -> String.format(java.util.Locale.US, "%.0f MB", bytes / 1_048_576.0)
            else -> "${bytes / 1024} KB"
        }
    }
}
