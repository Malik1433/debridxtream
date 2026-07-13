package com.tvonnet.debridxtreamiptv.player.stabilized

import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.animation.AlphaAnimation
import android.view.animation.Animation
import android.view.animation.PathInterpolator
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.ProgressBar
import android.widget.TextView
import androidx.core.view.isVisible
import androidx.media3.common.C
import androidx.media3.common.Player
import androidx.media3.common.TrackSelectionOverride
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.tvonnet.debridxtreamiptv.R
import com.tvonnet.debridxtreamiptv.data.local.entity.EpgEntity
import com.tvonnet.debridxtreamiptv.data.local.entity.deOverlap
import com.tvonnet.debridxtreamiptv.utils.updatePreservingFocus
import com.tvonnet.debridxtreamiptv.data.model.XtreamCategory
import com.tvonnet.debridxtreamiptv.util.FAVORITES_CATEGORY_ID
import com.tvonnet.debridxtreamiptv.util.GlideUtils
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Cinematic live-player OSD (design_handoff_debxtrem_tv_ui/Live Player.dc.html).
 *
 * Owns: channel bug, top-right status, zap OSD, bottom OSD (now/next + controls
 * + hint bar), channel surf drawer, toast notifications and the zap backdrop.
 * PlayerActivity forwards key events and EPG/zap state; tuning and favorites go
 * back through the constructor callbacks.
 */
