package com.tvonnet.debridxtreamiptv.data.local.dao

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.tvonnet.debridxtreamiptv.data.local.AppDatabase
import com.tvonnet.debridxtreamiptv.data.local.entity.SeriesEntity
import com.tvonnet.debridxtreamiptv.data.local.entity.VodEntity
import com.tvonnet.debridxtreamiptv.data.repository.CategoryFetchFreshness
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * B-8: the freshness inputs the category-open gate reads from Room, against the real schema.
 * The decision itself is pure (CategoryFetchFreshnessTest); here we pin that COUNT/MAX(cachedAt)
 * aggregate and the fresh-path list read return what the gate assumes — per category, in synced
 * order — so the "skip the refetch" branch can never be fed another category's data.
 */
@RunWith(AndroidJUnit4::class)
class CategoryFreshnessDaoTest {

    private lateinit var db: AppDatabase

    @Before
    fun createDb() {
        db = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(),
            AppDatabase::class.java
        ).build()
    }

    @After
    fun closeDb() {
        db.close()
    }

    private fun movie(id: String, categoryId: String, num: Int, cachedAt: Long) = VodEntity(
        streamId = id,
        name = "Movie $id",
        streamIcon = null,
        rating = null,
        rating5based = null,
        added = null,
        categoryId = categoryId,
        category_ids = emptyList(),
        containerExtension = null,
        customSid = null,
        directSource = null,
        releaseDate = null,
        duration = null,
        plot = null,
        cast = null,
        director = null,
        genre = null,
        youtubeTrailer = null,
        cover = null,
        ratingImdb = null,
        num = num,
        cachedAt = cachedAt
    )

    private fun series(id: String, categoryId: String, num: Int, cachedAt: Long) = SeriesEntity(
        seriesId = id,
        name = "Series $id",
        cover = null,
        plot = null,
        cast = null,
        director = null,
        genre = null,
        releaseDate = null,
        rating = null,
        rating5based = null,
        categoryId = categoryId,
        category_ids = emptyList(),
        backdrop_path = emptyList(),
        youtube_trailer = null,
        num = num,
        cachedAt = cachedAt
    )

    @Test
    fun emptyCategoryReportsZeroRowsAndNoTimestamp() = runBlocking {
        val freshness = db.vodDao().getCategoryFreshness("42")
        assertEquals(0, freshness.rowCount)
        assertNull(freshness.newestCachedAt)
        assertFalse(
            CategoryFetchFreshness.isFresh(
                freshness.rowCount, freshness.newestCachedAt, System.currentTimeMillis()
            )
        )
    }

    @Test
    fun freshnessIsScopedToTheAskedCategory() = runBlocking {
        val now = System.currentTimeMillis()
        db.vodDao().insertMovies(
            listOf(
                movie("1", categoryId = "fresh", num = 0, cachedAt = now),
                movie("2", categoryId = "stale", num = 0, cachedAt = now - CategoryFetchFreshness.CATEGORY_TTL_MS - 1)
            )
        )

        val fresh = db.vodDao().getCategoryFreshness("fresh")
        assertTrue(CategoryFetchFreshness.isFresh(fresh.rowCount, fresh.newestCachedAt, now))

        val stale = db.vodDao().getCategoryFreshness("stale")
        assertFalse(CategoryFetchFreshness.isFresh(stale.rowCount, stale.newestCachedAt, now))
    }

    @Test
    fun newestRowDecidesTheCategoryTimestamp() = runBlocking {
        val now = System.currentTimeMillis()
        val old = now - CategoryFetchFreshness.CATEGORY_TTL_MS * 2
        db.vodDao().insertMovies(
            listOf(
                movie("1", categoryId = "7", num = 0, cachedAt = old),
                movie("2", categoryId = "7", num = 1, cachedAt = now)
            )
        )
        assertEquals(now, db.vodDao().getCategoryFreshness("7").newestCachedAt)
    }

    @Test
    fun freshPathListReturnsOnlyThatCategoryInSyncedOrder() = runBlocking {
        val now = System.currentTimeMillis()
        db.vodDao().insertMovies(
            listOf(
                movie("b", categoryId = "7", num = 1, cachedAt = now),
                movie("a", categoryId = "7", num = 0, cachedAt = now),
                movie("x", categoryId = "8", num = 0, cachedAt = now)
            )
        )
        val rows = db.vodDao().getMoviesByCategorySync("7")
        assertEquals(listOf("a", "b"), rows.map { it.streamId })
    }

    @Test
    fun seriesFreshnessAndListBehaveTheSameWay() = runBlocking {
        val now = System.currentTimeMillis()
        db.seriesDao().insertSeries(
            listOf(
                series("s2", categoryId = "9", num = 1, cachedAt = now),
                series("s1", categoryId = "9", num = 0, cachedAt = now),
                series("s3", categoryId = "10", num = 0, cachedAt = now - CategoryFetchFreshness.CATEGORY_TTL_MS - 1)
            )
        )

        val fresh = db.seriesDao().getCategoryFreshness("9")
        assertTrue(CategoryFetchFreshness.isFresh(fresh.rowCount, fresh.newestCachedAt, now))
        assertEquals(listOf("s1", "s2"), db.seriesDao().getSeriesByCategorySync("9").map { it.seriesId })

        val stale = db.seriesDao().getCategoryFreshness("10")
        assertFalse(CategoryFetchFreshness.isFresh(stale.rowCount, stale.newestCachedAt, now))
    }
}
