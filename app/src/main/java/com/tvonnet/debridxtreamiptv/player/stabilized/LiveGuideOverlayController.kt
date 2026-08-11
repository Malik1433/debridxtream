package com.tvonnet.debridxtreamiptv.player.stabilized

import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.widget.FrameLayout
import android.widget.TextView
import androidx.core.view.isVisible
import androidx.media3.ui.PlayerView
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.tvonnet.debridxtreamiptv.R
import com.tvonnet.debridxtreamiptv.data.local.entity.EpgEntity
import com.tvonnet.debridxtreamiptv.data.local.entity.deOverlap
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * C8: the full-screen TV Guide grid drawn over live video (the "TV Guide" OSD button).
 *
 * It is a *lane* renderer, not a list: every channel row is a continuous timeline, so gaps between
 * known programmes are filled with "No information" blocks. Without that the grid shows floating
 * bars with holes between them, and D-pad LEFT/RIGHT walks into nothing.
 *
 * Three details here are load-bearing:
 *  - **`deOverlap()` on every row.** Overlapping XMLTV programmes are common in real feeds and
 *    render as ghosted/stacked titles; the guide is one of the two grids that must never skip it.
 *  - **filler blocks are skipped by navigation** ([focusableShowIndex] and the step-over in
 *    [guideMove]), so LEFT/RIGHT always lands on a real programme.
 *  - **the timeline width is measured, with a fallback to the display width.** A zero width
 *    silently renders every programme at zero pixels — an empty-looking guide, not a crash.
 *
 * Bodies moved verbatim from `LivePlayerOsdManager`.
 */
