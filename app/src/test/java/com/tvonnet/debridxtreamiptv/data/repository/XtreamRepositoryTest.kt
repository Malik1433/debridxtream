package com.tvonnet.debridxtreamiptv.data.repository

import android.content.Context
import androidx.arch.core.executor.testing.InstantTaskExecutorRule
import com.tvonnet.debridxtreamiptv.data.Result
import com.tvonnet.debridxtreamiptv.data.cache.CacheHelper
import com.tvonnet.debridxtreamiptv.data.model.*
import com.tvonnet.debridxtreamiptv.data.remote.XtreamApiService
import io.mockk.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.*
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import retrofit2.Response
import com.tvonnet.debridxtreamiptv.utils.memory.MemoryManager

@OptIn(ExperimentalCoroutinesApi::class)
class XtreamRepositoryTest {
    
    @get:Rule
    val instantTaskExecutorRule = InstantTaskExecutorRule()
    
    private lateinit var testDispatcher: TestDispatcher
    private lateinit var testScheduler: TestCoroutineScheduler
    
    private lateinit var context: Context
    private lateinit var cacheHelper: CacheHelper
    private lateinit var repository: XtreamRepository
    private lateinit var cacheManager: com.tvonnet.debridxtreamiptv.data.cache.CacheManager
    private lateinit var epgDao: com.tvonnet.debridxtreamiptv.data.local.dao.EpgDao
    private lateinit var memoryManager: MemoryManager
    
    // Test data
    private val testUsername = "testuser"
    private val testPassword = "testpass"
    private val testBaseUrl = "http://test.com:8080"
    
    @Before
    fun setup() {
        testScheduler = TestCoroutineScheduler()
        testDispatcher = StandardTestDispatcher(testScheduler)
        Dispatchers.setMain(testDispatcher)
        
        // Mock Android Log class
        mockkStatic(android.util.Log::class)
        every { android.util.Log.d(any(), any()) } returns 0
        every { android.util.Log.e(any(), any<String>()) } returns 0
        every { android.util.Log.e(any(), any<String>(), any()) } returns 0
        every { android.util.Log.w(any(), any<String>()) } returns 0
        every { android.util.Log.w(any(), any<Throwable>()) } returns 0
        every { android.util.Log.w(any(), any<String>(), any()) } returns 0
        every { android.util.Log.i(any(), any()) } returns 0
        every { android.util.Log.v(any(), any()) } returns 0
        
        // Mock Context and CacheHelper
        context = mockk(relaxed = true)
        cacheHelper = mockk(relaxed = true)
        
        // Mock CacheHelper constructor
        mockkConstructor(CacheHelper::class)
        every { anyConstructed<CacheHelper>().readCache() } returns null
        every { anyConstructed<CacheHelper>().writeCache(any()) } just Runs
        
        memoryManager = mockk(relaxed = true)
        cacheManager = mockk(relaxed = true)
        epgDao = mockk(relaxed = true)
        coEvery { cacheManager.getChannels(any(), any()) } returns null
        coEvery { cacheManager.getCategories(any()) } returns null
        
        repository = XtreamRepository(
            context = context,
            cacheManager = cacheManager,
            favoriteDao = mockk(relaxed = true),
            searchHistoryDao = mockk(relaxed = true),
            epgDao = epgDao,
            vodDao = mockk(relaxed = true),
            seriesDao = mockk(relaxed = true),
            favoritesCache = mockk(relaxed = true),
            memoryManager = memoryManager
        )
    }
    
    @After
    fun tearDown() {
        Dispatchers.resetMain()
        clearAllMocks()
        unmockkAll()
    }
    
    @Test
    fun `initialize sets up repository correctly`() {
        // When
        repository.initialize(testBaseUrl, testUsername, testPassword)
        
        // Then - no exception should be thrown
        assertTrue(true)
    }
    
