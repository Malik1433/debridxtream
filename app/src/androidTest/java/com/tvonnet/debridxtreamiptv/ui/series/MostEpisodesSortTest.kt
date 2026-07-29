package com.tvonnet.debridxtreamiptv.ui.series

import androidx.paging.PagingSource
import androidx.room.Room
import androidx.sqlite.db.SimpleSQLiteQuery
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.tvonnet.debridxtreamiptv.data.local.AppDatabase
import com.tvonnet.debridxtreamiptv.data.local.entity.EpisodeEntity
import com.tvonnet.debridxtreamiptv.data.local.entity.SeriesEntity
import com.tvonnet.debridxtreamiptv.features.seriesv2.data.model.EpisodeEntityV2
import com.tvonnet.debridxtreamiptv.features.seriesv2.data.model.SeriesEntityV2
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * B-3: the "Most Episodes" sort, executed against a real database.
 *
 * This is hand-written raw SQL behind a `@RawQuery`, so the compiler cannot check it — a typo ships
 * and only surfaces when a user taps the chip. The query was also just rewritten: it used to order by
 * two **correlated** subqueries (a COUNT over `episodes` and one over `episodes_v2_core`, re-run for
 * every series row) and now pre-aggregates each table once and joins. Same numbers, computed once —
 * which is exactly the kind of change that is easy to get subtly wrong, so the ordering is pinned
 * here rather than eyeballed.
 *
 * The counts are deliberately split across the two tables and the third series has none at all, so a
 * broken join, a dropped IFNULL or a MAX collapsed to one side all show up as a wrong order.
 */
@RunWith(AndroidJUnit4::class)
class MostEpisodesSortTest {

    private lateinit var db: AppDatabase

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(),
            AppDatabase::class.java
        ).build()
    }

    @After
    fun tearDown() = db.close()

    @Test
    fun seriesAreOrderedByTheirHighestEpisodeCountAcrossBothTables() = runBlocking {
        // legacy `episodes` only
        seedLegacySeries("s-legacy", "Legacy Show")
        db.seriesDao().insertEpisodes((1..5).map { legacyEpisode("s-legacy", it) })

        // v2 `episodes_v2_core` only — and MORE of them, so it must come first
        seedLegacySeries("s-v2", "V2 Show")
        seedV2Series("s-v2")
        db.episodeDaoV2().insertAll((1..9).map { v2Episode("s-v2", it) })

        // no episodes anywhere: must sort last, not first, and must not be dropped by the joins
        seedLegacySeries("s-none", "Nothing Cached")

        val ordered = loadAll()

        assertEquals(listOf("s-v2", "s-legacy", "s-none"), ordered)
    }

    @Test
    fun aSeriesCountedInBothTablesRanksOnTheHigherSideNotTheSum() = runBlocking {
        // MAX, not addition: 2 + 3 would beat 4, but max(2,3) must not.
        seedLegacySeries("s-both", "Both Tables")
        seedV2Series("s-both")
        db.seriesDao().insertEpisodes((1..2).map { legacyEpisode("s-both", it) })
        db.episodeDaoV2().insertAll((1..3).map { v2Episode("s-both", it) })

        seedLegacySeries("s-four", "Four Legacy")
        db.seriesDao().insertEpisodes((1..4).map { legacyEpisode("s-four", it) })

        assertEquals(listOf("s-four", "s-both"), loadAll())
    }

    @Test
    fun equalCountsFallBackToAlphabeticalOrder() = runBlocking {
        seedLegacySeries("s-b", "Bravo")
        seedLegacySeries("s-a", "Alpha")

        assertEquals(listOf("s-a", "s-b"), loadAll())
    }

    // ------------------------------------------------------------------ helpers

    /**
     * Executes the PRODUCTION string, not a copy of it. That distinction is the point: raw SQL behind
     * a `@RawQuery` is unchecked by the compiler, so a test carrying its own duplicate would keep
     * passing while the real query drifted or broke.
     */
    private suspend fun loadAll(): List<String> {
        val page = db.seriesDao().getSeriesByCategoryRaw(
            SimpleSQLiteQuery(buildMostEpisodesSql(whereClause = ""))
        ).load(
            PagingSource.LoadParams.Refresh(key = null, loadSize = 50, placeholdersEnabled = false)
        )
        val result = page as? PagingSource.LoadResult.Page
            ?: error("query failed to load: $page")
        return result.data.map { it.seriesId }
    }

    private suspend fun seedLegacySeries(id: String, name: String) {
        db.seriesDao().insertSeries(
            listOf(
                SeriesEntity(
                    seriesId = id, name = name, cover = null, plot = null, cast = null,
                    director = null, genre = null, releaseDate = null, rating = null,
                    rating5based = null, categoryId = "cat-1", num = 1
                )
            )
        )
    }

    /** `episodes_v2_core` has an FK onto `series_v2_core`, so the parent row has to exist. */
    private suspend fun seedV2Series(id: String) {
        db.seriesDaoV2().insertAll(
            listOf(
                SeriesEntityV2(
                    seriesId = id, num = 1, name = id, title = null, year = null, streamType = null,
                    cover = null, plot = null, cast = null, director = null, genre = null,
                    releaseDate = null, lastModified = null, rating = null, rating5based = null,
                    backdropPath = emptyList(), youtubeTrailer = null, episodeRunTime = null,
                    categoryId = "cat-1"
                )
            )
        )
    }

    private fun legacyEpisode(seriesId: String, n: Int) = EpisodeEntity(
        episodeId = "$seriesId-legacy-$n", seriesId = seriesId, seasonNumber = 1, episodeNumber = n,
        title = "E$n", containerExtension = "mkv", streamType = "series", durationSecs = null,
        added = null, customSid = null, directSource = null, thumbnail = null, plot = null,
        cast = null, director = null, genre = null, releaseDate = null, rating = null
    )

    private fun v2Episode(seriesId: String, n: Int) = EpisodeEntityV2(
        episodeId = "$seriesId-v2-$n", seriesId = seriesId, seasonNumber = 1, episodeNumber = n,
        title = "E$n", containerExtension = "mkv", streamType = "series", durationSecs = null,
        added = null, customSid = null, directSource = null, thumbnail = null, plot = null,
        cast = null, director = null, genre = null, releaseDate = null, rating = null,
        isWatched = false, resumePosition = 0, duration = 0
    )
}
