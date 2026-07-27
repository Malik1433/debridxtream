package com.tvonnet.debridxtreamiptv.features.seriesv2.data.repository

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Roadmap C5 — the pure name decisions behind multi-category stream aggregation.
 *
 * These rules decide which provider listings are *the same show*, and they fail in both
 * directions with visible consequences: too strict and the SELECT STREAM panel shows one source
 * when five exist; too loose and it offers episodes of a different programme entirely. Neither is
 * testable against a live provider, so both halves are pinned here — what must merge, and what
 * must stay apart.
 */
class SeriesTitleMatchingTest {

    // ── normalizeCoreTitle: decorated variants must collapse ───────────────────

    @Test
    fun `every decorated variant of one show normalizes to the same core title`() {
        val expected = "the walking dead"
        listOf(
            "EN - The Walking Dead (2010)",
            "NL - THE WALKING DEAD (2010)",
            "FR - The Walking Dead",
            "EN-  The Walking Dead",
            "[SE] The Walking Dead",
            "EN| The Walking Dead 4K",
            "The Walking Dead"
        ).forEach { raw ->
            assertEquals("failed for: $raw", expected, SeriesTitleMatching.normalizeCoreTitle(raw))
        }
    }

    @Test
    fun `quality and language noise words are dropped, real words are not`() {
        assertEquals("dark", SeriesTitleMatching.normalizeCoreTitle("Dark 4K UHD MULTI"))
        assertEquals("dark matter", SeriesTitleMatching.normalizeCoreTitle("Dark Matter 1080p"))
        // "sub" is noise; "suburra" is a real title and must survive intact.
        assertEquals("suburra", SeriesTitleMatching.normalizeCoreTitle("Suburra"))
    }

    @Test
    fun `a colon-separated subtitle is part of the title, not a strippable tag`() {
        // Dash-only prefix stripping exists precisely so "FBI: Most Wanted" keeps its subtitle —
        // otherwise it would collapse onto "FBI" and merge two different shows.
        assertEquals("fbi most wanted", SeriesTitleMatching.normalizeCoreTitle("FBI: Most Wanted"))
        assertTrue(
            SeriesTitleMatching.normalizeCoreTitle("FBI: Most Wanted") !=
                SeriesTitleMatching.normalizeCoreTitle("FBI")
        )
    }

    @Test
    fun `a dash inside a title survives because the prefix rule needs trailing whitespace`() {
        assertEquals("9 1 1", SeriesTitleMatching.normalizeCoreTitle("9-1-1"))
    }

    @Test
    fun `stacked leading tags are stripped, but only two deep`() {
        assertEquals("the office", SeriesTitleMatching.normalizeCoreTitle("EN - NF - The Office"))
    }

    @Test
    fun `blank input normalizes to empty rather than throwing`() {
        assertEquals("", SeriesTitleMatching.normalizeCoreTitle(""))
        assertEquals("", SeriesTitleMatching.normalizeCoreTitle("   "))
    }

    // ── likeSearchToken: the SQL LIKE pool ─────────────────────────────────────

    @Test
    fun `like token is the longest punctuation-free run of the cleaned title`() {
        assertEquals(
            "the walking dead",
            SeriesTitleMatching.likeSearchToken("NL - The Walking Dead: Dead City (2023)")
        )
    }

    @Test
    fun `like token is contained verbatim in the sibling names it has to match`() {
        val token = SeriesTitleMatching.likeSearchToken("EN| Breaking Bad (2008)")
        listOf("EN - Breaking Bad", "|MULTI| Breaking Bad 4K", "FR - Breaking Bad (2008)")
            .forEach { name ->
                assertTrue("'$name' must contain '$token'", name.lowercase().contains(token))
            }
    }

    @Test
    fun `short or blank titles yield a token too short to search on`() {
        // The caller refuses tokens under 4 chars — this is what keeps a two-letter
        // title from LIKE-matching half the catalog.
        assertTrue(SeriesTitleMatching.likeSearchToken("ER").length < 4)
        assertEquals("", SeriesTitleMatching.likeSearchToken(""))
    }

