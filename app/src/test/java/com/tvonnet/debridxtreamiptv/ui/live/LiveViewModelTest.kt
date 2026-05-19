package com.tvonnet.debridxtreamiptv.ui.live

import android.content.Context
import android.util.Log
import androidx.arch.core.executor.testing.InstantTaskExecutorRule
import com.tvonnet.debridxtreamiptv.data.model.XtreamCategory
import com.tvonnet.debridxtreamiptv.data.model.XtreamStream
import com.tvonnet.debridxtreamiptv.data.prefs.CredentialsPreferences
import com.tvonnet.debridxtreamiptv.data.repository.XtreamRepository
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.viewModelScope
import app.cash.turbine.test
import io.mockk.*
import kotlinx.coroutines.cancel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.test.*
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test

/**
 * Unit tests for LiveViewModel
 * Following TDD methodology - test business logic in isolation
 */
@OptIn(ExperimentalCoroutinesApi::class)
class LiveViewModelTest {

    @get:Rule
    val instantExecutorRule = InstantTaskExecutorRule()

    private lateinit var testDispatcher: TestDispatcher
    private lateinit var testScheduler: TestCoroutineScheduler

    private lateinit var viewModel: LiveViewModel
    private lateinit var mockRepository: XtreamRepository
    private lateinit var mockContext: Context

    // Test data
    private val testCategories = listOf(
        XtreamCategory(category_id = "1", category_name = "Sports", parent_id = "0"),
        XtreamCategory(category_id = "2", category_name = "Movies", parent_id = "0")
    )

    private val testStreams = listOf(
        XtreamStream(
            stream_id = "101", name = "Channel 1", category_id = "1",
            num = 1, stream_type = "live", stream_icon = null, epg_channel_id = null,
            added = null, category_ids = null, container_extension = null, custom_sid = null,
            direct_source = null, tv_archive = null, tv_archive_duration = null
        ),
        XtreamStream(
            stream_id = "102", name = "Channel 2", category_id = "1",
            num = 2, stream_type = "live", stream_icon = null, epg_channel_id = null,
            added = null, category_ids = null, container_extension = null, custom_sid = null,
            direct_source = null, tv_archive = null, tv_archive_duration = null
        )
    )

    @Before
    fun setup() {
        testScheduler = TestCoroutineScheduler()
        testDispatcher = StandardTestDispatcher(testScheduler)
        Dispatchers.setMain(testDispatcher)

        mockRepository = mockk(relaxed = true)
        mockContext = mockk(relaxed = true)

        // JVM unit tests don't have Android framework; mock Log calls used by ViewModels.
        mockkStatic(Log::class)
        every { Log.d(any(), any()) } returns 0
        every { Log.e(any(), any()) } returns 0
        every { Log.e(any(), any(), any()) } returns 0

        // Mock CredentialsPreferences constructor
        mockkConstructor(CredentialsPreferences::class)
        every { anyConstructed<CredentialsPreferences>().getServerUrl() } returns "http://test.com"
        every { anyConstructed<CredentialsPreferences>().getUsername() } returns "testuser"
        every { anyConstructed<CredentialsPreferences>().getPassword() } returns "testpass"

        // Mock repository initialization
        every { mockRepository.initialize(any(), any(), any()) } just Runs

        // Default: avoid unexpected background work during init unless a test overrides it.
        coEvery { mockRepository.ensureLiveCategories() } returns emptyList()
        coEvery { mockRepository.getCachedLiveChannelCount(any()) } returns null
    }

    @After
    fun tearDown() {
        if (this::viewModel.isInitialized) {
            viewModel.viewModelScope.cancel()
        }
        advanceUntilIdle()
        Dispatchers.resetMain()
        unmockkAll()
    }

    /**
     * Test 1: Initial state is correct
     */
    @Test
    fun `initial state should have empty lists`() {
        // When ViewModel is created
        viewModel = createViewModel()

        // Then initial state should be correct
        val state = viewModel.uiState.value
        assertTrue(state.categories.isEmpty())
        assertNull(state.selectedCategoryId)
        assertTrue(state.channels.isEmpty())
    }

    /**
     * Test 2: Load categories successfully
     */
    @Test
    fun `loadCategories should update state with categories`() {
        // Given
        coEvery { mockRepository.ensureLiveCategories() } returns testCategories

        // When ViewModel is created (auto-loads categories)
        viewModel = createViewModel()
        advanceUntilIdle()

        // Then state should have categories
        val state = viewModel.uiState.value
        assertEquals(testCategories, state.categories)
        assertEquals("1", state.selectedCategoryId)
        assertFalse(state.isLoadingCategories)
        assertNull(state.error)
        assertTrue(state.channels.isEmpty()) // Paging renders channels; state stays light.
    }

    /**
     * Test 3: Load categories with empty cache shows error
     */
    @Test
    fun `loadCategories with empty categories should show error`() {
        // Given
        coEvery { mockRepository.ensureLiveCategories() } returns emptyList()

        // When ViewModel is created
        viewModel = createViewModel()
        advanceUntilIdle()

        // Then state should show error
        val state = viewModel.uiState.value
        assertTrue(state.categories.isEmpty())
        assertEquals("No categories found. Please login and sync.", state.error)
    }

