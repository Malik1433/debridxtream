package com.tvonnet.debridxtreamiptv.player.stabilized

import android.os.Handler
import android.util.Log
import android.view.View
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.media3.exoplayer.ExoPlayer
import com.tvonnet.debridxtreamiptv.R
import com.tvonnet.debridxtreamiptv.util.SensitiveLogRedactor
import kotlinx.coroutines.launch
import java.util.Locale

/**
 * The Live-TV zapping + on-screen-display brain (Phase C2 of the route to ≤600).
 *
 * Moved verbatim out of [PlayerActivity]: [zapChannel] (per-keypress OSD update +
 * debounce), [tuneToZapChannel] (the single tune commit point), [tuneToZapIndex],
 * [toggleLiveFavorite], [setupLiveOsd] (builds the cinematic Live OSD + its four
 * lifecycle collectors) and [performSeamlessSwitch] (the GOLD-STANDARD atomic
 * stop→clear→prepare→seek→play switch used by every reconnect path).
 *
 * Landmine discipline (unchanged, that is the point of a verbatim move): the switch
 * block does NOT re-bind `playerView.player`; `buildMediaItem` (which owns the
 * TS-vs-HLS LiveConfiguration gate) stays on the Activity and is called through it;
 * the two timeout Handlers stay owned by the Activity (armed/cancelled from ~30
 * sites) — this tuner holds the same instances so the watchdog arms identically.
 *
 * State flows through [PlayerSessionState] (S1); Activity collaborators are reached
 * on the concrete [activity] (this cluster is too deeply wired to the Activity's
 * views/viewModel/prefs for a narrow interface to buy anything).
 */
