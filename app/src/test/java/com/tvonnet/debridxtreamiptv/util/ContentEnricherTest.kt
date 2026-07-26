package com.tvonnet.debridxtreamiptv.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Roadmap B3 — the torrent-name parser behind Debrid metadata matching.
 *
 * Everything a Debrid source shows (poster, plot, correct film) hangs off turning
 * "Movie.Name.2021.1080p.BluRay.x264-RARBG" into ("Movie Name", 2021) and then querying TMDB with
 * it. A parser like this quietly degrades as release-group conventions drift, and nothing else in
 * the app notices — the user just sees the wrong artwork.
 */
class ContentEnricherTest {

    @Test
    fun `a standard release name yields a clean title and its year`() {
        val parsed = ContentEnricher.parse("Movie.Name.2021.1080p.BluRay.x264-RARBG.mkv")

        assertEquals("Movie Name", parsed.title)
        assertEquals(2021, parsed.year)
    }

    @Test
    fun `bracketed and parenthesised years are found too`() {
        assertEquals(2010, ContentEnricher.parse("Inception (2010) 2160p UHD HDR.mkv").year)
        assertEquals(2010, ContentEnricher.parse("Inception [2010] WEB-DL.mkv").year)
    }

    @Test
    fun `the title stops at the year`() {
        val parsed = ContentEnricher.parse("The.Lord.of.the.Rings.2001.EXTENDED.1080p.mkv")

        assertEquals("The Lord of the Rings", parsed.title)
    }

    @Test
    fun `quality codec and release-group noise is stripped`() {
        val parsed = ContentEnricher.parse("Arrival 1080p WEBRip x265 HEVC DTS YTS")

        assertEquals("Arrival", parsed.title)
        assertNull("nothing here is a year", parsed.year)
    }

    @Test
    fun `episode and season clutter is cut off the title`() {
        assertEquals("Breaking Bad", ContentEnricher.parse("Breaking.Bad.S01E02.1080p.mkv").title)
        assertEquals("Breaking Bad", ContentEnricher.parse("Breaking.Bad.COMPLETE.SEASON.1080p").title)
    }

    @Test
    fun `an implausible four-digit number is not treated as a year`() {
        val parsed = ContentEnricher.parse("Movie.Name.1234.1080p.mkv")

        assertNull(parsed.year)
    }

    /** A title that IS a year must survive — the parser drops everything before the match only. */
    @Test
    fun `a film named after a year keeps something to search with`() {
        val parsed = ContentEnricher.parse("1917.2019.1080p.BluRay.x264.mkv")

        assertEquals(2019, parsed.year)
        assertEquals("1917", parsed.title)
    }

    @Test
    fun `a bare name with no metadata comes back as itself`() {
        val parsed = ContentEnricher.parse("Arrival")

        assertEquals("Arrival", parsed.title)
        assertNull(parsed.year)
    }
}