    /**
     * Test 4: Select category loads channels
     */
    @Test
    fun `selectCategory should update selectedCategoryId`() {
        // Given
        coEvery { mockRepository.ensureLiveCategories() } returns testCategories

        viewModel = createViewModel()
        advanceUntilIdle()

        // When selecting a category
        viewModel.onEvent(LiveEvent.SelectCategory("2"))
        advanceUntilIdle()

        // Then selected category should be updated (channels are rendered via Paging)
        val state = viewModel.uiState.value
        assertEquals("2", state.selectedCategoryId)
        assertTrue(state.channels.isEmpty())
        coVerify { mockRepository.getCachedLiveChannelCount("2") }
    }

    /**
     * Test 5: Repository initialization is called
     */
    @Test
    fun `viewModel should initialize repository with credentials`() {
        // Given mocked credentials
        every { anyConstructed<CredentialsPreferences>().getServerUrl() } returns "http://test.com"
        every { anyConstructed<CredentialsPreferences>().getUsername() } returns "user1"
        every { anyConstructed<CredentialsPreferences>().getPassword() } returns "pass1"

        // When ViewModel is created
        viewModel = createViewModel()
        advanceUntilIdle()

        // Then repository should be initialized
        verify { mockRepository.initialize("http://test.com", "user1", "pass1") }
    }

    /**
     * Test 6: Auto-load first category channels
     */
    @Test
    fun `viewModel should auto-select first category on init`() {
        // Given
        coEvery { mockRepository.ensureLiveCategories() } returns testCategories

        // When ViewModel is created
        viewModel = createViewModel()
        advanceUntilIdle()

        // Then first category should be selected (channels via Paging)
        val state = viewModel.uiState.value
        assertEquals("1", state.selectedCategoryId)
        coVerify { mockRepository.getCachedLiveChannelCount("1") }
    }

    /**
     * Test 7: Exception handling sets error state
     */
    @Test
    fun `exception during load should set error state`() {
        // Given
        coEvery { mockRepository.ensureLiveCategories() } throws RuntimeException("Test error")

        // When ViewModel is created
        viewModel = createViewModel()
        advanceUntilIdle()

        // Then error should be set
        val state = viewModel.uiState.value
        assertEquals("Test error", state.error)
        assertFalse(state.isLoadingCategories)
    }

    /**
     * Test 8: Multiple category selections update state correctly
     */
    @Test
    fun `multiple category selections should update selectedCategoryId`() {
        // Given
        coEvery { mockRepository.ensureLiveCategories() } returns testCategories
        viewModel = createViewModel()
        advanceUntilIdle()

        // When selecting different categories
        viewModel.onEvent(LiveEvent.SelectCategory("1"))
        advanceUntilIdle()
        
        viewModel.onEvent(LiveEvent.SelectCategory("2"))
        advanceUntilIdle()

        // Then selected category should be updated
        val state = viewModel.uiState.value
        assertEquals("2", state.selectedCategoryId)
    }

    /**
     * Test 9: LoadCategories event is handled
     */
    @Test
    fun `LoadCategories event should trigger category loading`() {
        // Given
        coEvery { mockRepository.ensureLiveCategories() } returns testCategories
        viewModel = createViewModel()
        advanceUntilIdle()

        // When triggering LoadCategories event
        viewModel.onEvent(LiveEvent.LoadCategories)
        advanceUntilIdle()

        // Then categories should be loaded
        val state = viewModel.uiState.value
        assertFalse(state.categories.isEmpty())
        coVerify(atLeast = 2) { mockRepository.ensureLiveCategories() }
    }

    /**
     * Test 10: Play channel event
     */
    @Test
    fun `PlayChannel event should emit navigation event`() = runTest {
        viewModel = createViewModel()
        advanceUntilIdle()

        val testStream = testStreams[0]
        viewModel.navigationEvent.test {
            viewModel.onEvent(LiveEvent.PlayChannel(testStream))
            assertEquals(testStream, awaitItem())
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `ChannelLoadStateChanged should update loading state`() {
        viewModel = createViewModel()
        advanceUntilIdle()

        viewModel.onEvent(LiveEvent.ChannelLoadStateChanged(true))
        assertTrue(viewModel.uiState.value.isLoadingChannels)

        viewModel.onEvent(LiveEvent.ChannelLoadStateChanged(false))
        assertFalse(viewModel.uiState.value.isLoadingChannels)
    }

    private fun advanceUntilIdle() {
        testScheduler.advanceUntilIdle()
    }

    private fun createViewModel(
        savedStateHandle: SavedStateHandle = SavedStateHandle(),
        ioDispatcher: CoroutineDispatcher = testDispatcher
    ): LiveViewModel {
        return LiveViewModel(mockRepository, mockContext, savedStateHandle, ioDispatcher)
    }
}