internal class LiveGuideOverlayController(
    private val root: View,
    private val playerView: PlayerView,
    private val host: Host,
) {
    /** What the guide needs from the OSD around it. */
    interface Host {
        /** The channels the guide draws, and which one is playing. */
        fun zapChannels(): List<ZapChannel>
        fun zapIndex(): Int

        /** The guide is full-screen: retire the bottom OSD, chrome and any open drawer. */
        fun retireChromeForGuide()
        fun showToast(message: String)

        /** Ask the host to load windowed EPG for every channel in the zap list. */
        fun requestGuideData(fromMs: Long, toMs: Long)
        fun tuneChannel(index: Int)
    }

    private companion object {
        const val GUIDE_BEFORE_MS = 60 * 60_000L
        const val GUIDE_WINDOW_MS = 180 * 60_000L
        const val GUIDE_CH_COL_DP = 140f
        const val GUIDE_MIN_FILLER_MS = 2 * 60_000L
        const val TIMEBAR_SLOT_MS = 30 * 60_000L
        const val FADE_IN_MS = 200L
        const val FADE_OUT_MS = 180L
    }

    private val clockFormat = SimpleDateFormat("h:mm a", Locale.getDefault())
    private val timeFormat = SimpleDateFormat("HH:mm", Locale.getDefault())

    private val guideOverlay: View = root.findViewById(R.id.live_guide_overlay)
    private val guideTimebar: FrameLayout = root.findViewById(R.id.live_guide_timebar)
    private val guideClock: TextView = root.findViewById(R.id.live_guide_clock)
    private val guideList: RecyclerView = root.findViewById(R.id.rv_live_guide)

    private val guideAdapter = LiveGuideAdapter()

    private var guideEpg: Map<String, List<EpgEntity>> = emptyMap()
    private var guideRowsCache: List<GuideRow> = emptyList()
    private var guideOpen = false
    private var guideRow = 0
    private var guideShowIdx = 0
    private var guideTimelineWidthPx = 0
    private var guideWindowStartMs = 0L

    val isOpen: Boolean get() = guideOpen

    init {
        guideList.layoutManager = LinearLayoutManager(root.context)
        guideList.adapter = guideAdapter
        guideList.isFocusable = false
    }

    /** Open the full-screen EPG grid over the video (from the TV Guide button). */
    fun open() {
        if (host.zapChannels().isEmpty()) {
            host.showToast(root.context.getString(R.string.player_zap_loading))
            return
        }
        guideOpen = true
        host.retireChromeForGuide()

        guideWindowStartMs = System.currentTimeMillis() - GUIDE_BEFORE_MS
        guideRow = host.zapIndex().coerceIn(0, (host.zapChannels().size - 1).coerceAtLeast(0))
        guideShowIdx = 0
        guideClock.text = clockFormat.format(Date())
        host.requestGuideData(guideWindowStartMs, guideWindowStartMs + GUIDE_WINDOW_MS)

        guideOverlay.alpha = 0f
        guideOverlay.isVisible = true
        guideOverlay.animate().alpha(1f).setDuration(FADE_IN_MS).start()
        guideList.post {
            guideTimelineWidthPx = (guideList.width - dp(GUIDE_CH_COL_DP)).coerceAtLeast(0)
            if (guideTimelineWidthPx == 0) {
                guideTimelineWidthPx =
                    (root.resources.displayMetrics.widthPixels - dp(GUIDE_CH_COL_DP)).coerceAtLeast(0)
            }
            rebuild(initialFocus = true)
        }
    }

    /** Windowed EPG for the guide, keyed by streamId, arriving async from the host. */
    fun setGuideEpg(map: Map<String, List<EpgEntity>>) {
        guideEpg = map
        if (guideOpen) rebuild(initialFocus = false)
    }

    /** The OSD's minute ticker also drives the guide clock, so the two never disagree. */
    fun onMinuteTick(label: String) {
        guideClock.text = label
    }

    /** The guide owns every D-pad key while open (BACK is routed separately). */
    fun handleKeyEvent(event: KeyEvent): Boolean {
        if (!guideOpen) return false
        return when (event.keyCode) {
            KeyEvent.KEYCODE_DPAD_UP -> { guideMove(-1, 0); true }
            KeyEvent.KEYCODE_DPAD_DOWN -> { guideMove(1, 0); true }
            KeyEvent.KEYCODE_DPAD_LEFT -> { guideMove(0, -1); true }
            KeyEvent.KEYCODE_DPAD_RIGHT -> { guideMove(0, 1); true }
            KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER -> {
                tuneFromGuide(); true
            }
            else -> false
        }
    }

    /** BACK inside the guide closes it. Returns true if consumed. */
    fun handleBack(): Boolean {
        if (!guideOpen) return false
        close(returnToPlayer = true)
        return true
    }

    // ── rendering ──────────────────────────────────────────────────────────────

    private fun rebuild(initialFocus: Boolean) {
        if (!guideOpen) return
        if (guideTimelineWidthPx <= 0) {
            guideTimelineWidthPx = (guideList.width - dp(GUIDE_CH_COL_DP)).coerceAtLeast(0)
        }
        val rows = buildGuideRows()
        guideRowsCache = rows
        val showCount = rows.getOrNull(guideRow)?.shows?.size ?: 0
        guideShowIdx = if (initialFocus) {
            focusableShowIndex(rows.getOrNull(guideRow)?.shows)
        } else {
            guideShowIdx.coerceIn(0, (showCount - 1).coerceAtLeast(0))
        }
        guideAdapter.submit(rows, guideTimelineWidthPx, guideRow, guideShowIdx)
        renderGuideTimebar()
        guideList.scrollToPosition(guideRow)
    }

    private fun buildGuideRows(): List<GuideRow> {
        val startMs = guideWindowStartMs
        val endMs = startMs + GUIDE_WINDOW_MS
        val span = GUIDE_WINDOW_MS.toFloat()
        val nowMs = System.currentTimeMillis()
        val nowFrac = ((nowMs - startMs).toFloat() / span).coerceIn(0f, 1f)
        val zapIndex = host.zapIndex()
        return host.zapChannels().mapIndexed { idx, ch ->
            GuideRow(
                name = ch.name,
                num = String.format(Locale.US, "%03d", idx + 1),
                logoUrl = ch.logoUrl,
                isActiveChannel = idx == zapIndex,
                nowLineFrac = nowFrac,
                shows = buildLane(ch.streamId, startMs, endMs, span, nowMs)
            )
        }
    }

    /**
     * Build a CONTINUOUS lane: gaps between (and around) known programmes are filled with
     * "No information" blocks so the grid never shows floating bars.
     */
    private fun buildLane(
        streamId: String?,
        startMs: Long,
        endMs: Long,
        span: Float,
        nowMs: Long
    ): List<GuideShow> {
        val noInfo = root.context.getString(R.string.epg_no_info)
        fun filler(from: Long, to: Long) = GuideShow(
            title = noInfo,
            timeLabel = "",
            leftFrac = ((from - startMs).toFloat() / span).coerceIn(0f, 1f),
            widthFrac = ((to - from).toFloat() / span).coerceIn(0f, 1f),
            isCurrent = from <= nowMs && to > nowMs,
            isFiller = true
        )
        val progs = guideEpg[streamId].orEmpty()
            .filter { it.stop > startMs && it.start < endMs }
            .deOverlap()

        val shows = mutableListOf<GuideShow>()
        var cursor = startMs
        for (p in progs) {
            val clipStart = p.start.coerceAtLeast(startMs)
            val clipEnd = p.stop.coerceAtMost(endMs)
            if (clipStart <= cursor) {
                // overlap/adjacent — nothing to fill
            } else if (clipStart - cursor >= GUIDE_MIN_FILLER_MS) {
                shows.add(filler(cursor, clipStart))
            }
            shows.add(
                GuideShow(
                    title = p.title ?: noInfo,
                    timeLabel = timeFormat.format(Date(p.start)) + " · " +
                        ((p.stop - p.start) / 60_000L).coerceAtLeast(1) + "m",
                    leftFrac = ((clipStart - startMs).toFloat() / span).coerceIn(0f, 1f),
                    widthFrac = ((clipEnd - clipStart).toFloat() / span).coerceIn(0f, 1f),
                    isCurrent = p.start <= nowMs && p.stop > nowMs
                )
            )
            cursor = maxOf(cursor, clipEnd)
        }
        if (endMs - cursor >= GUIDE_MIN_FILLER_MS) shows.add(filler(cursor, endMs))
        return shows
    }

    private fun renderGuideTimebar() {
        guideTimebar.removeAllViews()
        val width = guideTimelineWidthPx
        if (width <= 0) return
        val startMs = guideWindowStartMs
        val span = GUIDE_WINDOW_MS.toFloat()
        var t = ((startMs + TIMEBAR_SLOT_MS - 1) / TIMEBAR_SLOT_MS) * TIMEBAR_SLOT_MS
        while (t <= startMs + GUIDE_WINDOW_MS) {
            val frac = (t - startMs).toFloat() / span
            val label = TextView(root.context).apply {
                text = timeFormat.format(Date(t))
                textSize = 6f
                setTextColor(0xFF475569.toInt())
                typeface = android.graphics.Typeface.MONOSPACE
            }
            val lp = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT,
                FrameLayout.LayoutParams.WRAP_CONTENT,
                Gravity.CENTER_VERTICAL
            )
            lp.leftMargin = (frac * width).toInt() + dp(2f)
            guideTimebar.addView(label, lp)
            t += TIMEBAR_SLOT_MS
        }
        renderNowMarker(width, startMs, span)
    }

    private fun renderNowMarker(width: Int, startMs: Long, span: Float) {
        val nowMs = System.currentTimeMillis()
        val nowX = (((nowMs - startMs).toFloat() / span).coerceIn(0f, 1f) * width).toInt()
        val line = View(root.context).apply { setBackgroundColor(0xFF00F0FF.toInt()) }
        val lineLp = FrameLayout.LayoutParams(dp(2f), FrameLayout.LayoutParams.MATCH_PARENT)
        lineLp.leftMargin = nowX
        guideTimebar.addView(line, lineLp)
        val pill = TextView(root.context).apply {
            text = context.getString(R.string.c_now)
            textSize = 5.5f
            setTextColor(0xFF041014.toInt())
            typeface = android.graphics.Typeface.MONOSPACE
            setPadding(dp(4f), dp(1f), dp(4f), dp(1f))
            background = GradientDrawable().apply {
                cornerRadius = dp(2.5f).toFloat()
                setColor(0xFF00F0FF.toInt())
            }
        }
        val pillLp = FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.WRAP_CONTENT,
            FrameLayout.LayoutParams.WRAP_CONTENT,
            Gravity.CENTER_VERTICAL
        )
        pillLp.leftMargin = (nowX - dp(8f)).coerceAtLeast(0)
        guideTimebar.addView(pill, pillLp)
    }

    // ── navigation ─────────────────────────────────────────────────────────────

    private fun guideMove(dRow: Int, dShow: Int) {
        if (guideRowsCache.isEmpty()) return
        if (dRow != 0) {
            guideRow = (guideRow + dRow).coerceIn(0, guideRowsCache.size - 1)
            // Keep focus on a real programme near the same time when changing channel.
            guideShowIdx = focusableShowIndex(guideRowsCache[guideRow].shows)
            guideList.smoothScrollToPosition(guideRow)
        }
        if (dShow != 0) {
            val shows = guideRowsCache[guideRow].shows
            var j = guideShowIdx + dShow
            while (j in shows.indices && shows[j].isFiller) j += dShow  // step over "No information"
            if (j in shows.indices) guideShowIdx = j
        }
        guideAdapter.submit(guideRowsCache, guideTimelineWidthPx, guideRow, guideShowIdx)
    }

    /** First real (non-filler) programme covering now, else first real programme, else 0. */
    private fun focusableShowIndex(shows: List<GuideShow>?): Int {
        if (shows.isNullOrEmpty()) return 0
        shows.indexOfFirst { it.isCurrent && !it.isFiller }.takeIf { it >= 0 }?.let { return it }
        shows.indexOfFirst { !it.isFiller }.takeIf { it >= 0 }?.let { return it }
        return 0
    }

    private fun tuneFromGuide() {
        val idx = guideRow
        close(returnToPlayer = false)
        host.tuneChannel(idx)
    }

    private fun close(returnToPlayer: Boolean) {
        if (!guideOpen) return
        guideOpen = false
        guideOverlay.animate().alpha(0f).setDuration(FADE_OUT_MS).withEndAction {
            guideOverlay.isVisible = false
            guideTimebar.removeAllViews()
        }.start()
        if (returnToPlayer) playerView.requestFocus()
    }

    private fun dp(v: Float): Int = (v * root.resources.displayMetrics.density).toInt()
}
