package com.tvonnet.debridxtreamiptv.features.seriesv2.data.repository

import com.tvonnet.debridxtreamiptv.data.Result
import com.tvonnet.debridxtreamiptv.data.remote.XtreamApiService
import com.tvonnet.debridxtreamiptv.data.repository.XtreamRepository
import com.tvonnet.debridxtreamiptv.features.seriesv2.data.dao.EpisodeDaoV2
import com.tvonnet.debridxtreamiptv.features.seriesv2.data.dao.SeriesDaoV2
import io.mockk.clearAllMocks
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkAll
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Guards the exception handling in `getSeriesById` after cancellation was split out of the generic
 * `catch (e: Exception)`.
 *
 * The risk of that change is ordering: a `CancellationException` clause placed above the generic one
 * must not swallow ordinary failures too, because the recovery ladder behind it is what makes dead
 * regional-dub listings (array-shaped JSON that breaks `get_series_info` parsing) show episodes at
 * all. So this pins the half that IS reliably observable — a plain network failure still falls
 * through to the episodes-only fallbacks.
 *
 * The cancellation half is deliberately not asserted here: the flow runs on `Dispatchers.IO` via
 * `flowOn`, and once a coroutine is cancelled every downstream suspension point throws anyway, so a
 * test would be timing-dependent without proving much. That half rests on the rule in CLAUDE.md
 * ("always rethrow CancellationException") and on the code reading straightforwardly.
 */
class XtreamSeriesRepositoryV2RecoveryTest {

    private lateinit var legacyRepository: XtreamRepository
    private lateinit var apiService: XtreamApiService
    private lateinit var seriesDao: SeriesDaoV2
    private lateinit var episodeDao: EpisodeDaoV2
    private lateinit var repository: XtreamSeriesRepositoryV2

    @Before
    fun setUp() {
        mockkStatic(android.util.Log::class)
        every { android.util.Log.d(any(), any()) } returns 0
        every { android.util.Log.i(any(), any()) } returns 0
        every { android.util.Log.w(any(), any<String>()) } returns 0
        every { android.util.Log.e(any(), any<String>()) } returns 0
        every { android.util.Log.e(any(), any<String>(), any()) } returns 0

        apiService = mockk(relaxed = true)
        legacyRepository = mockk(relaxed = true)
        seriesDao = mockk(relaxed = true)
        episodeDao = mockk(relaxed = true)

        every { legacyRepository.getApiService() } returns apiService
        every { legacyRepository.getUsername() } returns "u"
        every { legacyRepository.getPassword() } returns "p"
        coEvery { seriesDao.getSeriesById(any()) } returns null
        coEvery { episodeDao.getCountForSeries(any()) } returns 0

        repository = XtreamSeriesRepositoryV2(
            legacyRepository = legacyRepository,
            seriesDao = seriesDao,
            episodeDao = episodeDao,
            categoryDao = mockk(relaxed = true),
            legacySeriesDao = mockk(relaxed = true),
            database = mockk(relaxed = true),
        )
    }

    @After
    fun tearDown() {
        clearAllMocks()
        unmockkAll()
    }

    @Test
    fun `a parse failure on get_series_info still falls through to the episodes-only fallbacks`() = runTest {
        // Dead regional dubs return array-shaped JSON that blows up get_series_info parsing.
        coEvery { apiService.getSeriesInfo(any(), any(), any(), any()) } throws
            java.io.IOException("malformed JSON")

        repository.getSeriesById("42").toList()

        coVerify(atLeast = 1) { apiService.getSeries(any(), any(), any(), any(), any()) }
        coVerify(atLeast = 1) { apiService.getSeriesEpisodes(any(), any(), any(), any()) }
    }

    @Test
    fun `with nothing cached and every endpoint failing the caller gets an error, not silence`() = runTest {
        coEvery { apiService.getSeriesInfo(any(), any(), any(), any()) } throws
            java.io.IOException("malformed JSON")

        val emissions = repository.getSeriesById("42").toList()

        assertTrue("expected an error emission, got $emissions", emissions.any { it is Result.Error })
    }
}
