package com.tvonnet.debridxtreamiptv.data.paging

import androidx.paging.PagingSource
import com.tvonnet.debridxtreamiptv.data.Result
import com.tvonnet.debridxtreamiptv.data.model.XtreamSeriesInfo
import com.tvonnet.debridxtreamiptv.data.repository.XtreamRepository
import io.mockk.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.*
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE)
class SeriesPagingSourceTest {

    private lateinit var repository: XtreamRepository
    private lateinit var pagingSource: SeriesPagingSource
    private val testScheduler = TestCoroutineScheduler()
    private lateinit var testDispatcher: TestDispatcher

    @Before
    fun setup() {
        testDispatcher = StandardTestDispatcher(testScheduler)
        Dispatchers.setMain(testDispatcher)
        // Mock Android Log
        mockkStatic(android.util.Log::class)
        every { android.util.Log.d(any(), any()) } returns 0
        every { android.util.Log.e(any(), any<String>()) } returns 0
        every { android.util.Log.e(any(), any<String>(), any()) } returns 0
        every { android.util.Log.w(any(), any<String>()) } returns 0

        repository = mockk(relaxed = true)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
        clearAllMocks()
        unmockkAll()
    }

    @Test
    fun `load returns page when successful`() = runTest {
        // Given
        val mockSeries = listOf(
            createMockSeries("1", "Series 1"),
            createMockSeries("2", "Series 2"),
            createMockSeries("3", "Series 3")
        )
        coEvery { repository.fetchSeriesForCategory("cat1") } returns Result.Success(mockSeries)

        pagingSource = SeriesPagingSource(repository, "cat1")

        // When
        val result = pagingSource.load(
            PagingSource.LoadParams.Refresh(
                key = null,
                loadSize = 10,
                placeholdersEnabled = false
            )
        )

        // Then
        assertTrue(result is PagingSource.LoadResult.Page)
        val page = result as PagingSource.LoadResult.Page
        assertEquals(3, page.data.size)
        assertEquals("Series 1", page.data[0].name)
        assertNull(page.prevKey)
        assertNull(page.nextKey) // All data fits in one page
    }

    @Test
    fun `load returns error when repository fails`() = runTest {
        // Given
        val exception = Exception("Network error")
        coEvery { repository.fetchSeriesForCategory("cat1") } returns Result.Error(exception)

        pagingSource = SeriesPagingSource(repository, "cat1")

        // When
        val result = pagingSource.load(
            PagingSource.LoadParams.Refresh(
                key = null,
                loadSize = 10,
                placeholdersEnabled = false
            )
        )

        // Then
        assertTrue(result is PagingSource.LoadResult.Page)
        val page = result as PagingSource.LoadResult.Page
        assertEquals(0, page.data.size) // Empty list on error
    }

    @Test
    fun `load returns empty page when category has no series`() = runTest {
        // Given
        coEvery { repository.fetchSeriesForCategory("cat1") } returns Result.Success(emptyList())

        pagingSource = SeriesPagingSource(repository, "cat1")

        // When
        val result = pagingSource.load(
            PagingSource.LoadParams.Refresh(
                key = null,
                loadSize = 10,
                placeholdersEnabled = false
            )
        )

        // Then
        assertTrue(result is PagingSource.LoadResult.Page)
        val page = result as PagingSource.LoadResult.Page
        assertEquals(0, page.data.size)
        assertNull(page.prevKey)
        assertNull(page.nextKey)
    }

    @Test
    fun `pagination works correctly with multiple pages`() = runTest {
        // Given - 25 items total
        val mockSeries = (1..25).map { createMockSeries(it.toString(), "Series $it") }
        coEvery { repository.fetchSeriesForCategory("cat1") } returns Result.Success(mockSeries)

        pagingSource = SeriesPagingSource(repository, "cat1")

        // When - Load first page (20 items)
        val firstPage = pagingSource.load(
            PagingSource.LoadParams.Refresh(
                key = null,
                loadSize = 20,
                placeholdersEnabled = false
            )
        ) as PagingSource.LoadResult.Page

        // Then - First page should have 20 items with nextKey = 20
        assertEquals(20, firstPage.data.size)
        assertEquals("Series 1", firstPage.data[0].name)
        assertNull(firstPage.prevKey)
        assertEquals(20, firstPage.nextKey)

        // When - Load second page
        val secondPage = pagingSource.load(
            PagingSource.LoadParams.Append(
                key = 20,
                loadSize = 20,
                placeholdersEnabled = false
            )
        ) as PagingSource.LoadResult.Page

        // Then - Second page should have 5 remaining items
        assertEquals(5, secondPage.data.size)
        assertEquals("Series 21", secondPage.data[0].name)
        assertEquals(0, secondPage.prevKey)
        assertNull(secondPage.nextKey) // No more data
    }

    @Test
    fun `caching prevents duplicate API calls`() = runTest {
        // Given
        val mockSeries = listOf(createMockSeries("1", "Series 1"))
        coEvery { repository.fetchSeriesForCategory("cat1") } returns Result.Success(mockSeries)

        pagingSource = SeriesPagingSource(repository, "cat1")

        // When - Load twice
        pagingSource.load(
            PagingSource.LoadParams.Refresh(
                key = null,
                loadSize = 10,
                placeholdersEnabled = false
            )
        )
        pagingSource.load(
            PagingSource.LoadParams.Refresh(
                key = null,
                loadSize = 10,
                placeholdersEnabled = false
            )
        )

        // Then - Repository should only be called once (cached)
        coVerify(exactly = 1) { repository.fetchSeriesForCategory("cat1") }
    }

    private fun createMockSeries(id: String, name: String): XtreamSeriesInfo {
        return XtreamSeriesInfo(
            num = id.toIntOrNull(),
            series_id = id,
            name = name,
            category_id = "cat1",
            cover = "cover$id.jpg",
            plot = "Test plot",
            cast = "Actor 1",
            director = "Director 1",
            genre = "Drama",
            releaseDate = "2023-01-01",
            rating = "8.5",
            rating_5based = 4.25,
            episodes = null,
            category_ids = null,
            backdrop_path = null,
            youtube_trailer = null
        )
    }
}

