package com.tvonnet.debridxtreamiptv.player.stabilized

import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
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
    }

    fun stopStallMonitor() = stallHandler.removeCallbacks(stallRunnable)

    private fun checkForStall() {
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
            showToast("Recovering video...")
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
