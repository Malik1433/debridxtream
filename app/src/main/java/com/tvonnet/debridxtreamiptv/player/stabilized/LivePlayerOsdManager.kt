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
    private val callbacks: Callbacks,
) {
    /**
     * Everything the OSD hands back to `PlayerLiveTuner`. Grouped rather than passed as four
     * separate lambdas so the constructor stays inside the parameter-count guardrail.
     */
    interface Callbacks {
        fun onRequestGuideData(windowStartMs: Long, windowEndMs: Long)
        fun onTuneChannel(index: Int)
        fun onToggleFavorite()
        fun onCategorySelected(categoryId: String)
    }

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
    // Bottom-OSD "controller" clusters we hide for the zap-only ON AIR NOW flash.
    private val nowInfoCluster: View = root.findViewById(R.id.live_now_info)
    private val controlsRow: View = root.findViewById(R.id.live_controls_row)
    private val nowTitle: TextView = root.findViewById(R.id.live_now_title)
    private val nowDesc: TextView = root.findViewById(R.id.live_now_desc)
    private val timeStart: TextView = root.findViewById(R.id.live_time_start)
    private val timeEnd: TextView = root.findViewById(R.id.live_time_end)
    private val minLeft: TextView = root.findViewById(R.id.live_min_left)
    private val progress: ProgressBar = root.findViewById(R.id.live_progress)
    // ON AIR NOW mini-EPG (bottom OSD, v2)
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
    // preview strip (top of channel drawer, v2)
    // categories side panel (v2)
    private val toast: View = root.findViewById(R.id.live_toast)
    private val toastDot: View = root.findViewById(R.id.live_toast_dot)
    private val toastText: TextView = root.findViewById(R.id.live_toast_text)
    // in-player TV Guide grid (v2)

    private val controls = listOf(btnChannels, btnGuide, btnCc, btnAudio, btnAspect, btnFav)

    /** Subtitles / audio / aspect cycling (C8). */
    private val trackControls = LiveTrackControls(
        playerView = playerView,
        playerProvider = playerProvider,
        labels = LiveTrackControls.Labels(ccLabel, audioLabel, aspectLabel),
        host = TrackControlsHost(),
    )

    // ── state ──────────────────────────────────────────────────────────────
    private var currentNow: EpgEntity? = null
    private var isFavorite = false
    private var channelName: String? = null
    private var connectionLabel: String = "XTREAM"

    /** The two-panel surf drawer, which also owns the zap state (C8). */
    private val surfDrawer = LiveSurfDrawerController(root, DrawerHost())

    /** The full-screen EPG grid drawn over the video (C8). */
    private val guide = LiveGuideOverlayController(root, playerView, GuideHost())

    val isDrawerOpen: Boolean get() = surfDrawer.isOpen
    val isGuideOpen: Boolean get() = guide.isOpen
    val isOsdVisible: Boolean get() = bottomOsd.isVisible && bottomOsd.alpha > 0.5f


    private val hideOsdRunnable = Runnable { hideOsd() }
    private val hideZapRunnable = Runnable { hideZapOsd() }
    private val hideToastRunnable = Runnable { dismissToast() }
    private val minuteTicker = object : Runnable {
        override fun run() {
            val now = clockFormat.format(Date())
            clock.text = now
            surfDrawer.onMinuteTick(now)
            guide.onMinuteTick(now)
            updateProgressRow()
            handler.postDelayed(this, 30_000L)
        }
    }

    init {
        btnChannels.setOnClickListener { surfDrawer.open() }
        btnGuide.setOnClickListener { guide.open() }
        btnCc.setOnClickListener { trackControls.cycleCc() }
        btnAudio.setOnClickListener { trackControls.cycleAudio() }
        btnAspect.setOnClickListener { trackControls.cycleAspect() }
        btnFav.setOnClickListener { callbacks.onToggleFavorite() }

        trackControls.applyInitialLabels()
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
        bugInitials.text = LiveChannelVisuals.channelInitials(name)
        bugLogo.background = LiveChannelVisuals.channelTileGradient(
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

        val quality = LiveChannelVisuals.detectQuality(name)
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
        val text = number?.let { String.format(java.util.Locale.US, "%03d", it) } ?: "---"
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
        // Also flash JUST the ON AIR NOW neighbour list on every channel change (no full
        // controller). If the OSD is already open (possibly the interactive controller), leave
        // it alone and just keep it up; otherwise reveal the on-air-only card non-interactively
        // so zapping (D-pad UP/DOWN) keeps working.
        if (isOsdVisible) {
            resetOsdTimer()
        } else {
            showOsd(focus = false, autoHide = true, interactive = false, onAirOnly = true)
        }
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
        zapBackdropInitials.text = LiveChannelVisuals.channelInitials(channelName)
        zapBackdropGradient.background = LiveChannelVisuals.watermarkGradient(channelName)
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
    /** Per-channel "now" EPG for the surf rows, ON AIR strip and preview. */
    fun setSurfEpg(map: Map<String, EpgEntity>) = surfDrawer.setSurfEpg(map)

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
    // interactive=false reveals the bar purely as info — controls stay non-focusable and the
    // player keeps focus, so D-pad UP/DOWN keeps zapping. onAirOnly hides the now-playing info
    // + control buttons, leaving just the ON AIR NOW neighbour list — used to flash the list on
    // every channel change without bringing up the full controller.
    fun showOsd(
        focus: Boolean = false,
        autoHide: Boolean = true,
        interactive: Boolean = true,
        onAirOnly: Boolean = false
    ) {
        handler.removeCallbacks(hideOsdRunnable)
        if (!isOsdVisible) {
            bottomOsd.isVisible = true
            bottomOsd.translationY = 15f * root.resources.displayMetrics.density
            bottomOsd.alpha = 0f
            bottomOsd.animate().translationY(0f).alpha(1f).setDuration(300).setInterpolator(easeOutExpo).start()
        }
        // The controller parts (now-playing + buttons) and the darkening scrims only show for
        // the full OSD; the zap-only flash keeps just the ON AIR NOW card floating. Use
        // INVISIBLE (not GONE) so the hidden clusters still reserve their space and the card
        // stays in the exact right-side position it has with the full controller.
        nowInfoCluster.visibility = if (onAirOnly) View.INVISIBLE else View.VISIBLE
        controlsRow.visibility = if (onAirOnly) View.INVISIBLE else View.VISIBLE
        scrimTop.isVisible = !onAirOnly
        scrimBottom.isVisible = !onAirOnly
        if (!onAirOnly) {
            scrimTop.animate().alpha(1f).setDuration(300).start()
            scrimBottom.animate().alpha(1f).setDuration(300).start()
        }
        setChromeVisible(true)
        setControlsFocusable(interactive && !onAirOnly)
        if (focus && interactive && !onAirOnly) btnChannels.requestFocus()
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

    /**
     * Is focus on the leftmost control that can actually be reached?
     *
     * Not `controls.first()`: the row hides the controls it has no data for (a channel with no
     * subtitle or audio tracks drops those buttons), so the leftmost *reachable* control is not
     * always the same view.
     */
    private fun isFirstControlFocused(): Boolean =
        controls.firstOrNull { it.isVisible && it.isFocusable }?.isFocused == true

    // ── two-panel surf drawer (channels + category picker, v2) ─────────────
    // Owned by [LiveSurfDrawerController], which also owns the zap state it renders.

    fun setZapState(categoryId: String, categoryName: String?, channels: List<ZapChannel>, index: Int) =
        surfDrawer.setZapState(categoryId, categoryName, channels, index)

    /** Category picker data for the second-LEFT panel. */
    fun setCategories(cats: List<XtreamCategory>) = surfDrawer.setCategories(cats)

    fun setFavorites(ids: Set<String>, currentStreamId: String?) {
        isFavorite = currentStreamId != null && ids.contains(currentStreamId)
        favIcon.setImageResource(if (isFavorite) R.drawable.ic_live_star_filled else R.drawable.ic_live_star)
        surfDrawer.setFavorites(ids)
    }

    fun openDrawer() = surfDrawer.open()

    fun closeDrawer(refocusControls: Boolean = true) = surfDrawer.close(refocusControls)

    /** BACK inside the drawer: categories → channels → close. Returns true if consumed. */
    fun handleDrawerBack(): Boolean = surfDrawer.handleBack()

    /** What the drawer needs back from the OSD around it. */
    private inner class DrawerHost : LiveSurfDrawerController.Host {
        override fun showToast(message: String) = this@LivePlayerOsdManager.showToast(message)
        override fun tuneChannel(index: Int) = callbacks.onTuneChannel(index)
        override fun selectCategory(categoryId: String) = callbacks.onCategorySelected(categoryId)

        override fun onDrawerOpening() {
            handler.removeCallbacks(hideOsdRunnable)
            // OSD dims to ~12% while the drawer is open (spec).
            bottomOsd.animate().alpha(0.12f).setDuration(250).start()
        }

        override fun onDrawerClosed(refocusControls: Boolean) {
            if (bottomOsd.isVisible) {
                bottomOsd.animate().alpha(1f).setDuration(250).start()
                handler.postDelayed(hideOsdRunnable, OSD_TIMEOUT_MS)
                if (refocusControls) btnChannels.requestFocus()
            } else {
                playerView.requestFocus()
            }
        }
    }

    // ── in-player TV Guide grid (v2) ─────────────────────────────────
    // Owned by [LiveGuideOverlayController]; these stay as the OSD's public surface because
    // PlayerLiveTuner and PlayerInputRouter already call them.

    /** Open the full-screen EPG grid over the video (from the TV Guide button). */
    fun openGuide() = guide.open()

    /** Windowed EPG for the guide, keyed by streamId, arriving async from the host. */
    fun setGuideEpg(map: Map<String, List<EpgEntity>>) = guide.setGuideEpg(map)

    /** BACK inside the guide closes it. Returns true if consumed. */
    fun handleGuideBack(): Boolean = guide.handleBack()

    /** What the guide needs back from the OSD around it. */
    private inner class GuideHost : LiveGuideOverlayController.Host {
        override fun zapChannels(): List<ZapChannel> = surfDrawer.channels()
        override fun zapIndex(): Int = surfDrawer.tunedIndex()
        override fun showToast(message: String) = this@LivePlayerOsdManager.showToast(message)
        override fun requestGuideData(fromMs: Long, toMs: Long) = callbacks.onRequestGuideData(fromMs, toMs)
        override fun tuneChannel(index: Int) = callbacks.onTuneChannel(index)

        /** The guide is full-screen: retire the bottom OSD, chrome and any open drawer. */
        override fun retireChromeForGuide() {
            handler.removeCallbacks(hideOsdRunnable)
            if (surfDrawer.isOpen) surfDrawer.close(refocusControls = false)
            setChromeVisible(false)
            setControlsFocusable(false)
            if (bottomOsd.isVisible) {
                bottomOsd.animate().alpha(0f).setDuration(180).withEndAction { bottomOsd.isVisible = false }.start()
                scrimTop.animate().alpha(0f).setDuration(180).withEndAction { scrimTop.isVisible = false }.start()
                scrimBottom.animate().alpha(0f).setDuration(180).withEndAction { scrimBottom.isVisible = false }.start()
            }
        }
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
    // ── control actions ──────────────────────────────────────
    // Subtitles / audio / aspect cycling is owned by [LiveTrackControls].

    private inner class TrackControlsHost : LiveTrackControls.Host {
        override fun showToast(message: String) = this@LivePlayerOsdManager.showToast(message)
        override fun resetOsdTimer() = this@LivePlayerOsdManager.resetOsdTimer()
    }

    // ── key routing (spec §8) ──────────────────────────────────────────────
    /** Returns true when the event was consumed. Zap keys stay with PlayerActivity. */
    fun handleKeyEvent(event: KeyEvent): Boolean {
        if (event.action != KeyEvent.ACTION_DOWN) {
            // Swallow key-ups for keys we consume on the way down.
            return false
        }
        if (guide.isOpen) return guide.handleKeyEvent(event)
        if (surfDrawer.isOpen) return surfDrawer.handleKeyEvent(event)

        when (event.keyCode) {
            KeyEvent.KEYCODE_DPAD_LEFT -> {
                // See leftOpensChannelDrawer: the row used to be a one-way street because LEFT
                // opened the drawer no matter where focus was.
                if (leftOpensChannelDrawer(isOsdVisible, isControlFocused(), isFirstControlFocused())) {
                    surfDrawer.open(); return true
                }
                resetOsdTimer()
                return false // let focus travel back across the controls row
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
        private const val GUIDE_CH_COL_DP = 140f
        // Don't draw a "No information" filler for sub-2min seams between programmes.
        private const val GUIDE_MIN_FILLER_MS = 2 * 60_000L


    }
}