class LivePlayerOsdManager(
    private val root: View,
    private val playerView: PlayerView,
    private val playerProvider: () -> Player?,
    private val onRequestGuideData: (Long, Long) -> Unit,
    private val onTuneChannel: (Int) -> Unit,
    private val onToggleFavorite: () -> Unit,
    private val onCategorySelected: (String) -> Unit
) {
    private val handler = Handler(Looper.getMainLooper())
    private val easeOutExpo = PathInterpolator(0.22f, 1f, 0.36f, 1f)
    private val clockFormat = SimpleDateFormat("h:mm a", Locale.getDefault())
    private val timeFormat = SimpleDateFormat("HH:mm", Locale.getDefault())

    // ── views ──────────────────────────────────────────────────────────────
    private val zapBackdrop: FrameLayout = root.findViewById(R.id.live_zap_backdrop)
    private val zapBackdropGradient: View = root.findViewById(R.id.live_zap_backdrop_gradient)
    private val zapBackdropInitials: TextView = root.findViewById(R.id.live_zap_backdrop_initials)
    private val scrimTop: View = root.findViewById(R.id.live_scrim_top)
    private val scrimBottom: View = root.findViewById(R.id.live_scrim_bottom)
    private val channelBug: View = root.findViewById(R.id.live_channel_bug)
    private val bugLogo: FrameLayout = root.findViewById(R.id.live_bug_logo)
    private val bugInitials: TextView = root.findViewById(R.id.live_bug_initials)
    private val bugLogoImg: ImageView = root.findViewById(R.id.live_bug_logo_img)
    private val bugNumber: TextView = root.findViewById(R.id.live_bug_number)
    private val bugName: TextView = root.findViewById(R.id.live_bug_name)
    private val bugLiveDot: View = root.findViewById(R.id.live_bug_live_dot)
    private val bugQuality: TextView = root.findViewById(R.id.live_bug_quality)
    private val statusRow: View = root.findViewById(R.id.live_status_row)
    private val connText: TextView = root.findViewById(R.id.live_conn_text)
    private val clock: TextView = root.findViewById(R.id.live_clock)
    private val zapOsd: View = root.findViewById(R.id.live_zap_osd)
    private val zapNumber: TextView = root.findViewById(R.id.live_zap_number)
    private val zapName: TextView = root.findViewById(R.id.live_zap_name)
    private val zapNow: TextView = root.findViewById(R.id.live_zap_now)
    private val bottomOsd: View = root.findViewById(R.id.live_bottom_osd)
    private val nowTitle: TextView = root.findViewById(R.id.live_now_title)
    private val nowDesc: TextView = root.findViewById(R.id.live_now_desc)
    private val timeStart: TextView = root.findViewById(R.id.live_time_start)
    private val timeEnd: TextView = root.findViewById(R.id.live_time_end)
    private val minLeft: TextView = root.findViewById(R.id.live_min_left)
    private val progress: ProgressBar = root.findViewById(R.id.live_progress)
    // ON AIR NOW mini-EPG (bottom OSD, v2)
    private val onAirClock: TextView = root.findViewById(R.id.live_onair_clock)
    private val onAirList: RecyclerView = root.findViewById(R.id.rv_live_onair)
    private val btnChannels: View = root.findViewById(R.id.btn_live_channels)
    private val btnGuide: View = root.findViewById(R.id.btn_live_guide)
    private val btnCc: View = root.findViewById(R.id.btn_live_cc)
    private val btnAudio: View = root.findViewById(R.id.btn_live_audio)
    private val btnAspect: View = root.findViewById(R.id.btn_live_aspect)
    private val btnFav: View = root.findViewById(R.id.btn_live_fav)
    private val ccLabel: TextView = root.findViewById(R.id.live_cc_label)
    private val audioLabel: TextView = root.findViewById(R.id.live_audio_label)
    private val aspectLabel: TextView = root.findViewById(R.id.live_aspect_label)
    private val favIcon: ImageView = root.findViewById(R.id.live_fav_icon)
    private val drawerScrim: View = root.findViewById(R.id.live_drawer_scrim)
    private val drawer: View = root.findViewById(R.id.live_surf_drawer)
    private val surfTitle: TextView = root.findViewById(R.id.live_surf_title)
    private val surfCount: TextView = root.findViewById(R.id.live_surf_count)
    private val surfList: RecyclerView = root.findViewById(R.id.rv_live_surf)
    // preview strip (top of channel drawer, v2)
    private val previewBg: View = root.findViewById(R.id.live_preview_bg)
    private val previewLogo: FrameLayout = root.findViewById(R.id.live_preview_logo)
    private val previewLogoText: TextView = root.findViewById(R.id.live_preview_logo_text)
    private val previewLogoImg: ImageView = root.findViewById(R.id.live_preview_logo_img)
    private val previewName: TextView = root.findViewById(R.id.live_preview_name)
    private val previewNow: TextView = root.findViewById(R.id.live_preview_now)
    private val previewQuality: TextView = root.findViewById(R.id.live_preview_quality)
    private val previewProgress: ProgressBar = root.findViewById(R.id.live_preview_progress)
    // categories side panel (v2)
    private val catsDrawer: View = root.findViewById(R.id.live_cats_drawer)
    private val catsList: RecyclerView = root.findViewById(R.id.rv_live_cats)
    private val toast: View = root.findViewById(R.id.live_toast)
    private val toastDot: View = root.findViewById(R.id.live_toast_dot)
    private val toastText: TextView = root.findViewById(R.id.live_toast_text)
    // in-player TV Guide grid (v2)
    private val guideOverlay: View = root.findViewById(R.id.live_guide_overlay)
    private val guideTimebar: FrameLayout = root.findViewById(R.id.live_guide_timebar)
    private val guideClock: TextView = root.findViewById(R.id.live_guide_clock)
    private val guideList: RecyclerView = root.findViewById(R.id.rv_live_guide)

    private val controls = listOf(btnChannels, btnGuide, btnCc, btnAudio, btnAspect, btnFav)

    // ── state ──────────────────────────────────────────────────────────────
    private var currentNow: EpgEntity? = null
    private var isFavorite = false
    private var aspectIndex = 0
    private var zapChannels: List<ZapChannel> = emptyList()
    private var zapIndex: Int = -1
    private var favoriteIds: Set<String> = emptySet()
    private var channelName: String? = null
    private var connectionLabel: String = "XTREAM"

    // Two-panel surf drawer: channel list, optionally with the category picker.
    private enum class Panel { NONE, CHANNELS, BOTH }
    private var panel: Panel = Panel.NONE
    private var categories: List<XtreamCategory> = emptyList()
    private var currentCategoryId: String? = null
    private var currentCategoryName: String? = null
    private var pendingCategoryRefocus = false
    private var surfEpg: Map<String, EpgEntity> = emptyMap()
    private var previewIndex: Int = -1

    // in-player TV Guide grid state (v2)
    private var guideEpg: Map<String, List<EpgEntity>> = emptyMap()
    private var guideRowsCache: List<GuideRow> = emptyList()
    private var guideOpen = false
    private var guideRow = 0
    private var guideShowIdx = 0
    private var guideTimelineWidthPx = 0
    private var guideWindowStartMs = 0L
    private val guideAdapter = LiveGuideAdapter()

    val isDrawerOpen: Boolean get() = panel != Panel.NONE
    val isGuideOpen: Boolean get() = guideOpen
    val isOsdVisible: Boolean get() = bottomOsd.isVisible && bottomOsd.alpha > 0.5f

    private val surfAdapter = LiveSurfChannelAdapter(
        onChannelClick = { index ->
            closeDrawer(refocusControls = false)
            onTuneChannel(index)
        },
        onChannelFocus = { index -> updatePreview(index) }
    )

    private val categoryAdapter: LiveSurfCategoryAdapter = LiveSurfCategoryAdapter { category ->
        val catId = category.category_id ?: return@LiveSurfCategoryAdapter
        currentCategoryId = catId
        currentCategoryName = category.category_name
        // Reload the zap list for the picked category; the refreshed list arrives
        // async via setZapState, which re-submits the category highlight + re-asserts
        // focus because of this flag.
        pendingCategoryRefocus = true
        onCategorySelected(catId)
    }

    private val onAirAdapter = LiveOnAirAdapter()

    private val hideOsdRunnable = Runnable { hideOsd() }
    private val hideZapRunnable = Runnable { hideZapOsd() }
    private val hideToastRunnable = Runnable { dismissToast() }
    private val minuteTicker = object : Runnable {
        override fun run() {
            val now = clockFormat.format(Date())
            clock.text = now
            onAirClock.text = now
            guideClock.text = now
            updateProgressRow()
            refreshOnAir()
            handler.postDelayed(this, 30_000L)
        }
    }

    init {
        surfList.layoutManager = LinearLayoutManager(root.context)
        surfList.adapter = surfAdapter
        catsList.layoutManager = LinearLayoutManager(root.context)
        catsList.adapter = categoryAdapter
        onAirList.layoutManager = LinearLayoutManager(root.context)
        onAirList.adapter = onAirAdapter
        onAirList.isFocusable = false

        guideList.layoutManager = LinearLayoutManager(root.context)
        guideList.adapter = guideAdapter
        guideList.isFocusable = false

        btnChannels.setOnClickListener { openDrawer() }
        btnGuide.setOnClickListener { openGuide() }
        btnCc.setOnClickListener { cycleCc() }
        btnAudio.setOnClickListener { cycleAudio() }
        btnAspect.setOnClickListener { cycleAspect() }
        btnFav.setOnClickListener { onToggleFavorite() }
        drawerScrim.setOnClickListener { closeDrawer() }

        ccLabel.text = root.context.getString(R.string.live_osd_off)
        audioLabel.text = root.context.getString(R.string.live_osd_original)
        aspectLabel.text = ASPECT_LABELS[0]
        setControlsFocusable(false)

        // LIVE badge pulse (design: livePulse 1.4s)
        bugLiveDot.startAnimation(pulseAnimation(1400L))
    }

    // ── lifecycle ──────────────────────────────────────────────────────────
    fun show() {
        root.isVisible = true
        handler.removeCallbacks(minuteTicker)
        handler.post(minuteTicker)
    }

    fun release() {
        handler.removeCallbacksAndMessages(null)
        bugLiveDot.clearAnimation()
        toastDot.clearAnimation()
    }

    // ── channel bug / zap ──────────────────────────────────────────────────
    fun bindChannel(name: String?, logoUrl: String?) {
        channelName = name
        bugName.text = name.orEmpty()
        bugInitials.text = channelInitials(name)
        bugLogo.background = channelTileGradient(
            name,
            8f * root.resources.displayMetrics.density,
            strokeColor = 0x1FFFFFFF
        )
        if (!logoUrl.isNullOrBlank()) {
            bugLogoImg.isVisible = true
            GlideUtils.loadChannelLogo(bugLogoImg, logoUrl)
        } else {
            bugLogoImg.isVisible = false
        }

        val quality = detectQuality(name)
        if (quality == null) {
            bugQuality.isVisible = false
        } else {
            bugQuality.isVisible = true
            bugQuality.text = quality
            bugQuality.setBackgroundResource(
                if (quality.contains("4K")) R.drawable.bg_live_badge_quality_4k
                else R.drawable.bg_live_badge_quality_hd
            )
        }
        connText.text = if (quality?.contains("4K") == true) "$connectionLabel · 4K" else connectionLabel

        // Chrome visibility is owned by the OSD (showOsd/showZapOsd). Only play the
        // slide-in entrance when the bug is already on screen; otherwise refresh
        // content silently so a background rebind can't force the chrome visible.
        if (channelBug.isVisible) {
            channelBug.translationX = -12f * root.resources.displayMetrics.density
            channelBug.alpha = 1f
            channelBug.animate().translationX(0f).alpha(1f).setDuration(400).setInterpolator(easeOutExpo).start()
        } else {
            channelBug.translationX = 0f
        }
    }

    fun setChannelNumber(number: Int?) {
        val text = number?.let { String.format("%03d", it) } ?: "---"
        bugNumber.text = text
        zapNumber.text = text
    }

    /** Big channel-number overlay, shown for 2.6s after a D-pad zap (spec §5). */
    fun showZapOsd() {
        zapName.text = channelName.orEmpty()
        zapNow.text = currentNow?.title.orEmpty()
        zapNow.isVisible = !currentNow?.title.isNullOrBlank()
        zapOsd.isVisible = true
        zapOsd.translationX = -12f * root.resources.displayMetrics.density
        zapOsd.alpha = 0f
        zapOsd.animate().translationX(0f).alpha(1f).setDuration(300).setInterpolator(easeOutExpo).start()
        // Flash the channel bug + status alongside the zap card so the user sees
        // the new channel/quality even when the full OSD is hidden.
        setChromeVisible(true)
        handler.removeCallbacks(hideZapRunnable)
        handler.postDelayed(hideZapRunnable, ZAP_OSD_TIMEOUT_MS)
    }

    private fun hideZapOsd() {
        zapOsd.animate().alpha(0f).setDuration(180).withEndAction { zapOsd.isVisible = false }.start()
        // Only retire the chrome if the full OSD isn't holding it open.
        if (!isOsdVisible) setChromeVisible(false)
    }

    // ── zap backdrop (gradient + watermark while switching) ────────────────
    fun showZapBackdrop() {
        zapBackdropInitials.text = channelInitials(channelName)
        zapBackdropGradient.background = watermarkGradient(channelName)
        zapBackdrop.alpha = 1f
        zapBackdrop.isVisible = true
    }

    fun hideZapBackdrop() {
        if (!zapBackdrop.isVisible) return
        zapBackdrop.animate().alpha(0f).setDuration(300).withEndAction { zapBackdrop.isVisible = false }.start()
    }

    // ── EPG binding ────────────────────────────────────────────────────────
    fun bindEpg(now: EpgEntity?, next: EpgEntity?) {
        currentNow = now

        val title = now?.title
        nowTitle.text = title ?: root.context.getString(R.string.epg_no_info)
        nowTitle.alpha = 0f
        nowTitle.animate().alpha(1f).setDuration(400).start()

        // v2 program-description line under the NOW title
        val desc = now?.description?.trim()
        if (desc.isNullOrBlank()) {
            nowDesc.isVisible = false
        } else {
            nowDesc.isVisible = true
            nowDesc.text = desc
        }

        if (zapOsd.isVisible) {
            zapNow.text = title.orEmpty()
            zapNow.isVisible = !title.isNullOrBlank()
        }
        updateProgressRow()
    }

    private fun updateProgressRow() {
        val program = currentNow
        if (program == null || program.stop <= program.start) {
            timeStart.text = "--:--"
            timeEnd.text = "--:--"
            minLeft.text = ""
            progress.progress = 0
            return
        }
        timeStart.text = timeFormat.format(Date(program.start))
        timeEnd.text = timeFormat.format(Date(program.stop))
        val duration = (program.stop - program.start).coerceAtLeast(1L)
        val elapsed = (System.currentTimeMillis() - program.start).coerceIn(0L, duration)
        progress.progress = (elapsed * 100 / duration).toInt()
        val left = ((duration - elapsed) / 60_000L).coerceAtLeast(1L)
        minLeft.text = root.context.getString(R.string.live_osd_min_left, left)
    }

    // ── per-channel EPG (v2 rich rows + ON AIR NOW + preview strip) ─────────
    /** Current-program snapshot for every channel, keyed by streamId. */
    fun setSurfEpg(map: Map<String, EpgEntity>) {
        surfEpg = map
        refreshOnAir()
        if (panel != Panel.NONE) {
            // CC-1: this fires on the 30s minute tick while the drawer is open; keep the
            // user's focused row instead of letting notifyDataSetChanged drop/flicker it.
            surfList.updatePreservingFocus {
                surfAdapter.submit(zapChannels, zapIndex, favoriteIds, rowEpgMap(), categoryColor())
            }
        }
        if (previewIndex in zapChannels.indices) bindPreviewStrip(zapChannels[previewIndex])
    }

    private fun toRowEpg(e: EpgEntity?): SurfRowEpg? {
        if (e == null || e.stop <= e.start) return e?.let { SurfRowEpg(it.title, 0, null) }
        val now = System.currentTimeMillis()
        val dur = (e.stop - e.start).coerceAtLeast(1L)
        val elapsed = (now - e.start).coerceIn(0L, dur)
        val pct = (elapsed * 100 / dur).toInt()
        val leftMin = ((dur - elapsed) / 60_000L).coerceAtLeast(1L)
        return SurfRowEpg(e.title, pct, "${leftMin}m left")
    }

    private fun rowEpgMap(): Map<String, SurfRowEpg> =
        surfEpg.mapNotNull { (id, e) -> toRowEpg(e)?.let { id to it } }.toMap()

    private fun categoryColor(): Int = accentColor(currentCategoryName)

    private fun categoryCounts(): Map<String, Int> {
        val current = currentCategoryId?.let { mapOf(it to zapChannels.size) } ?: emptyMap()
        // Favorites count is already tracked client-side (favoriteIds, kept live by
        // PlayerViewModel.observeLiveFavorites) — free to show without a query.
        return current + (FAVORITES_CATEGORY_ID to favoriteIds.size)
    }

    /** ON AIR NOW mini-EPG: prev / current / next around the tuned channel. */
    private fun refreshOnAir() {
        val n = zapChannels.size
        if (n == 0 || zapIndex < 0) {
            onAirAdapter.submit(emptyList())
            return
        }
        val offsets = when {
            n >= 3 -> listOf(-1, 0, 1)
            n == 2 -> listOf(0, 1)
            else -> listOf(0)
        }
        val rows = offsets.map { off ->
            val idx = ((zapIndex + off) % n + n) % n
            val ch = zapChannels[idx]
            val e = surfEpg[ch.streamId]
            OnAirRow(
                name = ch.name,
                num = String.format("%03d", idx + 1),
                nowTitle = e?.title,
                logoUrl = ch.logoUrl,
                isActive = off == 0,
                progress = toRowEpg(e)?.progress ?: 0
            )
        }
        onAirAdapter.submit(rows)
    }

    private fun updatePreview(index: Int) {
        if (index !in zapChannels.indices) return
        previewIndex = index
        bindPreviewStrip(zapChannels[index])
    }

    private fun bindPreviewStrip(ch: ZapChannel) {
        val density = root.resources.displayMetrics.density
        previewName.text = ch.name
        previewLogoText.text = channelInitials(ch.name)
        previewLogo.background = channelTileGradient(ch.name, 6f * density)
        previewBg.background = watermarkGradient(ch.name)
        if (!ch.logoUrl.isNullOrBlank()) {
            previewLogoImg.isVisible = true
            GlideUtils.loadChannelLogo(previewLogoImg, ch.logoUrl)
        } else {
            previewLogoImg.isVisible = false
        }
        val e = surfEpg[ch.streamId]
        previewNow.text = e?.title
        previewNow.isVisible = !e?.title.isNullOrBlank()
        val rp = toRowEpg(e)
        if (rp != null && rp.progress > 0) {
            previewProgress.isVisible = true
            previewProgress.progress = rp.progress
        } else {
            previewProgress.isVisible = false
        }
        val q = detectQuality(ch.name)
        if (q == null) {
            previewQuality.isVisible = false
        } else {
            previewQuality.isVisible = true
            previewQuality.text = q
            previewQuality.setBackgroundResource(
                if (q.contains("4K")) R.drawable.bg_live_badge_quality_4k
                else R.drawable.bg_live_badge_quality_hd
            )
        }
    }

    // ── chrome (channel bug + top-right status) show / hide ────────────────
    /**
     * The channel bug (top-left) and status row (top-right) are tied to the OSD:
     * they appear with the controls and disappear when everything auto-hides, so
     * the video plays clean when idle (user request).
     */
    private fun setChromeVisible(visible: Boolean, animate: Boolean = true) {
        if (visible) {
            channelBug.isVisible = true
            statusRow.isVisible = true
            channelBug.animate().cancel()
            statusRow.animate().cancel()
            if (animate) {
                channelBug.alpha = 0f
                statusRow.alpha = 0f
                channelBug.animate().alpha(1f).setDuration(300).start()
                statusRow.animate().alpha(1f).setDuration(300).start()
            } else {
                channelBug.alpha = 1f
                statusRow.alpha = 1f
            }
        } else {
            channelBug.animate().alpha(0f).setDuration(250)
                .withEndAction { channelBug.isVisible = false }.start()
            statusRow.animate().alpha(0f).setDuration(250)
                .withEndAction { statusRow.isVisible = false }.start()
        }
    }

    // ── bottom OSD show / hide ─────────────────────────────────────────────
    fun showOsd(focus: Boolean = false, autoHide: Boolean = true) {
        handler.removeCallbacks(hideOsdRunnable)
        if (!isOsdVisible) {
            bottomOsd.isVisible = true
            scrimTop.isVisible = true
            scrimBottom.isVisible = true
            bottomOsd.translationY = 15f * root.resources.displayMetrics.density
            bottomOsd.alpha = 0f
            bottomOsd.animate().translationY(0f).alpha(1f).setDuration(300).setInterpolator(easeOutExpo).start()
            scrimTop.animate().alpha(1f).setDuration(300).start()
            scrimBottom.animate().alpha(1f).setDuration(300).start()
        }
        setChromeVisible(true)
        setControlsFocusable(true)
        if (focus) btnChannels.requestFocus()
        if (autoHide) handler.postDelayed(hideOsdRunnable, OSD_TIMEOUT_MS)
    }

    fun hideOsd() {
        handler.removeCallbacks(hideOsdRunnable)
        // Chrome hides with the OSD even if the bottom OSD was already gone
        // (e.g. after a zap flash left the bug on screen).
        setChromeVisible(false)
        if (!bottomOsd.isVisible) {
            playerView.requestFocus()
            return
        }
        setControlsFocusable(false)
        bottomOsd.animate().alpha(0f).setDuration(250).withEndAction { bottomOsd.isVisible = false }.start()
        scrimTop.animate().alpha(0f).setDuration(250).withEndAction { scrimTop.isVisible = false }.start()
        scrimBottom.animate().alpha(0f).setDuration(250).withEndAction { scrimBottom.isVisible = false }.start()
        playerView.requestFocus()
    }

    fun resetOsdTimer() {
        if (isOsdVisible) {
            handler.removeCallbacks(hideOsdRunnable)
            handler.postDelayed(hideOsdRunnable, OSD_TIMEOUT_MS)
        }
    }

    private fun setControlsFocusable(focusable: Boolean) {
        controls.forEach { it.isFocusable = focusable }
    }

    private fun isControlFocused(): Boolean = controls.any { it.isFocused }

    // ── two-panel surf drawer (channels + category picker, v2) ─────────────
    fun setZapState(categoryId: String, categoryName: String?, channels: List<ZapChannel>, index: Int) {
        currentCategoryId = categoryId
        currentCategoryName = categoryName
        zapChannels = channels
        zapIndex = index
        surfTitle.text = categoryName ?: root.context.getString(R.string.live_osd_channels_title)
        surfCount.text = root.context.getString(R.string.live_osd_channel_count, channels.size)
        refreshOnAir()
        if (panel != Panel.NONE) {
            surfAdapter.submit(channels, index, favoriteIds, rowEpgMap(), categoryColor())
            categoryAdapter.submit(categories, currentCategoryId, categoryCounts())
            // A category switch refreshes the list async; re-assert focus + preview.
            if (pendingCategoryRefocus) {
                pendingCategoryRefocus = false
                updatePreview(index.coerceAtLeast(0))
                focusSurfRow(index.coerceAtLeast(0))
            }
        }
    }

    private fun focusSurfRow(position: Int) {
        surfList.scrollToPosition(position)
        surfList.post {
            surfList.findViewHolderForAdapterPosition(position)?.itemView?.requestFocus()
                ?: surfList.requestFocus()
        }
    }

    /** Category picker data for the second-LEFT panel. */
    fun setCategories(cats: List<XtreamCategory>) {
        categories = cats
        if (panel == Panel.BOTH) categoryAdapter.submit(cats, currentCategoryId, categoryCounts())
    }

    fun setFavorites(ids: Set<String>, currentStreamId: String?) {
        favoriteIds = ids
        isFavorite = currentStreamId != null && ids.contains(currentStreamId)
        favIcon.setImageResource(if (isFavorite) R.drawable.ic_live_star_filled else R.drawable.ic_live_star)
        if (panel != Panel.NONE) {
            // CC-1: favorites can update while the drawer is open — preserve the focused row.
            surfList.updatePreservingFocus {
                surfAdapter.submit(zapChannels, zapIndex, favoriteIds, rowEpgMap(), categoryColor())
            }
        }
    }

    fun openDrawer() {
        if (zapChannels.isEmpty()) {
            showToast(root.context.getString(R.string.player_zap_loading))
            return
        }
        handler.removeCallbacks(hideOsdRunnable)
        panel = Panel.CHANNELS
        surfTitle.text = currentCategoryName ?: root.context.getString(R.string.live_osd_channels_title)
        surfCount.text = root.context.getString(R.string.live_osd_channel_count, zapChannels.size)
        surfAdapter.submit(zapChannels, zapIndex, favoriteIds, rowEpgMap(), categoryColor())
        updatePreview(zapIndex.coerceAtLeast(0))
        drawerScrim.alpha = 0f
        drawerScrim.isVisible = true
        drawerScrim.animate().alpha(1f).setDuration(250).start()
        drawer.isVisible = true
        drawer.translationX = -drawer.layoutParams.width.toFloat()
        drawer.animate().translationX(0f).setDuration(300).setInterpolator(easeOutExpo).start()
        catsDrawer.isVisible = false
        // OSD dims to ~12% while the drawer is open (spec).
        bottomOsd.animate().alpha(0.12f).setDuration(250).start()
        focusSurfRow(zapIndex.coerceAtLeast(0))
    }

    /** Second LEFT — slide the category picker in far-left; channel list shifts right. */
    private fun openCategories() {
        if (categories.isEmpty()) {
            showToast(root.context.getString(R.string.live_osd_categories_loading))
            return
        }
        panel = Panel.BOTH
        categoryAdapter.submit(categories, currentCategoryId, categoryCounts())
        val catW = catsDrawer.layoutParams.width.toFloat()
        catsDrawer.isVisible = true
        catsDrawer.translationX = -catW
        catsDrawer.animate().translationX(0f).setDuration(320).setInterpolator(easeOutExpo).start()
        drawer.animate().translationX(catW).setDuration(320).setInterpolator(easeOutExpo).start()
        val selected = categories.indexOfFirst { it.category_id == currentCategoryId }.coerceAtLeast(0)
        catsList.scrollToPosition(selected)
        catsList.post {
            catsList.findViewHolderForAdapterPosition(selected)?.itemView?.requestFocus()
                ?: catsList.requestFocus()
        }
    }

    /** RIGHT from the category picker — collapse it; channel list slides back. */
    private fun closeCategories() {
        if (panel != Panel.BOTH) return
        panel = Panel.CHANNELS
        val catW = catsDrawer.layoutParams.width.toFloat()
        catsDrawer.animate().translationX(-catW).setDuration(260).setInterpolator(easeOutExpo)
            .withEndAction { catsDrawer.isVisible = false }.start()
        drawer.animate().translationX(0f).setDuration(260).setInterpolator(easeOutExpo).start()
        focusSurfRow(previewIndex.coerceAtLeast(0))
    }

    /** BACK inside the drawer: categories → channels → close. Returns true if consumed. */
    fun handleDrawerBack(): Boolean {
        if (panel == Panel.NONE) return false
        if (panel == Panel.BOTH) {
            closeCategories()
            return true
        }
        closeDrawer()
        return true
    }

    fun closeDrawer(refocusControls: Boolean = true) {
        if (panel == Panel.NONE) return
        panel = Panel.NONE
        pendingCategoryRefocus = false
        drawerScrim.animate().alpha(0f).setDuration(200).withEndAction { drawerScrim.isVisible = false }.start()
        val catW = catsDrawer.layoutParams.width.toFloat()
        if (catsDrawer.isVisible) {
            catsDrawer.animate().translationX(-catW).setDuration(250)
                .withEndAction { catsDrawer.isVisible = false }.start()
        }
        drawer.animate().translationX(-drawer.layoutParams.width.toFloat()).setDuration(250)
            .withEndAction { drawer.isVisible = false; drawer.translationX = 0f }.start()
        if (bottomOsd.isVisible) {
            bottomOsd.animate().alpha(1f).setDuration(250).start()
            handler.postDelayed(hideOsdRunnable, OSD_TIMEOUT_MS)
            if (refocusControls) btnChannels.requestFocus()
        } else {
            playerView.requestFocus()
        }
    }

    // ── in-player TV Guide grid (v2) ───────────────────────────────────────
    /** Open the full-screen EPG grid over the video (from the TV Guide button). */
    fun openGuide() {
        if (zapChannels.isEmpty()) {
            showToast(root.context.getString(R.string.player_zap_loading))
            return
        }
        guideOpen = true
        // The guide is full-screen: retire the bottom OSD, chrome and any drawer.
        handler.removeCallbacks(hideOsdRunnable)
        if (panel != Panel.NONE) closeDrawer(refocusControls = false)
        setChromeVisible(false)
        setControlsFocusable(false)
        if (bottomOsd.isVisible) {
            bottomOsd.animate().alpha(0f).setDuration(180).withEndAction { bottomOsd.isVisible = false }.start()
            scrimTop.animate().alpha(0f).setDuration(180).withEndAction { scrimTop.isVisible = false }.start()
            scrimBottom.animate().alpha(0f).setDuration(180).withEndAction { scrimBottom.isVisible = false }.start()
        }

        guideWindowStartMs = System.currentTimeMillis() - GUIDE_BEFORE_MS
        guideRow = zapIndex.coerceIn(0, (zapChannels.size - 1).coerceAtLeast(0))
        guideShowIdx = 0
        guideClock.text = clockFormat.format(Date())
        // Ask the host to load windowed EPG for every channel in the zap list.
        onRequestGuideData(guideWindowStartMs, guideWindowStartMs + GUIDE_WINDOW_MS)

        guideOverlay.alpha = 0f
        guideOverlay.isVisible = true
        guideOverlay.animate().alpha(1f).setDuration(200).start()
        guideList.post {
            guideTimelineWidthPx = (guideList.width - dp(GUIDE_CH_COL_DP)).coerceAtLeast(0)
            if (guideTimelineWidthPx == 0) {
                guideTimelineWidthPx =
                    (root.resources.displayMetrics.widthPixels - dp(GUIDE_CH_COL_DP)).coerceAtLeast(0)
            }
            rebuildGuide(initialFocus = true)
        }
    }

    /** Windowed EPG for the guide, keyed by streamId, arriving async from the host. */
    fun setGuideEpg(map: Map<String, List<EpgEntity>>) {
        guideEpg = map
        if (guideOpen) rebuildGuide(initialFocus = false)
    }

    private fun rebuildGuide(initialFocus: Boolean) {
        if (!guideOpen) return
        if (guideTimelineWidthPx <= 0) {
            guideTimelineWidthPx = (guideList.width - dp(GUIDE_CH_COL_DP)).coerceAtLeast(0)
        }
        val rows = buildGuideRows()
        guideRowsCache = rows
        val showCount = rows.getOrNull(guideRow)?.shows?.size ?: 0
        guideShowIdx = if (initialFocus) {
            rows.getOrNull(guideRow)?.shows?.indexOfFirst { it.isCurrent }?.takeIf { it >= 0 } ?: 0
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
        return zapChannels.mapIndexed { idx, ch ->
            val progs = guideEpg[ch.streamId].orEmpty()
                .filter { it.stop > startMs && it.start < endMs }
                .deOverlap()
            val shows = progs.map { p ->
                val clipStart = p.start.coerceAtLeast(startMs)
                val clipEnd = p.stop.coerceAtMost(endMs)
                GuideShow(
                    title = p.title ?: root.context.getString(R.string.epg_no_info),
                    timeLabel = timeFormat.format(Date(p.start)) + " · " +
                        ((p.stop - p.start) / 60_000L).coerceAtLeast(1) + "m",
                    leftFrac = ((clipStart - startMs).toFloat() / span).coerceIn(0f, 1f),
                    widthFrac = ((clipEnd - clipStart).toFloat() / span).coerceIn(0f, 1f),
                    isCurrent = p.start <= nowMs && p.stop > nowMs
                )
            }
            GuideRow(
                name = ch.name,
                num = String.format("%03d", idx + 1),
                isActiveChannel = idx == zapIndex,
                nowLineFrac = nowFrac,
                shows = shows
            )
        }
    }

    private fun renderGuideTimebar() {
        guideTimebar.removeAllViews()
        val width = guideTimelineWidthPx
        if (width <= 0) return
        val startMs = guideWindowStartMs
        val span = GUIDE_WINDOW_MS.toFloat()
        val slotMs = 30 * 60_000L
        var t = ((startMs + slotMs - 1) / slotMs) * slotMs
        while (t <= startMs + GUIDE_WINDOW_MS) {
            val frac = (t - startMs).toFloat() / span
            val label = TextView(root.context).apply {
                text = timeFormat.format(Date(t))
                textSize = 5f
                setTextColor(0xFF334155.toInt())
                typeface = android.graphics.Typeface.MONOSPACE
            }
            val lp = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT,
                FrameLayout.LayoutParams.WRAP_CONTENT,
                Gravity.CENTER_VERTICAL
            )
            lp.leftMargin = (frac * width).toInt() + dp(2f)
            guideTimebar.addView(label, lp)
            t += slotMs
        }
        val nowMs = System.currentTimeMillis()
        val nowX = (((nowMs - startMs).toFloat() / span).coerceIn(0f, 1f) * width).toInt()
        val line = View(root.context).apply { setBackgroundColor(0xFF00F0FF.toInt()) }
        val lineLp = FrameLayout.LayoutParams(dp(2f), FrameLayout.LayoutParams.MATCH_PARENT)
        lineLp.leftMargin = nowX
        guideTimebar.addView(line, lineLp)
        val pill = TextView(root.context).apply {
            text = "NOW"
            textSize = 4.5f
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

    private fun guideMove(dRow: Int, dShow: Int) {
        if (guideRowsCache.isEmpty()) return
        if (dRow != 0) {
            guideRow = (guideRow + dRow).coerceIn(0, guideRowsCache.size - 1)
            val cnt = guideRowsCache[guideRow].shows.size
            guideShowIdx = guideShowIdx.coerceIn(0, (cnt - 1).coerceAtLeast(0))
            guideList.smoothScrollToPosition(guideRow)
        }
        if (dShow != 0) {
            val cnt = guideRowsCache[guideRow].shows.size
            if (cnt > 0) guideShowIdx = (guideShowIdx + dShow).coerceIn(0, cnt - 1)
        }
        guideAdapter.submit(guideRowsCache, guideTimelineWidthPx, guideRow, guideShowIdx)
    }

    private fun tuneFromGuide() {
        val idx = guideRow
        closeGuide(returnToPlayer = false)
        onTuneChannel(idx)
    }

    /** BACK inside the guide closes it. Returns true if consumed. */
    fun handleGuideBack(): Boolean {
        if (!guideOpen) return false
        closeGuide(returnToPlayer = true)
        return true
    }

    private fun closeGuide(returnToPlayer: Boolean) {
        if (!guideOpen) return
        guideOpen = false
        guideOverlay.animate().alpha(0f).setDuration(180).withEndAction {
            guideOverlay.isVisible = false
            guideTimebar.removeAllViews()
        }.start()
        if (returnToPlayer) playerView.requestFocus()
    }

    private fun dp(v: Float): Int = (v * root.resources.displayMetrics.density).toInt()

    // ── toasts ─────────────────────────────────────────────────────────────
    fun showToast(message: String) {
        handler.removeCallbacks(hideToastRunnable)
        toastText.text = message
        toast.alpha = 0f
        toast.isVisible = true
        toast.animate().alpha(1f).setDuration(220).start()
        toastDot.clearAnimation()
        toastDot.startAnimation(pulseAnimation(1000L))
        handler.postDelayed(hideToastRunnable, TOAST_TIMEOUT_MS)
    }

    private fun dismissToast() {
        toast.animate().alpha(0f).setDuration(180).withEndAction {
            toast.isVisible = false
            toastDot.clearAnimation()
        }.start()
    }

    // ── control actions ────────────────────────────────────────────────────
    private fun cycleCc() {
        val player = playerProvider() ?: return
        val groups = player.currentTracks.groups.filter {
            it.type == C.TRACK_TYPE_TEXT && it.isSupported
        }
        val params = player.trackSelectionParameters
        val currentOverride = params.overrides.values.firstOrNull { it.type == C.TRACK_TYPE_TEXT }
        val currentIdx = if (params.disabledTrackTypes.contains(C.TRACK_TYPE_TEXT) || groups.isEmpty()) {
            -1
        } else {
            groups.indexOfFirst { it.mediaTrackGroup == currentOverride?.mediaTrackGroup }
        }
        val nextIdx = if (groups.isEmpty()) -1 else {
            val n = currentIdx + 1
            if (n >= groups.size) -1 else n
        }
        val builder = params.buildUpon().clearOverridesOfType(C.TRACK_TYPE_TEXT)
        val label: String
        if (nextIdx < 0) {
            builder.setTrackTypeDisabled(C.TRACK_TYPE_TEXT, true)
            label = root.context.getString(R.string.live_osd_off)
        } else {
            builder.setTrackTypeDisabled(C.TRACK_TYPE_TEXT, false)
            builder.addOverride(TrackSelectionOverride(groups[nextIdx].mediaTrackGroup, 0))
            label = trackLabel(groups[nextIdx].getTrackFormat(0).language, groups[nextIdx].getTrackFormat(0).label, nextIdx)
        }
        player.trackSelectionParameters = builder.build()
        ccLabel.text = label
        showToast("SUBTITLES · " + label.uppercase(Locale.getDefault()))
        resetOsdTimer()
    }

    private fun cycleAudio() {
        val player = playerProvider() ?: return
        val groups = player.currentTracks.groups.filter {
            it.type == C.TRACK_TYPE_AUDIO && it.isSupported
        }
        if (groups.isEmpty()) {
            showToast("AUDIO · " + root.context.getString(R.string.live_osd_original).uppercase(Locale.getDefault()))
            return
        }
        val params = player.trackSelectionParameters
        val currentOverride = params.overrides.values.firstOrNull { it.type == C.TRACK_TYPE_AUDIO }
        val currentIdx = groups.indexOfFirst { it.mediaTrackGroup == currentOverride?.mediaTrackGroup }
            .let { if (it < 0) groups.indexOfFirst { g -> g.isSelected } else it }
            .coerceAtLeast(0)
        val nextIdx = (currentIdx + 1) % groups.size
        player.trackSelectionParameters = params.buildUpon()
            .clearOverridesOfType(C.TRACK_TYPE_AUDIO)
            .addOverride(TrackSelectionOverride(groups[nextIdx].mediaTrackGroup, 0))
            .build()
        val format = groups[nextIdx].getTrackFormat(0)
        val label = when {
            format.channelCount >= 6 -> "Dolby 5.1"
            else -> trackLabel(format.language, format.label, nextIdx)
        }
        audioLabel.text = label
        showToast("AUDIO · " + label.uppercase(Locale.getDefault()))
        resetOsdTimer()
    }

    private fun cycleAspect() {
        aspectIndex = (aspectIndex + 1) % ASPECT_LABELS.size
        playerView.resizeMode = ASPECT_MODES[aspectIndex]
        aspectLabel.text = ASPECT_LABELS[aspectIndex]
        showToast("ASPECT · " + ASPECT_LABELS[aspectIndex].uppercase(Locale.getDefault()))
        resetOsdTimer()
    }

    private fun trackLabel(language: String?, label: String?, index: Int): String {
        return label
            ?: language?.let { Locale(it).displayLanguage.replaceFirstChar { c -> c.uppercase() } }
            ?: "Track ${index + 1}"
    }

    // ── key routing (spec §8) ──────────────────────────────────────────────
    /** Returns true when the event was consumed. Zap keys stay with PlayerActivity. */
    fun handleKeyEvent(event: KeyEvent): Boolean {
        if (event.action != KeyEvent.ACTION_DOWN) {
            // Swallow key-ups for keys we consume on the way down.
            return false
        }
        if (guideOpen) {
            // The guide owns every D-pad key while open (BACK is routed separately).
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
        if (isDrawerOpen) {
            // BACK is handled by PlayerActivity's back routing.
            return when (event.keyCode) {
                KeyEvent.KEYCODE_DPAD_LEFT -> {
                    // Channels → open category picker (both); both → close everything.
                    when (panel) {
                        Panel.CHANNELS -> openCategories()
                        Panel.BOTH -> closeDrawer()
                        Panel.NONE -> {}
                    }
                    true
                }
                KeyEvent.KEYCODE_DPAD_RIGHT -> {
                    // From the category picker, RIGHT collapses back to the channel list.
                    if (panel == Panel.BOTH) {
                        closeCategories(); true
                    } else false
                }
                else -> false // list focus handles up/down/OK natively
            }
        }
        when (event.keyCode) {
            KeyEvent.KEYCODE_DPAD_LEFT -> {
                openDrawer(); return true
            }
            KeyEvent.KEYCODE_DPAD_RIGHT -> {
                if (!isOsdVisible || !isControlFocused()) {
                    showOsd(focus = true); return true
                }
                resetOsdTimer()
                return false // let focus travel across the controls row
            }
            KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER -> {
                if (!isOsdVisible || !isControlFocused()) {
                    showOsd(focus = true); return true
                }
                resetOsdTimer()
                return false // activates the focused control
            }
        }
        return false
    }

    fun hideForPip() {
        root.isVisible = false
    }

    private fun pulseAnimation(durationMs: Long): Animation {
        return AlphaAnimation(1f, 0.35f).apply {
            duration = durationMs / 2
            repeatCount = Animation.INFINITE
            repeatMode = Animation.REVERSE
        }
    }

    companion object {
        private const val OSD_TIMEOUT_MS = 6000L
        private const val ZAP_OSD_TIMEOUT_MS = 2600L
        private const val TOAST_TIMEOUT_MS = 1800L

        // in-player TV Guide grid window (v2): 1h back, 3h span.
        private const val GUIDE_BEFORE_MS = 60 * 60_000L
        private const val GUIDE_WINDOW_MS = 180 * 60_000L
        private const val GUIDE_CH_COL_DP = 120f

        private val ASPECT_LABELS = listOf("Fit", "Fill", "16:9", "Zoom")
        private val ASPECT_MODES = listOf(
            AspectRatioFrameLayout.RESIZE_MODE_FIT,
            AspectRatioFrameLayout.RESIZE_MODE_FILL,
            AspectRatioFrameLayout.RESIZE_MODE_FIXED_WIDTH,
            AspectRatioFrameLayout.RESIZE_MODE_ZOOM
        )

        // Genre-bg gradients from the design (LOGO_BG); picked by name hash since
        // Xtream streams carry no genre.
        private val TILE_GRADIENTS = listOf(
            intArrayOf(0xFF1A5FB4.toInt(), 0xFF3584E4.toInt()), // news
            intArrayOf(0xFF00CC6A.toInt(), 0xFF00FF88.toInt()), // sports
            intArrayOf(0xFFB8860B.toInt(), 0xFFD4AF37.toInt()), // movies
            intArrayOf(0xFF7C3AED.toInt(), 0xFFA78BFA.toInt()), // ent
            intArrayOf(0xFFE11D74.toInt(), 0xFFFF6BA8.toInt()), // kids
            intArrayOf(0xFFE68A00.toInt(), 0xFFFFAA00.toInt())  // music
        )

        /** Stable accent color for a category's color-bar (v2 rich surf rows). */
        fun accentColor(name: String?): Int =
            TILE_GRADIENTS[Math.abs(name?.hashCode() ?: 0) % TILE_GRADIENTS.size][1]

        fun channelInitials(name: String?): String {
            if (name.isNullOrBlank()) return "TV"
            val cleaned = name.substringAfter(':').trim()
            val word = cleaned.split(' ', '-', '_').firstOrNull { it.any(Char::isLetterOrDigit) } ?: return "TV"
            return word.filter(Char::isLetterOrDigit).take(4).uppercase(Locale.getDefault())
        }

        fun channelTileGradient(name: String?, cornerRadiusPx: Float, strokeColor: Int = 0x14FFFFFF): GradientDrawable {
            val colors = TILE_GRADIENTS[Math.abs(name?.hashCode() ?: 0) % TILE_GRADIENTS.size]
            return GradientDrawable(GradientDrawable.Orientation.TL_BR, colors).apply {
                cornerRadius = cornerRadiusPx
                setStroke(1, strokeColor)
            }
        }

        /** Dark radial-ish backdrop used behind the watermark while zapping. */
        fun watermarkGradient(name: String?): GradientDrawable {
            val accent = TILE_GRADIENTS[Math.abs(name?.hashCode() ?: 0) % TILE_GRADIENTS.size][0]
            val dimmed = (0xFF shl 24) or (
                (((accent shr 16 and 0xFF) * 0.28f).toInt() shl 16) or
                (((accent shr 8 and 0xFF) * 0.28f).toInt() shl 8) or
                ((accent and 0xFF) * 0.28f).toInt()
            )
            return GradientDrawable(
                GradientDrawable.Orientation.TL_BR,
                intArrayOf(dimmed, 0xFF06090E.toInt())
            )
        }

        fun detectQuality(name: String?): String? {
            val upper = name?.uppercase(Locale.getDefault()) ?: return null
            return when {
                upper.contains("4K") || upper.contains("UHD") -> "4K UHD"
                upper.contains("FHD") || upper.contains("1080") -> "FHD"
                upper.contains("HD") -> "HD"
                else -> null
            }
        }
    }
}
