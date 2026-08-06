package com.tvonnet.debridxtreamiptv.player.stabilized

import android.view.GestureDetector
import android.view.KeyEvent
import android.view.MotionEvent
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import com.tvonnet.debridxtreamiptv.R
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
        attachTouchGesturesIfMobile()
    }

    /**
     * M9: the phone's equivalents of the two things a remote does here — OK shows the chrome, and
     * UP/DOWN change channel. Reported from a real handset: tapping the video did nothing and
     * there was no way to zap at all, because this screen only ever listened for keys.
     *
     * It deliberately SYNTHESISES the same key events rather than calling the handlers directly.
     * Everything downstream — the zap debouncer, the warm-zap adopt path, the OSD's own show and
     * auto-hide — is already wired to those keys and has been device-tested for months; routing a
     * gesture through a second, parallel path is how the two drift apart.
     */
    /** Set only in mobile mode; the Activity feeds every touch through it. */
    private var touchGestures: GestureDetector? = null

    override fun hostTouchEvent(event: MotionEvent) {
        touchGestures?.onTouchEvent(event)
    }

    private fun attachTouchGesturesIfMobile() {
        if (resources.getBoolean(R.bool.ui_uses_dpad_focus)) return
        if (touchGestures != null) return

        touchGestures = GestureDetector(
            requireContext(),
            object : GestureDetector.SimpleOnGestureListener() {
                override fun onSingleTapUp(e: MotionEvent): Boolean {
                    sendKey(KeyEvent.KEYCODE_DPAD_CENTER)
                    return true
                }

                override fun onFling(
                    e1: MotionEvent?,
                    e2: MotionEvent,
                    velocityX: Float,
                    velocityY: Float,
                ): Boolean {
                    val start = e1 ?: return false
                    val dy = e2.y - start.y
                    val dx = e2.x - start.x
                    // Vertical only: a horizontal fling is a seek gesture elsewhere and must not
                    // change channel by accident.
                    if (kotlin.math.abs(dy) < kotlin.math.abs(dx)) return false
                    if (kotlin.math.abs(dy) < MIN_ZAP_FLING_PX) return false
                    // Swipe UP = next channel, matching the remote's UP.
                    sendKey(if (dy < 0) KeyEvent.KEYCODE_DPAD_UP else KeyEvent.KEYCODE_DPAD_DOWN)
                    return true
                }
            }
        )

    }

    private fun sendKey(keyCode: Int) {
        val activity = activity ?: return
        activity.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, keyCode))
        activity.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_UP, keyCode))
    }

    private companion object {
        /** Below this a vertical drag is a stray finger, not a deliberate zap. */
        const val MIN_ZAP_FLING_PX = 120f
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

    // P27-e: warm hand-off from the Live TV EPG guide (LP-ADOPT). The guide's preview player
    // is built with the SAME tuned engine as a cold start (PreviewPlayerPanel: ffmpeg SW-audio
    // renderer, low-RAM LoadControl/OOM guard, 429 cool-off policy), so we ADOPT the already-
    // running instance instead of reconnecting — keeps the single provider connection (no
    // release→reconnect → no 403 storm on max_connections=1 accounts) while fullscreen still
    // runs a fully tuned player. We only take over audio focus (the muted preview never held it)
    // and cover our surface with the guide's last frame until we render our own — never black.
    override fun adoptSharedLivePlayerIfPresent(streamUrl: String): Boolean {
        val adopted = (if (sharedLiveSession) LiveSharedPlayer.adopt(streamUrl) else null) ?: return false
        didPlaybackComplete = false
        hasAppliedIndexOverride = false
        timeoutHandler.removeCallbacks(timeoutRunnable)
        hasRenderedFirstFrameForCurrentSource = true
        frameRateMatchedForCurrentSource = false
        player = adopted.also {
            playerView.player = it
            it.volume = 1f
            // The preview built the player muted with NO audio-focus handling (browsing the
            // guide must not duck other apps). Fullscreen owns audio now — take focus at runtime.
            it.setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(C.USAGE_MEDIA)
                    .setContentType(C.AUDIO_CONTENT_TYPE_MOVIE)
                    .build(),
                /* handleAudioFocus= */ true
            )
        }
        PlaybackDiagnosticsRecorder.record(
            requireContext(),
            "player_adopted_shared_tuned",
            diagnosticsPlaybackFields(streamUrl, null)
        )
        // Cover our surface with the guide's last rendered frame until we draw our own.
        LiveSharedPlayer.takeFrame()?.let { showAdoptedCoverFrame(requireActivity(), it, player, timeoutHandler) }
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