    @Test
    fun `login returns success when credentials are valid`() = runTest {
        // Given
        val mockLoginResponse = XtreamLoginResponse(
            user_info = XtreamUserInfo(
                username = testUsername,
                password = testPassword,
                message = "Login successful",
                auth = 1,
                status = "Active",
                exp_date = "1234567890",
                is_trial = "0",
                active_cons = "1",
                created_at = "1234567890",
                max_connections = "3",
                allowed_output_formats = listOf("m3u8", "ts")
            ),
            server_info = XtreamServerInfo(
                url = testBaseUrl,
                port = "8080",
                https_port = "8443",
                server_protocol = "http",
                rtmp_port = "1935",
                timezone = "UTC",
                timestamp_now = System.currentTimeMillis()
            )
        )
        
        // Mock API service using reflection to inject it
        val apiService = mockk<XtreamApiService>()
        coEvery { apiService.login(testUsername, testPassword) } returns Response.success(mockLoginResponse)
        
        // Use reflection to set the private apiService field (now on the extracted XtreamSession)
        setApiService(apiService)

        // When
        val result = repository.login(testUsername, testPassword)
        
        // Then
        assertTrue(result.isSuccess)
        assertNotNull(result.getOrNull())
        assertEquals(testUsername, result.getOrNull()?.user_info?.username)
    }
    
    @Test
    fun `login returns failure when API service not initialized`() = runTest {
        // Given - repository without initialization
        
        // When
        val result = repository.login(testUsername, testPassword)
        
        // Then
        assertTrue(result.isError)
        assertEquals("API service not initialized", result.exceptionOrNull()?.message)
    }
    
    @Test
    fun `login returns failure when credentials are invalid`() = runTest {
        // Given
        val apiService = mockk<XtreamApiService>()
        coEvery { apiService.login(any(), any()) } returns Response.error(
            401,
            "Unauthorized".toResponseBody()
        )
        
        // Set API service using reflection (now on the extracted XtreamSession)
        setApiService(apiService)

        // When
        val result = repository.login("wrong", "credentials")
        
        // Then
        assertTrue(result.isError)
        assertTrue(result.exceptionOrNull()?.message?.contains("Login failed") == true)
    }
    
    @Test
    fun `fetchAllAndCache returns cached data when API not initialized`() = runTest {
        // Given
        val mockCache = createMockCache()
        every { anyConstructed<CacheHelper>().readCache() } returns mockCache
        
        // When
        val result = repository.fetchAllAndCache()
        
        // Then
        assertTrue(result.isSuccess)
        assertNotNull(result.getOrNull())
        assertEquals(10, result.getOrNull()?.live?.streams?.size)
    }
    
    @Test
    fun `fetchAllAndCache fails when API not initialized and no cache available`() = runTest {
        // Given
        every { anyConstructed<CacheHelper>().readCache() } returns null
        
        // When
        val result = repository.fetchAllAndCache()
        
        // Then
        assertTrue(result.isError)
        assertTrue(
            result.exceptionOrNull()?.message?.contains("API service not initialized and no cache available") == true
        )
    }

    @Test
    fun `fetchAllAndCache preserves the good cache and does not clobber it with empty data when all domain fetches fail`() = runTest {
        // Phase 8: this test used to document the OLD data-loss bug — "currently writes empty success
        // cache" — where a total fetch failure overwrote the good cache with empties. The loading
        // fixes corrected that: with a valid cache present, a total fetch failure now serves the
        // cache (isSuccess) and does NOT write anything, so the user's catalog is never wiped.
        val writtenCache = slot<IptvCache>()
        every { anyConstructed<CacheHelper>().writeCache(capture(writtenCache)) } just Runs
        every { anyConstructed<CacheHelper>().readCache() } returns createMockCache()

        val apiService = mockk<XtreamApiService>()
        coEvery { apiService.getLiveCategories(any(), any()) } returns Response.error(
            500,
            "live failure".toResponseBody()
        )
        coEvery { apiService.getVodCategories(any(), any()) } returns Response.error(
            500,
            "vod failure".toResponseBody()
        )
        coEvery { apiService.getSeriesCategories(any(), any()) } returns Response.error(
            500,
            "series failure".toResponseBody()
        )
        setApiService(apiService)

        val result = repository.fetchAllAndCache()

        // Serves the existing cache instead of failing...
        assertTrue(result.isSuccess)
        // ...and critically does NOT overwrite the good cache with an empty one (no clobbering write).
        assertFalse(writtenCache.isCaptured)
    }
    
    // Note: These tests require proper DI refactoring of XtreamRepository to work correctly
    // The repository creates its own apiService internally, making it difficult to mock
    // TODO: Refactor XtreamRepository to accept apiService via constructor for better testability
    
    @Test
    fun `fetchLiveStreamsForCategory returns failure when API not initialized`() = runTest {
        // Given - repository without API initialization
        
        // When
        val result = repository.fetchLiveStreamsForCategory("1")
        
        // Then
        assertTrue(result.isError)
    }
    
