package com.tvonnet.debridxtreamiptv.util

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Roadmap B3 — the shared title cleanup, untested until now despite sitting under three things that
 * users notice: browse card titles, the A–Z channel type-ahead, and Continue-Watching identity
 * (a title that cleans differently is a *different row* in Continue Watching).
 *
 * The rules are deliberately conservative — only pipe-delimited tags, leading release-site prefixes
 * and leading brackets go. That conservatism is the point, so both halves are pinned: what it
 * strips, and what it must leave alone.
 */
class MediaTitleCleanerTest {

    @Test
    fun `leading provider tag blocks are stripped`() {
        assertEquals("Breaking Bad", MediaTitleCleaner.clean("|MULTI| |EN| Breaking Bad"))
        assertEquals("Sky Sports", MediaTitleCleaner.clean("|4K|Sky Sports"))
    }

    @Test
    fun `short pipe tags are stripped wherever they appear`() {
        assertEquals("Al Jazeera", MediaTitleCleaner.clean("Al Jazeera |AR|"))
        assertEquals("Canal Plus", MediaTitleCleaner.clean("Canal |FR| Plus"))
    }

    @Test
    fun `release-site prefixes and leading brackets are stripped`() {
        assertEquals("Inception", MediaTitleCleaner.clean("www.1TamilBlasters.dad - Inception"))
        assertEquals("Inception", MediaTitleCleaner.clean("[Bolly4u.limo] Inception"))
        assertEquals("Inception", MediaTitleCleaner.clean("(YTS) Inception"))
        assertEquals("stacked prefixes unwind", "Inception", MediaTitleCleaner.clean("[YTS] (1080p) Inception"))
    }

    @Test
    fun `json escapes from the provider are unescaped`() {
        assertEquals("Don't Look Up", MediaTitleCleaner.clean("Don\\'t Look Up"))
        assertEquals("\"Salem\"", MediaTitleCleaner.clean("\\\"Salem\\\""))
    }

    @Test
    fun `leftover separators and doubled spaces are collapsed`() {
        assertEquals("BBC One", MediaTitleCleaner.clean("  -  BBC   One  -  "))
        assertEquals("BBC One", MediaTitleCleaner.clean("| | BBC One"))
    }

    /** The conservative half: a long word inside pipes is title text, not a tag. */
    @Test
    fun `a long piped word in the middle of a title is left alone`() {
        assertEquals("Cinema |Classics| Channel", MediaTitleCleaner.clean("Cinema |Classics| Channel"))
    }

    @Test
    fun `a bare quality prefix is part of the title`() {
        assertEquals(
            "only pipe-delimited tags are stripped — this is why '4K Sky' type-aheads under K",
            "4K Sky Sports",
            MediaTitleCleaner.clean("4K Sky Sports")
        )
    }

    @Test
    fun `blank input is empty, never null`() {
        assertEquals("", MediaTitleCleaner.clean(null))
        assertEquals("", MediaTitleCleaner.clean("   "))
    }
}
