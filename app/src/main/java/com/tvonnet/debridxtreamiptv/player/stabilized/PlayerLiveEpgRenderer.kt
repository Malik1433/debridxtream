package com.tvonnet.debridxtreamiptv.player.stabilized

import android.view.View
import android.widget.ProgressBar
import android.widget.TextView
import androidx.core.view.isVisible
import com.tvonnet.debridxtreamiptv.R
import com.tvonnet.debridxtreamiptv.data.model.ContentType

/**
 * The legacy live EPG overlay STRIP renderer (Phase P21, fragment-split prep).
 *
 * Owns the ~15 now/next TextViews + progress bar and the two render methods
 * ([renderOverlay], [bindNextSlot]) that populate them from a [PlayerOverlayUiState].
 * This is the non-cinematic path — when the rich `liveOsd` is present, renderOverlay
 * hands the EPG to it and returns; only when there is no OSD does this strip draw.
 *
 * The overlay CONTAINER (`epgOverlay`) + its mode/pin/visibility (`epgOverlayUi`) +
 * the channel-meta views stay on the Activity (shared with PiP + the OSD + C5), so
 * the renderer reaches them through [activity]. Bodies are byte-identical.
 */
internal class PlayerLiveEpgRenderer(
    private val activity: BasePlayerFragment,
    private val session: PlayerSessionState,
) {

    private var tvNowTitle: TextView? = null
    private var tvNowTime: TextView? = null
    private var progressNow: ProgressBar? = null
    private var tvEpgStatus: TextView? = null
    private var tvNowCardTime: TextView? = null
    private var tvNowCardTitle: TextView? = null
    private var cardNext1: View? = null
    private var tvNext1Time: TextView? = null
    private var tvNext1Title: TextView? = null
    private var cardNext2: View? = null
    private var tvNext2Time: TextView? = null
    private var tvNext2Title: TextView? = null
    private var cardNext3: View? = null
    private var tvNext3Time: TextView? = null
    private var tvNext3Title: TextView? = null

    private var hasShownOverlayOnce = false
    private var lastOverlayState: PlayerOverlayUiState? = null

    private val contentType: ContentType? by session::contentType
    private val liveOsd get() = activity.liveOsd
    private val epgOverlay get() = activity.epgOverlay

    private fun showEpgOverlay(mode: EpgOverlayMode, pinned: Boolean) = activity.showEpgOverlay(mode, pinned)
    private fun updateOverlayVisibility() = activity.updateOverlayVisibility()
    private fun formatTimeRangeCompact(program: com.tvonnet.debridxtreamiptv.data.local.entity.EpgEntity) =
        activity.formatTimeRangeCompact(program)
    private fun getString(resId: Int): String = activity.getString(resId)

    /** Bind this strip's views (the EPG slice of the Activity's setupOverlayViews). */
    fun bindViews() {
        tvNowTitle = activity.findViewById(R.id.tv_now_title)
        tvNowTime = activity.findViewById(R.id.tv_now_time)
        progressNow = activity.findViewById(R.id.progress_now)
        tvEpgStatus = activity.findViewById(R.id.tv_epg_status)
        tvNowCardTime = activity.findViewById(R.id.tv_now_card_time)
        tvNowCardTitle = activity.findViewById(R.id.tv_now_card_title)
        cardNext1 = activity.findViewById(R.id.card_next_1)
        tvNext1Time = activity.findViewById(R.id.tv_next_1_time)
        tvNext1Title = activity.findViewById(R.id.tv_next_1_title)
        cardNext2 = activity.findViewById(R.id.card_next_2)
        tvNext2Time = activity.findViewById(R.id.tv_next_2_time)
        tvNext2Title = activity.findViewById(R.id.tv_next_2_title)
        cardNext3 = activity.findViewById(R.id.card_next_3)
        tvNext3Time = activity.findViewById(R.id.tv_next_3_time)
        tvNext3Title = activity.findViewById(R.id.tv_next_3_title)
    }

    /** Seed the initial "syncing / no guide yet" state before the first EPG arrives. */
    fun seedInitialStateIfNeeded() {
        if (lastOverlayState == null) {
            lastOverlayState = PlayerOverlayUiState(epgAvailable = false, isSyncing = true, errorMessage = null)
        }
    }

    /** Re-render the last known state (called right after bindViews on setup). */
    fun renderLastState() {
        lastOverlayState?.let { renderOverlay(it) }
    }

    fun renderOverlay(state: PlayerOverlayUiState) {
        lastOverlayState = state
        if (contentType != ContentType.LIVE_TV) return
        liveOsd?.let { osd ->
            osd.bindEpg(state.now, state.next ?: state.upcoming.firstOrNull())
            return
        }
        if (epgOverlay == null) return

        val statusText = when {
            state.isSyncing -> getString(R.string.player_epg_syncing)
            !state.errorMessage.isNullOrBlank() -> state.errorMessage
            !state.epgAvailable -> getString(R.string.player_epg_no_guide)
            else -> null
        }
        tvEpgStatus?.text = statusText
        tvEpgStatus?.isVisible = !statusText.isNullOrBlank()

        val now = state.now
        tvNowTitle?.text = now?.title ?: getString(R.string.epg_no_info)
        tvNowTime?.text = now?.let { formatTimeRangeCompact(it) } ?: "--:--"
        tvNowCardTitle?.text = now?.title ?: getString(R.string.epg_no_info)
        tvNowCardTime?.text = now?.let { formatTimeRangeCompact(it) } ?: "--:--"

        val progress = now?.let { program ->
            val duration = (program.stop - program.start).coerceAtLeast(1L)
            ((System.currentTimeMillis() - program.start)
                .coerceAtLeast(0)
                .coerceAtMost(duration)
                * 100 / duration).toInt()
        } ?: 0
        progressNow?.progress = progress

        val nextPrograms = state.upcoming.take(3)
        bindNextSlot(cardNext1, tvNext1Title, tvNext1Time, nextPrograms.getOrNull(0))
        bindNextSlot(cardNext2, tvNext2Title, tvNext2Time, nextPrograms.getOrNull(1))
        bindNextSlot(cardNext3, tvNext3Title, tvNext3Time, nextPrograms.getOrNull(2))

        if (!hasShownOverlayOnce) {
            showEpgOverlay(EpgOverlayMode.COMPACT, pinned = false)
            hasShownOverlayOnce = true
        }

        updateOverlayVisibility()
    }

    private fun bindNextSlot(container: View?, titleView: TextView?, timeView: TextView?, program: com.tvonnet.debridxtreamiptv.data.local.entity.EpgEntity?) {
        titleView ?: return
        timeView ?: return
        if (program == null) {
            container?.isVisible = false
            return
        }
        container?.isVisible = true
        timeView.text = formatTimeRangeCompact(program)
        titleView.text = program.title ?: getString(R.string.epg_no_info)
    }
}