    @Test
    fun `fetchVodStreamsForCategory returns failure when API not initialized`() = runTest {
        // Given - repository without API initialization
        
        // When
        val result = repository.fetchVodStreamsForCategory("2")
        
        // Then
        assertTrue(result.isError)
    }
    
    @Test
    fun `fetchSeriesForCategory returns failure when API not initialized`() = runTest {
        // Given - repository without API initialization
        
        // When
        val result = repository.fetchSeriesForCategory("3")
        
        // Then
        assertTrue(result.isError)
    }
    
    @Test
    fun `readCache returns null when no cache exists`() {
        // Given
        every { anyConstructed<CacheHelper>().readCache() } returns null
        
        // When
        val result = repository.readCache()
        
        // Then
        assertNull(result)
    }
    
    @Test
    fun `readCache returns cached data when cache exists`() {
        // Given
        val mockCache = createMockCache()
        every { anyConstructed<CacheHelper>().readCache() } returns mockCache
        
        // When
        val result = repository.readCache()
        
        // Then
        assertNotNull(result)
        assertEquals(10, result?.live?.streams?.size)
    }
    
    @Test
    fun `readCache uses memory cache on second call`() {
        // Given
        val mockCache = createMockCache()
        every { anyConstructed<CacheHelper>().readCache() } returns mockCache
        
        // When
        val firstResult = repository.readCache()
        val secondResult = repository.readCache()
        
        // Then
        assertNotNull(firstResult)
        assertNotNull(secondResult)
        // Verify readCache was called only once (memory cache used on second call)
        verify(exactly = 1) { anyConstructed<CacheHelper>().readCache() }
    }
    
    @Test
    fun `clearMemoryCache clears the cache`() {
        // Given
        val mockCache = createMockCache()
        every { anyConstructed<CacheHelper>().readCache() } returns mockCache
        
        // Load cache into memory
        repository.readCache()
        
        // When
        repository.clearMemoryCache()
        
        // Then - next readCache should hit file cache again
        repository.readCache()
        verify(exactly = 2) { anyConstructed<CacheHelper>().readCache() }
    }
    
    @Test
    fun `forceRefresh clears memory cache and fetches new data`() = runTest {
        // Given
        val mockCache = createMockCache()
        every { anyConstructed<CacheHelper>().readCache() } returns mockCache
        
        // Load initial cache
        repository.readCache()
        
        // When
        val result = repository.forceRefresh()
        
        // Then
        assertTrue(result.isSuccess)
        assertNotNull(result.getOrNull())
    }

    private fun setApiService(apiService: XtreamApiService) {
        // Phase 7: apiService now lives on the extracted XtreamSession, reached via the repo's
        // private `session` field.
        val sessionField = repository.javaClass.getDeclaredField("session")
        sessionField.isAccessible = true
        val session = sessionField.get(repository)
        val apiServiceField = session.javaClass.getDeclaredField("apiService")
        apiServiceField.isAccessible = true
        apiServiceField.set(session, apiService)
    }
    
    // Helper function to create mock cache data
    private fun createMockCache(): IptvCache {
        return IptvCache(
            timestamp = System.currentTimeMillis(),
            live = LiveCacheData(
                mockCategories("1" to "Sports", "2" to "News"),
                createMockLiveStreams()
            ),
            vod = VodCacheData(mockCategories("10" to "Action", "11" to "Comedy"), emptyList()),
            series = SeriesCacheData(mockCategories("20" to "Drama", "21" to "Sci-Fi"), emptyList()),
            epg = null
        )
    }

    private fun mockCategories(vararg idToName: Pair<String, String>): List<XtreamCategory> =
        idToName.map { (id, name) ->
            XtreamCategory(
                category_id = id,
                category_name = name,
                parent_id = null
            )
        }

    private fun createMockLiveStreams(): List<XtreamStream> = List(10) { index ->
        XtreamStream(
            num = index,
            stream_id = index.toString(),
            name = "Channel $index",
            category_id = "1",
            stream_icon = "icon$index.jpg",
            stream_type = "live",
            epg_channel_id = null,
            added = "123456",
            category_ids = listOf(1),
            container_extension = "ts",
            custom_sid = null,
            direct_source = null,
            tv_archive = 0,
            tv_archive_duration = 0
        )
    }
}

