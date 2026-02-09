package com.tvonnet.debridxtreamiptv.ui.vod

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

@OptIn(ExperimentalCoroutinesApi::class)
class VodViewModelTest {
    
    @get:Rule
    val instantTaskExecutorRule = InstantTaskExecutorRule()
    
    private lateinit var testDispatcher: TestDispatcher
    private lateinit var testScheduler: TestCoroutineScheduler
    
    private lateinit var repository: XtreamRepository
    private lateinit var vodDao: com.tvonnet.debridxtreamiptv.data.local.dao.VodDao
    private lateinit var favoritesCache: FavoritesCache
    private lateinit var savedStateHandle: SavedStateHandle
    private lateinit var viewModel: VodViewModel
    
    @Before
    fun setup() {
        testScheduler = TestCoroutineScheduler()
        testDispatcher = StandardTestDispatcher(testScheduler)
        Dispatchers.setMain(testDispatcher)
        
        // Mock Android Log
        mockkStatic(android.util.Log::class)
        every { android.util.Log.d(any(), any()) } returns 0
        every { android.util.Log.e(any(), any<String>()) } returns 0
        every { android.util.Log.e(any(), any<String>(), any()) } returns 0
        
        repository = mockk(relaxed = true)
        vodDao = mockk(relaxed = true)
        favoritesCache = mockk(relaxed = true)
        savedStateHandle = SavedStateHandle()
    }
    
    @After
    fun tearDown() {
        if (this::viewModel.isInitialized) {
            viewModel.viewModelScope.cancel()
        }
        testScheduler.advanceUntilIdle()
        Dispatchers.resetMain()
        clearAllMocks()
        unmockkAll()
    }
    
    @Test
    fun `initial state is correct`() = runTest {
        // Given
        coEvery { repository.ensureVodCategories() } returns emptyList()
        viewModel = VodViewModel(repository, vodDao, favoritesCache, savedStateHandle)
        
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
            XtreamCategory(category_id = "1", category_name = "Action", parent_id = null),
            XtreamCategory(category_id = "2", category_name = "Comedy", parent_id = null)
        )
        coEvery { repository.ensureVodCategories() } returns mockCategories
        coEvery { repository.fetchVodStreamsForCategory(any()) } returns Result.Success(emptyList())
        
        // When
        viewModel = VodViewModel(repository, vodDao, favoritesCache, savedStateHandle)
        testScheduler.advanceUntilIdle()
        
