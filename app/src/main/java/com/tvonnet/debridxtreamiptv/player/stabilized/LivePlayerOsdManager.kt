package com.tvonnet.debridxtreamiptv.player.stabilized

import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
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
import com.tvonnet.debridxtreamiptv.data.model.XtreamCategory
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
    private val onOpenGuide: () -> Unit,
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
    private val timeStart: TextView = root.findViewById(R.id.live_time_start)
    private val timeEnd: TextView = root.findViewById(R.id.live_time_end)
    private val minLeft: TextView = root.findViewById(R.id.live_min_left)
    private val progress: ProgressBar = root.findViewById(R.id.live_progress)
    private val nextLabel: TextView = root.findViewById(R.id.live_next_label)
    private val nextTitle: TextView = root.findViewById(R.id.live_next_title)
    private val nextCard: View = root.findViewById(R.id.live_next_card)
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
    private val toast: View = root.findViewById(R.id.live_toast)
    private val toastDot: View = root.findViewById(R.id.live_toast_dot)
    private val toastText: TextView = root.findViewById(R.id.live_toast_text)

    private val controls = listOf(btnChannels, btnGuide, btnCc, btnAudio, btnAspect, btnFav)

    // ── state ──────────────────────────────────────────────────────────────
    private var currentNow: EpgEntity? = null
    private var currentNext: EpgEntity? = null
    private var isFavorite = false
    private var aspectIndex = 0
    private var zapChannels: List<ZapChannel> = emptyList()
    private var zapIndex: Int = -1
    private var favoriteIds: Set<String> = emptySet()
    private var channelName: String? = null
    private var connectionLabel: String = "XTREAM"

    // Surf drawer can show the current category's channels, or the category picker.
    private enum class DrawerMode { CHANNELS, CATEGORIES }
    private var drawerMode: DrawerMode = DrawerMode.CHANNELS
    private var categories: List<XtreamCategory> = emptyList()
    private var currentCategoryId: String? = null
    private var currentCategoryName: String? = null
    private var pendingCategoryRefocus = false

    val isDrawerOpen: Boolean get() = drawer.isVisible
    val isOsdVisible: Boolean get() = bottomOsd.isVisible && bottomOsd.alpha > 0.5f

    private val surfAdapter = LiveSurfChannelAdapter { index ->
        closeDrawer(refocusControls = false)
        onTuneChannel(index)
    }

    private val categoryAdapter = LiveSurfCategoryAdapter { category ->
        val catId = category.category_id ?: return@LiveSurfCategoryAdapter
        currentCategoryId = catId
        currentCategoryName = category.category_name
        // Ask PlayerActivity to reload the zap list for the picked category, then
        // flip back to the channel list. The refreshed list arrives async via
        // setZapState, which re-asserts focus because of this flag.
        pendingCategoryRefocus = true
        onCategorySelected(catId)
        switchDrawerToChannels()
    }

    private val hideOsdRunnable = Runnable { hideOsd() }
    private val hideZapRunnable = Runnable { hideZapOsd() }
    private val hideToastRunnable = Runnable { dismissToast() }
    private val minuteTicker = object : Runnable {
        override fun run() {
            clock.text = clockFormat.format(Date())
            updateProgressRow()
            handler.postDelayed(this, 30_000L)
        }
    }

    init {
        surfList.layoutManager = LinearLayoutManager(root.context)
        surfList.adapter = surfAdapter

        btnChannels.setOnClickListener { openDrawer() }
        btnGuide.setOnClickListener { onOpenGuide() }
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
        currentNext = next

        val title = now?.title
        nowTitle.text = title ?: root.context.getString(R.string.epg_no_info)
        nowTitle.alpha = 0f
        nowTitle.animate().alpha(1f).setDuration(400).start()

        if (zapOsd.isVisible) {
            zapNow.text = title.orEmpty()
            zapNow.isVisible = !title.isNullOrBlank()
        }

        if (next?.title.isNullOrBlank()) {
            nextCard.isVisible = false
        } else {
            nextCard.isVisible = true
            nextLabel.text = "UP NEXT · " + (next?.start?.let { timeFormat.format(Date(it)) } ?: "--:--")
            nextTitle.text = next?.title
            nextTitle.alpha = 0f
            nextTitle.animate().alpha(1f).setDuration(400).start()
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

    // ── channel surf drawer ────────────────────────────────────────────────
    fun setZapState(categoryId: String, categoryName: String?, channels: List<ZapChannel>, index: Int) {
        currentCategoryId = categoryId
        currentCategoryName = categoryName
        zapChannels = channels
        zapIndex = index
        if (drawerMode == DrawerMode.CHANNELS) {
            surfTitle.text = categoryName ?: root.context.getString(R.string.live_osd_channels_title)
            surfCount.text = root.context.getString(R.string.live_osd_channel_count, channels.size)
            if (isDrawerOpen) {
                surfAdapter.submit(channels, index, favoriteIds, nowTitlesForAdapter())
                // Only a category switch re-asserts focus (the async list swap under
                // the open drawer would otherwise drop D-pad focus). A normal tune
                // is closing the drawer, so leave its focus alone.
                if (pendingCategoryRefocus) {
                    pendingCategoryRefocus = false
                    focusSurfRow(index.coerceAtLeast(0))
                }
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

    /** Category picker data for the second-LEFT drawer view. */
    fun setCategories(cats: List<XtreamCategory>) {
        categories = cats
    }

    fun setFavorites(ids: Set<String>, currentStreamId: String?) {
        favoriteIds = ids
        isFavorite = currentStreamId != null && ids.contains(currentStreamId)
        favIcon.setImageResource(if (isFavorite) R.drawable.ic_live_star_filled else R.drawable.ic_live_star)
        if (isDrawerOpen) {
            surfAdapter.submit(zapChannels, zapIndex, favoriteIds, nowTitlesForAdapter())
        }
    }

    private fun nowTitlesForAdapter(): Map<String, String> {
        // EPG is observed only for the tuned channel; show its NOW line in the list.
        val id = zapChannels.getOrNull(zapIndex)?.streamId ?: return emptyMap()
        val title = currentNow?.title ?: return emptyMap()
        return mapOf(id to title)
    }

    fun openDrawer() {
        if (zapChannels.isEmpty()) {
            showToast(root.context.getString(R.string.player_zap_loading))
            return
        }
        handler.removeCallbacks(hideOsdRunnable)
        // Always open on the channel list of the current category (first LEFT).
        drawerMode = DrawerMode.CHANNELS
        surfList.adapter = surfAdapter
        surfTitle.text = currentCategoryName ?: root.context.getString(R.string.live_osd_channels_title)
        surfCount.text = root.context.getString(R.string.live_osd_channel_count, zapChannels.size)
        surfAdapter.submit(zapChannels, zapIndex, favoriteIds, nowTitlesForAdapter())
        drawerScrim.alpha = 0f
        drawerScrim.isVisible = true
        drawerScrim.animate().alpha(1f).setDuration(250).start()
        drawer.isVisible = true
        drawer.translationX = -drawer.layoutParams.width.toFloat()
        drawer.animate().translationX(0f).setDuration(300).setInterpolator(easeOutExpo).start()
        // OSD fades to 15% while the drawer is open (spec §7)
        bottomOsd.animate().alpha(0.15f).setDuration(250).start()
        val target = zapIndex.coerceAtLeast(0)
        surfList.scrollToPosition(target)
        surfList.post {
            surfList.findViewHolderForAdapterPosition(target)?.itemView?.requestFocus()
                ?: surfList.requestFocus()
        }
    }

    /** Second LEFT while on the channel list — swap to the category picker in place. */
    private fun switchDrawerToCategories() {
        if (categories.isEmpty()) {
            showToast(root.context.getString(R.string.live_osd_categories_loading))
            return
        }
        drawerMode = DrawerMode.CATEGORIES
        surfTitle.text = root.context.getString(R.string.live_osd_categories_title)
        surfCount.text = root.context.getString(R.string.live_osd_category_count, categories.size)
        categoryAdapter.submit(categories, currentCategoryId)
        surfList.adapter = categoryAdapter
        val selected = categories.indexOfFirst { it.category_id == currentCategoryId }.coerceAtLeast(0)
        surfList.scrollToPosition(selected)
        surfList.post {
            surfList.findViewHolderForAdapterPosition(selected)?.itemView?.requestFocus()
                ?: surfList.requestFocus()
        }
    }

    /** Back to the channel list (RIGHT from categories, or after picking one). */
    private fun switchDrawerToChannels() {
        drawerMode = DrawerMode.CHANNELS
        surfTitle.text = currentCategoryName ?: root.context.getString(R.string.live_osd_channels_title)
        surfCount.text = root.context.getString(R.string.live_osd_channel_count, zapChannels.size)
        surfAdapter.submit(zapChannels, zapIndex, favoriteIds, nowTitlesForAdapter())
        surfList.adapter = surfAdapter
        val target = zapIndex.coerceAtLeast(0)
        surfList.scrollToPosition(target)
        surfList.post {
            surfList.findViewHolderForAdapterPosition(target)?.itemView?.requestFocus()
                ?: surfList.requestFocus()
        }
    }

    /** BACK inside the drawer: categories → channels → close. Returns true if consumed. */
    fun handleDrawerBack(): Boolean {
        if (!isDrawerOpen) return false
        if (drawerMode == DrawerMode.CATEGORIES) {
            switchDrawerToChannels()
            return true
        }
        closeDrawer()
        return true
    }

    fun closeDrawer(refocusControls: Boolean = true) {
        if (!isDrawerOpen) return
        drawerMode = DrawerMode.CHANNELS
        pendingCategoryRefocus = false
        surfList.adapter = surfAdapter
        drawerScrim.animate().alpha(0f).setDuration(200).withEndAction { drawerScrim.isVisible = false }.start()
        drawer.animate().translationX(-drawer.layoutParams.width.toFloat()).setDuration(250)
            .withEndAction { drawer.isVisible = false }.start()
        if (bottomOsd.isVisible) {
            bottomOsd.animate().alpha(1f).setDuration(250).start()
            handler.postDelayed(hideOsdRunnable, OSD_TIMEOUT_MS)
            if (refocusControls) btnChannels.requestFocus()
        } else {
            playerView.requestFocus()
        }
    }

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
        if (isDrawerOpen) {
            // BACK is handled by PlayerActivity's back routing.
            return when (event.keyCode) {
                KeyEvent.KEYCODE_DPAD_LEFT -> {
                    // Channels → categories (second LEFT); categories → close (third LEFT).
                    when (drawerMode) {
                        DrawerMode.CHANNELS -> switchDrawerToCategories()
                        DrawerMode.CATEGORIES -> closeDrawer()
                    }
                    true
                }
                KeyEvent.KEYCODE_DPAD_RIGHT -> {
                    // From the category picker, RIGHT returns to the channel list.
                    if (drawerMode == DrawerMode.CATEGORIES) {
                        switchDrawerToChannels(); true
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
