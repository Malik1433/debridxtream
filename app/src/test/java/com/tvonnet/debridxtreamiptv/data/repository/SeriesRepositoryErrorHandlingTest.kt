package com.tvonnet.debridxtreamiptv.data.repository

import android.content.Context
import com.tvonnet.debridxtreamiptv.data.Result
import com.tvonnet.debridxtreamiptv.data.model.XtreamSeriesInfo
import com.tvonnet.debridxtreamiptv.data.remote.XtreamApiService
import io.mockk.*
import com.tvonnet.debridxtreamiptv.testutil.MainDispatcherRule
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import retrofit2.HttpException
import retrofit2.Response
import java.io.IOException
import java.net.SocketTimeoutException
import com.tvonnet.debridxtreamiptv.utils.memory.MemoryManager

/**
 * Tests error handling and propagation in XtreamRepository
 * Ensures errors are properly wrapped in Result.Error instead of being masked
 */
class SeriesRepositoryErrorHandlingTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    private lateinit var context: Context
    private lateinit var apiService: XtreamApiService
    private lateinit var repository: XtreamRepository

    @Before
    fun setup() {
        // Mock Android Log
        mockkStatic(android.util.Log::class)
        every { android.util.Log.d(any(), any()) } returns 0
        every { android.util.Log.e(any(), any<String>()) } returns 0
        every { android.util.Log.e(any(), any<String>(), any()) } returns 0
        every { android.util.Log.w(any(), any<String>()) } returns 0

        context = mockk(relaxed = true)
        apiService = mockk(relaxed = true)
        val memoryManager = mockk<MemoryManager>(relaxed = true)
        val cacheManager = mockk<com.tvonnet.debridxtreamiptv.data.cache.CacheManager>(relaxed = true)
        coEvery { cacheManager.getChannels(any(), "series") } returns null
        coEvery { cacheManager.getCategories("series") } returns null
        repository = spyk(XtreamRepository(
            context = context,
            cacheManager = cacheManager,
            favoriteDao = mockk(relaxed = true),
            searchHistoryDao = mockk(relaxed = true),
            epgDao = mockk(relaxed = true),
            vodDao = mockk(relaxed = true),
            seriesDao = mockk(relaxed = true),
            favoritesCache = mockk(relaxed = true),
            memoryManager = memoryManager
        ))
    }

    @After
    fun tearDown() {
        clearAllMocks()
        unmockkAll()
    }

    @Test
    fun `fetchSeriesForCategory propagates network timeout error`() = runTest {
        // Given
        val timeoutException = SocketTimeoutException("Connection timed out")
        coEvery { repository.fetchSeriesForCategory("cat1") } returns Result.Error(timeoutException)

        // When
        val result = repository.fetchSeriesForCategory("cat1")

        // Then
        assertTrue(result is Result.Error)
        val error = (result as Result.Error).exception
        assertTrue(error is SocketTimeoutException)
        assertEquals("Connection timed out", error.message)
    }

    @Test
    fun `fetchSeriesForCategory propagates HTTP 500 error`() = runTest {
        // Given
        val response: Response<List<XtreamSeriesInfo>> = mockk()
        every { response.isSuccessful } returns false
        every { response.code() } returns 500
        every { response.message() } returns "Internal Server Error"
        val httpException = HttpException(response)
        coEvery { repository.fetchSeriesForCategory("cat1") } returns Result.Error(httpException)

        // When
        val result = repository.fetchSeriesForCategory("cat1")

        // Then
        assertTrue(result is Result.Error)
        val error = (result as Result.Error).exception
        assertTrue(error is HttpException)
    }

    @Test
    fun `fetchSeriesForCategory propagates HTTP 401 unauthorized error`() = runTest {
        // Given
        val response: Response<List<XtreamSeriesInfo>> = mockk()
        every { response.isSuccessful } returns false
        every { response.code() } returns 401
        every { response.message() } returns "Unauthorized"
        val httpException = HttpException(response)
        coEvery { repository.fetchSeriesForCategory("cat1") } returns Result.Error(httpException)

        // When
        val result = repository.fetchSeriesForCategory("cat1")

        // Then
        assertTrue(result is Result.Error)
        assertTrue((result as Result.Error).exception is HttpException)
    }

    @Test
    fun `fetchSeriesForCategory returns Error when API not initialized`() = runTest {
        // Given
        coEvery { repository.fetchSeriesForCategory("cat1") } returns 
            Result.Error(Exception("API service not initialized"))

        // When
        val result = repository.fetchSeriesForCategory("cat1")

        // Then
        assertTrue(result is Result.Error)
        assertEquals("API service not initialized", (result as Result.Error).exception.message)
    }

    @Test
    fun `fetchSeriesForCategory handles IOException`() = runTest {
        // Given
        val ioException = IOException("No internet connection")
        coEvery { repository.fetchSeriesForCategory("cat1") } returns Result.Error(ioException)

        // When
        val result = repository.fetchSeriesForCategory("cat1")

        // Then
        assertTrue(result is Result.Error)
        assertTrue((result as Result.Error).exception is IOException)
        assertEquals("No internet connection", result.exception.message)
    }

    @Test
    fun `fetchSeriesForCategory returns empty list for 404 without error`() = runTest {
        // Given - 404 is treated as empty category, not error
        coEvery { repository.fetchSeriesForCategory("cat1") } returns Result.Success(emptyList())

        // When
        val result = repository.fetchSeriesForCategory("cat1")

        // Then
        assertTrue(result is Result.Success)
        assertEquals(0, (result as Result.Success).data.size)
    }

    @Test
    fun `fetchSeriesDetail propagates network errors`() = runTest {
        // Given
        val networkException = Exception("Network failure")
        coEvery { repository.fetchSeriesDetail("series1") } returns Result.Error(networkException)

        // When
        val result = repository.fetchSeriesDetail("series1")

        // Then
        assertTrue(result is Result.Error)
        assertEquals("Network failure", (result as Result.Error).exception.message)
    }

    @Test
    fun `multiple consecutive errors are all propagated`() = runTest {
        // Given
        val error1 = IOException("Connection lost")
        val error2 = SocketTimeoutException("Timeout")
        coEvery { repository.fetchSeriesForCategory("cat1") } returnsMany listOf(
            Result.Error(error1),
            Result.Error(error2)
        )

        // When
        val result1 = repository.fetchSeriesForCategory("cat1")
        val result2 = repository.fetchSeriesForCategory("cat1")

        // Then
        assertTrue(result1 is Result.Error)
        assertTrue(result2 is Result.Error)
        assertEquals("Connection lost", (result1 as Result.Error).exception.message)
        assertEquals("Timeout", (result2 as Result.Error).exception.message)
    }

    @Test
    fun `error after success is properly handled`() = runTest {
        // Given
        val mockSeries = listOf(
            XtreamSeriesInfo(
                num = 1,
                series_id = "1",
                name = "Series 1",
                category_id = "cat1",
                cover = null,
                plot = null,
                cast = null,
                director = null,
                genre = null,
                releaseDate = null,
                rating = null,
                rating_5based = null,
                episodes = null,
                category_ids = null,
                backdrop_path = null,
                youtube_trailer = null
            )
        )
        coEvery { repository.fetchSeriesForCategory("cat1") } returnsMany listOf(
            Result.Success(mockSeries),
            Result.Error(Exception("Subsequent error"))
        )

        // When
        val result1 = repository.fetchSeriesForCategory("cat1")
        val result2 = repository.fetchSeriesForCategory("cat1")

        // Then
        assertTrue(result1 is Result.Success)
        assertEquals(1, (result1 as Result.Success).data.size)
        assertTrue(result2 is Result.Error)
        assertEquals("Subsequent error", (result2 as Result.Error).exception.message)
    }
}

