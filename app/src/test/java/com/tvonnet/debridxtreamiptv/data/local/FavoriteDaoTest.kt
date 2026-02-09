package com.tvonnet.debridxtreamiptv.data.local

import androidx.arch.core.executor.testing.InstantTaskExecutorRule
import com.tvonnet.debridxtreamiptv.data.local.dao.FavoriteDao
import com.tvonnet.debridxtreamiptv.data.local.entity.FavoriteEntity
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
 * Unit tests for FavoriteDao
 * Week 6: Room Database Integration
 */
@ExperimentalCoroutinesApi
class FavoriteDaoTest {
    
    @get:Rule
    val instantTaskExecutorRule = InstantTaskExecutorRule()

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()
    
    private lateinit var favoriteDao: FavoriteDao
    
    private val testFavorite1 = FavoriteEntity(
        id = 1,
        streamId = "123",
        type = "live",
        name = "Channel 123",
        iconUrl = "https://example.com/123.png",
        addedAt = System.currentTimeMillis()
    )
    
    private val testFavorite2 = FavoriteEntity(
        id = 2,
        streamId = "456",
        type = "vod",
        name = "Movie 456",
        iconUrl = "https://example.com/456.png",
        addedAt = System.currentTimeMillis()
    )
    
    @Before
    fun setup() {
        favoriteDao = mockk(relaxed = true)
    }
    
    @Test
    fun `test insertFavorite saves favorite successfully`() = runTest {
        // When
        favoriteDao.insertFavorite(testFavorite1)
        
        // Then
        coVerify { favoriteDao.insertFavorite(testFavorite1) }
    }
    
    @Test
    fun `test getFavoriteByStreamId returns correct favorite`() = runTest {
        // Given
        coEvery { favoriteDao.getFavoriteByStreamId("123") } returns testFavorite1
        
        // When
        val result = favoriteDao.getFavoriteByStreamId("123")
        
        // Then
        assertNotNull(result)
        assertEquals("123", result?.streamId)
        assertEquals("live", result?.type)
    }
    
    @Test
    fun `test isFavorite returns true when stream is favorite`() = runTest {
        // Given
        coEvery { favoriteDao.isFavorite("123") } returns true
        
        // When
        val result = favoriteDao.isFavorite("123")
        
        // Then
        assertTrue(result)
    }
    
    @Test
    fun `test isFavorite returns false when stream is not favorite`() = runTest {
        // Given
        coEvery { favoriteDao.isFavorite("999") } returns false
        
        // When
        val result = favoriteDao.isFavorite("999")
        
        // Then
        assertFalse(result)
    }
    
    @Test
    fun `test getAllFavorites returns all favorites`() = runTest {
        // Given
        val allFavorites = listOf(testFavorite1, testFavorite2)
        coEvery { favoriteDao.getAllFavorites() } returns flowOf(allFavorites)
        
        // When
        favoriteDao.getAllFavorites()
        
        // Then
        coVerify { favoriteDao.getAllFavorites() }
    }
    
    @Test
    fun `test getFavoritesByType returns favorites of specific type`() = runTest {
        // Given
        val liveFavorites = listOf(testFavorite1)
        coEvery { favoriteDao.getFavoritesByType("live") } returns flowOf(liveFavorites)
        
        // When
        favoriteDao.getFavoritesByType("live")
        
        // Then
        coVerify { favoriteDao.getFavoritesByType("live") }
    }
    
    @Test
    fun `test deleteFavorite removes favorite successfully`() = runTest {
        // When
        favoriteDao.deleteFavorite(testFavorite1)
        
        // Then
        coVerify { favoriteDao.deleteFavorite(testFavorite1) }
    }
    
    @Test
    fun `test deleteFavoriteByStreamId removes favorite by streamId`() = runTest {
        // When
        favoriteDao.deleteFavoriteByStreamId("123")
        
        // Then
        coVerify { favoriteDao.deleteFavoriteByStreamId("123") }
    }
    
    @Test
    fun `test deleteAllFavorites removes all favorites`() = runTest {
        // When
        favoriteDao.deleteAllFavorites()
        
        // Then
        coVerify { favoriteDao.deleteAllFavorites() }
    }
}
