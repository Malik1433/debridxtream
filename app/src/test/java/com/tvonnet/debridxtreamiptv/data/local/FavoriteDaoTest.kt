package com.tvonnet.debridxtreamiptv.data.local

import android.content.Context
import androidx.arch.core.executor.testing.InstantTaskExecutorRule
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.tvonnet.debridxtreamiptv.data.local.dao.FavoriteDao
import com.tvonnet.debridxtreamiptv.data.local.entity.FavoriteEntity
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Phase 8: REAL Room DAO test (was mockk(relaxed=true) + coVerify, which asserted nothing about SQL).
 */
@ExperimentalCoroutinesApi
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [33])
class FavoriteDaoTest {

    @get:Rule
    val instantTaskExecutorRule = InstantTaskExecutorRule()

    private lateinit var db: AppDatabase
    private lateinit var favoriteDao: FavoriteDao

    private val fav1 = FavoriteEntity(
        id = 1,
        streamId = "123",
        type = "live",
        name = "Channel 123",
        iconUrl = "https://example.com/123.png",
        addedAt = 1_000L
    )
    private val fav2 = FavoriteEntity(
        id = 2,
        streamId = "456",
        type = "vod",
        name = "Movie 456",
        iconUrl = "https://example.com/456.png",
        addedAt = 2_000L
    )

    @Before
    fun setup() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        favoriteDao = db.favoriteDao()
    }

    @After
    fun teardown() = db.close()

    @Test
    fun `insertFavorite then getFavoriteByStreamId round-trips`() = runTest {
        favoriteDao.insertFavorite(fav1)

        val result = favoriteDao.getFavoriteByStreamId("123")
        assertEquals("Channel 123", result?.name)
        assertEquals("live", result?.type)
    }

    @Test
    fun `isFavorite reflects presence`() = runTest {
        favoriteDao.insertFavorite(fav1)

        assertTrue(favoriteDao.isFavorite("123"))
        assertFalse(favoriteDao.isFavorite("999"))
    }

    @Test
    fun `getAllFavorites is ordered by addedAt DESC`() = runTest {
        favoriteDao.insertFavorite(fav1) // addedAt 1000
        favoriteDao.insertFavorite(fav2) // addedAt 2000

        val all = favoriteDao.getAllFavorites().first()
        assertEquals(listOf("456", "123"), all.map { it.streamId })
    }

    @Test
    fun `getFavoritesByType filters`() = runTest {
        favoriteDao.insertFavorite(fav1)
        favoriteDao.insertFavorite(fav2)

        val vodFavs = favoriteDao.getFavoritesByType("vod").first()
        assertEquals(1, vodFavs.size)
        assertEquals("456", vodFavs.first().streamId)
    }

    @Test
    fun `deleteFavoriteByStreamId removes only that row`() = runTest {
        favoriteDao.insertFavorite(fav1)
        favoriteDao.insertFavorite(fav2)

        favoriteDao.deleteFavoriteByStreamId("123")

        assertFalse(favoriteDao.isFavorite("123"))
        assertTrue(favoriteDao.isFavorite("456"))
    }

    @Test
    fun `deleteFavorite removes the entity`() = runTest {
        favoriteDao.insertFavorite(fav1)

        favoriteDao.deleteFavorite(fav1)

        assertNull(favoriteDao.getFavoriteByStreamId("123"))
    }

    @Test
    fun `deleteAllFavorites empties the table`() = runTest {
        favoriteDao.insertFavorite(fav1)
        favoriteDao.insertFavorite(fav2)

        favoriteDao.deleteAllFavorites()

        assertEquals(0, favoriteDao.getAllFavorites().first().size)
    }
}
