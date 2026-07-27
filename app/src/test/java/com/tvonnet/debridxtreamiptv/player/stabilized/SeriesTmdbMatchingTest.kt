package com.tvonnet.debridxtreamiptv.player.stabilized

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Roadmap C10 — getting from a provider's release string to a show name TMDB will match.
 *
 * This decides whether a series gets artwork, an overview and a cast list, or none of it. It fails
 * silently: a title that does not clean up simply returns no match, and the screen looks empty for
 * a reason nobody can see. So the real provider shapes are pinned here.
 */
class SeriesTmdbMatchingTest {

    // ── title cleanup ──────────────────────────────────────────────────────────

    @Test
    fun `a full release string reduces to the show name`() {
        assertEquals(
            "Breaking Bad",
            SeriesTmdbMatching.cleanSeriesTitleForTmdb("EN | Breaking Bad S01E02 1080p WEB-DL MULTI (2008)")
        )
    }

    @Test
    fun `every episode-marker style is removed`() {
        listOf(
            "The Office S01E02",
            "The Office 1x02",
            "The Office Season 1 Episode 2",
        ).forEach {
            assertEquals("failed for: $it", "The Office", SeriesTmdbMatching.cleanSeriesTitleForTmdb(it))
        }
    }

    @Test
    fun `language and quality noise is removed`() {
        assertEquals("Dark", SeriesTmdbMatching.cleanSeriesTitleForTmdb("Dark 2160p HDR x265 Dual Audio"))
        assertEquals("Dark", SeriesTmdbMatching.cleanSeriesTitleForTmdb("[DE] Dark hindi dubbed"))
    }

    @Test
    fun `a trailing year is dropped in both spellings`() {
        assertEquals("Fargo", SeriesTmdbMatching.cleanSeriesTitleForTmdb("Fargo (2014)"))
        assertEquals("Fargo", SeriesTmdbMatching.cleanSeriesTitleForTmdb("Fargo 2014"))
    }

    @Test
    fun `a title made only of noise yields null rather than an empty search`() {
        assertNull(SeriesTmdbMatching.cleanSeriesTitleForTmdb("S01E02 1080p"))
        assertNull(SeriesTmdbMatching.cleanSeriesTitleForTmdb("   "))
        assertNull(SeriesTmdbMatching.cleanSeriesTitleForTmdb(null))
    }

    @Test
    fun `digits inside a real title survive`() {
        // The episode-marker rules must not eat a show that is named with numbers.
        assertEquals("9 1 1", SeriesTmdbMatching.cleanSeriesTitleForTmdb("9-1-1"))
        assertEquals("Stranger Things", SeriesTmdbMatching.cleanSeriesTitleForTmdb("Stranger Things"))
    }

    // ── query fallbacks ────────────────────────────────────────────────────────

    @Test
    fun `queries go from most specific to least, without duplicates`() {
        assertEquals(
            listOf("Star Wars: Andor", "Star Wars"),
            SeriesTmdbMatching.buildTmdbSearchQueries("Star Wars: Andor")
        )
        // Nothing to strip — one query, not three copies of it.
        assertEquals(listOf("Andor"), SeriesTmdbMatching.buildTmdbSearchQueries("Andor"))
    }

    @Test
    fun `a suffix that would leave too little is dropped`() {
        // "A: Long Title" must not produce a one-character query that matches everything.
        assertFalse(SeriesTmdbMatching.buildTmdbSearchQueries("A: Long Title").contains("A"))
    }

    // ── comparison keys ────────────────────────────────────────────────────────

    @Test
    fun `normalization makes casing, punctuation and a leading article irrelevant`() {
        val a = SeriesTmdbMatching.normalizeTmdbTitle("The Office")
        assertEquals(a, SeriesTmdbMatching.normalizeTmdbTitle("office"))
        assertEquals(a, SeriesTmdbMatching.normalizeTmdbTitle("THE  OFFICE!"))
    }

    @Test
    fun `a title with nothing comparable normalizes to null`() {
        assertNull(SeriesTmdbMatching.normalizeTmdbTitle("---"))
        assertNull(SeriesTmdbMatching.normalizeTmdbTitle(null))
    }

    // ── fuzzy scoring ──────────────────────────────────────────────────────────

    @Test
    fun `an exact match scores one and an unrelated title scores zero`() {
        assertEquals(1f, SeriesTmdbMatching.tokenOverlapScore("breakingbad", "breakingbad"), 0.001f)
        assertEquals(0f, SeriesTmdbMatching.tokenOverlapScore("breakingbad", "xyzqwv"), 0.001f)
    }

    @Test
    fun `a longer official name still matches when the chunk boundaries line up`() {
        assertEquals(1f, SeriesTmdbMatching.tokenOverlapScore("office", "theoffice"), 0.001f)
    }

    @Test
    fun `a trailing suffix costs only the partial chunk`() {
        assertEquals(0.5f, SeriesTmdbMatching.tokenOverlapScore("andor", "andorseason1"), 0.001f)
        val walking = SeriesTmdbMatching.tokenOverlapScore("thewalkingdead", "thewalkingdeaddeadcity")
        assertTrue("expected a partial score, got \$walking", walking > 0.5f && walking < 1.01f)
    }

    @Test
    fun `KNOWN WEAKNESS - containment scores zero when the chunk boundaries do not line up`() {
        // chunked(3) splits at fixed positions rather than producing sliding shingles, so this
        // scores by alignment, not by containment: "office" in "theoffice" scores 1.0 (above) but
        // "andor" in "starwarsandor" scores 0.0. Pinned as current behaviour, NOT endorsed - see
        // the note on tokenOverlapScore. Changing it changes which show every series resolves to.
        assertEquals(0f, SeriesTmdbMatching.tokenOverlapScore("andor", "starwarsandor"), 0.001f)
    }

    @Test
    fun `an empty side scores zero rather than dividing by nothing`() {
        assertEquals(0f, SeriesTmdbMatching.tokenOverlapScore("", "andor"), 0.001f)
        assertEquals(0f, SeriesTmdbMatching.tokenOverlapScore("andor", ""), 0.001f)
    }

    // ── artwork sanity ─────────────────────────────────────────────────────────

    @Test
    fun `the strings providers send for no artwork are treated as no artwork`() {
        assertFalse(SeriesTmdbMatching.hasUsableArtworkUrl(null))
        assertFalse(SeriesTmdbMatching.hasUsableArtworkUrl("  "))
        assertFalse(SeriesTmdbMatching.hasUsableArtworkUrl("null"))
        assertFalse(SeriesTmdbMatching.hasUsableArtworkUrl("N/A"))
        assertTrue(SeriesTmdbMatching.hasUsableArtworkUrl("http://host/poster.jpg"))
    }
}
