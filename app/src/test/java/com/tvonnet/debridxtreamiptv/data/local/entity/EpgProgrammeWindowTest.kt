package com.tvonnet.debridxtreamiptv.data.local.entity

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.TimeUnit

/**
 * Roadmap B3 — the "is this programme on now" predicates.
 *
 * They decide the NOW badge, the ON AIR NOW strip and which row the guide highlights. Each reads
 * the wall clock internally, so the tests build programmes *relative to now* — no fixed timestamps,
 * and no sleeping. The boundary cases are the ones that matter: a programme is on at its start
 * instant and off at its stop instant, so back-to-back programmes never both claim to be live.
 */
class EpgProgrammeWindowTest {

    private val now get() = System.currentTimeMillis()

    @Test
    fun `a programme spanning now is playing`() {
        val current = programme(startOffsetMin = -10, stopOffsetMin = 20)

        assertTrue(current.isPlaying())
        assertFalse(current.hasEnded())
        assertFalse("it has already started, so it is not upcoming", current.isUpcoming())
    }

    @Test
    fun `a finished programme has ended and is not playing`() {
        val past = programme(startOffsetMin = -120, stopOffsetMin = -60)

        assertTrue(past.hasEnded())
        assertFalse(past.isPlaying())
        assertFalse(past.isUpcoming())
    }

    @Test
    fun `a programme starting within two hours is upcoming`() {
        assertTrue(programme(startOffsetMin = 30, stopOffsetMin = 90).isUpcoming())
        assertTrue("the two-hour edge is included", programme(startOffsetMin = 119, stopOffsetMin = 180).isUpcoming())
        assertFalse("beyond the window it is just scheduled", programme(startOffsetMin = 180, stopOffsetMin = 240).isUpcoming())
    }

    /**
     * The boundary that keeps a guide honest: stop is exclusive, so the programme ending exactly
     * now has finished and its successor is the live one — never both.
     */
    @Test
    fun `back-to-back programmes never both claim to be live`() {
        val ending = programme(startOffsetMin = -60, stopOffsetMin = 0)
        val starting = programme(startOffsetMin = 0, stopOffsetMin = 60)

        assertFalse(ending.isPlaying())
        assertTrue(starting.isPlaying())
    }

    @Test
    fun `duration is reported in whole minutes`() {
        assertEquals(90, programme(startOffsetMin = -30, stopOffsetMin = 60).getDurationMinutes())
        assertEquals(0, programme(startOffsetMin = 0, stopOffsetMin = 0).getDurationMinutes())
    }

    private fun programme(startOffsetMin: Long, stopOffsetMin: Long) = EpgEntity(
        channelId = "bbc.one",
        start = now + TimeUnit.MINUTES.toMillis(startOffsetMin),
        stop = now + TimeUnit.MINUTES.toMillis(stopOffsetMin),
        title = "Programme",
        description = null,
        category = null,
        cachedAt = 0L
    )
}
