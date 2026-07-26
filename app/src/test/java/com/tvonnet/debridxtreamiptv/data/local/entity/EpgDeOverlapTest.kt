package com.tvonnet.debridxtreamiptv.data.local.entity

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Roadmap B3 — the EPG overlap invariant, previously enforced by a comment and nothing else.
 *
 * The guide grids position every programme purely from its (start, stop), so two overlapping
 * programmes get painted on top of each other — the "two titles ghosted in one cell" symptom that
 * XMLTV feeds cause routinely (duplicate `<programme>` entries, or a coarse placeholder block
 * layered under granular ones). [deOverlap] is the earliest-stop-first greedy that fixes it, and it
 * must satisfy two things at once: the output never overlaps, and it keeps as many programmes as
 * possible rather than throwing the schedule away.
 */
class EpgDeOverlapTest {

    @Test
    fun `a clean schedule passes through untouched`() {
        val clean = listOf(
            programme("News", 0, 60),
            programme("Film", 60, 180),
            programme("Late", 180, 240)
        )

        assertEquals(clean, clean.deOverlap())
    }

    @Test
    fun `an exact duplicate programme is collapsed to one`() {
        val withDuplicate = listOf(
            programme("News", 0, 60),
            programme("News", 0, 60)
        )

        val result = withDuplicate.deOverlap()

        assertEquals(1, result.size)
        assertNoOverlap(result)
    }

    /** The real feed shape: a coarse block laid under the granular ones it covers. */
    @Test
    fun `a placeholder block layered under granular programmes loses to them`() {
        val messy = listOf(
            programme("PLACEHOLDER", 0, 240),
            programme("News", 0, 60),
            programme("Film", 60, 180),
            programme("Late", 180, 240)
        )

        val result = messy.deOverlap()

        assertEquals(listOf("News", "Film", "Late"), result.map { it.title })
        assertNoOverlap(result)
    }

    @Test
    fun `back-to-back programmes are not treated as overlapping`() {
        val touching = listOf(
            programme("A", 0, 60),
            programme("B", 60, 120)
        )

        assertEquals(2, touching.deOverlap().size)
    }

    @Test
    fun `zero and negative length entries are dropped`() {
        val broken = listOf(
            programme("Zero", 60, 60),
            programme("Backwards", 120, 60),
            programme("Good", 0, 60)
        )

        assertEquals(listOf("Good"), broken.deOverlap().map { it.title })
    }

    @Test
    fun `the result is ordered by start so a grid can render it directly`() {
        val shuffled = listOf(
            programme("Late", 180, 240),
            programme("News", 0, 60),
            programme("Film", 60, 180)
        )

        assertEquals(listOf("News", "Film", "Late"), shuffled.deOverlap().map { it.title })
    }

    /** Greedy-by-earliest-stop is chosen because it keeps the MOST programmes, not the first ones. */
    @Test
    fun `it keeps the maximum number of programmes`() {
        val overlapping = listOf(
            programme("Long", 0, 200),
            programme("Short1", 0, 50),
            programme("Short2", 60, 110),
            programme("Short3", 120, 170)
        )

        val result = overlapping.deOverlap()

        assertEquals(listOf("Short1", "Short2", "Short3"), result.map { it.title })
        assertNoOverlap(result)
    }

    @Test
    fun `empty and single-entry lists are handled`() {
        assertTrue(emptyList<EpgEntity>().deOverlap().isEmpty())
        assertEquals(1, listOf(programme("Solo", 0, 60)).deOverlap().size)
        assertTrue("a single broken entry is still dropped", listOf(programme("Bad", 60, 60)).deOverlap().isEmpty())
    }

    // ------------------------------------------------------------------ helpers

    private fun assertNoOverlap(programmes: List<EpgEntity>) {
        programmes.sortedBy { it.start }.zipWithNext { a, b ->
            assertTrue(
                "'${a.title}' (${a.start}-${a.stop}) overlaps '${b.title}' (${b.start}-${b.stop})",
                b.start >= a.stop
            )
        }
    }

    private fun programme(title: String, startMinute: Long, stopMinute: Long) = EpgEntity(
        channelId = "bbc.one",
        start = startMinute * 60_000L,
        stop = stopMinute * 60_000L,
        title = title,
        description = null,
        category = null,
        cachedAt = 0L
    )
}
