package com.tvonnet.debridxtreamiptv.data.local

import android.content.Context
import androidx.arch.core.executor.testing.InstantTaskExecutorRule
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.tvonnet.debridxtreamiptv.data.local.dao.ChannelDao
import com.tvonnet.debridxtreamiptv.data.local.entity.ChannelEntity
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
 * Phase 8: REAL Room DAO test. The previous version mocked the DAO under test (mockk(relaxed=true)
 * then coVerify) — which proves nothing about the actual SQL. This builds a real in-memory Room
 * database under Robolectric and asserts on genuine query results, so a broken @Query, schema
 * mismatch, or converter bug now fails the build.
 */
@ExperimentalCoroutinesApi
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [33])
class ChannelDaoTest {

    @get:Rule
    val instantTaskExecutorRule = InstantTaskExecutorRule()

    private lateinit var db: AppDatabase
    private lateinit var channelDao: ChannelDao

    private val liveChannel = ChannelEntity(
        streamId = "123",
        name = "Test Channel 1",
        categoryId = "cat1",
        streamIcon = "http://example.com/icon1.png",
        epgChannelId = "epg1",
        added = System.currentTimeMillis(),
        isFavorite = false,
        lastWatched = null,
        streamType = "live"
    )

    private val favoriteChannel = ChannelEntity(
        streamId = "456",
        name = "News Central",
        categoryId = "cat1",
        streamIcon = "http://example.com/icon2.png",
        epgChannelId = "epg2",
        added = System.currentTimeMillis(),
        isFavorite = true,
        lastWatched = System.currentTimeMillis(),
        streamType = "live"
    )

    @Before
    fun setup() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        channelDao = db.channelDao()
    }

    @After
    fun teardown() {
        db.close()
    }

    @Test
    fun `insertChannel then getChannelById round-trips the row`() = runTest {
        channelDao.insertChannel(liveChannel)

        val result = channelDao.getChannelById("123")
        assertNotNull(result)
        assertEquals("123", result?.streamId)
        assertEquals("Test Channel 1", result?.name)
        assertEquals("cat1", result?.categoryId)
    }

    @Test
    fun `getChannelById returns null for a missing id`() = runTest {
        assertNull(channelDao.getChannelById("does-not-exist"))
    }

    @Test
    fun `insertChannels then getChannelsByCategory returns all rows in the category`() = runTest {
        channelDao.insertChannels(listOf(liveChannel, favoriteChannel))

        val result = channelDao.getChannelsByCategory("cat1")
        assertEquals(2, result.size)
        assertTrue(result.all { it.categoryId == "cat1" })
    }

    @Test
    fun `getFavoriteChannels emits only favorites`() = runTest {
        channelDao.insertChannels(listOf(liveChannel, favoriteChannel))

        val favorites = channelDao.getFavoriteChannels().first()
        assertEquals(1, favorites.size)
        assertEquals("456", favorites.first().streamId)
    }

    @Test
    fun `updateFavoriteStatus flips the persisted flag`() = runTest {
        channelDao.insertChannel(liveChannel) // isFavorite = false

        channelDao.updateFavoriteStatus("123", true)

        assertTrue(channelDao.getChannelById("123")?.isFavorite == true)
        assertEquals(1, channelDao.getFavoriteChannels().first().size)
    }

    @Test
    fun `updateLastWatched persists the timestamp`() = runTest {
        channelDao.insertChannel(liveChannel)

        channelDao.updateLastWatched("123", 42L)

        assertEquals(42L, channelDao.getChannelById("123")?.lastWatched)
    }

    @Test
    fun `deleteChannel removes only that row`() = runTest {
        channelDao.insertChannels(listOf(liveChannel, favoriteChannel))

        channelDao.deleteChannel(liveChannel)

        assertNull(channelDao.getChannelById("123"))
        assertNotNull(channelDao.getChannelById("456"))
    }

    @Test
    fun `deleteChannelsByCategory clears the category`() = runTest {
        channelDao.insertChannels(listOf(liveChannel, favoriteChannel))

        channelDao.deleteChannelsByCategory("cat1")

        assertEquals(0, channelDao.getChannelsByCategory("cat1").size)
    }

    @Test
    fun `deleteAllChannels empties the table`() = runTest {
        channelDao.insertChannels(listOf(liveChannel, favoriteChannel))

        channelDao.deleteAllChannels()

        assertEquals(0, channelDao.countAllLiveChannels())
    }

    @Test
    fun `searchChannels matches on a name substring`() = runTest {
        channelDao.insertChannels(listOf(liveChannel, favoriteChannel))

        val hits = channelDao.searchChannels("News")
        assertEquals(1, hits.size)
        assertEquals("456", hits.first().streamId)
    }

    @Test
    fun `countAllLiveChannels counts distinct live rows`() = runTest {
        channelDao.insertChannels(listOf(liveChannel, favoriteChannel))

        assertEquals(2, channelDao.countAllLiveChannels())
    }
}
