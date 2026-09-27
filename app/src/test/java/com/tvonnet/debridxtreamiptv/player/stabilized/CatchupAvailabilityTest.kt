package com.tvonnet.debridxtreamiptv.player.stabilized

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** C1: resume-where-it-stopped is offered only when every condition holds. */
class CatchupAvailabilityTest {

    private val now = 10_000_000L

    private fun offer(
        missedMs: Long = 60_000L,
        zone: Boolean = true,
        archive: Boolean? = true,
        failed: Boolean = false,
    ) = CatchupAvailability.canOfferResume(now - missedMs, now, zone, archive, failed)

    @Test
    fun `a minute missed on an archiving channel is offered`() = assertTrue(offer())

    @Test
    fun `a short blip is not worth a prompt`() = assertFalse(offer(missedMs = 5_000L))

    @Test
    fun `beyond the 2-day archive nothing is left to play`() =
        assertFalse(offer(missedMs = CatchupAvailability.ARCHIVE_WINDOW_MS + 1))

    @Test
    fun `unknown server zone, no archive, unknown archive or a failed recording all say no`() {
        assertFalse(offer(zone = false))
        assertFalse(offer(archive = false))
        assertFalse(offer(archive = null))
        assertFalse(offer(failed = true))
    }
}
