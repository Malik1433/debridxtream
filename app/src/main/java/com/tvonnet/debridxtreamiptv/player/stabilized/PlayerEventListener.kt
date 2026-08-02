package com.tvonnet.debridxtreamiptv.player.stabilized

import android.os.SystemClock
import android.util.Log
import androidx.media3.common.C
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.TrackSelectionOverride
import com.tvonnet.debridxtreamiptv.data.model.ContentType
import com.tvonnet.debridxtreamiptv.debug.PlaybackDiagnosticsRecorder

/**
 * The ExoPlayer `Player.Listener` (Phase P20 of the route to ≤600, fragment-split prep).
 *
 * Moved verbatim out of the 325-line `initializePlayer`: the state-transition brain —
 * READY resets (budget/banner/loader/watchdog), BUFFERING watchdog arming, the
 * ENDED reconnect (live + VOD mid-stream) and series auto-advance, first-frame, track
 * discovery + software-audio auto-select, and the error hand-off to the recovery
 * controller. This is landmine-adjacent state code, so the bodies are byte-identical;
 * only the plumbing (state via [session], collaborators via [activity]) changed.
 *
 * `isSoftwareAudioEnabled` is captured once at listener creation (as it was as a local
 * in `initializePlayer`) so `onTracksChanged` never re-reads prefs from disk.
 */
