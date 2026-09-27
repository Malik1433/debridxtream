package com.tvonnet.debridxtreamiptv.player.stabilized

import androidx.media3.common.Timeline
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.LoadControl
import androidx.media3.exoplayer.analytics.PlayerId
import androidx.media3.exoplayer.source.MediaSource
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LivePatienceLoadControlTest {

    private var now = 0L
    private val control = LivePatienceLoadControl(
        DefaultLoadControl.Builder().setBufferDurationsMs(15_000, 30_000, 2_000, 2_000).build(),
        LiveRebufferPatience { now },
    )

    private fun params(bufferedMs: Long, rebuffering: Boolean) = LoadControl.Parameters(
        PlayerId.UNSET, Timeline.EMPTY, MediaSource.MediaPeriodId(Any()),
        0L, bufferedMs * 1000, 1f, true, rebuffering, androidx.media3.common.C.TIME_UNSET,
    )

    @Test
    fun `DefaultLoadControl gets onPrepared, so its per-player state exists`() {
        // Without the explicit forward DefaultLoadControl has no state for the player and throws here.
        control.onPrepared(PlayerId.UNSET)
        assertTrue(control.shouldContinueLoading(params(1_000L, rebuffering = false)))
        control.onReleased(PlayerId.UNSET)
    }

    @Test
    fun `a second stall is held for the cushion and keeps loading meanwhile`() {
        control.onPrepared(PlayerId.UNSET)
        assertTrue(control.shouldStartPlayback(params(2_000L, rebuffering = true)))
        now += 30_000L
        assertFalse(control.shouldStartPlayback(params(2_000L, rebuffering = true)))
        assertTrue(control.shouldContinueLoading(params(2_000L, rebuffering = true)))
        assertTrue(control.shouldStartPlayback(params(5_000L, rebuffering = true)))
        control.onReleased(PlayerId.UNSET)
    }
}
