package com.tvonnet.debridxtreamiptv.player.stabilized

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** C2: when a Live outage started - the FIRST stall, not the last retry. */
class LiveOutageClockTest {

    private var now = 1_000L
    private val clock = LiveOutageClock { now }

    @Test
    fun `playing with no stall reports nothing`() = assertNull(clock.onPlaying())

    @Test
    fun `the outage starts at the first stall and survives the retries after it`() {
        clock.onStalled()
        now += 5_000; clock.onStalled()
        now += 40_000; clock.onStalled()
        now += 10_000
        assertEquals(1_000L, clock.onPlaying())
    }

    @Test
    fun `playing closes the outage`() {
        clock.onStalled()
        clock.onPlaying()
        assertNull(clock.onPlaying())
    }

    @Test
    fun `a zap resets it`() {
        clock.onStalled()
        clock.reset()
        assertNull(clock.onPlaying())
    }
}
