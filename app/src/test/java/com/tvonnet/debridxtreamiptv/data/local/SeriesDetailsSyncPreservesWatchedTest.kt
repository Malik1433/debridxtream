package com.tvonnet.debridxtreamiptv.data.local

import android.content.Context
import androidx.arch.core.executor.testing.InstantTaskExecutorRule
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.tvonnet.debridxtreamiptv.data.local.dao.SeriesDao
import com.tvonnet.debridxtreamiptv.data.local.entity.EpisodeEntity
import com.tvonnet.debridxtreamiptv.data.local.entity.SeasonEntity
import com.tvonnet.debridxtreamiptv.data.local.entity.SeriesEntity
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * A series re-sync must not throw away what the player recorded (bug found 2026-09-10).
 *
 * `saveSeriesDetails` deletes a series' episode rows and re-inserts what the provider just sent.
 * Three columns on that row are written by THIS DEVICE and are absent from the provider payload —
 * `is_watched`, `resume_position` and `duration` — so the re-insert silently reset every one of
 * them. The sync runs whenever a series detail is opened, which is why a customer's watched ticks
 * survived only until the next launch: the ticks were real, the refresh wiped them.
 */
@ExperimentalCoroutinesApi
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [33])
class SeriesDetailsSyncPreservesWatchedTest {

    @get:Rule
    val instantTaskExecutorRule = InstantTaskExecutorRule()

    private lateinit var db: AppDatabase
    private lateinit var dao: SeriesDao

    private val seriesId = "s-100"

    @Before
    fun setup() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        dao = db.seriesDao()
    }

    @After
    fun teardown() = db.close()

    private fun season(number: Int) = SeasonEntity(
        seriesId = seriesId,
        seasonNumber = number,
        name = "Season $number",
        overview = null,
        airDate = null,
        cover = null
    )

    private fun episode(id: String, number: Int) = EpisodeEntity(
        episodeId = id,
        seriesId = seriesId,
        seasonNumber = 1,
        episodeNumber = number,
        title = "Episode $number",
        containerExtension = "mkv",
        streamType = "series",
        durationSecs = "1500",
        added = null,
        customSid = null,
        directSource = null,
        thumbnail = null,
        plot = null,
        cast = null,
        director = null,
        genre = null,
        releaseDate = null,
        rating = null
    )

    /** The provider listing, exactly as a fresh fetch produces it: no playback state at all. */
    private val remoteListing = listOf(episode("e-1", 1), episode("e-2", 2))

    private suspend fun seedSeries() {
        db.seriesDao().insertSeries(
            listOf(
                SeriesEntity(
                    seriesId = seriesId,
                    name = "Test Series",
                    cover = null,
                    plot = null,
                    cast = null,
                    director = null,
                    genre = null,
                    releaseDate = null,
                    rating = null,
                    rating5based = null,
                    categoryId = "c-1",
                    num = 1
                )
            )
        )
        dao.saveSeriesDetails(seriesId, listOf(season(1)), remoteListing)
    }

    @Test
    fun `a re-sync keeps the watched flag the player wrote`() = runTest {
        seedSeries()
        dao.updatePlaybackStatus(episodeId = "e-1", isWatched = true, resumePosition = 0L, duration = 1_500_000L)

        // The customer reopens the series; the detail fetch runs again with the same listing.
        dao.saveSeriesDetails(seriesId, listOf(season(1)), remoteListing)

        val after = dao.getEpisodePlaybackState(seriesId).associateBy { it.episodeId }
        assertTrue("the watched episode came back unwatched", after.getValue("e-1").is_watched)
        assertEquals(1_500_000L, after.getValue("e-1").duration)
        assertFalse("an untouched episode must stay unwatched", after.getValue("e-2").is_watched)
    }

    @Test
    fun `a re-sync keeps a mid-episode resume position`() = runTest {
        seedSeries()
        dao.updatePlaybackStatus(episodeId = "e-2", isWatched = false, resumePosition = 420_000L, duration = 1_500_000L)

        dao.saveSeriesDetails(seriesId, listOf(season(1)), remoteListing)

        val after = dao.getEpisodePlaybackState(seriesId).associateBy { it.episodeId }
        assertEquals(420_000L, after.getValue("e-2").resume_position)
        assertFalse(after.getValue("e-2").is_watched)
    }

    @Test
    fun `an empty listing from a failed fetch leaves the cached episodes alone`() = runTest {
        seedSeries()
        dao.updatePlaybackStatus(episodeId = "e-1", isWatched = true, resumePosition = 0L, duration = 1_500_000L)

        // A partial or failed get_series_info reaches the DAO as an empty episode list.
        dao.saveSeriesDetails(seriesId, emptyList(), emptyList())

        val after = dao.getEpisodePlaybackState(seriesId).associateBy { it.episodeId }
        assertEquals("a failed fetch emptied the series", setOf("e-1", "e-2"), after.keys)
        assertTrue(after.getValue("e-1").is_watched)
    }

    @Test
    fun `an episode the provider stopped listing simply goes, and a new one starts unwatched`() = runTest {
        seedSeries()
        dao.updatePlaybackStatus(episodeId = "e-1", isWatched = true, resumePosition = 0L, duration = 1_500_000L)
        dao.updatePlaybackStatus(episodeId = "e-2", isWatched = true, resumePosition = 0L, duration = 1_500_000L)

        // The provider renumbers: e-2 disappears, e-3 arrives.
        dao.saveSeriesDetails(seriesId, listOf(season(1)), listOf(episode("e-1", 1), episode("e-3", 3)))

        val after = dao.getEpisodePlaybackState(seriesId).associateBy { it.episodeId }
        assertEquals(setOf("e-1", "e-3"), after.keys)
        assertTrue("the surviving episode keeps its state", after.getValue("e-1").is_watched)
        assertFalse("a newly listed episode has nothing to carry over", after.getValue("e-3").is_watched)
    }
}
