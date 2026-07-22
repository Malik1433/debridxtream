package com.tvonnet.debridxtreamiptv.player.stabilized

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Freezes the stall / video-freeze DETECTION extracted in P15. This logic decides
 * when playback gets torn down and rebuilt, so the thresholds and the strike counting
 * are exactly what must not drift.
 */
class PlayerStallDetectorTest {

    private val threshold = 5_000L
    private val strikes = 3

    @Test
    fun `advancing position is always progress`() {
        val d = PlayerStallDetector()
        d.reset(0L, 0L)

        assertEquals(
            StallVerdict.PROGRESSING,
            d.onTick(true, positionMs = 1_000L, nowMs = 3_000L, thresholdMs = threshold, requiredStrikes = strikes)
        )
        assertEquals(0, d.strikeCount)
    }

    @Test
    fun `a stuck position only strikes after the threshold elapses`() {
        val d = PlayerStallDetector()
        d.reset(1_000L, 0L)

        // Same position, but only 3s in — too early to call it a stall.
        assertEquals(
            StallVerdict.IDLE,
            d.onTick(true, 1_000L, 3_000L, threshold, strikes)
        )
        assertEquals(0, d.strikeCount)

        assertEquals(
            StallVerdict.WARNING,
            d.onTick(true, 1_000L, 6_000L, threshold, strikes)
        )
        assertEquals(1, d.strikeCount)
    }

    @Test
    fun `it takes the required strikes to escalate, and the count resets after`() {
        val d = PlayerStallDetector()
        d.reset(1_000L, 0L)

        assertEquals(StallVerdict.WARNING, d.onTick(true, 1_000L, 6_000L, threshold, strikes))
        assertEquals(StallVerdict.WARNING, d.onTick(true, 1_000L, 12_000L, threshold, strikes))
        assertEquals(StallVerdict.STALLED, d.onTick(true, 1_000L, 18_000L, threshold, strikes))
        assertEquals("strikes reset so recovery starts clean", 0, d.strikeCount)
    }

    @Test
    fun `progress in between wipes the accumulated strikes`() {
        val d = PlayerStallDetector()
        d.reset(1_000L, 0L)

        assertEquals(StallVerdict.WARNING, d.onTick(true, 1_000L, 6_000L, threshold, strikes))
        assertEquals(StallVerdict.PROGRESSING, d.onTick(true, 2_000L, 9_000L, threshold, strikes))
        assertEquals(0, d.strikeCount)
        // Back to a fresh window: the next stuck tick is only strike 1 again.
        assertEquals(StallVerdict.WARNING, d.onTick(true, 2_000L, 15_000L, threshold, strikes))
        assertEquals(1, d.strikeCount)
    }

    @Test
    fun `position zero never stalls — that is a load problem, not a stall`() {
        val d = PlayerStallDetector()
        d.reset(0L, 0L)

        assertEquals(StallVerdict.IDLE, d.onTick(true, 0L, 60_000L, threshold, strikes))
        assertEquals(0, d.strikeCount)
    }

    @Test
    fun `a paused or buffering player is idle and re-arms`() {
        val d = PlayerStallDetector()
        d.reset(1_000L, 0L)
        d.onTick(true, 1_000L, 6_000L, threshold, strikes)
        assertEquals(1, d.strikeCount)

        assertEquals(StallVerdict.IDLE, d.onTick(false, 1_000L, 12_000L, threshold, strikes))
        assertEquals("pausing clears the strikes", 0, d.strikeCount)
    }

    // NOTE: nowMs is SystemClock.elapsedRealtime() in production, never 0 — the
    // detector uses 0 as its "no baseline yet" sentinel, so these use real-ish stamps.
    @Test
    fun `video freeze needs no new frame for the whole window`() {
        val f = VideoFreezeDetector()

        assertFalse(f.onTick(renderedFrames = 100, nowMs = 1_000L, freezeThresholdMs = 4_000L))
        // Same frame count, but still inside the window.
        assertFalse(f.onTick(100, 3_000L, 4_000L))
        assertTrue("no new frame for 4s", f.onTick(100, 6_000L, 4_000L))
    }

    @Test
    fun `a new frame keeps resetting the freeze window`() {
        val f = VideoFreezeDetector()

        assertFalse(f.onTick(100, 1_000L, 4_000L))
        assertFalse(f.onTick(101, 4_000L, 4_000L))
        assertFalse("window restarted at 4s", f.onTick(101, 7_000L, 4_000L))
        assertTrue(f.onTick(101, 9_000L, 4_000L))
    }

    @Test
    fun `after reporting a freeze the detector re-arms for the next window`() {
        val f = VideoFreezeDetector()
        f.onTick(100, 1_000L, 4_000L)
        assertFalse(f.onTick(100, 3_000L, 4_000L))
        assertTrue(f.onTick(100, 8_000L, 4_000L))

        // Re-armed: the same stuck count is a new baseline, not an instant second freeze.
        assertFalse(f.onTick(100, 9_000L, 4_000L))
        assertFalse(f.onTick(100, 11_000L, 4_000L))
        assertTrue(f.onTick(100, 14_000L, 4_000L))
    }

    @Test
    fun `losing the video format forgets the frame baseline`() {
        val f = VideoFreezeDetector()
        f.onTick(100, 1_000L, 4_000L)

        f.onNoVideoFormat()

        // 100 now looks like a brand-new count, so this tick is progress, not a freeze.
        assertFalse(f.onTick(100, 9_000L, 4_000L))
    }
}