    // ── year guard: different years are different shows ────────────────────────

    @Test
    fun `year is read from a bracketed four-digit year only`() {
        assertEquals(2024, SeriesTitleMatching.yearOf("Nemesis (2024)"))
        assertEquals(2010, SeriesTitleMatching.yearOf("EN - The Walking Dead (2010)"))
        assertNull(SeriesTitleMatching.yearOf("Nemesis"))
        assertNull(SeriesTitleMatching.yearOf("Squad 1899"))
    }

    @Test
    fun `a missing year matches anything, so unlabelled listings are never dropped`() {
        assertTrue(SeriesTitleMatching.yearsCompatible(null, 2024))
        assertTrue(SeriesTitleMatching.yearsCompatible(2024, null))
        assertTrue(SeriesTitleMatching.yearsCompatible(null, null))
    }

    @Test
    fun `one year of drift is tolerated but two is a different show`() {
        // Regional first-air years routinely differ by one; strict equality once wiped
        // every listing for shows where TMDB and the provider label disagree.
        assertTrue(SeriesTitleMatching.yearsCompatible(2024, 2025))
        assertTrue(SeriesTitleMatching.yearsCompatible(2025, 2024))
        assertFalse(SeriesTitleMatching.yearsCompatible(2024, 2026))
    }

    // ── shortCategoryTag: the badge on each stream row ─────────────────────────

    @Test
    fun `category markup wins over the listing name prefix`() {
        assertEquals("EN", SeriesTitleMatching.shortCategoryTag("|EN| TOP SERIES", "FR - Title"))
        assertEquals("FR", SeriesTitleMatching.shortCategoryTag("[FR] ACTION", null))
        assertEquals("MULTI", SeriesTitleMatching.shortCategoryTag("MULTI| NETFLIX 4K", null))
    }

    @Test
    fun `a name prefix is used when the category carries no markup`() {
        assertEquals("NL", SeriesTitleMatching.shortCategoryTag("SERIES", "NL- Title"))
        assertEquals("EN", SeriesTitleMatching.shortCategoryTag(null, "EN - Title"))
    }

    @Test
    fun `keyword fallback covers plain-language category names`() {
        assertEquals("FR", SeriesTitleMatching.shortCategoryTag("SERIES VOSTFR", null))
        assertEquals("AR", SeriesTitleMatching.shortCategoryTag("Ramadan Series", null))
        assertEquals("ES", SeriesTitleMatching.shortCategoryTag("Series en Espanol", null))
        assertEquals("HI", SeriesTitleMatching.shortCategoryTag("Hindi Shows", null))
    }

    @Test
    fun `an unrecognisable category still gets a badge rather than a blank`() {
        assertEquals("SRV", SeriesTitleMatching.shortCategoryTag("Random Category", "Some Title"))
        assertEquals("SRV", SeriesTitleMatching.shortCategoryTag(null, null))
    }

    // ── siblingEpisodeLikelihood: which dead listing to try first ──────────────

    @Test
    fun `international variants are tried before regional dubs`() {
        val multi = SeriesTitleMatching.siblingEpisodeLikelihood("|MULTI| The Show")
        val english = SeriesTitleMatching.siblingEpisodeLikelihood("EN - The Show")
        val dub = SeriesTitleMatching.siblingEpisodeLikelihood("SOM - The Show")
        assertTrue("MULTI must outrank EN", multi > english)
        assertTrue("EN must outrank a regional dub", english > dub)
        assertTrue("a regional dub must score below a plain listing", dub < 0)
    }

    @Test
    fun `a null or plain name scores neutral`() {
        assertEquals(0, SeriesTitleMatching.siblingEpisodeLikelihood(null))
        assertEquals(0, SeriesTitleMatching.siblingEpisodeLikelihood("The Show"))
    }
}
