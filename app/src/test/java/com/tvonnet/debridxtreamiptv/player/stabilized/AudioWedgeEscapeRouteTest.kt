package com.tvonnet.debridxtreamiptv.player.stabilized

import com.tvonnet.debridxtreamiptv.player.stabilized.AudioWedgeEscape.RouteDecision
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * The routing rule behind the wedged-HDMI escape, after 2026-09-15 showed the escape
 * route itself can wedge: the flag must come OFF when the primary mixer consumes again,
 * and must not flap.
 */
class AudioWedgeEscapeRouteTest {

    @Before
    fun setUp() = AudioWedgeEscape.resetForTest()

    @After
    fun tearDown() = AudioWedgeEscape.resetForTest()

    @Test
    fun `a wedged primary engages the escape, a healthy one leaves it alone`() {
        assertEquals(RouteDecision.ENGAGE, AudioWedgeEscape.decide(engaged = false, forced = false, primaryConsumes = false))
        assertEquals(RouteDecision.KEEP, AudioWedgeEscape.decide(engaged = false, forced = false, primaryConsumes = true))
    }

    @Test
    fun `a primary that consumes again releases the escape, a still-wedged one keeps it`() {
        assertEquals(RouteDecision.RELEASE, AudioWedgeEscape.decide(engaged = true, forced = false, primaryConsumes = true))
        assertEquals(RouteDecision.KEEP, AudioWedgeEscape.decide(engaged = true, forced = false, primaryConsumes = false))
    }

    @Test
    fun `the adb override always engages and never releases`() {
        assertEquals(RouteDecision.ENGAGE, AudioWedgeEscape.decide(engaged = false, forced = true, primaryConsumes = true))
        assertEquals(RouteDecision.KEEP, AudioWedgeEscape.decide(engaged = true, forced = true, primaryConsumes = true))
    }

    @Test
    fun `release clears the flag once, then refuses for five minutes`() {
        AudioWedgeEscape.engage()
        assertTrue(AudioWedgeEscape.release(nowMs = 1_000_000L))
        assertFalse(AudioWedgeEscape.engaged)

        AudioWedgeEscape.engage()
        assertFalse("a second flip inside the backoff must be refused", AudioWedgeEscape.release(nowMs = 1_000_000L + 4 * 60_000L))
        assertTrue("and must leave the escape engaged", AudioWedgeEscape.engaged)

        assertTrue(AudioWedgeEscape.release(nowMs = 1_000_000L + 5 * 60_000L))
        assertFalse(AudioWedgeEscape.engaged)
    }

    @Test
    fun `the first release after process start is never refused`() {
        AudioWedgeEscape.engage()
        // elapsedRealtime can be tiny right after boot; the backoff must not swallow it.
        assertTrue(AudioWedgeEscape.release(nowMs = 10L))
    }

    // W0 (2026-09-27): the probe timing line is what the field analysis greps. "late" is the case
    // under suspicion - a healthy but cold HDMI route that consumed only after 500 ms had already
    // been called wedged.
    @Test
    fun `probe timing line separates in-window, late and never`() {
        assertEquals(
            "Audio wedge probe timing: result=in_window firstProgressMs=120 windowMs=500 verdict=consuming",
            AudioWedgeEscape.probeTimingLogLine(120L, 500L, 3000L)
        )
        assertEquals(
            "Audio wedge probe timing: result=late firstProgressMs=850 windowMs=500 verdict=wedged (false positive)",
            AudioWedgeEscape.probeTimingLogLine(850L, 500L, 3000L)
        )
        assertEquals(
            "Audio wedge probe timing: result=late firstProgressMs=500 windowMs=500 verdict=wedged (false positive)",
            AudioWedgeEscape.probeTimingLogLine(500L, 500L, 3000L)
        )
        assertEquals(
            "Audio wedge probe timing: result=no_progress firstProgressMs=none observedMs=3000 verdict=wedged",
            AudioWedgeEscape.probeTimingLogLine(null, 500L, 3000L)
        )
    }
}
