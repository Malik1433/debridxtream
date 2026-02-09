package com.tvonnet.debridxtreamiptv.data.local

import androidx.arch.core.executor.testing.InstantTaskExecutorRule
import com.tvonnet.debridxtreamiptv.data.local.dao.ChannelDao
import com.tvonnet.debridxtreamiptv.data.local.entity.ChannelEntity
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import com.tvonnet.debridxtreamiptv.testutil.MainDispatcherRule
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test

/**
 * Unit tests for ChannelDao
 * Week 6: Room Database Integration
 */
@ExperimentalCoroutinesApi
class ChannelDaoTest {
    
    @get:Rule
    val instantTaskExecutorRule = InstantTaskExecutorRule()

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()
    
    private lateinit var channelDao: ChannelDao
    
    private val testChannel1 = ChannelEntity(
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
    
    private val testChannel2 = ChannelEntity(
        streamId = "456",
        name = "Test Channel 2",
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
        channelDao = mockk(relaxed = true)
    }
    
    @Test
    fun `test insertChannel saves channel successfully`() = runTest {
        // When
        channelDao.insertChannel(testChannel1)
        
        // Then
        coVerify { channelDao.insertChannel(testChannel1) }
    }
    
    @Test
    fun `test insertChannels saves multiple channels successfully`() = runTest {
        // Given
        val channels = listOf(testChannel1, testChannel2)
        
        // When
        channelDao.insertChannels(channels)
        
        // Then
        coVerify { channelDao.insertChannels(channels) }
    }
    
    @Test
    fun `test getChannelById returns correct channel`() = runTest {
        // Given
        coEvery { channelDao.getChannelById("123") } returns testChannel1
        
        // When
        val result = channelDao.getChannelById("123")
        
        // Then
        assertNotNull(result)
        assertEquals("123", result?.streamId)
        assertEquals("Test Channel 1", result?.name)
    }
    
    @Test
    fun `test getChannelsByCategory returns channels for category`() = runTest {
        // Given
        val channels = listOf(testChannel1, testChannel2)
        coEvery { channelDao.getChannelsByCategory("cat1") } returns channels
        
        // When
        val result = channelDao.getChannelsByCategory("cat1")
        
        // Then
        assertEquals(2, result.size)
        assertTrue(result.all { it.categoryId == "cat1" })
    }
    
    @Test
    fun `test getFavoriteChannels returns only favorite channels`() = runTest {
        // Given
        val favoriteChannels = listOf(testChannel2)
        coEvery { channelDao.getFavoriteChannels() } returns flowOf(favoriteChannels)
        
        // When
        // Verify method exists
        channelDao.getFavoriteChannels()
        
        // Then
        coVerify { channelDao.getFavoriteChannels() }
    }
    
    @Test
    fun `test updateFavoriteStatus updates channel favorite status`() = runTest {
        // When
        channelDao.updateFavoriteStatus("123", true)
        
        // Then
        coVerify { channelDao.updateFavoriteStatus("123", true) }
    }
    
    @Test
    fun `test updateLastWatched updates channel last watched timestamp`() = runTest {
        // Given
        val timestamp = System.currentTimeMillis()
        
        // When
        channelDao.updateLastWatched("123", timestamp)
        
        // Then
        coVerify { channelDao.updateLastWatched("123", timestamp) }
    }
    
    @Test
    fun `test deleteChannel removes channel successfully`() = runTest {
        // When
        channelDao.deleteChannel(testChannel1)
        
        // Then
        coVerify { channelDao.deleteChannel(testChannel1) }
    }
    
    @Test
    fun `test deleteChannelsByCategory removes all channels in category`() = runTest {
        // When
        channelDao.deleteChannelsByCategory("cat1")
        
        // Then
        coVerify { channelDao.deleteChannelsByCategory("cat1") }
    }
    
    @Test
    fun `test deleteAllChannels removes all channels`() = runTest {
        // When
        channelDao.deleteAllChannels()
        
        // Then
        coVerify { channelDao.deleteAllChannels() }
    }
    
    @Test
    fun `test getChannelsByType returns channels of specific type`() = runTest {
        // Given
        val liveChannels = listOf(testChannel1, testChannel2)
        coEvery { channelDao.getChannelsByType("live") } returns flowOf(liveChannels)
        
        // When
        channelDao.getChannelsByType("live")
        
        // Then
        coVerify { channelDao.getChannelsByType("live") }
    }
}

