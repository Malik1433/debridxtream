package com.tvonnet.debridxtreamiptv.player.stabilized

import androidx.media3.common.util.UnstableApi
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
}
