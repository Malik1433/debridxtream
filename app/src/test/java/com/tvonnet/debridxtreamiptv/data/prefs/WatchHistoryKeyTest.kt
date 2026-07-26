package com.tvonnet.debridxtreamiptv.data.prefs

import com.tvonnet.debridxtreamiptv.data.model.ContentType
import com.tvonnet.debridxtreamiptv.data.model.ContinueWatchingItem
import com.tvonnet.debridxtreamiptv.data.prefs.WatchHistoryPreferences.Companion.consumeSuppressedContinueWatchingKey
import com.tvonnet.debridxtreamiptv.data.prefs.WatchHistoryPreferences.Companion.continueWatchingKey
import com.tvonnet.debridxtreamiptv.data.prefs.WatchHistoryPreferences.Companion.normalizeTitleKey
import com.tvonnet.debridxtreamiptv.data.prefs.WatchHistoryPreferences.Companion.suppressContinueWatchingWrite
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Roadmap B2.3 — Continue-Watching **identity**, the thing resume is built on.
 *
 * The same film or series can arrive from Xtream (numeric `series_id`, list-prefixed titles like
 * "EN| Breaking Bad 4K") or from a Debrid addon (TMDB/IMDB ids, clean titles). If the two do not
 * collapse to one key the user sees the same show twice in Continue Watching and resumes the wrong
 * copy. `continueWatchingKey` / `normalizeTitleKey` are that collapse, and they are pure — so they
 * get frozen here rather than re-derived by reading the regexes.
 */
class WatchHistoryKeyTest {

    // ── normalizeTitleKey ────────────────────────────────────────────────────

    @Test
    fun `an IPTV list prefix and a quality tag do not create a second identity`() {
        assertEquals("breaking-bad", normalizeTitleKey("EN| Breaking Bad 4K"))
        assertEquals("breaking-bad", normalizeTitleKey("Breaking Bad"))
    }

    @Test
    fun `bracketed segments and years are stripped`() {
        assertEquals("inception", normalizeTitleKey("Inception (2010)"))
        assertEquals("inception", normalizeTitleKey("Inception 2010"))
        assertEquals("inception", normalizeTitleKey("Inception [MULTI]"))
    }

    /** The year strip must not erase films whose whole title is a year. */
    @Test
    fun `a title that is only a year survives`() {
        assertEquals("1917", normalizeTitleKey("1917"))
        assertEquals("2012", normalizeTitleKey("2012"))
    }

    @Test
    fun `case punctuation and spacing are normalized away`() {
        assertEquals("the-lord-of-the-rings", normalizeTitleKey("  The Lord of the Rings  "))
        assertEquals("wall-e", normalizeTitleKey("WALL·E"))
    }

    @Test
    fun `blank input yields an empty key rather than a fake one`() {
        assertEquals("", normalizeTitleKey(null))
        assertEquals("", normalizeTitleKey("   "))
    }

    // ── continueWatchingKey ──────────────────────────────────────────────────

    /** The whole point: one series, two sources, one Continue-Watching row. */
    @Test
    fun `the same series from IPTV and from Debrid collapses to one key`() {
        val iptv = episode(
            contentId = "xtream-9911",
            seriesId = "9911",
            seriesTitle = "EN| Breaking Bad 4K",
            title = "S01E02"
        )
        val debrid = episode(
            contentId = "tt0903747:1:2",
            tmdbId = "1396",
            seriesTitle = "Breaking Bad",
            title = "Cat's in the Bag..."
        )

        assertEquals(continueWatchingKey(iptv), continueWatchingKey(debrid))
        assertEquals("series:title:breaking-bad", continueWatchingKey(iptv))
    }

    @Test
    fun `a movie and a series with the same title stay distinct`() {
        val movie = movie(contentId = "m1", title = "Fargo")
        val series = episode(contentId = "s1", seriesTitle = "Fargo", title = "S01E01")

        assertNotEquals(continueWatchingKey(movie), continueWatchingKey(series))
        assertEquals("movie:title:fargo", continueWatchingKey(movie))
        assertEquals("series:title:fargo", continueWatchingKey(series))
    }

    @Test
    fun `an episode keys off the series title, not the episode title`() {
        val first = episode(contentId = "a", seriesTitle = "The Wire", title = "The Target")
        val second = episode(contentId = "b", seriesTitle = "The Wire", title = "The Detail")

        assertEquals(continueWatchingKey(first), continueWatchingKey(second))
    }

    /** When a title normalizes to nothing, identity falls back down the id ladder. */
    @Test
    fun `an unusable title falls back to seriesId then tmdb then imdb then contentId`() {
        assertEquals(
            "series:seriesId:9911",
            continueWatchingKey(episode(contentId = "c1", seriesId = "9911", seriesTitle = "[MULTI]", title = ""))
        )
        assertEquals(
            "movie:tmdb:27205",
            continueWatchingKey(movie(contentId = "c2", title = "", tmdbId = "27205", imdbId = "tt1375666"))
        )
        assertEquals(
            "movie:imdb:tt1375666",
            continueWatchingKey(movie(contentId = "c3", title = "", imdbId = "tt1375666"))
        )
        assertEquals(
            "movie:content:c4",
            continueWatchingKey(movie(contentId = "c4", title = ""))
        )
    }

    // ── suppression (the "I just removed this, do not re-add it" guard) ──────

    @Test
    fun `a suppressed key is consumed exactly once`() {
        val key = "movie:title:inception"

        suppressContinueWatchingWrite(key)

        assertTrue("the write right after a removal must be suppressed", consumeSuppressedContinueWatchingKey(key))
        assertFalse("suppression is one-shot — later writes must land", consumeSuppressedContinueWatchingKey(key))
    }

    @Test
    fun `an unknown key is never suppressed`() {
        assertFalse(consumeSuppressedContinueWatchingKey("movie:title:never-suppressed"))
    }

    // ------------------------------------------------------------------ fixtures

    private fun movie(
        contentId: String,
        title: String,
        tmdbId: String? = null,
        imdbId: String? = null
    ) = ContinueWatchingItem(
        contentId = contentId,
        contentType = ContentType.MOVIE,
        title = title,
        posterUrl = null,
        backdropUrl = null,
        currentPosition = 60_000L,
        totalDuration = 6_000_000L,
        lastWatchedTimestamp = 1_000L,
        streamUrl = null,
        tmdbId = tmdbId,
        imdbId = imdbId
    )

    private fun episode(
        contentId: String,
        title: String,
        seriesTitle: String,
        seriesId: String? = null,
        tmdbId: String? = null
    ) = ContinueWatchingItem(
        contentId = contentId,
        contentType = ContentType.EPISODE,
        title = title,
        posterUrl = null,
        backdropUrl = null,
        currentPosition = 60_000L,
        totalDuration = 2_700_000L,
        lastWatchedTimestamp = 1_000L,
        streamUrl = null,
        tmdbId = tmdbId,
        seriesId = seriesId,
        seriesTitle = seriesTitle
    )
}
