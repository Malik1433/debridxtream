package com.tvonnet.debridxtreamiptv.player.stabilized

import com.tvonnet.debridxtreamiptv.data.model.XtreamVodInfo
import com.tvonnet.debridxtreamiptv.data.repository.MovieSource
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Roadmap C10 — keeping a binge on the same release.
 *
 * When the next episode auto-plays, this decides which of dozens of candidate sources it comes
 * from. Get it wrong and episode 2 arrives in a different language or from a different rip, which
 * is the most jarring thing a player can do without actually failing.
 *
 * The relative weights are the behaviour, so they are pinned as *comparisons* rather than as magic
 * numbers: a same-release candidate must beat a same-quality one, and a language match must beat
 * both.
 */
class DebridSourceScoringTest {

    private fun source(
        provider: String? = null,
        sourceType: String? = null,
        sourceName: String? = null,
        quality: String? = null,
        bingeGroup: String? = null,
        languages: List<String>? = null,
        directSource: String? = null,
    ) = MovieSource(
        stream = XtreamVodInfo(
            num = 1, name = "Test", stream_type = "movie", stream_id = "s1", stream_icon = null,
            rating = null, rating_5based = null, added = null, category_id = null,
            category_ids = null, container_extension = "mkv", custom_sid = null,
            direct_source = directSource, releaseDate = null, duration = null, plot = null,
            cast = null, director = null, genre = null, youtube_trailer = null, cover = null,
            rating_imdb = null
        ),
        category = null,
        label = "test",
        isPrimary = false,
        provider = provider,
        sourceType = sourceType,
        sourceName = sourceName,
        quality = quality,
        bingeGroup = bingeGroup,
        languages = languages,
    )

    private fun profile(
        provider: String? = null,
        sourceType: String? = null,
        sourceName: String? = null,
        quality: String? = null,
        bingeGroup: String? = null,
        languages: List<String>? = null,
        directPlayback: Boolean = false,
    ) = DebridSourceProfile(
        provider = provider,
        sourceType = sourceType,
        sourceName = sourceName,
        languages = languages,
        quality = quality,
        streamId = null,
        bingeGroup = bingeGroup,
        fileIdx = null,
        directPlayback = directPlayback,
    )

    // ── the ranking that makes a binge feel consistent ─────────────────────────

    @Test
    fun `no profile means nothing to stay consistent with, so everything scores zero`() {
        assertEquals(0, DebridSourceScoring.continuityScore(source(provider = "RD"), null))
    }

    @Test
    fun `staying on the same release beats matching only the quality`() {
        val current = profile(sourceType = "torrent", provider = "RD", quality = "1080p")
        val sameRelease = DebridSourceScoring.continuityScore(
            source(sourceType = "torrent", provider = "RD", quality = "720p"), current
        )
        val sameQualityOnly = DebridSourceScoring.continuityScore(
            source(sourceType = "usenet", provider = "AD", quality = "1080p"), current
        )
        assertTrue(
            "same release ($sameRelease) must outrank same quality ($sameQualityOnly)",
            sameRelease > sameQualityOnly
        )
    }

    @Test
    fun `a language match is worth more than a quality match`() {
        val current = profile(quality = "1080p", languages = listOf("German"))
        val sameLanguage = DebridSourceScoring.continuityScore(
            source(quality = "720p", languages = listOf("de")), current
        )
        val sameQuality = DebridSourceScoring.continuityScore(
            source(quality = "1080p", languages = listOf("en")), current
        )
        assertTrue("language ($sameLanguage) must outrank quality ($sameQuality)", sameLanguage > sameQuality)
    }

    @Test
    fun `an unset field on the profile is no preference, not a mismatch`() {
        // A profile that never knew its provider must not penalise every candidate.
        val current = profile(sourceType = "torrent")
        val a = DebridSourceScoring.continuityScore(source(sourceType = "torrent", provider = "RD"), current)
        val b = DebridSourceScoring.continuityScore(source(sourceType = "torrent", provider = "AD"), current)
        assertEquals(a, b)
    }

