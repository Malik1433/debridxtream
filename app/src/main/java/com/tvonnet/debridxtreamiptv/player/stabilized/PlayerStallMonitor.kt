package com.tvonnet.debridxtreamiptv.player.stabilized

import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import com.tvonnet.debridxtreamiptv.R
import com.tvonnet.debridxtreamiptv.data.model.ContentType
import com.tvonnet.debridxtreamiptv.debug.PlaybackDiagnosticsRecorder
import com.tvonnet.debridxtreamiptv.util.DeviceProfile

/**
 * The stall + video-freeze watchdog (Phase P22, fragment-split prep).
 *
 * Moved verbatim out of [PlayerActivity]: the 5-second polling loop
 * ([startStallMonitor]/[stopStallMonitor]) plus its verdict handlers —
 * [checkForStall] (position not advancing while READY → recovery), and
 * [checkVideoRenderProgress] (audio plays but no rendered frames → live re-prepare
 * or one-shot tunneling-off reinit, both landmines). The [PlayerStallDetector] and
 * [VideoFreezeDetector] state machines are owned here; reactions still hand off to
 * the recovery controller / live tuner via [activity]. Bodies are byte-identical.
 */
internal class PlayerStallMonitor(
    private val activity: BasePlayerFragment,
    private val session: PlayerSessionState,
) {

    private val stallHandler = Handler(Looper.getMainLooper())
    private val stallDetector = PlayerStallDetector()
    private val freezeDetector = VideoFreezeDetector()
    private val stallRunnable = object : Runnable {
        override fun run() {
            checkForStall()
            stallHandler.postDelayed(this, 3000)
        }
    }
    private val fastWedgeRunnable = Runnable { checkFastWedge() }
    // The playback clock as first seen READY for the CURRENT source; -1 = not sampled yet.
    private var fastWedgeSampleMs = -1L

    private var player: ExoPlayer?
        get() = activity.player
        set(value) { activity.player = value }

    private val recovery get() = activity.recovery
    private val liveTuner get() = activity.liveTuner
    private val retryHandler get() = activity.retryHandler

    private val contentType: ContentType? by session::contentType
    private val playbackSource: PlaybackSource by session::playbackSource
    private val directDebridPlayback: Boolean by session::directDebridPlayback
    private var disableTunnelingForSession: Boolean by session::disableTunnelingForSession
    private val currentUrl: String? by session::currentUrl
    private var lastFreezeRecoveryUrl: String? by session::lastFreezeRecoveryUrl
    private var lastFreezeRecoveryAtMs: Long by session::lastFreezeRecoveryAtMs
    private var liveFreezeReprepares: Int by session::liveFreezeReprepares
    private var startPositionMs: Long by session::startPositionMs
    private val retryCount: Int by session::retryCount

    private fun canAttemptReconnect(): Boolean = activity.canAttemptReconnect()
    private fun showToast(message: String) = activity.showToast(message)
    private fun initializePlayer(streamUrl: String) = activity.initializePlayer(streamUrl)
    private fun diagnosticsPlaybackFields() = activity.diagnosticsFields(currentUrl, retryCount)

    fun startStallMonitor() {
        stallDetector.reset(player?.currentPosition ?: 0L, SystemClock.elapsedRealtime())
        freezeDetector.reset()
        stallHandler.removeCallbacks(stallRunnable); stallHandler.postDelayed(stallRunnable, 5000)
        // Fast path for the wedged-HDMI freeze: a READY player whose position has not
        // moved AT ALL a few seconds after READY is already pathological (network pauses
        // go through BUFFERING, not READY), so the route change doesn't have to wait for
        // the generic 12s+strikes stall verdict. Armed on every start, engaged or not —
        // the escape route can wedge too (2026-09-15), and then the way out is BACK.
        // The clock is sampled by the check itself once the player is READY - never here:
        // a live zap calls this BEFORE prepare(), so a baseline read now is the OLD
        // channel's position, and every zap looked frozen (2026-09-21, "channel stops").
        stallHandler.removeCallbacks(fastWedgeRunnable)
        fastWedgeSampleMs = -1L
        stallHandler.postDelayed(fastWedgeRunnable, FAST_WEDGE_CHECK_MS)
    }

    fun stopStallMonitor() {
        stallHandler.removeCallbacks(stallRunnable)
        stallHandler.removeCallbacks(fastWedgeRunnable)
    }

    private fun checkFastWedge() {
        val p = player ?: return
        if (!p.playWhenReady || p.playbackState != Player.STATE_READY) return
        val pos = p.currentPosition
        if (fastWedgeSampleMs < 0L) {
            // First READY observation of this source: sample now, judge after a second window.
            fastWedgeSampleMs = pos
            stallHandler.postDelayed(fastWedgeRunnable, FAST_WEDGE_SAMPLE_MS)
            return
        }
        if (pos - fastWedgeSampleMs >= FAST_WEDGE_MIN_PROGRESS_MS) return
        tryAudioWedgeEscape(p, pos)
    }

    private fun checkForStall() {
        PlaybackDiagnosticsRecorder.maybeRecordMemorySample(activity.requireContext()) // G1: throttled to 30s inside
        val p = player ?: return
        val now = SystemClock.elapsedRealtime()
        val isLowRamDevice = DeviceProfile.isLowRamDevice(activity.requireContext())
        val stallThresholdMs = if (isLowRamDevice) LOW_RAM_STALL_THRESHOLD_MS else STALL_THRESHOLD_MS
        val requiredStrikes = readyStallRequiredStrikes(isLowRamDevice)
        val currentPos = p.currentPosition

        when (
            stallDetector.onTick(
                isActivelyPlaying = p.playWhenReady && p.playbackState == Player.STATE_READY,
                positionMs = currentPos,
                nowMs = now,
                thresholdMs = stallThresholdMs,
                requiredStrikes = requiredStrikes
            )
        ) {
            StallVerdict.PROGRESSING -> checkVideoRenderProgress(p, now)
            StallVerdict.IDLE -> Unit
            StallVerdict.WARNING -> PlaybackDiagnosticsRecorder.record(
                activity.requireContext(),
                "stall_warning",
                diagnosticsPlaybackFields() + mapOf(
                    "positionMs" to currentPos,
                    "stallThresholdMs" to stallThresholdMs,
                    "stallStrikeCount" to stallDetector.strikeCount,
                    "requiredStrikes" to requiredStrikes
                )
            )
            StallVerdict.STALLED -> {
                if (tryAudioWedgeEscape(p, currentPos)) return
                PlaybackDiagnosticsRecorder.record(
                    activity.requireContext(),
                    "stall_triggered",
                    diagnosticsPlaybackFields() + mapOf(
                        "positionMs" to currentPos,
                        "stallThresholdMs" to stallThresholdMs
                    )
                )
                recovery.handlePlaybackError(PlaybackException(null, null, PlaybackException.ERROR_CODE_REMOTE_ERROR))
            }
        }
    }

    /**
     * READY + position frozen + mono/stereo PCM audio = a wedged HDMI output thread
     * (device-verified 2026-08-27: the thread blocks in write() and the playback clock
     * stops on frame 1 with NO exception — see [AudioWedgeEscape]).
     *
     * Not engaged: engage the 5.1 upmix escape and rebuild, so audio reopens on the
     * DIRECT HAL output. Engaged and STILL frozen: that single DIRECT output is the one
     * that has wedged now (2026-09-15 capture), so ask the primary mixer whether it is
     * consuming again — if so release the escape and rebuild back onto stereo; if not,
     * both HDMI routes are dead and only the TV off/on the message asks for will help.
     */
    private fun tryAudioWedgeEscape(p: ExoPlayer, currentPos: Long): Boolean {
        val channels = p.audioFormat?.channelCount ?: return false
        if (channels > 2) return false
        if (!AudioWedgeEscape.engaged) {
            AudioWedgeEscape.engage()
            Log.w(
                "PlayerActivity",
                "READY-stall with ${channels}ch audio — engaging 5.1 upmix escape (wedged primary HDMI mixer suspected)"
            )
            recordWedge("audio_wedge_escape", currentPos, channels)
            // Deliberately SILENT (owner decision 2026-08-30): the escape repairs audio in
            // ~1-2s, so a toast only advertises a problem the viewer barely experiences.
            // The log line + audio_wedge_escape diagnostic remain the audit trail.
            rebuildForAudioRoute(currentPos)
            return true
        }
        if (wedgeRouteProbePending) return true
        wedgeRouteProbePending = true
        AudioWedgeEscape.probePrimaryAsync(activity.requireContext()) { primaryConsumes ->
            wedgeRouteProbePending = false
            if (player !== p) return@probePrimaryAsync // a newer player took over meanwhile
            when {
                primaryConsumes && AudioWedgeEscape.release() -> {
                    Log.w(
                        "PlayerActivity",
                        "READY-stall ON the 5.1 escape route — primary mixer consuming again, releasing the escape and rebuilding"
                    )
                    recordWedge("audio_wedge_escape_released", currentPos, channels)
                    rebuildForAudioRoute(currentPos)
                }
                primaryConsumes -> {
                    // Released less than five minutes ago and frozen again: the probe and
                    // the playback disagree, so stop flipping and use the ordinary path.
                    recordWedge("audio_wedge_flip_refused", currentPos, channels)
                    recovery.handlePlaybackError(PlaybackException(null, null, PlaybackException.ERROR_CODE_REMOTE_ERROR))
                }
                else -> {
                    Log.w("PlayerActivity", "READY-stall ON the 5.1 escape route and the primary mixer is not consuming either — both HDMI routes wedged")
                    recordWedge("audio_wedge_both_routes", currentPos, channels)
                    player?.release(); player = null
                    activity.handleTerminalPlaybackFailure(activity.getString(R.string.c_audio_route_wedged))
                }
            }
        }
        return true
    }

    private var wedgeRouteProbePending = false

    private fun recordWedge(event: String, currentPos: Long, channels: Int) = PlaybackDiagnosticsRecorder.record(
        activity.requireContext(),
        event,
        diagnosticsPlaybackFields() + mapOf("positionMs" to currentPos, "audioChannels" to channels)
    )

    private fun rebuildForAudioRoute(currentPos: Long) {
        if (contentType != ContentType.LIVE_TV && currentPos > 1000L) startPositionMs = currentPos
        player?.release(); player = null
        retryHandler.postDelayed({ currentUrl?.let { initializePlayer(it) } }, 250L)
    }

    /**
     * Detects "audio plays, video frozen": playback position advances but the video
     * decoder stops producing rendered frames (seen on MTK Fire TV HEVC live channels
     * — GuiExt alloc errors leave the tunneled decoder outputting nothing). First
     * occurrence re-initializes the player with tunneling disabled for the rest of
     * this session; repeats fall through to the normal error/retry path.
     */
    private fun checkVideoRenderProgress(p: ExoPlayer, now: Long) {
        if (p.videoFormat == null) { freezeDetector.onNoVideoFormat(); return }
        val counters = p.videoDecoderCounters ?: return
        counters.ensureUpdated()
        val rendered = counters.renderedOutputBufferCount
        val freezeThresholdMs =
            if (contentType == ContentType.LIVE_TV) LIVE_VIDEO_FREEZE_THRESHOLD_MS else VIDEO_FREEZE_THRESHOLD_MS
        if (!freezeDetector.onTick(rendered, now, freezeThresholdMs)) return

        PlaybackDiagnosticsRecorder.record(
            activity.requireContext(),
            "video_freeze_detected",
            diagnosticsPlaybackFields() + mapOf(
                "positionMs" to p.currentPosition,
                "renderedFrames" to rendered,
                "tunnelingDisabledRetry" to !disableTunnelingForSession
            )
        )
        if (contentType == ContentType.LIVE_TV) {
            recoverLiveFreeze(rendered, now)
            return
        }

        recoverVodFreeze(p)
    }

    // Live freeze = usually broken PTS in the provider's TS mux, not the
    // decoder. Re-preparing the same URL restarts the timestamp adjuster
    // from the current live data, past the discontinuity.
    private fun recoverLiveFreeze(rendered: Int, now: Long) {
        if (currentUrl != lastFreezeRecoveryUrl || now - lastFreezeRecoveryAtMs > 60_000L) {
            liveFreezeReprepares = 0
            lastFreezeRecoveryUrl = currentUrl
        }
        // Inner cap (liveFreezeReprepares) AND the aggregate budget (fix 2) must
        // both allow it; canAttemptReconnect() records the attempt + shows the
        // banner (fix 3) only when it returns true.
        if (liveFreezeReprepares < MAX_LIVE_FREEZE_REPREPARES && canAttemptReconnect()) {
            liveFreezeReprepares++
            lastFreezeRecoveryAtMs = now
            Log.i(
                "PlayerActivity",
                "Live video frozen (rendered=$rendered) — re-preparing stream, attempt $liveFreezeReprepares/$MAX_LIVE_FREEZE_REPREPARES"
            )
            currentUrl?.let { liveTuner.performSeamlessSwitch(it) }
        } else {
            recovery.handlePlaybackError(PlaybackException(null, null, PlaybackException.ERROR_CODE_REMOTE_ERROR))
        }
    }

    // VOD freeze: first strike rebuilds the player with tunneling off (the proven
    // 4K "audio plays, video black" recovery); a second strike escalates to the
    // normal error path.
    private fun recoverVodFreeze(p: ExoPlayer) {
        if (!disableTunnelingForSession) {
            disableTunnelingForSession = true
            Log.w("PlayerActivity", "Video frozen while audio playing — reinitializing without tunneling")
            showToast(activity.getString(R.string.c_recovering_video))
            if (contentType != ContentType.LIVE_TV && p.currentPosition > 1000L) startPositionMs = p.currentPosition
            player?.release(); player = null
            retryHandler.postDelayed({ currentUrl?.let { initializePlayer(it) } }, 250L)
        } else {
            recovery.handlePlaybackError(PlaybackException(null, null, PlaybackException.ERROR_CODE_REMOTE_ERROR))
        }
    }

    private fun readyStallRequiredStrikes(isLowRamDevice: Boolean): Int {
        if (playbackSource != PlaybackSource.DEBRID) {
            return when {
                contentType == ContentType.LIVE_TV -> LIVE_READY_STALL_STRIKES
                isLowRamDevice -> LOW_RAM_STALL_STRIKES
                else -> NON_DEBRID_READY_STALL_STRIKES
            }
        }
        if (directDebridPlayback) {
            return if (isLowRamDevice) DIRECT_DEBRID_LOW_RAM_STALL_STRIKES else DIRECT_DEBRID_READY_STALL_STRIKES
        }
        return DEBRID_READY_STALL_STRIKES
    }

    private companion object {
        // Fast wedge check: 3s after READY with <250ms of progress = frozen clock.
        const val FAST_WEDGE_CHECK_MS = 3000L
        /** Window between the READY sample and the verdict. */
        const val FAST_WEDGE_SAMPLE_MS = 1500L
        const val FAST_WEDGE_MIN_PROGRESS_MS = 250L
        const val VIDEO_FREEZE_THRESHOLD_MS = 8000L
        // Live viewers zap within seconds — catch a frozen first frame fast.
        const val LIVE_VIDEO_FREEZE_THRESHOLD_MS = 4000L
        const val MAX_LIVE_FREEZE_REPREPARES = 2
        const val STALL_THRESHOLD_MS = 12000L
        const val LOW_RAM_STALL_THRESHOLD_MS = 15000L
        const val LOW_RAM_STALL_STRIKES = 2
        // M3 parity: IPTV VOD gets the same multi-strike tolerance as Debrid so a
        // single transiently-slow 12s window no longer forces a full teardown.
        const val NON_DEBRID_READY_STALL_STRIKES = 3
        // Live recovers faster than VOD (frozen live edge), but no single-strike
        // hair-trigger either.
        const val LIVE_READY_STALL_STRIKES = 2
        const val DEBRID_READY_STALL_STRIKES = 3
        const val DIRECT_DEBRID_READY_STALL_STRIKES = 4
        const val DIRECT_DEBRID_LOW_RAM_STALL_STRIKES = 3
    }
}
