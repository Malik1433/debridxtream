package com.tvonnet.debridxtreamiptv.player.stabilized

import com.tvonnet.debridxtreamiptv.player.stabilized.SessionSummaryPolicy.Summary
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Which sessions are worth a report of their own, and that a report never carries content. */
class SessionSummaryPolicyTest {

    private fun s(
        rebuffers: Int = 0, errors: Int = 0, firstFrame: Boolean = true, sec: Long = 600, ttff: Long? = 1200,
    ) = Summary("vod", sec, rebuffers, 12, errors, firstFrame, ttff)

    @Test
    fun `a normal session is healthy - no non-fatal of its own`() {
        assertFalse(SessionSummaryPolicy.isUnhealthy(s()))
        assertFalse(SessionSummaryPolicy.isUnhealthy(s(rebuffers = 2)))
    }

    @Test
    fun `repeated rebuffering, an error, or no frame after 20 s are unhealthy`() {
        assertTrue(SessionSummaryPolicy.isUnhealthy(s(rebuffers = 3)))
        assertTrue(SessionSummaryPolicy.isUnhealthy(s(errors = 1)))
        assertTrue(SessionSummaryPolicy.isUnhealthy(s(firstFrame = false, sec = 25, ttff = null)))
        assertFalse("a 5 s session with no frame is a user who left, not a failure", SessionSummaryPolicy.isUnhealthy(s(firstFrame = false, sec = 5, ttff = null)))
    }

    @Test
    fun `keys are primitives with stable names and no content`() {
        val k = SessionSummaryPolicy.keys(s(rebuffers = 1))
        assertEquals(listOf("pb_mode", "pb_session_sec", "pb_rebuffers", "pb_dropped_frames", "pb_errors", "pb_first_frame", "pb_ttff_ms"), k.keys.toList())
        assertEquals("vod", k["pb_mode"]); assertEquals(600L, k["pb_session_sec"]); assertEquals(1, k["pb_rebuffers"])
        assertEquals(true, k["pb_first_frame"]); assertEquals(1200L, k["pb_ttff_ms"])
        assertEquals(-1L, SessionSummaryPolicy.keys(s(ttff = null))["pb_ttff_ms"])
        k.values.forEach { v -> assertTrue(v is String || v is Int || v is Long || v is Boolean) }
    }

    @Test
    fun `the message is numbers and a mode word`() {
        val m = SessionSummaryPolicy.message(s(rebuffers = 4, errors = 1))
        assertEquals("playback_session mode=vod sec=600 rebuffers=4 dropped=12 errors=1 firstFrame=true ttff=1200", m)
    }
}
