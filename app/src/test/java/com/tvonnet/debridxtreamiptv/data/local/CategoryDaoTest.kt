package com.tvonnet.debridxtreamiptv.data.local

import androidx.arch.core.executor.testing.InstantTaskExecutorRule
import com.tvonnet.debridxtreamiptv.data.local.dao.CategoryDao
import com.tvonnet.debridxtreamiptv.data.local.entity.CategoryEntity
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
 * Unit tests for CategoryDao
 * Week 6: Room Database Integration
 */
@ExperimentalCoroutinesApi
class CategoryDaoTest {
    
    @get:Rule
    val instantTaskExecutorRule = InstantTaskExecutorRule()

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()
    
    private lateinit var categoryDao: CategoryDao
    
    private val testCategory1 = CategoryEntity(
        categoryId = "cat1",
        categoryName = "Sports",
        type = "live"
    )
    
    private val testCategory2 = CategoryEntity(
        categoryId = "cat2",
        categoryName = "Movies",
        type = "vod"
    )
    
    @Before
    fun setup() {
        categoryDao = mockk(relaxed = true)
    }
    
    @Test
    fun `test insertCategory saves category successfully`() = runTest {
        // When
        categoryDao.insertCategory(testCategory1)
        
        // Then
        coVerify { categoryDao.insertCategory(testCategory1) }
    }
    
    @Test
    fun `test insertCategories saves multiple categories successfully`() = runTest {
        // Given
        val categories = listOf(testCategory1, testCategory2)
        
        // When
        categoryDao.insertCategories(categories)
        
        // Then
        coVerify { categoryDao.insertCategories(categories) }
    }
    
    @Test
    fun `test getCategoryById returns correct category`() = runTest {
        // Given
        coEvery { categoryDao.getCategoryById("cat1") } returns testCategory1
        
        // When
        val result = categoryDao.getCategoryById("cat1")
        
        // Then
        assertNotNull(result)
        assertEquals("cat1", result?.categoryId)
        assertEquals("Sports", result?.categoryName)
    }
    
    @Test
    fun `test getCategoriesByType returns categories of specific type`() = runTest {
        // Given
        val liveCategories = listOf(testCategory1)
        coEvery { categoryDao.getCategoriesByType("live") } returns flowOf(liveCategories)
        
        // When
        categoryDao.getCategoriesByType("live")
        
        // Then
        coVerify { categoryDao.getCategoriesByType("live") }
    }
    
    @Test
    fun `test getAllCategories returns all categories`() = runTest {
        // Given
        val allCategories = listOf(testCategory1, testCategory2)
        coEvery { categoryDao.getAllCategories() } returns flowOf(allCategories)
        
        // When
        categoryDao.getAllCategories()
        
        // Then
        coVerify { categoryDao.getAllCategories() }
    }
    
    @Test
    fun `test deleteCategory removes category successfully`() = runTest {
        // When
        categoryDao.deleteCategory(testCategory1)
        
        // Then
        coVerify { categoryDao.deleteCategory(testCategory1) }
    }
    
    @Test
    fun `test deleteCategoriesByType removes all categories of type`() = runTest {
        // When
        categoryDao.deleteCategoriesByType("live")
        
        // Then
        coVerify { categoryDao.deleteCategoriesByType("live") }
    }
    
    @Test
    fun `test deleteAllCategories removes all categories`() = runTest {
        // When
        categoryDao.deleteAllCategories()
        
        // Then
        coVerify { categoryDao.deleteAllCategories() }
    }
}

