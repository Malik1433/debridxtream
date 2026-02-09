package com.tvonnet.debridxtreamiptv.ui.series

import androidx.arch.core.executor.testing.InstantTaskExecutorRule
import androidx.lifecycle.viewModelScope
import app.cash.turbine.test
import com.tvonnet.debridxtreamiptv.data.Result
import com.tvonnet.debridxtreamiptv.data.local.relation.SeriesWithSeasonsAndEpisodes
import com.tvonnet.debridxtreamiptv.data.local.relation.toXtreamSeriesDetailResponse
import com.tvonnet.debridxtreamiptv.data.repository.XtreamRepository
import com.tvonnet.debridxtreamiptv.data.local.entity.SeriesEntity
import com.tvonnet.debridxtreamiptv.worker.SeriesSyncScheduler
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkAll
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestCoroutineScheduler
import kotlinx.coroutines.test.TestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@ExperimentalCoroutinesApi
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE)
class SeriesDetailViewModelTest {

    @get:Rule
    val instantExecutorRule = InstantTaskExecutorRule()

    private lateinit var viewModel: SeriesDetailViewModel
    private val repository: XtreamRepository = mockk(relaxed = true)
    private val scheduler: SeriesSyncScheduler = mockk(relaxed = true) // Assuming we can mock or inject this

    private lateinit var testDispatcher: TestDispatcher
    private lateinit var testScheduler: TestCoroutineScheduler

    @Before
    fun setup() {
        testScheduler = TestCoroutineScheduler()
        testDispatcher = StandardTestDispatcher(testScheduler)
        Dispatchers.setMain(testDispatcher)
        mockkStatic(android.util.Log::class)
        every { android.util.Log.d(any(), any()) } returns 0
        every { android.util.Log.e(any(), any()) } returns 0
        every { android.util.Log.e(any(), any(), any()) } returns 0
        // For now, we assume we can inject the scheduler wrapper or interact with it
        // If it's a singleton object, we might need a wrapper, but let's assume constructor injection first
        viewModel = SeriesDetailViewModel(repository, scheduler) 
    }

    @After
    fun tearDown() {
        viewModel.viewModelScope.cancel()
        testScheduler.advanceUntilIdle()
        Dispatchers.resetMain()
        unmockkAll()
    }

    @Test
    fun `loadSeries emits Loading then Success when data exists`() = runTest {
        val seriesId = "123"
        val mockSeriesEntity = SeriesEntity(
            seriesId = "123",
            name = "Test Series",
            cover = null,
            plot = null,
            cast = null,
            director = null,
            genre = null,
            releaseDate = null,
            rating = null,
            rating5based = null,
            categoryId = null,
            category_ids = emptyList(),
            backdrop_path = emptyList(),
            youtube_trailer = null,
            num = null
        )
        val mockData = SeriesWithSeasonsAndEpisodes(
            series = mockSeriesEntity,
            seasons = emptyList(),
            episodes = emptyList()
        )

        coEvery { repository.getSeriesDetailFlow(seriesId) } returns flowOf(mockData)
        coEvery { repository.syncSeriesDetail(seriesId) } returns Result.Success(
            mockData.toXtreamSeriesDetailResponse()
        )

        viewModel.uiState.test {
            val initialItem = awaitItem()
            assertTrue(initialItem is SeriesDetailUiState.Idle)

            viewModel.loadSeries(seriesId)

            val loadingItem = awaitItem()
            assertTrue(loadingItem is SeriesDetailUiState.Loading)

            testScheduler.advanceUntilIdle()

            // Should see Success
            val secondItem = awaitItem()
            assertTrue(secondItem is SeriesDetailUiState.Success)
            assertEquals("Test Series", (secondItem as SeriesDetailUiState.Success).detail.info?.name)
            
            cancelAndIgnoreRemainingEvents()
        }
    }
}
