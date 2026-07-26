package com.tvonnet.debridxtreamiptv.ui.live

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Roadmap B3 — the channel grid's D-pad arithmetic and quick-jump scanning.
 *
 * `LiveChannelFocusController` (LF-6) was extracted verbatim with no tests, and it is the app's
 * most D-pad-sensitive path: hold DOWN on the remote and the grid must keep up; type "1-4-2" and
 * land on channel 142; press "b" twice and advance to the *next* B channel rather than sticking.
 * The controller needs a Fragment + RecyclerView + paging adapter, so those rules are now in
 * [LiveChannelQuickJump] and pinned here.
 */
class LiveChannelQuickJumpTest {

    // ── D-pad movement ───────────────────────────────────────────────────────

    @Test
    fun `a step moves one row`() {
        assertEquals(6, LiveChannelQuickJump.targetFor(base = 5, delta = 1, itemCount = 100))
        assertEquals(4, LiveChannelQuickJump.targetFor(base = 5, delta = -1, itemCount = 100))
    }

    /**
     * The reason movement counts from the PENDING position: holding DOWN queues steps faster than
     * focus settles, so each keypress must build on the last requested row, not the visible one.
     */
    @Test
    fun `repeated steps accumulate from the pending row`() {
        var position = 0
        repeat(5) { position = LiveChannelQuickJump.targetFor(position, delta = 1, itemCount = 100)!! }

        assertEquals("five DOWN presses must advance five rows, not one", 5, position)
    }

    @Test
    fun `the ends of the list absorb the step instead of wrapping`() {
        assertNull("UP at the top is a no-op, not a jump to the bottom", LiveChannelQuickJump.targetFor(0, -1, 100))
        assertNull("DOWN at the bottom is a no-op", LiveChannelQuickJump.targetFor(99, 1, 100))
        assertEquals(99, LiveChannelQuickJump.targetFor(90, 20, 100))
        assertEquals(0, LiveChannelQuickJump.targetFor(10, -50, 100))
    }

    @Test
    fun `an empty list has nowhere to move`() {
        assertNull(LiveChannelQuickJump.targetFor(0, 1, itemCount = 0))
    }

    // ── number-zap ───────────────────────────────────────────────────────────

    @Test
    fun `digits accumulate into a channel number`() {
        var buffer = ""
        listOf(1, 4, 2).forEach { buffer = LiveChannelQuickJump.appendDigit(buffer, it)!! }

        assertEquals("142", buffer)
    }

    @Test
    fun `a leading zero is ignored because there is no channel zero`() {
        assertNull(LiveChannelQuickJump.appendDigit("", 0))
        assertEquals("10", LiveChannelQuickJump.appendDigit("1", 0))
    }

    @Test
    fun `a fifth digit starts a new number rather than extending an unreachable one`() {
        assertEquals("1234", LiveChannelQuickJump.appendDigit("123", 4))
        assertEquals("a 5th digit restarts the buffer", "5", LiveChannelQuickJump.appendDigit("1234", 5))
    }

    @Test
    fun `channel numbers are one-based, positions are zero-based`() {
        assertEquals(0, LiveChannelQuickJump.positionForNumber(1, itemCount = 100))
        assertEquals(141, LiveChannelQuickJump.positionForNumber(142, itemCount = 500))
    }

    @Test
    fun `a number past the end of the list clamps to the last row`() {
        assertEquals(99, LiveChannelQuickJump.positionForNumber(9999, itemCount = 100))
        assertEquals(0, LiveChannelQuickJump.positionForNumber(0, itemCount = 100))
        assertNull(LiveChannelQuickJump.positionForNumber(5, itemCount = 0))
    }

    // ── A–Z type-ahead ───────────────────────────────────────────────────────

    @Test
    fun `type-ahead finds the next channel starting with the letter`() {
        val names = listOf("ARD", "BBC One", "CNN", "BBC Two")

        assertEquals(1, LiveChannelQuickJump.nextIndexStartingWith(names, fromPosition = 0, letter = 'b'))
    }

    /** Pressing the same letter again must advance, which is what makes it usable as a scan. */
    @Test
    fun `pressing the same letter again advances to the next match and wraps`() {
        val names = listOf("ARD", "BBC One", "CNN", "BBC Two")

        val first = LiveChannelQuickJump.nextIndexStartingWith(names, fromPosition = 0, letter = 'b')!!
        val second = LiveChannelQuickJump.nextIndexStartingWith(names, fromPosition = first, letter = 'b')!!
        val third = LiveChannelQuickJump.nextIndexStartingWith(names, fromPosition = second, letter = 'b')!!

        assertEquals(1, first)
        assertEquals(3, second)
        assertEquals("after the last match it wraps back to the first", 1, third)
    }

    /** Provider tags must not decide the letter — "|UK| BBC One" is a B channel, not a U one. */
    @Test
    fun `provider tags are stripped before the first letter is taken`() {
        val names = listOf("ARD", "|UK| BBC One", "CNN")

        assertEquals(1, LiveChannelQuickJump.nextIndexStartingWith(names, fromPosition = 0, letter = 'b'))
        assertNull(LiveChannelQuickJump.nextIndexStartingWith(names, fromPosition = 0, letter = 'u'))
    }

    @Test
    fun `channels whose name starts with digits or symbols fall through to their first letter`() {
        assertEquals('h', LiveChannelQuickJump.firstLetterOf("  ++ HBO"))
        assertNull(LiveChannelQuickJump.firstLetterOf("12345"))
        assertNull(LiveChannelQuickJump.firstLetterOf(null))
    }

    /**
     * Documented quirk, not a bug being papered over: `MediaTitleCleaner` only strips tags that are
     * **pipe-delimited**, so a bare "4K " prefix stays and the channel type-aheads under K.
     * Providers that write "|4K| Sky Sports" behave the way a user expects.
     */
    @Test
    fun `a bare quality prefix is part of the name, a piped one is not`() {
        assertEquals('k', LiveChannelQuickJump.firstLetterOf("4K Sky Sports"))
        assertEquals('s', LiveChannelQuickJump.firstLetterOf("|4K| Sky Sports"))
    }

    @Test
    fun `no match and an empty list both mean stay put`() {
        assertNull(LiveChannelQuickJump.nextIndexStartingWith(listOf("ARD", "CNN"), 0, 'z'))
        assertNull(LiveChannelQuickJump.nextIndexStartingWith(emptyList(), 0, 'a'))
    }

    /** A stale focus position must not throw — the list can shrink under a type-ahead. */
    @Test
    fun `a from-position beyond the list still scans`() {
        val names = listOf("ARD", "BBC One", "CNN")

        assertEquals(1, LiveChannelQuickJump.nextIndexStartingWith(names, fromPosition = 99, letter = 'b'))
    }
}
