package com.tvonnet.debridxtreamiptv.data.repository

import android.content.Context
import com.tvonnet.debridxtreamiptv.data.cache.CacheHelper
import com.tvonnet.debridxtreamiptv.data.model.SeriesCategoryState
import com.tvonnet.debridxtreamiptv.data.model.XtreamSeriesInfo
import com.tvonnet.debridxtreamiptv.data.remote.XtreamApiService
import com.tvonnet.debridxtreamiptv.utils.memory.MemoryManager
import io.mockk.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.*
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import retrofit2.Response
import java.net.SocketTimeoutException

/**
 * Phase 7 freeze test for the missing "series outage is NOT treated as confirmed empty" case from
 * XTREAM_REPOSITORY_REFACTOR_PLAN.md. Locks the CURRENT behaviour of the seriesCategoryStatus state
 * machine before it (and the shared status map) is extracted:
 *
 *  - a provider outage (HTTP 500 or a thrown timeout) records TEMPORARILY_UNAVAILABLE and returns
 *    Result.Error — it is distinguishable from an empty/healthy category, and
 *  - a successful fetch with data clears any status (healthy => no entry).
 *
 * NOTE: this documents present behaviour; it is not an endorsement of it.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SeriesOutageStatusTest {

    private lateinit var testDispatcher: TestDispatcher
    private lateinit var context: Context
    private lateinit var repository: XtreamRepository

    @Before
    fun setup() {
        testDispatcher = StandardTestDispatcher(TestCoroutineScheduler())
        Dispatchers.setMain(testDispatcher)

        mockkStatic(android.util.Log::class)
        every { android.util.Log.d(any(), any()) } returns 0
        every { android.util.Log.e(any(), any<String>()) } returns 0
        every { android.util.Log.e(any(), any<String>(), any()) } returns 0
        every { android.util.Log.w(any(), any<String>()) } returns 0
        every { android.util.Log.w(any(), any<String>(), any()) } returns 0

        context = mockk(relaxed = true)

        mockkConstructor(CacheHelper::class)
        every { anyConstructed<CacheHelper>().readCache() } returns null
        every { anyConstructed<CacheHelper>().readAllSeries() } returns null
        every { anyConstructed<CacheHelper>().writeCache(any()) } just Runs

        repository = XtreamRepository(
            context = context,
            cacheManager = mockk(relaxed = true),
            favoriteDao = mockk(relaxed = true),
            searchHistoryDao = mockk(relaxed = true),
            epgDao = mockk(relaxed = true),
            vodDao = mockk(relaxed = true),
            seriesDao = mockk(relaxed = true),
            favoritesCache = mockk(relaxed = true),
            memoryManager = mockk<MemoryManager>(relaxed = true)
        )
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
        clearAllMocks()
        unmockkAll()
    }

    private fun setApiService(apiService: XtreamApiService) {
        // Phase 7: apiService now lives on the extracted XtreamSession (repo's private `session` field).
        val sessionField = repository.javaClass.getDeclaredField("session")
        sessionField.isAccessible = true
        val session = sessionField.get(repository)
        val field = session.javaClass.getDeclaredField("apiService")
        field.isAccessible = true
        field.set(session, apiService)
    }

    private fun sampleSeries(categoryId: String) = XtreamSeriesInfo(
        num = 0, name = "Show", series_id = "s1", cover = null, plot = null, cast = null,
        director = null, genre = null, releaseDate = null, rating = null, rating_5based = null,
        category_id = categoryId, category_ids = null, backdrop_path = null,
        youtube_trailer = null, episodes = null
    )

    @Test
    fun `http 500 marks category temporarily unavailable and returns error, not empty success`() = runTest {
        val api = mockk<XtreamApiService>()
        coEvery { api.getSeries(any(), any(), any(), any(), any()) } returns
            Response.error(500, "boom".toResponseBody())
        setApiService(api)

        val result = repository.fetchSeriesForCategory("42")

        assertTrue("outage must be an error, not empty success", result.isError)
        assertEquals(
            SeriesCategoryState.TEMPORARILY_UNAVAILABLE,
            repository.getSeriesCategoryStatus("42")?.state
        )
    }

    @Test
    fun `thrown timeout marks category temporarily unavailable and returns error`() = runTest {
        val api = mockk<XtreamApiService>()
        coEvery { api.getSeries(any(), any(), any(), any(), any()) } throws
            SocketTimeoutException("timeout")
        setApiService(api)

        val result = repository.fetchSeriesForCategory("42")

        assertTrue(result.isError)
        assertEquals(
            SeriesCategoryState.TEMPORARILY_UNAVAILABLE,
            repository.getSeriesCategoryStatus("42")?.state
        )
    }

    @Test
    fun `successful fetch with data clears status - healthy category has no outage entry`() = runTest {
        val api = mockk<XtreamApiService>()
        coEvery { api.getSeries(any(), any(), any(), any(), any()) } returns
            Response.success(listOf(sampleSeries("42")))
        setApiService(api)

        val result = repository.fetchSeriesForCategory("42")

        assertTrue(result.isSuccess)
        assertTrue((result.getOrNull()?.isNotEmpty()) == true)
        assertNull("a healthy category must not carry a status entry", repository.getSeriesCategoryStatus("42"))
    }
}
