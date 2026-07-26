package com.tvonnet.debridxtreamiptv.data.prefs

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.tvonnet.debridxtreamiptv.data.model.ContentType
import com.tvonnet.debridxtreamiptv.data.model.ContinueWatchingItem
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class WatchHistoryPreferencesTest {
    private lateinit var context: Context
    private lateinit var prefs: WatchHistoryPreferences

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        context.getSharedPreferences("watch_history", Context.MODE_PRIVATE)
            .edit()
            .clear()
            .commit()
        prefs = WatchHistoryPreferences(context)
    }

    @Test
    fun saveContinueWatching_dedupesByStableSeriesKey_notByVolatileContentId() {
        val first = continueItem(
            contentId = "old-episode-id",
            title = "Pilot",
            seriesId = "series-42",
            seriesTitle = "Sample Show"
        )
        val second = continueItem(
            contentId = "new-episode-id",
            title = "Episode 2",
            seriesId = "series-42",
            seriesTitle = "Sample Show"
        )

        prefs.saveContinueWatchingItem(first)
        prefs.saveContinueWatchingItem(second)

        val list = prefs.getContinueWatchingList()
        assertEquals(1, list.size)
        assertEquals("new-episode-id", list.first().contentId)
    }

    @Test
    fun clearStatusFlow_removeThenSuppress_preventsImmediateReAdd_forSameStableKey() {
        val original = continueItem(
            contentId = "content-a",
            title = "Episode 1",
            seriesId = "series-77",
            seriesTitle = "Guard Show"
        )
        val sameLogicalItemNewId = continueItem(
            contentId = "content-b",
            title = "Episode 1 Reloaded",
            seriesId = "series-77",
            seriesTitle = "Guard Show"
        )

        prefs.saveContinueWatchingItem(original)
        prefs.removeContinueWatchingItem(original)
        prefs.suppressContinueWatchingWrite(original)
        prefs.saveContinueWatchingItem(sameLogicalItemNewId)

        assertTrue(prefs.getContinueWatchingList().isEmpty())

        prefs.saveContinueWatchingItem(sameLogicalItemNewId)
        assertEquals(1, prefs.getContinueWatchingList().size)
        assertEquals("content-b", prefs.getContinueWatchingList().first().contentId)
    }

    // ── Roadmap B2.3: the rest of the store's rules, which every "Resume" button depends on ──

    @Test
    fun resumingAgain_overwritesInPlaceAndMovesToTheFront() {
        prefs.saveContinueWatchingItem(movie(contentId = "m1", title = "Inception", position = 10_000L))
        prefs.saveContinueWatchingItem(movie(contentId = "m2", title = "Arrival", position = 20_000L))
        prefs.saveContinueWatchingItem(movie(contentId = "m1", title = "Inception", position = 55_000L))

        val list = prefs.getContinueWatchingList()

        assertEquals(2, list.size)
        assertEquals("m1", list.first().contentId)
        assertEquals("resuming again must overwrite, not append", 55_000L, list.first().currentPosition)
    }

    /**
     * The cross-source merge. Xtream gives a numeric series id and a list-prefixed title, Debrid
     * gives TMDB ids and a clean title — id-based keys can never match across the two, so identity
     * runs through the normalized title. One show must mean one row.
     */
    @Test
    fun theSameSeriesWatchedThroughBothSources_keepsASingleRow() {
        prefs.saveContinueWatchingItem(
            continueItem(contentId = "xtream-9911", title = "S01E02", seriesId = "9911", seriesTitle = "EN| Breaking Bad 4K")
        )
        prefs.saveContinueWatchingItem(
            continueItem(contentId = "tt0903747:1:3", title = "Cat's in the Bag...", seriesTitle = "Breaking Bad")
        )

        assertEquals(1, prefs.getContinueWatchingList().size)
    }

    @Test
    fun theListIsCappedSoItCannotGrowWithoutBound() {
        repeat(25) { i -> prefs.saveContinueWatchingItem(movie(contentId = "m$i", title = "Film $i")) }

        assertEquals(20, prefs.getContinueWatchingList().size)
    }

    @Test
    fun theMovieResumeBar_findsItsEntryByContentIdTmdbImdbOrTitle() {
        prefs.saveContinueWatchingItem(
            movie(contentId = "m1", title = "Inception", position = 90_000L, tmdbId = "27205", imdbId = "tt1375666")
        )

        assertNotNull(prefs.getMovieResumeItem("m1", null, null, null))
        assertNotNull(prefs.getMovieResumeItem(null, "27205", null, null))
        assertNotNull(prefs.getMovieResumeItem(null, null, "tt1375666", null))
        assertNotNull(
            "a differently-tagged provider title must still match",
            prefs.getMovieResumeItem(null, null, null, "EN| Inception (2010)")
        )
        assertNull(prefs.getMovieResumeItem("other", "0", "tt0", "Arrival"))
    }

    @Test
    fun theMovieResumeBar_ignoresEpisodes() {
        prefs.saveContinueWatchingItem(continueItem(contentId = "e1", title = "S01E01", seriesTitle = "Fargo"))

        assertNull(
            "an episode of a same-named series must never drive the movie resume bar",
            prefs.getMovieResumeItem(null, null, null, "Fargo")
        )
    }

    /**
     * A half-written value must degrade to "nothing to show" WITHOUT destroying what is stored —
     * the `CLAUDE.md` rule that a failed read may never wipe good state.
     */
    @Test
    fun aCorruptStore_readsAsEmptyButIsNotWiped() {
        prefs.saveContinueWatchingItem(movie(contentId = "m1", title = "Inception"))
        val raw = context.getSharedPreferences("watch_history", Context.MODE_PRIVATE)
        raw.edit().putString("continue_watching", "[{\"contentId\":").commit()

        assertTrue(prefs.getContinueWatchingList().isEmpty())
        assertEquals(
            "the unreadable value must be left alone so a later good read can recover it",
            "[{\"contentId\":",
            raw.getString("continue_watching", null)
        )
    }

    private fun movie(
        contentId: String,
        title: String,
        position: Long = 60_000L,
        tmdbId: String? = null,
        imdbId: String? = null
    ): ContinueWatchingItem = ContinueWatchingItem(
        contentId = contentId,
        contentType = ContentType.MOVIE,
        title = title,
        posterUrl = null,
        backdropUrl = null,
        currentPosition = position,
        totalDuration = 6_000_000L,
        lastWatchedTimestamp = 1_000L,
        streamUrl = null,
        tmdbId = tmdbId,
        imdbId = imdbId
    )

    private fun continueItem(
        contentId: String,
        title: String,
        seriesId: String? = null,
        seriesTitle: String? = null
    ): ContinueWatchingItem {
        return ContinueWatchingItem(
            contentId = contentId,
            contentType = ContentType.EPISODE,
            title = title,
            posterUrl = "https://example.com/poster.jpg",
            backdropUrl = "https://example.com/backdrop.jpg",
            currentPosition = 30_000L,
            totalDuration = 1_000_000L,
            lastWatchedTimestamp = 123456789L,
            streamUrl = "https://example.com/stream.m3u8",
            seriesId = seriesId,
            seriesTitle = seriesTitle,
            seasonNumber = 1,
            episodeNumber = 1
        )
    }
}