internal class PlayerLiveTuner(
    private val activity: BasePlayerFragment,
    private val session: PlayerSessionState,
    private val timeoutHandler: Handler,
    private val timeoutRunnable: Runnable,
) {

    // ── collaborators owned by the Activity, reached verbatim ──
    private val viewModel get() = activity.viewModel
    private val playerView get() = activity.playerView
    private val qoeTracker get() = activity.qoeTracker
    private val zapDebouncer get() = activity.zapDebouncer
    private val epgOverlayUi get() = activity.epgOverlayUi
    private val prefs get() = activity.prefs
    private val lifecycleScope get() = activity.lifecycleScope
    private val supportActionBar get() = activity.supportActionBar
    private val isFinishing get() = activity.isFinishing
    private val isDestroyed get() = activity.isDestroyed

    private var player: ExoPlayer?
        get() = activity.player
        set(value) { activity.player = value }
    private var liveOsd: LivePlayerOsdManager?
        get() = activity.liveOsd
        set(value) { activity.liveOsd = value }

    // ── screen state (S1) ──
    private var currentZapRequestId: Long by session::currentZapRequestId
    private var retryCount: Int by session::retryCount
    private var audioSinkRecoveryCount: Int by session::audioSinkRecoveryCount
    private var endedReconnects: Int by session::endedReconnects
    private var liveFreezeReprepares: Int by session::liveFreezeReprepares
    private var contentId: String? by session::contentId
    private var currentUrl: String? by session::currentUrl
    private var channelLogoUrl: String? by session::channelLogoUrl
    private var currentEpgChannelId: String? by session::currentEpgChannelId
    private val pendingChannelName: String? by session::pendingChannelName
    private val baseServerUrl: String? by session::baseServerUrl
    private var isSwitching: Boolean by session::isSwitching
    private var switchCount: Int by session::switchCount
    private var frameRateMatchedForCurrentSource: Boolean by session::frameRateMatchedForCurrentSource
    private var timeoutMs: Long by session::timeoutMs
    private var startPositionMs: Long by session::startPositionMs

    // ── thin forwarders to Activity collaborators kept on the Activity ──
    private fun resolveTimeoutMs(url: String): Long = activity.resolveTimeoutMs(url)
    private fun armWatchdogBaseline() = activity.armWatchdogBaseline()
    private fun startStallMonitor() = activity.startStallMonitor()
    private fun buildMediaItem(url: String) = activity.buildMediaItem(url)
    private fun bindChannelMeta(channelName: String?) = activity.bindChannelMeta(channelName)
    private fun showEpgOverlay(mode: EpgOverlayMode, pinned: Boolean) = activity.showEpgOverlay(mode, pinned)
    private fun resetReconnectBudget() = activity.resetReconnectBudget()
    private fun hideReconnectingBanner() = activity.hideReconnectingBanner()
    private fun showToast(message: String) = activity.showToast(message)
    private fun getString(resId: Int): String = activity.getString(resId)

    fun zapChannel(direction: Int) {
        val target = viewModel.moveZap(direction) ?: run {
            val isReady = viewModel.zapState.value?.channels?.isNotEmpty() == true
            val msg = if (isReady) getString(R.string.player_zap_unavailable)
                else getString(R.string.player_zap_loading)
            liveOsd?.showToast(msg.uppercase(Locale.getDefault())) ?: showToast(msg)
            return
        }
        // LP-B-1: moveZap already advanced the in-memory index. Update the OSD
        // immediately for every keypress (including auto-repeat) so surfing feels
        // instant, but DEBOUNCE the real stream connect — only tuneToZapChannel
        // (which resets the reconnect budget + calls performSeamlessSwitch) fires,
        // once, ZAP_DEBOUNCE_MS after the user stops pressing.
        val osd = liveOsd
        if (osd != null) {
            osd.setChannelNumber((viewModel.zapState.value?.index ?: 0) + 1)
            osd.setFavorites(viewModel.liveFavoriteIds.value, target.streamId)
            osd.showZapOsd()
        } else {
            val keepPinned = epgOverlayUi.isPinnedUp()
            showEpgOverlay(mode = EpgOverlayMode.COMPACT, pinned = keepPinned)
        }
        zapDebouncer.schedule(target)
    }

    /** OK-tune from the surf drawer (Live Player OSD spec §7). */
    private fun tuneToZapIndex(index: Int) {
        val target = viewModel.zapTo(index) ?: return
        liveOsd?.showToast("▶ TUNING · ${target.name}")
        tuneToZapChannel(target)
    }

    fun tuneToZapChannel(target: ZapChannel) {
        // This is the single commit point for a tune (debounced zap, drawer OK-tune,
        // resume). Cancel any still-pending debounced zap so a late commit can't
        // fire on top of this one (LP-B-1).
        zapDebouncer.cancel()

        currentZapRequestId++
        val reqId = currentZapRequestId

        // A user-initiated zap is a fresh recovery context — reset the unified
        // reconnect budget (fix 2) and retire any leftover banner (fix 3) so the new
        // channel starts with a full budget.
        resetReconnectBudget()
        hideReconnectingBanner()
        // …and the same for the health verdict. A live channel changes HERE, not in
        // initializePlayer — the warm zap reuses the player — so a diagnosis pinned to the old
        // channel would otherwise follow the customer through every channel they tried, saying
        // "this channel is slow" about channels it had never measured.
        activity.streamHealth.reset()
        retryCount = 0
        audioSinkRecoveryCount = 0
        endedReconnects = 0
        liveFreezeReprepares = 0

        contentId = target.streamId
        currentUrl = target.streamUrl
        channelLogoUrl = target.logoUrl
        bindChannelMeta(target.name)
        supportActionBar?.title = target.name

        val epgKey = target.epgChannelId?.takeIf { it.isNotBlank() } ?: target.streamId
        currentEpgChannelId = epgKey
        viewModel.observeEpg(epgKey, target.streamId)

        val osd = liveOsd
        if (osd != null) {
            osd.setChannelNumber((viewModel.zapState.value?.index ?: 0) + 1)
            osd.setFavorites(viewModel.liveFavoriteIds.value, target.streamId)
            osd.showZapOsd()
        } else {
            val keepPinned = epgOverlayUi.isPinnedUp()
            showEpgOverlay(mode = EpgOverlayMode.COMPACT, pinned = keepPinned)
        }

        performSeamlessSwitch(target.streamUrl, reqId)
    }

    fun setupLiveOsd() {
        if (liveOsd != null) return
        val osdRoot = activity.findViewById<View>(R.id.view_live_osd) ?: return
        liveOsd = LivePlayerOsdManager(
            root = osdRoot,
            playerView = playerView,
            playerProvider = { player },
            callbacks = object : LivePlayerOsdManager.Callbacks {
                override fun onRequestGuideData(windowStartMs: Long, windowEndMs: Long) {
                    viewModel.loadGuideEpg(windowStartMs, windowEndMs)
                }
                override fun onTuneChannel(index: Int) = tuneToZapIndex(index)
                override fun onToggleFavorite() = toggleLiveFavorite()
                override fun onCategorySelected(categoryId: String) {
                    viewModel.switchZapCategory(categoryId, baseServerUrl ?: prefs.getServerUrl())
                }
            }
        ).also {
            it.show()
            it.showOsd(focus = false)
        }
        viewModel.observeLiveFavorites()
        viewModel.loadSurfCategories()
        lifecycleScope.launch {
            activity.repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.liveFavoriteIds.collect { ids ->
                    liveOsd?.setFavorites(ids, contentId)
                }
            }
        }
        lifecycleScope.launch {
            activity.repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.surfCategories.collect { cats ->
                    liveOsd?.setCategories(cats)
                }
            }
        }
        lifecycleScope.launch {
            activity.repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.surfEpg.collect { map ->
                    liveOsd?.setSurfEpg(map)
                }
            }
        }
        lifecycleScope.launch {
            activity.repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.guideEpg.collect { map ->
                    liveOsd?.setGuideEpg(map)
                }
            }
        }
    }

    private fun toggleLiveFavorite() {
        val id = contentId
        val name = pendingChannelName
        lifecycleScope.launch {
            val added = viewModel.toggleLiveFavorite(id, name, channelLogoUrl) ?: return@launch
            liveOsd?.showToast(
                if (added) "★ ADDED TO FAVORITES · ${name.orEmpty()}"
                else "REMOVED FROM FAVORITES"
            )
        }
    }

    fun performSeamlessSwitch(newUrl: String, requestId: Long = -1L) {
        if (isFinishing || isDestroyed) return
        val playerSnapshot = player ?: return

        if (requestId != -1L && requestId != currentZapRequestId) {
            Log.w("SWITCH_DEBUG", "Dropping stale switch request: $requestId vs latest $currentZapRequestId")
            return
        }

        isSwitching = true
        switchCount++
        frameRateMatchedForCurrentSource = false
        liveOsd?.showZapBackdrop()
        qoeTracker?.markPrepareStart() // LP-D-2: TTFF measures this switch (zap latency)
        Log.i("PlayerActivity", "Performing seamless switch to: ${SensitiveLogRedactor.describeUrl(newUrl)}")

        timeoutHandler.removeCallbacks(timeoutRunnable)
        timeoutMs = resolveTimeoutMs(newUrl)
        timeoutHandler.postDelayed(timeoutRunnable, timeoutMs)
        armWatchdogBaseline()

        startStallMonitor()

        // GOLD STANDARD SWITCH BLOCK (Atomic Reset)
        // 1. Stop current playback pipeline
        playerSnapshot.stop()

        // 2. Clear previous media items to avoid state leakage
        playerSnapshot.clearMediaItems()

        val mediaItem = buildMediaItem(newUrl)

        // 3. Set new target
        playerSnapshot.setMediaItem(mediaItem)

        // 4. Prepare FIRST to initialize codecs
        playerSnapshot.prepare()

        // 5. Seek AFTER prepare for stable decoder positioning
        if (startPositionMs > 0L) {
            playerSnapshot.seekTo(startPositionMs)
        }

        // 6. Start
        playerSnapshot.playWhenReady = true

        // NOTE: DO NOT re-bind playerView.player here.
        // Re-binding causes Surface detachment/attachment race conditions on many Android TV devices.
        Log.d("SWITCH_DEBUG", "Seamless switch command sequence complete (Position: $startPositionMs)")
    }
}
