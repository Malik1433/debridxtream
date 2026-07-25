package com.tvonnet.debridxtreamiptv.player.stabilized

import androidx.media3.common.util.UnstableApi
import com.tvonnet.debridxtreamiptv.debug.PlaybackDiagnosticsRecorder
import dagger.hilt.android.AndroidEntryPoint

/**
 * The Live-TV player screen (P27 fragment split).
 *
 * P27-b moved the live-only setup + zap-init branches of [BasePlayerFragment.onViewCreated]
 * down here as hook overrides (behaviour-preserving; the base runs the empty defaults for a
 * VOD instance). The live collaborator fields — live tuner / cinematic OSD / legacy EPG strip
 * / channel zap / channel browser — still live in the base because shared paths (recovery /
 * stall / PiP / overlay / lifecycle) reference them; a later P27 step moves those down and the
 * shared-player adopt + hand-off + live key routing with them so the base shrinks toward ≤600.
 */
@UnstableApi
@AndroidEntryPoint
class LivePlayerFragment : BasePlayerFragment() {

    override fun setupLiveOverlayChrome(channelName: String?) {
        setupOverlayViews()
        liveTuner.setupLiveOsd()
        bindChannelMeta(channelName)
        // Backdrop covers the black surface until the first frame renders.
        liveOsd?.showZapBackdrop()
    }

    override fun observeLiveEpgAndState() {
        playerView.useController = false
        val epgChannelId = currentEpgChannelId
        val streamIdForEpg = contentId?.takeIf { it.toLongOrNull() != null }
        viewModel.observeEpg(epgChannelId, streamIdForEpg)
        viewModelBinder.observeOverlayState()
        viewModelBinder.observeZapState()
    }

    override fun startLiveZappingAndBrowser() {
        viewModel.initLiveZapping(
            categoryId = liveCategoryId,
            currentStreamId = contentId,
            baseServerUrl = baseServerUrl ?: prefs.getServerUrl(),
            orderedStreamIds = liveChannelIds
        )
        initBrowserOverlay()
    }

    override fun seedLiveEpgOverlay() {
        epgRenderer.seedInitialStateIfNeeded()
        if (liveOsd == null) showEpgOverlay(EpgOverlayMode.COMPACT, pinned = false)
    }

    // P27-c: live-only BACK. Same priority order as the old inline branch; the
    // `contentType == LIVE_TV` guard is implicit here (this subclass is only hosted for live).
    override fun handleLiveBack(): Boolean {
        if (liveOsd?.handleDrawerBack() == true) return true
        if (viewModel.browserState.value.isVisible) {
            viewModel.toggleBrowser(false)
            return true
        }
        if (epgOverlayUi.isPinned || epgOverlayUi.mode != EpgOverlayMode.HIDDEN) {
            hideEpgOverlay()
            return true
        }
        return false
    }

    // P27-d: live+shared exit. Hand the running player back to the EPG guide's mini
    // preview instead of releasing it (seamless shrink-to-mini). Verbatim from the base;
    // `contentType == LIVE_TV` is implicit for this subclass.
    override fun performLiveSharedExitIfNeeded(): Boolean {
        if (!(sharedLiveSession && player != null)) return false
        if (sharedExitInProgress) return true
        sharedExitInProgress = true
        // Snapshot the on-screen frame first (async for SurfaceView), then hand the
        // running player back and leave without animation.
        captureVideoFrame(playerView) { frame ->
            if (isFinishing || isDestroyed) return@captureVideoFrame
            handBackSharedPlayerIfNeeded(frame)
            releasePlayer()
            finish()
            overridePendingTransition(0, 0)
        }
        return true
    }

    private fun handBackSharedPlayerIfNeeded(frame: android.graphics.Bitmap? = null): Boolean {
        if (!sharedLiveSession) return false
        val p = player ?: return false
        playerListener?.let { p.removeListener(it) }
        playerListener = null
        debugListener?.let { p.removeListener(it) }
        debugListener = null
        stopStallMonitor()
        timeoutHandler.removeCallbacks(timeoutRunnable)
        playerView.player = null
        player = null
        LiveSharedPlayer.offer(p, currentUrl, contentId, frame)
        PlaybackDiagnosticsRecorder.record(
            requireContext(),
            "player_handed_back_shared",
            diagnosticsPlaybackFields()
        )
        return true
    }
}
