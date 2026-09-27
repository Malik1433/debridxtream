package com.tvonnet.debridxtreamiptv.player.stabilized

import android.util.Log
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player
import androidx.media3.common.Timeline
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.LoadControl
import androidx.media3.exoplayer.analytics.PlayerId
import androidx.media3.exoplayer.source.MediaSource
import androidx.media3.exoplayer.upstream.Allocator
import androidx.media3.exoplayer.source.TrackGroupArray
import androidx.media3.exoplayer.trackselection.ExoTrackSelection

/**
 * A live player's own LoadControl, with [LiveRebufferPatience] deciding when a rebuffer may end.
 * Everything else is the wrapped DefaultLoadControl, unchanged.
 *
 * It also keeps loading while it holds a rebuffer: Media3 fails a player that is buffering and
 * not loading for 4 s ("Playback stuck buffering and not loading"), and on a low-RAM box the byte
 * cap could otherwise stop the loader short of the cushion being waited for.
 */
internal class LivePatienceLoadControl(
    private val delegate: LoadControl,
    private val patience: LiveRebufferPatience,
) : LoadControl {

    // Every call Media3 makes, forwarded explicitly: Kotlin's `by` delegation skips Java default
    // methods, and DefaultLoadControl keeps per-player state that onPrepared(PlayerId) creates.
    override fun getAllocator(): Allocator = delegate.allocator
    override fun onPrepared(playerId: PlayerId) = delegate.onPrepared(playerId)
    override fun onStopped(playerId: PlayerId) = delegate.onStopped(playerId)
    override fun onReleased(playerId: PlayerId) = delegate.onReleased(playerId)
    override fun getBackBufferDurationUs(playerId: PlayerId): Long = delegate.getBackBufferDurationUs(playerId)
    override fun retainBackBufferFromKeyframe(playerId: PlayerId): Boolean = delegate.retainBackBufferFromKeyframe(playerId)
    override fun shouldContinuePreloading(timeline: Timeline, mediaPeriodId: MediaSource.MediaPeriodId, bufferedDurationUs: Long) =
        delegate.shouldContinuePreloading(timeline, mediaPeriodId, bufferedDurationUs)

    override fun onTracksSelected(
        parameters: LoadControl.Parameters,
        trackGroups: TrackGroupArray,
        trackSelections: Array<out ExoTrackSelection?>,
    ) {
        patience.onStream(streamKey(parameters))
        delegate.onTracksSelected(parameters, trackGroups, trackSelections)
    }

    override fun shouldStartPlayback(parameters: LoadControl.Parameters): Boolean {
        val baseSaysGo = delegate.shouldStartPlayback(parameters)
        if (!parameters.rebuffering) return baseSaysGo
        return patience.shouldResume(parameters.bufferedDurationUs / 1000, baseSaysGo)
    }

    override fun shouldContinueLoading(parameters: LoadControl.Parameters): Boolean =
        delegate.shouldContinueLoading(parameters) || patience.isWaitingFor(parameters.bufferedDurationUs / 1000)

    /** The stream being played, so a zap on a reused player starts the stall count afresh. Never logged. */
    private fun streamKey(parameters: LoadControl.Parameters): Int? = runCatching {
        val period = parameters.timeline.getPeriodByUid(parameters.mediaPeriodId.periodUid, Timeline.Period())
        parameters.timeline.getWindow(period.windowIndex, Timeline.Window())
            .mediaItem.localConfiguration?.uri?.toString()?.hashCode()
    }.getOrNull()

    companion object {
        /** Wraps [delegate] and logs when a stall asks for a bigger cushion. */
        fun wrap(delegate: LoadControl, patience: LiveRebufferPatience): LoadControl {
            patience.onPatient = { stalls, targetMs ->
                Log.i("PlayerActivity", "live cushion: stall $stalls in 3 min, waiting for ${targetMs / 1000}s of buffer")
            }
            return LivePatienceLoadControl(delegate, patience)
        }
    }
}

/**
 * Applies [LiveSlowFillPolicy] to a live player, from the stall monitor's 3 s tick.
 *
 * Some audio paths cannot change speed at all - tunneled playback and passthrough (AC3/E-AC3 to
 * the TV) - and Media3 then quietly keeps 1.0x. That is detected (asked for 0.97, still 1.0 a tick
 * later), logged once, and slow-fill stays off for that player: nothing breaks, it just does not help.
 */
internal class LiveSlowFill {
    private val policy = LiveSlowFillPolicy()
    private var player: ExoPlayer? = null
    private var requested = LiveSlowFillPolicy.NORMAL_SPEED
    private var unsupported = false

    fun onTick(p: ExoPlayer, isLive: Boolean) {
        if (p !== player) {
            player = p
            policy.reset()
            requested = LiveSlowFillPolicy.NORMAL_SPEED
            unsupported = false
        }
        if (!isLive || unsupported) return
        if (requested != LiveSlowFillPolicy.NORMAL_SPEED && p.playbackParameters.speed == LiveSlowFillPolicy.NORMAL_SPEED) {
            unsupported = true
            Log.i("PlayerActivity", "live cushion: slow-fill unavailable on this audio path (tunneled or passthrough)")
            return
        }
        if (!p.playWhenReady || p.playbackState != Player.STATE_READY) return
        val bufferedMs = p.totalBufferedDuration
        val target = policy.speedFor(bufferedMs)
        if (target == requested) return
        requested = target
        p.setPlaybackParameters(PlaybackParameters(target))
        Log.i("PlayerActivity", "live cushion: speed ${"%.2f".format(target)} at buffer ${bufferedMs / 1000}s")
    }
}
