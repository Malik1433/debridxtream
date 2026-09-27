package com.tvonnet.debridxtreamiptv.player.stabilized

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LiveCushionPolicyTest {

    private var now = 0L
    private val patience = LiveRebufferPatience { now }

    /** One whole stall: the first check opens it, [bufferedMs] then decides. */
    private fun stall(bufferedMs: Long, baseSaysGo: Boolean = true) = patience.shouldResume(bufferedMs, baseSaysGo)

    @Test
    fun `the first stall resumes on the player's own threshold`() {
        assertTrue(stall(bufferedMs = 2_000L))
    }

    @Test
    fun `a second stall within three minutes waits for five seconds of buffer`() {
        assertTrue(stall(2_000L))
        now += 60_000L
        assertFalse(stall(2_000L))
        assertTrue(patience.isWaitingFor(2_000L))
        assertTrue(stall(5_000L))
        assertFalse(patience.isWaitingFor(5_000L))
    }

    @Test
    fun `a third stall waits for eight seconds`() {
        stall(2_000L); now += 30_000L
        stall(9_000L); now += 30_000L
        assertFalse(stall(6_000L))
        assertTrue(stall(8_000L))
    }

    @Test
    fun `stalls older than the window are forgotten`() {
        stall(2_000L)
        now += LiveRebufferPatience.STALL_WINDOW_MS + 1
        assertTrue(stall(2_000L))
    }

    @Test
    fun `a feed that cannot fill the cushion plays after the longest wait`() {
        stall(2_000L); now += 10_000L
        assertFalse(stall(3_000L))
        now += LiveRebufferPatience.MAX_WAIT_MS
        assertFalse(patience.isWaitingFor(3_000L))
        assertTrue(stall(3_000L))
    }

    @Test
    fun `the player's own no still wins`() {
        stall(2_000L); now += 10_000L
        assertFalse(stall(9_000L, baseSaysGo = false))
    }

    @Test
    fun `a new stream starts the count afresh, the same stream keeps it`() {
        patience.onStream("a")
        stall(2_000L); now += 10_000L
        patience.onStream("a")
        assertFalse(stall(2_000L))
        patience.onStream("b")
        now += 1_000L
        assertTrue(stall(2_000L))
    }

    @Test
    fun `slow-fill starts below five seconds and stops at eight`() {
        val fill = LiveSlowFillPolicy()
        assertEquals(1f, fill.speedFor(6_000L))
        assertEquals(LiveSlowFillPolicy.SLOW_SPEED, fill.speedFor(4_000L))
        assertEquals(LiveSlowFillPolicy.SLOW_SPEED, fill.speedFor(7_000L))
        assertEquals(1f, fill.speedFor(8_000L))
        assertEquals(1f, fill.speedFor(6_000L))
    }

    @Test
    fun `a reconnect that re-prepares the same stream counts as a stall`() {
        patience.onStream("a")
        assertTrue(patience.shouldStart(2_000L, rebuffering = false, baseSaysGo = true)) // first start
        now += 20_000L
        patience.expectRecoveryStart()
        assertTrue(patience.shouldStart(2_000L, rebuffering = false, baseSaysGo = true)) // re-prepare: stall 1
        now += 40_000L
        patience.expectRecoveryStart()
        assertFalse(patience.shouldStart(2_000L, rebuffering = false, baseSaysGo = true)) // stall 2: hold
        assertTrue(patience.shouldStart(5_000L, rebuffering = false, baseSaysGo = true))
    }

    @Test
    fun `the first start of a new stream is never held`() {
        patience.onStream("a")
        patience.shouldStart(2_000L, rebuffering = false, baseSaysGo = true)
        now += 10_000L
        patience.shouldStart(2_000L, rebuffering = true, baseSaysGo = true)
        patience.onStream("b")
        assertTrue(patience.shouldStart(1_000L, rebuffering = false, baseSaysGo = true))
    }

    @Test
    fun `the viewer opening the same channel again is not a stall`() {
        patience.onStream("a")
        repeat(4) {
            assertTrue(patience.shouldStart(1_000L, rebuffering = false, baseSaysGo = true))
            now += 10_000L
        }
    }
}