internal class PlayerEventListener(
    private val activity: BasePlayerFragment,
    private val session: PlayerSessionState,
    private val isSoftwareAudioEnabled: Boolean,
) : Player.Listener {

    private var player: androidx.media3.exoplayer.ExoPlayer?
        get() = activity.player
        set(value) { activity.player = value }

    // ── collaborators / views on the Activity ──
    private val playerView get() = activity.playerView
    private val loaderUi get() = activity.loaderUi
    private val liveOsd get() = activity.liveOsd
    private val viewModel get() = activity.viewModel
    private val recovery get() = activity.recovery
    private val liveTuner get() = activity.liveTuner
    private val seriesController get() = activity.seriesController
    private val addonProxyFailures get() = activity.addonProxyFailures
    private val isControllerVisible get() = activity.isControllerVisible
    private val isFinishing get() = activity.isFinishing
    private val isDestroyed get() = activity.isDestroyed
    private val timeoutHandler get() = activity.timeoutHandler
    private val timeoutRunnable get() = activity.timeoutRunnable
    private val retryHandler get() = activity.retryHandler
    private val blackVideoCheckRunnable get() = activity.blackVideoCheckRunnable
    private val captureRunnable get() = activity.captureRunnable
    private var lastEndedReconnectAtMs: Long
        get() = activity.lastEndedReconnectAtMs
        set(value) { activity.lastEndedReconnectAtMs = value }

    // ── screen state (S1) ──
    private var isSwitching: Boolean by session::isSwitching
    private var lastBufferingStartMs: Long by session::lastBufferingStartMs
    private var isResolvingDebrid: Boolean by session::isResolvingDebrid
    private var startPositionMs: Long by session::startPositionMs
    private val contentType: ContentType? by session::contentType
    private var hasRenderedFirstFrameForCurrentSource: Boolean by session::hasRenderedFirstFrameForCurrentSource
    private val blackVideoFallbackTried: Boolean by session::blackVideoFallbackTried
    private var watchdogExtensions: Int by session::watchdogExtensions
    private var watchdogBufferedPosAtArm: Long by session::watchdogBufferedPosAtArm
    private var retryCount: Int by session::retryCount
    private var audioSinkRecoveryCount: Int by session::audioSinkRecoveryCount
    private var backHideArmed: Boolean by session::backHideArmed
    private var endedReconnects: Int by session::endedReconnects
    private val currentUrl: String? by session::currentUrl
    private var didPlaybackComplete: Boolean by session::didPlaybackComplete
    private var hasAppliedIndexOverride: Boolean by session::hasAppliedIndexOverride
    private val timeoutMs: Long by session::timeoutMs

    // ── forwarders to collaborators kept on the Activity ──
    private fun armWatchdogBaseline() = activity.armWatchdogBaseline()
    private fun maybeUpdateNetworkQuality() = activity.maybeUpdateNetworkQuality()
    private fun maybeMatchDisplayFrameRate() = activity.maybeMatchDisplayFrameRate()
    private fun resetReconnectBudget() = activity.resetReconnectBudget()
    private fun hideReconnectingBanner() = activity.hideReconnectingBanner()
    private fun maybeRecordDirectAddonProxySuccess() = activity.maybeRecordDirectAddonProxySuccess()
    private fun applyTrackIndexOverrides() = activity.applyTrackIndexOverrides()
    private fun canAttemptReconnect(): Boolean = activity.canAttemptReconnect()
    private fun maybeSnapToLiveEdge() = activity.maybeSnapToLiveEdge()
    private fun diagnosticsPlaybackFields() = activity.diagnosticsFields(currentUrl, retryCount)

    override fun onPlaybackStateChanged(playbackState: Int) {
        PlaybackDiagnosticsRecorder.record(
            activity.requireContext(),
            "player_state_changed",
            diagnosticsPlaybackFields() + mapOf(
                "playerState" to PlaybackDiagnosticsRecorder.playerStateName(playbackState),
                "positionMs" to (player?.currentPosition ?: 0L),
                "durationMs" to (player?.duration ?: 0L)
            )
        )
        if (playbackState == Player.STATE_READY) {
            onStateReady()
        } else if (playbackState == Player.STATE_BUFFERING) {
            timeoutHandler.removeCallbacks(timeoutRunnable)
            timeoutHandler.postDelayed(timeoutRunnable, timeoutMs)
            armWatchdogBaseline()
            if (lastBufferingStartMs == 0L) lastBufferingStartMs = SystemClock.elapsedRealtime()
        } else if (playbackState == Player.STATE_ENDED || playbackState == Player.STATE_IDLE) {
            timeoutHandler.removeCallbacks(timeoutRunnable)
            lastBufferingStartMs = 0L
            if (playbackState == Player.STATE_ENDED) {
                onStateEnded()
            }
        }
    }

    private fun onStateReady() {
         isSwitching = false
         // BUFFERING→READY: refresh the session network-quality estimate
         // (QA fix 7) using the passive bandwidth meter, debounced.
         if (lastBufferingStartMs != 0L) maybeUpdateNetworkQuality()
         // Keep the resume target alive while a debrid re-resolve is in flight:
         // clearing it here would make the upcoming seamless switch start from 0.
         if (!isResolvingDebrid && player?.currentPosition ?: 0L > 1000) startPositionMs = 0L
        maybeMatchDisplayFrameRate()
        // Black-video watchdog (VOD): audio can play while the HW decoder
        // renders no frames under tunneling — reinit without tunneling if so.
        if (contentType != ContentType.LIVE_TV &&
            !hasRenderedFirstFrameForCurrentSource && !blackVideoFallbackTried
        ) {
            timeoutHandler.removeCallbacks(blackVideoCheckRunnable)
            timeoutHandler.postDelayed(blackVideoCheckRunnable, BLACK_VIDEO_CHECK_MS)
        }
        timeoutHandler.removeCallbacks(timeoutRunnable)
        watchdogExtensions = 0
        watchdogBufferedPosAtArm = -1L
        retryCount = 0
        audioSinkRecoveryCount = 0
        // A clean READY means recovery succeeded — reset the unified
        // reconnect budget (fix 2) and retire the banner (fix 3).
        resetReconnectBudget()
        hideReconnectingBanner()
        // First playable frame — dissolve the cinematic loader to reveal video.
        loaderUi.hide()
        lastBufferingStartMs = 0L
        addonProxyFailures.clearFailures()
        maybeRecordDirectAddonProxySuccess()
        if (contentType != ContentType.LIVE_TV) {
            updatePlayPauseVisibility(playerView, player?.playWhenReady == true, isControllerVisible)
            backHideArmed = false
            playerView.showController()
        }
    }

    private fun onStateEnded() {
         val nowMs = SystemClock.elapsedRealtime()
         if (nowMs - lastEndedReconnectAtMs > 30_000L) endedReconnects = 0
         val canReconnect = endedReconnects < 3
         // A live stream never legitimately ends — ENDED means the
         // provider dropped the socket. Reconnect instead of
         // silently finishing back to the channel list.
         if (contentType == ContentType.LIVE_TV) {
              onLiveEnded(nowMs, canReconnect)
              return
         }
         // Same for VOD: a mid-stream socket drop reports ENDED
         // long before the real end. Only finish when actually
         // at the end of the content; otherwise resume in place.
         val durationMs = player?.duration ?: 0L
         val positionMs = player?.currentPosition ?: 0L
         val endedMidStream = durationMs > 0L && positionMs < durationMs - 15_000L
         if (endedMidStream && canReconnect && canAttemptReconnect()) {
              endedReconnects++; lastEndedReconnectAtMs = nowMs
              Log.i(
                  "PlayerActivity",
                  "Stream ended ${durationMs - positionMs}ms before content end — resuming ($endedReconnects/3)"
              )
              startPositionMs = positionMs
              currentUrl?.let { liveTuner.performSeamlessSwitch(it) }
              return
         }
         // T1.5 episode continuity. The decision itself is a pure function so it can be
         // unit-tested (see endedActionFor / PlayerEndedActionTest): advance ONLY on a real
         // hasNext (no ghost load past the final episode), and never double-advance or
         // finish() while a next-episode resolve is still in flight.
         val isSeriesLike = contentType == ContentType.SERIES || contentType == ContentType.EPISODE
         when (
              endedActionFor(
                   isSeriesLike = isSeriesLike,
                   isResolvingDebrid = isResolvingDebrid,
                   hasNext = viewModel.seriesPlaylistState.value?.hasNext == true,
                   isNextPromptVisible = activity.isNextEpisodePromptVisible(),
              )
         ) {
              EndedAction.WAIT_FOR_RESOLVE -> return
              EndedAction.ADVANCE_TO_NEXT -> {
                   seriesController.playNextEpisode()
                   return
              }
              EndedAction.COMPLETE_AND_KEEP_PROMPT -> didPlaybackComplete = true
              EndedAction.COMPLETE_AND_FINISH -> {
                   didPlaybackComplete = true
                   activity.finish()
              }
         }
    }

    private fun onLiveEnded(nowMs: Long, canReconnect: Boolean) {
         // Inner cap (endedReconnects) AND the aggregate budget
         // must both allow it; canAttemptReconnect() records the
         // attempt + shows the banner only when it returns true.
         if (canReconnect && canAttemptReconnect()) {
              endedReconnects++; lastEndedReconnectAtMs = nowMs
              Log.i("PlayerActivity", "Live stream ended unexpectedly — reconnecting ($endedReconnects/3)")
              // A just-dropped socket usually refuses an immediate
              // reconnect — give the provider a moment so the FIRST
              // attempt succeeds instead of burning budget slots.
              // Stale-guarded: a zap meanwhile changes currentUrl
              // and this runnable no-ops.
              val urlAtEnded = currentUrl
              retryHandler.postDelayed({ reconnectIfStillOn(urlAtEnded) }, LIVE_ENDED_RECONNECT_DELAY_MS)
         } else {
              recovery.handlePlaybackError(PlaybackException(null, null, PlaybackException.ERROR_CODE_REMOTE_ERROR))
         }
    }

    private fun reconnectIfStillOn(urlAtEnded: String?) {
         if (!isFinishing && !isDestroyed && currentUrl == urlAtEnded) {
              urlAtEnded?.let { liveTuner.performSeamlessSwitch(it) }
         }
    }
    override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) {
        if (contentType != ContentType.LIVE_TV) {
            updatePlayPauseVisibility(playerView, playWhenReady, isControllerVisible)
        } else if (playWhenReady) {
            maybeSnapToLiveEdge()
        }
    }
    override fun onRenderedFirstFrame() {
        hasRenderedFirstFrameForCurrentSource = true
        timeoutHandler.removeCallbacks(blackVideoCheckRunnable)
        liveOsd?.hideZapBackdrop()
        // First rendered frame = the source is genuinely playing; clear the
        // unified reconnect budget (fix 2) and hide the banner (fix 3).
        resetReconnectBudget()
        hideReconnectingBanner()
        maybeRecordDirectAddonProxySuccess()
        PlaybackDiagnosticsRecorder.record(
            activity.requireContext(),
            "first_frame_rendered",
            diagnosticsPlaybackFields() + mapOf("positionMs" to (player?.currentPosition ?: 0L))
        )
    }
    override fun onTrackSelectionParametersChanged(parameters: androidx.media3.common.TrackSelectionParameters) {
        timeoutHandler.removeCallbacks(captureRunnable)
        timeoutHandler.postDelayed(captureRunnable, 1500)
    }

    override fun onTracksChanged(tracks: androidx.media3.common.Tracks) {
        PlaybackDiagnosticsRecorder.record(
            activity.requireContext(),
            "track_discovery",
            diagnosticsPlaybackFields() + trackDiagnosticsFields(tracks)
        )
        if (!hasAppliedIndexOverride) {
            applyTrackIndexOverrides()
            hasAppliedIndexOverride = true
        }

        timeoutHandler.removeCallbacks(captureRunnable)
        timeoutHandler.postDelayed(captureRunnable, 1500)

        if (isSoftwareAudioEnabled) {
            maybeAutoSelectSupportedAudio(tracks)
        }
    }

    private fun maybeAutoSelectSupportedAudio(tracks: androidx.media3.common.Tracks) {
        val audioGroups = tracks.groups.filter { it.type == C.TRACK_TYPE_AUDIO }
        var hasSupportedAudioSelected = false
        var firstSupportedGroup: androidx.media3.common.Tracks.Group? = null
        var hasAudioOverride = false
        for (group in audioGroups) {
            if (player?.trackSelectionParameters?.overrides?.containsKey(group.mediaTrackGroup) == true) {
                hasAudioOverride = true
            }
            if (group.isSupported) {
                if (firstSupportedGroup == null) firstSupportedGroup = group
                if (group.isSelected) { hasSupportedAudioSelected = true; break }
            }
        }
        // Smart fallback fires only when there IS an alternative and the user hasn't chosen.
        val fallbackGroup = if (audioGroups.size > 1) firstSupportedGroup else null
        if (!hasSupportedAudioSelected && fallbackGroup != null && !hasAudioOverride) {
             // LP-B-6: guard the snapshot — the old `?: player?.trackSelectionParameters!!`
             // fallback NPE'd if the player was released while this callback was in flight.
             player?.let { p ->
                 p.trackSelectionParameters = p.trackSelectionParameters.buildUpon()
                     .setOverrideForType(TrackSelectionOverride(fallbackGroup.mediaTrackGroup, 0))
                     .build()
             }
        }
    }
    override fun onPlayerError(error: PlaybackException) {
        isSwitching = false
        timeoutHandler.removeCallbacks(timeoutRunnable)
        recovery.handlePlaybackError(error)
    }

    private companion object {
        // How long audio may play with no video frame before the black-video fallback
        // (reinit without tunneling) kicks in.
        const val BLACK_VIDEO_CHECK_MS = 5_000L
        // A just-dropped provider socket usually needs a moment before it accepts a
        // new connection — an immediate retry mostly fails and burns a budget slot.
        const val LIVE_ENDED_RECONNECT_DELAY_MS = 1500L
    }
}
