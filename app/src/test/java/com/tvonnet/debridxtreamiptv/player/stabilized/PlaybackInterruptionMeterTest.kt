package com.tvonnet.debridxtreamiptv.player.stabilized

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PlaybackInterruptionMeterTest {

    private var now = 0L
    private val meter = PlaybackInterruptionMeter { now }

    @Test
    fun `a stop the viewer did not ask for is counted with its length`() {
        meter.onPlaying(); now += 60_000L
        meter.onStopped(viewerStillWantsPlay = true); now += 20_000L
        meter.onPlaying(); now += 60_000L
        assertEquals(1, meter.count)
        assertEquals(20_000L, meter.stoppedMs)
        assertTrue(meter.logLine("live").contains("count=1 stopped_ms=20000 longest_ms=20000 playing_ms=120000 per_hour=30"))
    }

    @Test
    fun `an error and the rebuilt player's buffering are one interruption`() {
        meter.onPlaying(); now += 10_000L
        meter.onStopped(true) // error
        now += 5_000L
        meter.onStopped(true) // the new player buffers before it plays
        now += 10_000L
        meter.onPlaying()
        assertEquals(1, meter.count)
        assertEquals(15_000L, meter.longestMs)
    }

    @Test
    fun `a pause, a zap and a seek are the viewer's own`() {
        meter.onPlaying(); now += 1_000L
        meter.onStopped(viewerStillWantsPlay = false) // pause
        meter.onPlaying(); now += 1_000L
        meter.onUserChange(); meter.onStopped(true) // zap
        now += 3_000L
        meter.onPlaying(); now += 1_000L
        meter.onUserChange(); meter.onStopped(true) // seek
        meter.onPlaying()
        assertEquals(0, meter.count)
    }

    @Test
    fun `our own dropped line is not held against the feed for a minute`() {
        assertTrue(networkRecentlyLost(networkAvailable = false, lastLossAtMs = 0L, nowMs = 1_000_000L))
        assertTrue(networkRecentlyLost(true, lastLossAtMs = 1_000_000L, nowMs = 1_030_000L))
        assertFalse(networkRecentlyLost(true, lastLossAtMs = 1_000_000L, nowMs = 1_000_000L + NETWORK_GRACE_MS))
        assertFalse(networkRecentlyLost(true, lastLossAtMs = 0L, nowMs = 5_000L))
    }
}
