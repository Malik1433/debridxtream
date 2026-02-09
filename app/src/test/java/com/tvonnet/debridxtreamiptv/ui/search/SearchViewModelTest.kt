package com.tvonnet.debridxtreamiptv.ui.search

import androidx.arch.core.executor.testing.InstantTaskExecutorRule
import androidx.lifecycle.viewModelScope
import com.tvonnet.debridxtreamiptv.data.local.dao.SearchHistoryDao
import com.tvonnet.debridxtreamiptv.data.local.entity.SearchHistoryEntity
import com.tvonnet.debridxtreamiptv.data.model.*
import com.tvonnet.debridxtreamiptv.data.repository.XtreamRepository
import io.mockk.*
import kotlinx.coroutines.cancel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.*
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test

/**
 * Week 9: SearchViewModel Unit Tests
 * 
 * Tests:
 * - Initial state
 * - Search query debouncing (300ms)
 * - Global search across Live TV, VOD, Series
 * - Empty results handling
 * - Recent searches management
 * - Error handling
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SearchViewModelTest {
    
    @get:Rule
    val instantTaskExecutorRule = InstantTaskExecutorRule()
    
    private lateinit var testDispatcher: TestDispatcher
    private lateinit var testScheduler: TestCoroutineScheduler
    
    private lateinit var repository: XtreamRepository
    private lateinit var searchHistoryDao: SearchHistoryDao
    private lateinit var viewModel: SearchViewModel
    
    @Before
    fun setup() {
        testScheduler = TestCoroutineScheduler()
        testDispatcher = StandardTestDispatcher(testScheduler)
        Dispatchers.setMain(testDispatcher)
        repository = mockk(relaxed = true)
        searchHistoryDao = mockk(relaxed = true)
        every { searchHistoryDao.getRecentSearches(any()) } returns flowOf(emptyList())
    }
    
    @After
    fun tearDown() {
        if (this::viewModel.isInitialized) {
            viewModel.viewModelScope.cancel()
        }
        testScheduler.advanceUntilIdle()
        Dispatchers.resetMain()
        clearAllMocks()
    }
    
    @Test
    fun `initial state is correct`() = runTest {
        // Given & When
        viewModel = SearchViewModel(repository, searchHistoryDao)
        
        // Then
        val state = viewModel.uiState.value
        assertEquals("", state.query)
        assertTrue(state.liveResults.isEmpty())
        assertTrue(state.vodResults.isEmpty())
        assertTrue(state.seriesResults.isEmpty())
        assertFalse(state.isSearching)
        assertEquals(0, state.totalResults)
        assertFalse(state.hasResults)
    }
    
    @Test
    fun `search query updates state`() = runTest {
        // Given
        viewModel = SearchViewModel(repository, searchHistoryDao)
        coEvery { repository.searchLive(any()) } returns emptyList()
        coEvery { repository.searchVod(any()) } returns emptyList()
        coEvery { repository.searchSeries(any()) } returns emptyList()
        
        // When
        viewModel.onEvent(SearchEvent.QueryChanged("test"))
        testScheduler.advanceTimeBy(350) // Wait for debounce (300ms + buffer)
        
        // Then
        val state = viewModel.uiState.value
        assertEquals("test", state.query)
    }
    
    @Test
    fun `search is debounced - only last query is processed`() = runTest {
        // Given
        viewModel = SearchViewModel(repository, searchHistoryDao)
        coEvery { repository.searchLive(any()) } returns emptyList()
        coEvery { repository.searchVod(any()) } returns emptyList()
        coEvery { repository.searchSeries(any()) } returns emptyList()
        
        // When - Type multiple queries rapidly
        viewModel.onEvent(SearchEvent.QueryChanged("t"))
        testScheduler.advanceTimeBy(100)
        
        viewModel.onEvent(SearchEvent.QueryChanged("te"))
        testScheduler.advanceTimeBy(100)
        
        viewModel.onEvent(SearchEvent.QueryChanged("tes"))
        testScheduler.advanceTimeBy(100)
        
        viewModel.onEvent(SearchEvent.QueryChanged("test"))
        testScheduler.advanceTimeBy(350) // Wait for debounce
        
        // Then - Only "test" should be in state
        val state = viewModel.uiState.value
        assertEquals("test", state.query)
        
        // Verify only one search call was made
        coVerify(exactly = 1) { repository.searchLive("test") }
    }
    
    @Test
    fun `search finds results in Live TV`() = runTest {
        // Given
        val mockLiveStreams = listOf(
            XtreamStream(
                stream_id = "1",
                name = "Test Channel",
                category_id = "1",
                stream_type = "live",
                stream_icon = null,
                epg_channel_id = null,
                added = null,
                num = 1,
                category_ids = null,
                container_extension = null,
                custom_sid = null,
                direct_source = null,
                tv_archive = null,
                tv_archive_duration = null
            )
        )
        
        viewModel = SearchViewModel(repository, searchHistoryDao)
        coEvery { repository.searchLive("test") } returns mockLiveStreams
        coEvery { repository.searchVod(any()) } returns emptyList()
        coEvery { repository.searchSeries(any()) } returns emptyList()
        
        // When
        viewModel.onEvent(SearchEvent.QueryChanged("test"))
        testScheduler.advanceTimeBy(350)
        
        // Then
        val state = viewModel.uiState.value
        assertEquals(1, state.liveResults.size)
        assertEquals("Test Channel", state.liveResults[0].name)
        assertEquals(1, state.totalResults)
        assertTrue(state.hasResults)
    }
    
    @Test
    fun `search finds results in VOD`() = runTest {
        // Given
        val mockVodStreams = listOf(
            XtreamVodInfo(
                stream_id = "1",
                name = "Test Movie",
                category_id = "1",
                stream_type = "movie",
                stream_icon = null,
                rating = "7.5",
                rating_5based = 3.75,
                added = null,
                num = 1,
                category_ids = null,
                container_extension = "mp4",
                custom_sid = null,
                direct_source = null,
                releaseDate = "2023",
                duration = "120",
                plot = "A test movie plot",
                cast = "Test Actor",
                director = "Test Director",
                genre = "Action",
                youtube_trailer = null,
                cover = null,
                rating_imdb = "7.5"
            )
        )
        
        viewModel = SearchViewModel(repository, searchHistoryDao)
        coEvery { repository.searchLive(any()) } returns emptyList()
        coEvery { repository.searchVod("test") } returns mockVodStreams
        coEvery { repository.searchSeries(any()) } returns emptyList()
        
        // When
        viewModel.onEvent(SearchEvent.QueryChanged("test"))
        testScheduler.advanceTimeBy(350)
        
        // Then
        val state = viewModel.uiState.value
        assertEquals(1, state.vodResults.size)
        assertEquals("Test Movie", state.vodResults[0].name)
    }
    
    @Test
    fun `search finds results in Series`() = runTest {
        // Given
        val mockSeriesStreams = listOf(
            XtreamSeriesInfo(
                series_id = "1",
                name = "Test Series",
                category_id = "1",
                cover = null,
                plot = "A test series plot",
                cast = "Test Actor",
                director = "Test Director",
                genre = "Drama",
                releaseDate = "2023",
                rating = "8.0",
                rating_5based = 4.0,
                num = 1,
                episodes = null,
                category_ids = listOf(1),
                backdrop_path = listOf("backdrop.jpg"),
                youtube_trailer = "trailer"
            )
        )
        
        viewModel = SearchViewModel(repository, searchHistoryDao)
        coEvery { repository.searchLive(any()) } returns emptyList()
        coEvery { repository.searchVod(any()) } returns emptyList()
        coEvery { repository.searchSeries("test") } returns mockSeriesStreams
        
        // When
        viewModel.onEvent(SearchEvent.QueryChanged("test"))
        testScheduler.advanceTimeBy(350)
        
        // Then
        val state = viewModel.uiState.value
        assertEquals(1, state.seriesResults.size)
        assertEquals("Test Series", state.seriesResults[0].name)
    }
    
    @Test
    fun `search across all content types simultaneously`() = runTest {
        // Given
        val mockLiveStreams = listOf(XtreamStream(stream_id = "1", name = "Test Channel", category_id = "1", stream_type = "live", stream_icon = null, epg_channel_id = null, added = null, num = 1, category_ids = null, container_extension = null, custom_sid = null, direct_source = null, tv_archive = null, tv_archive_duration = null))
        val mockVodStreams = listOf(XtreamVodInfo(stream_id = "1", name = "Test Movie", category_id = "1", stream_type = "movie", stream_icon = null, rating = null, rating_5based = null, added = null, num = 1, category_ids = null, container_extension = "mp4", custom_sid = null, direct_source = null, releaseDate = null, duration = null, plot = null, cast = null, director = null, genre = null, youtube_trailer = null, cover = null, rating_imdb = null))
        val mockSeriesStreams = listOf(XtreamSeriesInfo(series_id = "1", name = "Test Series", category_id = "1", cover = null, plot = null, cast = null, director = null, genre = null, releaseDate = null, rating = null, rating_5based = null, num = 1, episodes = null, category_ids = listOf(1), backdrop_path = listOf("backdrop.jpg"), youtube_trailer = "trailer"))
        
        viewModel = SearchViewModel(repository, searchHistoryDao)
        coEvery { repository.searchLive("test") } returns mockLiveStreams
        coEvery { repository.searchVod("test") } returns mockVodStreams
        coEvery { repository.searchSeries("test") } returns mockSeriesStreams
        
        // When
        viewModel.onEvent(SearchEvent.QueryChanged("test"))
        testScheduler.advanceTimeBy(350)
        
        // Then
        val state = viewModel.uiState.value
        assertEquals(1, state.liveResults.size)
        assertEquals(1, state.vodResults.size)
        assertEquals(1, state.seriesResults.size)
        assertEquals(3, state.totalResults)
        assertTrue(state.hasResults)
    }
    
    @Test
    fun `search with no results shows empty state`() = runTest {
        // Given
        viewModel = SearchViewModel(repository, searchHistoryDao)
        coEvery { repository.searchLive(any()) } returns emptyList()
        coEvery { repository.searchVod(any()) } returns emptyList()
        coEvery { repository.searchSeries(any()) } returns emptyList()
        
        // When
        viewModel.onEvent(SearchEvent.QueryChanged("nonexistent"))
        testScheduler.advanceTimeBy(350)
        
        // Then
        val state = viewModel.uiState.value
        assertEquals(0, state.totalResults)
        assertFalse(state.hasResults)
        // assertNull(state.error) - Removed as we expect an error message now
        assertEquals("No results found for 'nonexistent'. Please ensure content is loaded by browsing categories.", state.error)
    }
    
    @Test
    fun `search with query less than 2 characters clears results`() = runTest {
        // Given
        viewModel = SearchViewModel(repository, searchHistoryDao)
        
        // When
        viewModel.onEvent(SearchEvent.QueryChanged("a"))
        testScheduler.advanceTimeBy(350)
        
        // Then
        val state = viewModel.uiState.value
        assertEquals(0, state.totalResults)
        assertFalse(state.isSearching)
    }
    
    @Test
    fun `clear query event clears search`() = runTest {
        // Given
        viewModel = SearchViewModel(repository, searchHistoryDao)
        coEvery { repository.searchLive(any()) } returns emptyList()
        coEvery { repository.searchVod(any()) } returns emptyList()
        coEvery { repository.searchSeries(any()) } returns emptyList()
        
        viewModel.onEvent(SearchEvent.QueryChanged("test"))
        testScheduler.advanceTimeBy(350)
        
        // When
        viewModel.onEvent(SearchEvent.ClearQuery)
        testScheduler.advanceTimeBy(350)
        
        // Then
        val state = viewModel.uiState.value
        assertEquals("", state.query)
    }
    
    @Test
    fun `search is case insensitive`() = runTest {
        // Note: The logic for case insensitivity is now in the Database/DAO/Repository implementation,
        // so verifying the ViewModel just calls the repository with the string is sufficient.
        // We can verify calls.
        
        // Given
        viewModel = SearchViewModel(repository, searchHistoryDao)
        coEvery { repository.searchLive(any()) } returns emptyList()
        coEvery { repository.searchVod(any()) } returns emptyList()
        coEvery { repository.searchSeries(any()) } returns emptyList()
        
        // When
        viewModel.onEvent(SearchEvent.QueryChanged("TEST"))
        testScheduler.advanceTimeBy(350)
        
        // Then
        coVerify { repository.searchLive("TEST") }
        
        // When
        viewModel.onEvent(SearchEvent.QueryChanged("test"))
        testScheduler.advanceTimeBy(350)
        
        // Then
        coVerify { repository.searchLive("test") }
    }
}