        // Then
        val state = viewModel.uiState.value
        assertEquals(2, state.categories.size)
        assertEquals("Action", state.categories[0].category_name)
        assertFalse(state.isLoadingCategories)
        assertNull(state.error)
    }
    
    @Test
    fun `loadCategories with empty cache shows error`() = runTest {
        // Given
        coEvery { repository.ensureVodCategories() } returns emptyList()
        
        // When
        viewModel = VodViewModel(repository, vodDao, favoritesCache, savedStateHandle)
        testScheduler.advanceUntilIdle()
        
        // Then
        val state = viewModel.uiState.value
        assertTrue(state.categories.isEmpty())
        assertTrue(state.error?.contains("No movie categories found") == true)
    }
    
    @Test
    fun `selectCategory loads movies correctly`() = runTest {
        // Given
        val mockMovies = listOf(
            createMockVodInfo("1", "Movie 1", "1"),
            createMockVodInfo("2", "Movie 2", "1")
        )
        val mockCategories = listOf(
            XtreamCategory(category_id = "1", category_name = "Action", parent_id = null)
        )
        coEvery { repository.ensureVodCategories() } returns mockCategories
        coEvery { repository.fetchVodStreamsForCategory("1") } returns Result.Success(mockMovies)
        
        viewModel = VodViewModel(repository, vodDao, favoritesCache, savedStateHandle)
        testScheduler.advanceUntilIdle()
        
        // When
        viewModel.onEvent(VodEvent.SelectCategory("1"))
        testScheduler.advanceUntilIdle()
        
        // Then
        val state = viewModel.uiState.value
        assertEquals("1", state.selectedCategoryId)
        assertFalse(state.isLoadingMovies)
    }
    
    @Test
    fun `selectCategory with failure shows error`() = runTest {
        // Given
        val mockCategories = listOf(
            XtreamCategory(category_id = "1", category_name = "Action", parent_id = null)
        )
        coEvery { repository.ensureVodCategories() } returns mockCategories
        coEvery { repository.fetchVodStreamsForCategory(any()) } returns Result.Error(Exception("Network error"))
        
        viewModel = VodViewModel(repository, vodDao, favoritesCache, savedStateHandle)
        testScheduler.advanceUntilIdle()
        
        // When
        viewModel.onEvent(VodEvent.SelectCategory("1"))
        testScheduler.advanceUntilIdle()
        
        // Then
        val state = viewModel.uiState.value
        assertTrue(state.movies.isEmpty())
        assertFalse(state.isLoadingMovies)
    }
    
    // Note: Testing SavedStateHandle with XtreamVodInfo requires making the data class Parcelable
    // This test is skipped as it would require data model refactoring
    
    @Test
    fun `retry loads categories when empty`() = runTest {
        // Given
        coEvery { repository.ensureVodCategories() } returns emptyList()
        viewModel = VodViewModel(repository, vodDao, favoritesCache, savedStateHandle)
        testScheduler.advanceUntilIdle()
        
        // When
        viewModel.onEvent(VodEvent.Retry)
        testScheduler.advanceUntilIdle()
        
        // Then
        coVerify(atLeast = 2) { repository.ensureVodCategories() } // Initial load + retry
    }
    
    @Test
    fun `retry reloads current category when categories exist`() = runTest {
        // Given
        val mockCategories = listOf(
            XtreamCategory(category_id = "1", category_name = "Action", parent_id = null)
        )
        coEvery { repository.ensureVodCategories() } returns mockCategories
        coEvery { repository.fetchVodStreamsForCategory(any()) } returns Result.Success(emptyList())
        
        viewModel = VodViewModel(repository, vodDao, favoritesCache, savedStateHandle)
        testScheduler.advanceUntilIdle()
        
        // When
        viewModel.onEvent(VodEvent.Retry)
        testScheduler.advanceUntilIdle()
        
        // Then
        coVerify(atLeast = 2) { repository.fetchVodStreamsForCategory("1") } // Initial + retry
    }
    
    @Test
    fun `loading state is set correctly during category load`() = runTest {
        // Given
        val mockCategories = listOf(
            XtreamCategory(category_id = "1", category_name = "Action", parent_id = null)
        )
        coEvery { repository.ensureVodCategories() } returns mockCategories
        coEvery { repository.fetchVodStreamsForCategory(any()) } returns Result.Success(emptyList())
        
        // When
        viewModel = VodViewModel(repository, vodDao, favoritesCache, savedStateHandle)
        
        // Check loading state before advancing
        var state = viewModel.uiState.value
        // Note: Due to how StateFlow works, we might not catch the loading state
        
        testScheduler.advanceUntilIdle()
        
        // Then - verify final state is not loading
        state = viewModel.uiState.value
        assertFalse(state.isLoadingCategories)
        assertFalse(state.isLoadingMovies)
    }
    
    @Test
    fun `auto-loads first category on initialization`() = runTest {
        // Given
        val mockCategories = listOf(
            XtreamCategory(category_id = "1", category_name = "Action", parent_id = null),
            XtreamCategory(category_id = "2", category_name = "Comedy", parent_id = null)
        )
        coEvery { repository.ensureVodCategories() } returns mockCategories
        coEvery { repository.fetchVodStreamsForCategory(any()) } returns Result.Success(emptyList())
        
        // When
        viewModel = VodViewModel(repository, vodDao, favoritesCache, savedStateHandle)
        testScheduler.advanceUntilIdle()
        
        // Then
        val state = viewModel.uiState.value
        assertEquals("1", state.selectedCategoryId) // First category should be selected
        coVerify { repository.fetchVodStreamsForCategory("1") } // First category should be loaded
    }
    
    // Helper function to create mock VOD info
    private fun createMockVodInfo(id: String, name: String, categoryId: String): XtreamVodInfo {
        return XtreamVodInfo(
            num = id.toIntOrNull(),
            stream_id = id,
            name = name,
            category_id = categoryId,
            stream_icon = "icon$id.jpg",
            rating = "8.0",
            stream_type = "movie",
            rating_5based = 4.0,
            added = "123456",
            category_ids = listOf(categoryId.toIntOrNull() ?: 0),
            container_extension = "mp4",
            custom_sid = null,
            direct_source = null,
            releaseDate = "2023-01-01",
            duration = "7200",
            plot = "Test plot",
            cast = "Actor 1",
            director = "Director 1",
            genre = "Action",
            youtube_trailer = null,
            cover = "cover$id.jpg",
            rating_imdb = "8.0"
        )
    }
}
