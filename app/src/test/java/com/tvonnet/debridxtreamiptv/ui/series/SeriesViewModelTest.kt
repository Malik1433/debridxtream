package com.tvonnet.debridxtreamiptv.ui.series

import android.content.Context
import androidx.arch.core.executor.testing.InstantTaskExecutorRule
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.viewModelScope
import com.tvonnet.debridxtreamiptv.data.Result
import com.tvonnet.debridxtreamiptv.data.cache.FavoritesCache
import com.tvonnet.debridxtreamiptv.data.model.*
import com.tvonnet.debridxtreamiptv.data.repository.XtreamRepository
import io.mockk.*
import kotlinx.coroutines.cancel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.*
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE)
class SeriesViewModelTest {
    
    @get:Rule
    val instantTaskExecutorRule = InstantTaskExecutorRule()
    
    private lateinit var testDispatcher: TestDispatcher
    private lateinit var testScheduler: TestCoroutineScheduler
    
    private lateinit var repository: XtreamRepository
    private lateinit var seriesDao: com.tvonnet.debridxtreamiptv.data.local.dao.SeriesDao
    private lateinit var favoritesCache: FavoritesCache
    private lateinit var context: Context
    private lateinit var savedStateHandle: SavedStateHandle
    private lateinit var viewModel: SeriesViewModel
    
    @Before
    fun setup() {
        testScheduler = TestCoroutineScheduler()
        testDispatcher = StandardTestDispatcher(testScheduler)
        Dispatchers.setMain(testDispatcher)
        SeriesViewModel.ioDispatcher = testDispatcher
        
        // Mock Android Log
        mockkStatic(android.util.Log::class)
        every { android.util.Log.d(any(), any()) } returns 0
        every { android.util.Log.e(any(), any<String>()) } returns 0
        every { android.util.Log.e(any(), any<String>(), any()) } returns 0
        
        repository = mockk(relaxed = true)
        seriesDao = mockk(relaxed = true)
        favoritesCache = mockk(relaxed = true)
        context = mockk(relaxed = true)
        savedStateHandle = SavedStateHandle()
    }
    
    @After
    fun tearDown() {
        if (this::viewModel.isInitialized) {
            viewModel.viewModelScope.cancel()
        }
        advanceUntilIdle()
        Dispatchers.resetMain()
        SeriesViewModel.ioDispatcher = Dispatchers.IO
        clearAllMocks()
        unmockkAll()
    }
    
    @Test
    fun `initial state is correct`() = runTest {
        // Given
        coEvery { repository.ensureSeriesCategories() } returns emptyList()
        viewModel = SeriesViewModel(repository, seriesDao, favoritesCache, context, savedStateHandle)
        
        // When
        val state = viewModel.uiState.value
        
        // Then
        assertTrue(state.categories.isEmpty())
        assertFalse(state.isLoadingCategories)
    }
    
    @Test
    fun `loadCategories success updates state correctly`() = runTest {
        // Given
        val mockCategories = listOf(
            XtreamCategory(category_id = "1", category_name = "Drama", parent_id = null),
            XtreamCategory(category_id = "2", category_name = "Sci-Fi", parent_id = null)
        )
        coEvery { repository.ensureSeriesCategories() } returns mockCategories
        coEvery { repository.fetchSeriesForCategory(any()) } returns Result.Success(emptyList())
        
        // When
        viewModel = SeriesViewModel(repository, seriesDao, favoritesCache, context, savedStateHandle)
        advanceUntilIdle()
        
        // Then
        val state = viewModel.uiState.value
        assertEquals(2, state.categories.size)
        assertEquals("Drama", state.categories[0].category_name)
        assertFalse(state.isLoadingCategories)
        assertNull(state.error)
    }
    
    @Test
    fun `loadCategories with empty cache shows error`() = runTest {
        // Given
        coEvery { repository.ensureSeriesCategories() } returns emptyList()
        
        // When
        viewModel = SeriesViewModel(repository, seriesDao, favoritesCache, context, savedStateHandle)
        advanceUntilIdle()
        
        // Then
        val state = viewModel.uiState.value
        assertTrue(state.categories.isEmpty())
        assertTrue(state.error?.contains("No series categories found") == true)
    }
    
    @Test
    fun `selectCategory loads series correctly`() = runTest {
        // Given
        val mockSeriesList = listOf(
            createMockSeriesInfo("1", "Series 1", "1"),
            createMockSeriesInfo("2", "Series 2", "1")
        )
        val mockCategories = listOf(
            XtreamCategory(category_id = "1", category_name = "Drama", parent_id = null)
        )
        coEvery { repository.ensureSeriesCategories() } returns mockCategories
        coEvery { repository.fetchSeriesForCategory("1") } returns Result.Success(mockSeriesList)
        
        viewModel = SeriesViewModel(repository, seriesDao, favoritesCache, context, savedStateHandle)
        advanceUntilIdle()
        
        // When
        viewModel.onEvent(SeriesEvent.SelectCategory("1"))
        advanceUntilIdle()
        
        // Then
        val state = viewModel.uiState.value
        assertEquals("1", state.selectedCategoryId)
        assertFalse(state.isLoadingSeries)
    }
    