    @Test
    fun `more matched languages score higher than one`() {
        val current = profile(languages = listOf("German", "English"))
        val both = DebridSourceScoring.continuityScore(source(languages = listOf("de", "en")), current)
        val one = DebridSourceScoring.continuityScore(source(languages = listOf("de")), current)
        assertTrue("both ($both) must outrank one ($one)", both > one)
    }

    // ── language normalization ────────────────────────────────────────────────

    @Test
    fun `providers write languages every way imaginable and they all fold together`() {
        val expected = setOf("de", "en")
        assertEquals(expected, DebridSourceScoring.normalizeLanguageSet(listOf("German, English")))
        assertEquals(expected, DebridSourceScoring.normalizeLanguageSet(listOf("ger/eng")))
        assertEquals(expected, DebridSourceScoring.normalizeLanguageSet(listOf("DEU", " Eng ")))
        assertEquals(expected, DebridSourceScoring.normalizeLanguageSet(listOf("de|en")))
    }

    @Test
    fun `every spelling of a multi-language release collapses to one token`() {
        listOf("MULTI", "Dual Audio", "dual").forEach {
            assertEquals("failed for: $it", setOf("multi"), DebridSourceScoring.normalizeLanguageSet(listOf(it)))
        }
    }

    @Test
    fun `no languages yields an empty set rather than a blank entry`() {
        assertTrue(DebridSourceScoring.normalizeLanguageSet(null).isEmpty())
        assertTrue(DebridSourceScoring.normalizeLanguageSet(listOf("", "  ")).isEmpty())
    }

    // ── quality ranking ───────────────────────────────────────────────────────

    @Test
    fun `quality ranks from 4K down and an unknown label scores lowest`() {
        assertEquals(4, DebridSourceScoring.qualityScore("2160p"))
        assertEquals(4, DebridSourceScoring.qualityScore("4K HDR"))
        assertEquals(3, DebridSourceScoring.qualityScore("1080p"))
        assertEquals(2, DebridSourceScoring.qualityScore("720p"))
        assertEquals(1, DebridSourceScoring.qualityScore("480p"))
        assertEquals(0, DebridSourceScoring.qualityScore("CAM"))
        assertEquals(0, DebridSourceScoring.qualityScore(null))
    }

    // ── identity + direct playability ─────────────────────────────────────────

    @Test
    fun `the per-file suffix is stripped so one release keeps one identity`() {
        // Providers append _1, _2 … per episode file; without stripping it every episode would
        // look like a brand new source and continuity would never match.
        assertEquals("abc123", DebridSourceScoring.stableIdentity("ABC123_7"))
        assertEquals("abc123", DebridSourceScoring.stableIdentity("  abc123  "))
        assertNull(DebridSourceScoring.stableIdentity(null))
        assertNull(DebridSourceScoring.stableIdentity("   "))
    }

    @Test
    fun `only an http file counts as directly playable`() {
        assertTrue(DebridSourceScoring.isDirectPlayableUrl("https://host/video.mkv"))
        assertFalse(DebridSourceScoring.isDirectPlayableUrl("https://host/file.torrent"))
        assertFalse(DebridSourceScoring.isDirectPlayableUrl("magnet:?xt=urn:btih:abc"))
        assertFalse(DebridSourceScoring.isDirectPlayableUrl(null))
    }

    @Test
    fun `a direct source only earns its bonus when the profile was playing directly`() {
        val src = source(directSource = "https://host/video.mkv")
        val wanted = DebridSourceScoring.continuityScore(src, profile(directPlayback = true))
        val notWanted = DebridSourceScoring.continuityScore(src, profile(directPlayback = false))
        assertTrue("direct ($wanted) must outrank non-direct ($notWanted)", wanted > notWanted)
    }

    @Test
    fun `the profile snapshot carries the fields continuity is scored on`() {
        val src = source(provider = "RD", sourceType = "torrent", quality = "1080p", bingeGroup = "grp")
        val p = DebridSourceScoring.profileOf(src, directPlayback = true)
        assertEquals("RD", p.provider)
        assertEquals("torrent", p.sourceType)
        assertEquals("1080p", p.quality)
        assertEquals("grp", p.bingeGroup)
        assertTrue(p.directPlayback)
    }
}
