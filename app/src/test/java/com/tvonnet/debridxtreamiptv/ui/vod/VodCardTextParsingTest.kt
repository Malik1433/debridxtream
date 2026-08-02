package com.tvonnet.debridxtreamiptv.ui.vod

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Freezes the movie-card title/quality parsing (parseVodCardText), extracted verbatim from
 * VodViewHolder.bind so the provider-name heuristics are provable off-device: trailing
 * language tags are stripped, trailing quality tags become the badge (and leave the title),
 * and quality tokens embedded mid-name still set the badge without touching the title.
 */
class VodCardTextParsingTest {

    @Test
    fun `plain name - untouched, defaults to HD badge`() {
        val parsed = parseVodCardText("Inception")
        assertEquals("Inception", parsed.title)
        assertEquals("HD", parsed.qualityBadge)
    }

    @Test
    fun `null or blank name - Unknown Movie`() {
        assertEquals("Unknown Movie", parseVodCardText(null).title)
        assertEquals("Unknown Movie", parseVodCardText("   ").title)
    }

    @Test
    fun `trailing quality tag - becomes the badge and leaves the title`() {
        val parsed = parseVodCardText("Dune Part Two [4K]")
        assertEquals("Dune Part Two", parsed.title)
        assertEquals("4K", parsed.qualityBadge)
    }

    @Test
    fun `trailing 1080p and FHD - both map to the FHD badge`() {
        assertEquals("FHD", parseVodCardText("Movie 1080p").qualityBadge)
        assertEquals("FHD", parseVodCardText("Movie (FHD)").qualityBadge)
    }

    @Test
    fun `trailing 720p - stripped from the title but badge stays HD`() {
        val parsed = parseVodCardText("Movie 720p")
        assertEquals("Movie", parsed.title)
        assertEquals("HD", parsed.qualityBadge)
    }

    @Test
    fun `trailing language tag - stripped, separator cleaned up`() {
        val parsed = parseVodCardText("Oppenheimer |EN")
        assertEquals("Oppenheimer", parsed.title)
    }

    @Test
    fun `language tag then quality tag - both stripped, badge set`() {
        // The lang regex only matches at the END, so it fires first; quality is then trailing.
        val parsed = parseVodCardText("Movie UHD |FR")
        assertEquals("Movie", parsed.title)
        assertEquals("4K", parsed.qualityBadge)
    }

    @Test
    fun `embedded quality token - sets the badge without a trailing tag`() {
        assertEquals("4K", parseVodCardText("4K Remaster of a Classic").qualityBadge)
        assertEquals("FHD", parseVodCardText("Movie 1080p Extended Cut").qualityBadge)
    }

    @Test
    fun `leading provider pipe tags - cleaned by the shared cleaner`() {
        val parsed = parseVodCardText("|MULTI| Inception")
        assertEquals("Inception", parsed.title)
    }

    @Test
    fun `known quirk - a title ending in a language code loses that suffix`() {
        // The lang regex is unanchored on the left, so "...ar" at the end of a real word
        // matches the AR tag. Pre-existing bind behaviour, frozen here so a future fix is
        // a deliberate decision, not an accident.
        assertEquals("Interstell", parseVodCardText("Interstellar").title)
    }

    @Test
    fun `name that is only tags - falls back to Unknown Movie`() {
        assertEquals("Unknown Movie", parseVodCardText("[4K]").title)
    }
}