    @Test
    fun `selectCategory with failure shows error`() = runTest {
        // Given
        val mockCategories = listOf(
            XtreamCategory(category_id = "1", category_name = "Drama", parent_id = null)
        )
        coEvery { repository.ensureSeriesCategories() } returns mockCategories
        coEvery { repository.fetchSeriesForCategory(any()) } returns Result.Error(Exception("Network error"))
        
        viewModel = SeriesViewModel(repository, seriesDao, favoritesCache, context, savedStateHandle)
        advanceUntilIdle()
        
        // When
        viewModel.onEvent(SeriesEvent.SelectCategory("1"))
        advanceUntilIdle()
        
        // Then
        val state = viewModel.uiState.value
        assertTrue(state.series.isEmpty())
        assertTrue(state.error?.contains("Network error") == true)
        assertFalse(state.isLoadingSeries)
    }
    
    @Test
    fun `retry loads categories when empty`() = runTest {
        // Given
        coEvery { repository.ensureSeriesCategories() } returns emptyList()
        viewModel = SeriesViewModel(repository, seriesDao, favoritesCache, context, savedStateHandle)
        advanceUntilIdle()
        
        // When
        viewModel.onEvent(SeriesEvent.Retry)
        advanceUntilIdle()
        
        // Then
        coVerify(atLeast = 2) { repository.ensureSeriesCategories() } // Initial load + retry
    }
    
    @Test
    fun `retry reloads current category when categories exist`() = runTest {
        // Given
        val mockCategories = listOf(
            XtreamCategory(category_id = "1", category_name = "Drama", parent_id = null)
        )
        coEvery { repository.ensureSeriesCategories() } returns mockCategories
        coEvery { repository.fetchSeriesForCategory(any()) } returns Result.Success(emptyList())
        
        viewModel = SeriesViewModel(repository, seriesDao, favoritesCache, context, savedStateHandle)
        advanceUntilIdle()
        
        // When
        viewModel.onEvent(SeriesEvent.Retry)
        advanceUntilIdle()
        
        // Then
        coVerify(atLeast = 2) { repository.fetchSeriesForCategory("1") } // Initial + retry
    }
    
    @Test
    fun `loading state is cleared after load completes`() = runTest {
        // Given
        val mockCategories = listOf(
            XtreamCategory(category_id = "1", category_name = "Drama", parent_id = null)
        )
        coEvery { repository.ensureSeriesCategories() } returns mockCategories
        coEvery { repository.fetchSeriesForCategory(any()) } returns Result.Success(emptyList())
        
        // When
        viewModel = SeriesViewModel(repository, seriesDao, favoritesCache, context, savedStateHandle)
        advanceUntilIdle()
        
        // Then
        val state = viewModel.uiState.value
        assertFalse(state.isLoadingCategories)
        assertFalse(state.isLoadingSeries)
    }
    
    @Test
    fun `auto-loads first category on initialization`() = runTest {
        // Given
        val mockCategories = listOf(
            XtreamCategory(category_id = "1", category_name = "Drama", parent_id = null),
            XtreamCategory(category_id = "2", category_name = "Sci-Fi", parent_id = null)
        )
        coEvery { repository.ensureSeriesCategories() } returns mockCategories
        coEvery { repository.fetchSeriesForCategory(any()) } returns Result.Success(emptyList())
        
        // When
        viewModel = SeriesViewModel(repository, seriesDao, favoritesCache, context, savedStateHandle)
        advanceUntilIdle()
        
        // Then
        val state = viewModel.uiState.value
        assertEquals("1", state.selectedCategoryId) // First category should be selected
        coVerify { repository.fetchSeriesForCategory("1") } // First category should be loaded
    }
    
    // Helper function to create mock series info
    private fun createMockSeriesInfo(id: String, name: String, categoryId: String): XtreamSeriesInfo {
        return XtreamSeriesInfo(
            num = id.toIntOrNull(),
            series_id = id,
            name = name,
            category_id = categoryId,
            cover = "cover$id.jpg",
            plot = "Test plot",
            cast = "Actor 1",
            director = "Director 1",
            genre = "Drama",
            releaseDate = "2023-01-01",
            rating = "8.5",
            rating_5based = 4.25,
            episodes = null,
            category_ids = listOf(categoryId.toIntOrNull() ?: 0),
            backdrop_path = listOf("backdrop$id.jpg"),
            youtube_trailer = "trailer$id"
        )
    }

    private fun advanceUntilIdle() {
        testScheduler.advanceUntilIdle()
        testScheduler.advanceTimeBy(600)
        testScheduler.advanceUntilIdle()
    }
}
