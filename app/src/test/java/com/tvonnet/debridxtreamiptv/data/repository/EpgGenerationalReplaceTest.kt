package com.tvonnet.debridxtreamiptv.data.repository

import android.content.Context
import androidx.arch.core.executor.testing.InstantTaskExecutorRule
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.tvonnet.debridxtreamiptv.data.epg.EpgParser
import com.tvonnet.debridxtreamiptv.data.local.AppDatabase
import com.tvonnet.debridxtreamiptv.data.local.dao.EpgDao
import com.tvonnet.debridxtreamiptv.data.local.entity.EpgEntity
import com.tvonnet.debridxtreamiptv.data.remote.XtreamApiService
import com.tvonnet.debridxtreamiptv.utils.memory.MemoryManager
import io.mockk.*
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import retrofit2.Response

/**
 * Phase 7 freeze test for the missing "EPG failure does not erase the old guide" case from
 * XTREAM_REPOSITORY_REFACTOR_PLAN.md. This locks the generational-replace invariant introduced to
 * fix the "EPG sometimes doesn't show" bug BEFORE the EPG responsibilities are extracted:
 *
 *  - a successful, non-empty parse retires the previous generation (old rows gone, new rows in), but
 *  - a parse that yields zero programmes (empty body) KEEPS the previous generation — the guide is
 *    never wiped to empty.
 *
 * Uses a real in-memory Room EpgDao and the real MemoryManager/EpgParser so the actual streaming
 * parse + SQL runs; only apiService.getEpg is mocked to feed a controlled XMLTV body.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [33])
class EpgGenerationalReplaceTest {

    @get:Rule
    val instantTaskExecutorRule = InstantTaskExecutorRule()

    private lateinit var context: Context
    private lateinit var db: AppDatabase
    private lateinit var epgDao: EpgDao
    private lateinit var repository: XtreamRepository

    // Far-future so the 24h clearOldPrograms() sweep (deletes WHERE stop < now-24h) never removes
    // our rows — isolating the generational-replace behaviour we are actually freezing.
    private val farFutureStart = 2_208_988_800_000L // ~2040-01-01
    private val farFutureStop = farFutureStart + 3_600_000L

    @Before
    fun setup() {
        context = ApplicationProvider.getApplicationContext()
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        epgDao = db.epgDao()
        EpgParser.initialize(context)

        repository = XtreamRepository(
            context = context,
            cacheManager = mockk(relaxed = true),
            favoriteDao = mockk(relaxed = true),
            searchHistoryDao = mockk(relaxed = true),
            epgDao = epgDao,
            vodDao = mockk(relaxed = true),
            seriesDao = mockk(relaxed = true),
            favoritesCache = mockk(relaxed = true),
            memoryManager = MemoryManager.getInstance(context)
        )
    }

    @After
    fun tearDown() {
        db.close()
        clearAllMocks()
        unmockkAll()
    }

    private fun setApiService(api: XtreamApiService) {
        // Phase 7: apiService now lives on the extracted XtreamSession (repo's private `session` field).
        val sessionField = repository.javaClass.getDeclaredField("session")
        sessionField.isAccessible = true
        val session = sessionField.get(repository)
        val field = session.javaClass.getDeclaredField("apiService")
        field.isAccessible = true
        field.set(session, api)
    }

    private fun stubEpgBody(xml: String) {
        val api = mockk<XtreamApiService>()
        coEvery { api.getEpg(any(), any()) } returns
            Response.success(xml.toResponseBody("application/xml".toMediaTypeOrNull()))
        setApiService(api)
    }

    private suspend fun seedOldGeneration() {
        epgDao.insertAll(
            listOf(
                EpgEntity(
                    channelId = "bbc",
                    start = farFutureStart,
                    stop = farFutureStop,
                    title = "OLD",
                    description = null,
                    category = null,
                    cachedAt = 1L // ancient generation -> < any real syncStamp
                )
            )
        )
    }

    @Test
    fun `non-empty parse retires the old generation and installs the new one`() = runTest {
        seedOldGeneration()
        stubEpgBody(
            """
            <?xml version="1.0" encoding="UTF-8"?>
            <tv>
              <programme channel="bbc" start="20400101060000 +0000" stop="20400101070000 +0000">
                <title>NEW</title>
              </programme>
            </tv>
            """.trimIndent()
        )

        val result = repository.fetchAndSaveEpg()

        assertTrue(result.isSuccess)
        assertEquals(1, result.getOrNull())
        assertEquals(1, epgDao.getTotalProgramCount())
        assertEquals("NEW", epgDao.getProgramsByChannel("bbc").first().single().title)
    }

    @Test
    fun `empty parse keeps the previous generation - the guide is never wiped`() = runTest {
        seedOldGeneration()
        stubEpgBody("<?xml version=\"1.0\" encoding=\"UTF-8\"?><tv></tv>")

        val result = repository.fetchAndSaveEpg()

        // 0 parsed programmes -> success with 0, and crucially the old row is still present.
        assertTrue(result.isSuccess)
        assertEquals(0, result.getOrNull())
        assertEquals(1, epgDao.getTotalProgramCount())
        assertEquals("OLD", epgDao.getProgramsByChannel("bbc").first().single().title)
    }
}
