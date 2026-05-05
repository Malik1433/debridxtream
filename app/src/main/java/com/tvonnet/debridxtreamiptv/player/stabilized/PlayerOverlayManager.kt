package com.tvonnet.debridxtreamiptv.player.stabilized

import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.ProgressBar
import android.widget.TextView
import androidx.core.view.isVisible
import androidx.lifecycle.LifecycleCoroutineScope
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.tvonnet.debridxtreamiptv.R

import com.tvonnet.debridxtreamiptv.data.local.entity.EpgEntity
import com.tvonnet.debridxtreamiptv.util.GlideUtils
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Manages all UI overlays for PlayerActivity.
 * Centralizes logic for Episode List, EPG, Browser, and Next Episode Prompts.
 */
class PlayerOverlayManager(
    private val activity: PlayerActivity,
    private val rootView: View
) {
    
    enum class EpgOverlayMode { HIDDEN, COMPACT, EXPANDED }

    // State
    var isEpisodeOverlayVisible = false
        private set
    var epgOverlayMode = EpgOverlayMode.HIDDEN
        private set
    var epgOverlayPinned = false
        private set
    var isNextPromptVisible = false
        private set
    private var currentContentType: com.tvonnet.debridxtreamiptv.data.model.ContentType? = null
    
    // Stale data prevention
    private var currentChannel: String? = null
    private var currentVod: String? = null
    private var currentEpisode: String? = null

    // --- Views ---

    // Episode List
    private val episodeOverlay: View? = rootView.findViewById(R.id.layout_episode_overlay)
    private val rvEpisodes: RecyclerView? = rootView.findViewById(R.id.rv_episodes)
    private val pbEpisodesLoading: ProgressBar? = rootView.findViewById(R.id.pb_episodes_loading)
    private val tvEpisodesEmpty: TextView? = rootView.findViewById(R.id.tv_episodes_empty)
    
    // EPG Overlay
    private val epgOverlay: View? = rootView.findViewById(R.id.epg_overlay)
    private val imgChannelLogo: ImageView? = rootView.findViewById(R.id.img_channel_logo)
    private val tvChannelNumber: TextView? = rootView.findViewById(R.id.tv_channel_number)
    private val tvChannelName: TextView? = rootView.findViewById(R.id.tv_channel_name)
    private val tvNowTitle: TextView? = rootView.findViewById(R.id.tv_now_title)
    private val tvNowTime: TextView? = rootView.findViewById(R.id.tv_now_time)
    private val progressNow: ProgressBar? = rootView.findViewById(R.id.progress_now)
    private val tvEpgStatus: TextView? = rootView.findViewById(R.id.tv_epg_status)
    private val tvNowCardTime: TextView? = rootView.findViewById(R.id.tv_now_card_time)
    private val tvNowCardTitle: TextView? = rootView.findViewById(R.id.tv_now_card_title)
    private val cardNext1: View? = rootView.findViewById(R.id.card_next_1)
    private val tvNext1Time: TextView? = rootView.findViewById(R.id.tv_next_1_time)
    private val tvNext1Title: TextView? = rootView.findViewById(R.id.tv_next_1_title)
    private val cardNext2: View? = rootView.findViewById(R.id.card_next_2)
    private val tvNext2Time: TextView? = rootView.findViewById(R.id.tv_next_2_time)
    private val tvNext2Title: TextView? = rootView.findViewById(R.id.tv_next_2_title)
    private val cardNext3: View? = rootView.findViewById(R.id.card_next_3)
    private val tvNext3Time: TextView? = rootView.findViewById(R.id.tv_next_3_time)
    private val tvNext3Title: TextView? = rootView.findViewById(R.id.tv_next_3_title)

    // Debrid
    private val debridResolving: View? = rootView.findViewById(R.id.layout_debrid_resolving)
    private val resolvingStatus: TextView? = rootView.findViewById(R.id.tv_resolving_status)

    // Next Episode Prompt
    private val nextEpisodeOverlay: View? = rootView.findViewById(R.id.view_next_episode_prompt)
    private val tvNextEpTitle: TextView? = nextEpisodeOverlay?.findViewById(R.id.tv_next_episode_title)
    private val tvCountdownSeconds: TextView? = nextEpisodeOverlay?.findViewById(R.id.tv_countdown_seconds)
    private val btnPlayNext: View? = nextEpisodeOverlay?.findViewById(R.id.btn_play_now)
    private val btnCancelNext: View? = nextEpisodeOverlay?.findViewById(R.id.btn_cancel)
    
    // Support QR
    private val layoutSupportQr: View? = rootView.findViewById(R.id.layout_support_qr)
    private val imgSupportQr: ImageView? = rootView.findViewById(R.id.img_support_qr)

    // Browser Overlay
    private val browserView: View? = rootView.findViewById(R.id.view_channel_browser)
    private val rvCategories: RecyclerView? = browserView?.findViewById(R.id.rv_browser_categories)
    private val rvChannels: RecyclerView? = browserView?.findViewById(R.id.rv_browser_channels)
    private val pbBrowserLoading: View? = browserView?.findViewById(R.id.pb_browser_loading)
    private val tvBrowserEmpty: View? = browserView?.findViewById(R.id.tv_browser_empty)
    private val tvBrowserClock: TextView? = browserView?.findViewById(R.id.tv_browser_clock)

    private val overlayHandler = Handler(Looper.getMainLooper())
    private val overlayHideRunnable = Runnable { hideEpgOverlay() }
    private val OVERLAY_TIMEOUT = 6000L
    private var nextEpisodeTimer: android.os.CountDownTimer? = null
    
    private val timeFormatter = SimpleDateFormat("HH:mm", Locale.getDefault())

    var onPlayNextClicked: (() -> Unit)? = null
    var onCancelNextClicked: (() -> Unit)? = null

    init {
        tvBrowserClock?.text = SimpleDateFormat("h:mm a", Locale.getDefault()).format(Date())
    }

    // --- Browser Overlay ---

    fun initBrowserOverlay(
        viewModel: PlayerViewModel,
        lifecycleScope: LifecycleCoroutineScope,
        browserCategoryAdapter: BrowserCategoryAdapter,
        browserChannelAdapter: BrowserChannelAdapter,
        onChannelFocus: (Int) -> Unit
    ) {
        rvCategories?.layoutManager = LinearLayoutManager(activity)
        rvCategories?.adapter = browserCategoryAdapter

        rvChannels?.layoutManager = GridLayoutManager(activity, 4)
        rvChannels?.adapter = browserChannelAdapter

        lifecycleScope.launch {
            var wasVisible = false
            viewModel.browserState.collect { state ->
                browserView?.visibility = if (state.isVisible) View.VISIBLE else View.GONE
                if (state.isVisible) {
                    browserCategoryAdapter.submitList(state.categories)
                    browserCategoryAdapter.setSelectedId(state.selectedCategoryId)
                    
                    browserChannelAdapter.submitList(state.channels) {
                        if (state.channels.isNotEmpty()) {
                            onChannelFocus(state.channels.size)
                        }
                    }
                    pbBrowserLoading?.visibility = if (state.isLoadingChannels) View.VISIBLE else View.GONE
                    tvBrowserEmpty?.visibility = if (!state.isLoadingChannels && state.channels.isEmpty()) View.VISIBLE else View.GONE
                } else if (wasVisible) {
                    com.tvonnet.debridxtreamiptv.utils.FocusCoordinator.force("PLAYER") { 
                        activity.findViewById<View>(R.id.player_view)?.requestFocus() 
                    }
                }
                wasVisible = state.isVisible
            }
        }
    }

    fun requestBrowserChannelFocus(channelIndex: Int) {
        rvChannels?.scrollToPosition(channelIndex)
        rvChannels?.post {
            rvChannels.findViewHolderForAdapterPosition(channelIndex)?.itemView?.requestFocus()
        }
    }

    fun hideBrowserOverlay() {
        browserView?.visibility = View.GONE
    }

    // --- Episode Overlay ---

    fun hideEpisodeOverlay() {
        toggleEpisodeOverlay(false)
    }

    fun toggleEpisodeOverlay(visible: Boolean) {
        val o = episodeOverlay ?: return
        if (visible == isEpisodeOverlayVisible) return
        
        Log.d("PlayerOverlay", "Episode overlay ${if (visible) "shown" else "hidden"}")
        isEpisodeOverlayVisible = visible
        
        if (visible) {
            o.alpha = 0f
            o.isVisible = true
            o.animate().alpha(1f).setDuration(200).start()
            o.requestFocus()
        } else {
            o.animate().alpha(0f).setDuration(200).withEndAction { 
                o.isVisible = false 
                activity.findViewById<View>(R.id.player_view)?.requestFocus()
            }.start()
        }
    }

    fun setEpisodesLoading(loading: Boolean) {
        pbEpisodesLoading?.isVisible = loading
    }

    fun setEpisodesEmpty(empty: Boolean) {
        tvEpisodesEmpty?.isVisible = empty
    }

    fun getEpisodesRecyclerView(): RecyclerView? = rvEpisodes

    // --- EPG Overlay ---

    fun showEpgOverlay(mode: EpgOverlayMode, pinned: Boolean) {
        Log.d("PlayerOverlay", "EPG shown mode=$mode pinned=$pinned")
        epgOverlayMode = if (mode == EpgOverlayMode.HIDDEN) EpgOverlayMode.HIDDEN else EpgOverlayMode.COMPACT
        epgOverlayPinned = pinned
        
        applyEpgOverlayMode()
        updateEpgVisibility(true)
        
        overlayHandler.removeCallbacks(overlayHideRunnable)
        if (!pinned) {
            overlayHandler.postDelayed(overlayHideRunnable, OVERLAY_TIMEOUT)
        }
    }

    fun hideEpgOverlay() {
        Log.d("PlayerOverlay", "EPG hidden")
        overlayHandler.removeCallbacks(overlayHideRunnable)
        epgOverlayPinned = false
        epgOverlayMode = EpgOverlayMode.HIDDEN
        updateEpgVisibility(false)
    }

    fun toggleEpgOverlayPinned() {
        if (epgOverlayMode == EpgOverlayMode.HIDDEN) {
            showEpgOverlay(EpgOverlayMode.COMPACT, pinned = true)
        } else {
            if (epgOverlayPinned) hideEpgOverlay() else showEpgOverlay(EpgOverlayMode.COMPACT, pinned = true)
        }
    }

    fun renderEpgOverlay(state: PlayerOverlayUiState) {
        if (epgOverlay == null) return

        val statusText = when {
            state.isSyncing -> activity.getString(R.string.player_epg_syncing)
            !state.errorMessage.isNullOrBlank() -> state.errorMessage
            !state.epgAvailable -> activity.getString(R.string.player_epg_no_guide)
            else -> null
        }
        tvEpgStatus?.text = statusText
        tvEpgStatus?.isVisible = !statusText.isNullOrBlank()

        val now = state.now
        tvNowTitle?.text = now?.title ?: activity.getString(R.string.epg_no_info)
        val nowTimeStr = now?.let { formatTimeRangeCompact(it) } ?: "--:--"
        tvNowTime?.text = nowTimeStr
        tvNowCardTitle?.text = now?.title ?: activity.getString(R.string.epg_no_info)
        tvNowCardTime?.text = nowTimeStr

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
    }

    private fun bindNextSlot(container: View?, titleView: TextView?, timeView: TextView?, program: EpgEntity?) {
        titleView ?: return
        timeView ?: return
        if (program == null) {
            container?.isVisible = false
            return
        }
        container?.isVisible = true
        timeView.text = formatTimeRangeCompact(program)
        titleView.text = program.title ?: activity.getString(R.string.epg_no_info)
    }

    fun bindChannelMeta(channelName: String?, logoUrl: String?) {
        tvChannelName?.text = channelName ?: activity.getString(R.string.player_epg_channel_unknown)
        imgChannelLogo?.let { logoView ->
            GlideUtils.loadChannelLogo(logoView, logoUrl)
        }
    }

    fun setChannelNumber(number: Int) {
        tvChannelNumber?.text = number.toString()
    }

    fun bindLive(channelName: String?, logoUrl: String?, channelNumber: Int? = null) {
        currentChannel = channelName
        bindChannelMeta(channelName, logoUrl)
        channelNumber?.let { setChannelNumber(it) }
    }

    fun bindVod(title: String?, posterUrl: String?) {
        currentVod = title
        // VOD specific binding logic if needed, currently using PlayerActivity's bindModernMetadata
    }

    fun bindEpisode(title: String?) {
        currentEpisode = title
    }

    private fun applyEpgOverlayMode() {
        val o = epgOverlay ?: return
        val layoutParams = o.layoutParams as? ViewGroup.MarginLayoutParams ?: return
        
        when (epgOverlayMode) {
            EpgOverlayMode.COMPACT -> {
                layoutParams.height = ViewGroup.LayoutParams.WRAP_CONTENT
            }
            EpgOverlayMode.EXPANDED -> {
                layoutParams.height = ViewGroup.LayoutParams.WRAP_CONTENT
            }
            else -> {}
        }
        o.layoutParams = layoutParams
    }

    fun updateEpgVisibility(visible: Boolean) {
        val o = epgOverlay ?: return
        if (visible) {
            if (o.isVisible) return
            o.alpha = 0f
            o.isVisible = true
            o.animate().alpha(1f).setDuration(160).start()
        } else {
            if (!o.isVisible) return
            o.animate().alpha(0f).setDuration(140).withEndAction { o.isVisible = false }.start()
        }
    }

    private fun formatTimeRangeCompact(program: EpgEntity): String {
        return "${timeFormatter.format(Date(program.start))}-${timeFormatter.format(Date(program.stop))}"
    }

    // --- Next Episode Prompt ---

    fun showNextEpisodePrompt(nextTitle: String, seconds: Int) {
        val prompt = nextEpisodeOverlay ?: return
        if (isNextPromptVisible) return
        
        Log.d("PlayerOverlay", "Next episode prompt shown: $nextTitle")
        isNextPromptVisible = true
        tvNextEpTitle?.text = nextTitle
        
        prompt.alpha = 0f
        prompt.isVisible = true
        prompt.animate().alpha(1f).setDuration(300).start()
        prompt.requestFocus()
        
        btnPlayNext?.setOnClickListener { onPlayNextClicked?.invoke() }
        btnCancelNext?.setOnClickListener { onCancelNextClicked?.invoke() }

        startNextEpisodeTimer(seconds)
    }

    private fun startNextEpisodeTimer(seconds: Int) {
        nextEpisodeTimer?.cancel()
        nextEpisodeTimer = object : android.os.CountDownTimer((seconds * 1000).toLong(), 1000) {
            override fun onTick(millisUntilFinished: Long) {
                tvCountdownSeconds?.text = (millisUntilFinished / 1000).toString()
            }
            override fun onFinish() {
                onPlayNextClicked?.invoke()
            }
        }.start()
    }

    fun hideNextEpisodePrompt() {
        val prompt = nextEpisodeOverlay ?: return
        if (!isNextPromptVisible) return
        
        Log.d("PlayerOverlay", "Next episode prompt hidden")
        isNextPromptVisible = false
        nextEpisodeTimer?.cancel()
        prompt.animate().alpha(0f).setDuration(300).withEndAction { prompt.isVisible = false }.start()
    }

    // --- Support QR ---

    fun showSupportQr(supportUrl: String) {
        layoutSupportQr?.isVisible = true
        try {
            val barcodeEncoder = com.journeyapps.barcodescanner.BarcodeEncoder()
            val bitmap = barcodeEncoder.encodeBitmap(supportUrl, com.google.zxing.BarcodeFormat.QR_CODE, 400, 400)
            imgSupportQr?.setImageBitmap(bitmap)
        } catch (e: Exception) {
            Log.e("PlayerOverlay", "Failed to generate QR code", e)
        }
    }

    // --- Misc ---

    fun setDebridResolving(visible: Boolean, status: String? = null) {
        debridResolving?.isVisible = visible
        status?.let { resolvingStatus?.text = it }
    }

    fun resetAll() {
        Log.d("OVERLAY_FIX", "resetAll called. PreviousType=$currentContentType")
        hideEpisodeOverlay()
        hideEpgOverlay()
        hideBrowserOverlay()
        clearData()
    }

    fun clearData() {
        currentChannel = null
        currentVod = null
        currentEpisode = null
    }

    fun setContentType(type: com.tvonnet.debridxtreamiptv.data.model.ContentType?) {
        Log.d("OVERLAY_FIX", "ContentType=$type")
        this.currentContentType = type
        
        // Auto-visibility management based on type
        if (type == com.tvonnet.debridxtreamiptv.data.model.ContentType.LIVE_TV) {
            showEpgOverlay(EpgOverlayMode.COMPACT, pinned = false)
        } else {
            hideEpgOverlay()
        }
    }

    fun isAnyOverlayVisible(): Boolean {
        return isEpisodeOverlayVisible || epgOverlayMode != EpgOverlayMode.HIDDEN || isNextPromptVisible
    }

    fun release() {
        overlayHandler.removeCallbacksAndMessages(null)
        nextEpisodeTimer?.cancel()
    }
}
