package com.tvonnet.debridxtreamiptv.repository

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.tvonnet.debridxtreamiptv.data.cache.CacheHelper
import com.tvonnet.debridxtreamiptv.data.model.*
import com.tvonnet.debridxtreamiptv.data.repository.XtreamRepository
import kotlinx.coroutines.runBlocking
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import com.tvonnet.debridxtreamiptv.utils.memory.MemoryManager
import io.mockk.*

/**
 * Unit Tests for XtreamRepository Stream Lookup Methods
 * Week 12: QA Recommendation #3
 * 
 * Tests:
 * - getLiveStreamById()
 * - getVodById()
 * - getSeriesById()
 * - buildLiveStreamUrl()
 * - buildVodStreamUrl()
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class XtreamRepositoryStreamLookupTest {
    
    private lateinit var repository: XtreamRepository
    private lateinit var context: Context
    private lateinit var cacheHelper: CacheHelper
    
    @Before
    fun setup() {
        context = ApplicationProvider.getApplicationContext()
        cacheHelper = CacheHelper(context)
        val memoryManager = io.mockk.mockk<com.tvonnet.debridxtreamiptv.utils.memory.MemoryManager>(relaxed = true)
        val cacheManager = mockk<com.tvonnet.debridxtreamiptv.data.cache.CacheManager>(relaxed = true)
        val favoriteDao = mockk<com.tvonnet.debridxtreamiptv.data.local.dao.FavoriteDao>(relaxed = true)
        val searchHistoryDao = mockk<com.tvonnet.debridxtreamiptv.data.local.dao.SearchHistoryDao>(relaxed = true)
        val epgDao = mockk<com.tvonnet.debridxtreamiptv.data.local.dao.EpgDao>(relaxed = true)
        val vodDao = mockk<com.tvonnet.debridxtreamiptv.data.local.dao.VodDao>(relaxed = true)
        val seriesDao = mockk<com.tvonnet.debridxtreamiptv.data.local.dao.SeriesDao>(relaxed = true)
        
        coEvery { cacheManager.getChannelById(any()) } returns null
        coEvery { vodDao.getVodById(any()) } returns null
        coEvery { seriesDao.getSeriesById(any()) } returns null
        
        repository = XtreamRepository(
            context = context,
            cacheManager = cacheManager,
            favoriteDao = favoriteDao,
            searchHistoryDao = searchHistoryDao,
            epgDao = epgDao,
            vodDao = vodDao,
            seriesDao = seriesDao,
            favoritesCache = mockk(relaxed = true),
            memoryManager = memoryManager
        )
        cacheHelper.clearCache()
    }
    
    // ========== getLiveStreamById() Tests ==========
    
    @Test
    fun `getLiveStreamById returns correct stream when exists`() {
        // Given: Cache with live streams
        val testStream = createLiveStream(
            streamId = "12345",
            name = "BBC News HD",
            streamIcon = "http://example.com/icon.png",
            epgChannelId = "bbc.news"
        )
        
        val cache = IptvCache(
            timestamp = System.currentTimeMillis(),
            live = LiveCacheData(
                categories = emptyList(),
                streams = listOf(testStream)
            ),
            vod = null,
            series = null,
            epg = null
        )
        
        cacheHelper.writeCache(cache)
        
        // When: Looking up stream
        val result = runBlocking { repository.getLiveStreamById("12345") }
        
        // Then: Correct stream returned
        assertNotNull(result)
        assertEquals("12345", result?.stream_id)
        assertEquals("BBC News HD", result?.name)
    }
    
    @Test
    fun `getLiveStreamById returns null when stream not found`() {
        // Given: Empty cache
        val cache = IptvCache(
            timestamp = System.currentTimeMillis(),
            live = LiveCacheData(categories = emptyList(), streams = emptyList()),
            vod = null,
            series = null,
            epg = null
        )
        
        cacheHelper.writeCache(cache)
        
        // When: Looking up non-existent stream
        val result = runBlocking { repository.getLiveStreamById("99999") }
        
        // Then: Null returned
        assertNull(result)
    }
    
    @Test
    fun `getLiveStreamById returns null when cache is empty`() {
        // Given: No cache
        cacheHelper.clearCache()
        
        // When: Looking up stream
        val result = runBlocking { repository.getLiveStreamById("12345") }
        
        // Then: Null returned
        assertNull(result)
    }
    
    // ========== getVodById() Tests ==========
    
    @Test
    fun `getVodById returns correct VOD when exists`() {
        // Given: Cache with VOD streams
        val testVod = createVodStream(
            streamId = "vod123",
            name = "Inception",
            streamIcon = "http://example.com/inception.jpg",
            containerExtension = "mp4"
        )
        
        val cache = IptvCache(
            timestamp = System.currentTimeMillis(),
            live = null,
            vod = VodCacheData(
                categories = emptyList(),
                streams = listOf(testVod)
            ),
            series = null,
            epg = null
        )
        
        cacheHelper.writeCache(cache)
        
        // When: Looking up VOD
        val result = runBlocking { repository.getVodById("vod123") }
        
        // Then: Correct VOD returned
        assertNotNull(result)
        assertEquals("vod123", result?.stream_id)
        assertEquals("Inception", result?.name)
    }
    
    @Test
    fun `getVodById returns null when VOD not found`() {
        // Given: Cache without target VOD
        val cache = IptvCache(
            timestamp = System.currentTimeMillis(),
            live = null,
            vod = VodCacheData(categories = emptyList(), streams = emptyList()),
            series = null,
            epg = null
        )
        
        cacheHelper.writeCache(cache)
        
        // When: Looking up non-existent VOD
        val result = runBlocking { repository.getVodById("vod999") }
        
        // Then: Null returned
        assertNull(result)
    }
    
    // ========== getSeriesById() Tests ==========
    
    @Test
    fun `getSeriesById returns correct series when exists`() {
        // Given: Cache with series
        val testSeries = createSeries(
            seriesId = "series456",
            name = "Breaking Bad",
            cover = "http://example.com/bb.jpg"
        )
        
        val cache = IptvCache(
            timestamp = System.currentTimeMillis(),
            live = null,
            vod = null,
            series = SeriesCacheData(
                categories = emptyList(),
                streams = listOf(testSeries)
            ),
            epg = null
        )
        
        cacheHelper.writeCache(cache)
        
        // When: Looking up series
        val result = runBlocking { repository.getSeriesById("series456") }
        
        // Then: Correct series returned
        assertNotNull(result)
        assertEquals("series456", result?.series_id)
        assertEquals("Breaking Bad", result?.name)
    }

    @Test
    fun `getMovieSources aggregates primary and alternate sources`() = runBlocking {
        val actionCategory = XtreamCategory(category_id = "100", category_name = "Action", parent_id = null)
        val thrillerCategory = XtreamCategory(category_id = "200", category_name = "Thriller", parent_id = null)

        val primaryVod = createVodStream(streamId = "vod123", name = "Inception").copy(
            category_id = "100",
            category_ids = listOf(200),
            releaseDate = "2010-01-01",
            container_extension = "mp4"
        )

        val alternateVod = createVodStream(streamId = "vod456", name = "Inception").copy(
            category_id = "200",
            category_ids = listOf(100),
            releaseDate = "2010-06-01",
            container_extension = "mkv"
        )

        val cache = IptvCache(
            timestamp = System.currentTimeMillis(),
            live = null,
            vod = VodCacheData(
                categories = listOf(actionCategory, thrillerCategory),
                streams = emptyList()
            ),
            series = null,
            epg = null
        )
        cacheHelper.writeCache(cache)
        repository.clearMemoryCache()
        setPerCategoryVodCache(
            "100" to listOf(primaryVod),
            "200" to listOf(alternateVod)
        )

        val sources = repository.getMovieSources(
            streamId = "vod123",
            title = "Inception",
            primaryCategoryId = "100",
            yearHint = "2010"
        )

        assertEquals(2, sources.size)
        assertEquals("vod123", sources.first().stream.stream_id)
        assertEquals(true, sources.first().isPrimary)
        assertEquals("vod456", sources[1].stream.stream_id)
    }

    @Test
    fun `getMovieSources filters variants with mismatched year`() = runBlocking {
        val actionCategory = XtreamCategory(category_id = "100", category_name = "Action", parent_id = null)
        val dramaCategory = XtreamCategory(category_id = "300", category_name = "Drama", parent_id = null)

        val primaryVod = createVodStream(streamId = "vod123", name = "Inception").copy(
            category_id = "100",
            category_ids = listOf(300),
            releaseDate = "2010-01-01",
            container_extension = "mp4"
        )

        val mismatchedYearVod = createVodStream(streamId = "vod789", name = "Inception").copy(
            category_id = "300",
            category_ids = listOf(100),
            releaseDate = "2015-03-01",
            container_extension = "mp4"
        )

        val cache = IptvCache(
            timestamp = System.currentTimeMillis(),
            live = null,
            vod = VodCacheData(
                categories = listOf(actionCategory, dramaCategory),
                streams = emptyList()
            ),
            series = null,
            epg = null
        )
        cacheHelper.writeCache(cache)
        repository.clearMemoryCache()
        setPerCategoryVodCache(
            "100" to listOf(primaryVod),
            "300" to listOf(mismatchedYearVod)
        )

        val sources = repository.getMovieSources(
            streamId = "vod123",
            title = "Inception",
            primaryCategoryId = "100",
            yearHint = "2010"
        )

        assertEquals(1, sources.size)
        assertEquals("vod123", sources.first().stream.stream_id)
    }
    
    @Test
    fun `getSeriesById returns null when series not found`() {
        // Given: Empty cache
        val cache = IptvCache(
            timestamp = System.currentTimeMillis(),
            live = null,
            vod = null,
            series = SeriesCacheData(categories = emptyList(), streams = emptyList()),
            epg = null
        )
        
        cacheHelper.writeCache(cache)
        
        // When: Looking up non-existent series
        val result = runBlocking { repository.getSeriesById("series999") }
        
        // Then: Null returned
        assertNull(result)
    }
    
    // ========== buildLiveStreamUrl() Tests ==========
    
    @Test
    fun `buildLiveStreamUrl formats URL correctly`() {
        // Given: Stream and server URL
        val stream = createLiveStream(
            streamId = "12345",
            name = "Test Channel",
            containerExtension = "ts"
        )
        val serverUrl = "http://example.com:8080"
        
        // When: Building URL
        val result = repository.buildLiveStreamUrl(stream, serverUrl)
        
        // Then: Correct format
        assertEquals("http://example.com:8080/live/username/password/12345.ts", result)
        // Note: username/password placeholders in actual implementation
    }
    
    @Test
    fun `buildLiveStreamUrl handles trailing slash`() {
        // Given: Server URL with trailing slash
        val stream = createLiveStream(streamId = "12345", containerExtension = "ts")
        val serverUrl = "http://example.com:8080/"
        
        // When: Building URL
        val result = repository.buildLiveStreamUrl(stream, serverUrl)
        
        // Then: No double slash
        assert(!result.contains("//live"))
    }
    
    @Test
    fun `buildLiveStreamUrl uses default extension when null`() {
        // Given: Stream without extension
        val stream = createLiveStream(streamId = "12345", containerExtension = null)
        val serverUrl = "http://example.com:8080"
        
        // When: Building URL
        val result = repository.buildLiveStreamUrl(stream, serverUrl)
        
        // Then: Default .ts extension used
        assert(result.endsWith(".ts"))
    }
    
    // ========== buildVodStreamUrl() Tests ==========
    
    @Test
    fun `buildVodStreamUrl formats URL correctly`() {
        // Given: VOD and server URL
        val vod = createVodStream(streamId = "vod123", name = "Test Movie", containerExtension = "mp4")
        val serverUrl = "http://example.com:8080"
        
        // When: Building URL
        val result = repository.buildVodStreamUrl(vod, serverUrl)
        
        // Then: Correct format
        assertEquals("http://example.com:8080/movie/username/password/vod123.mp4", result)
    }
    
    @Test
    fun `buildVodStreamUrl uses default extension when null`() {
        // Given: VOD without extension
        val vod = createVodStream(streamId = "vod123", containerExtension = null)
        val serverUrl = "http://example.com:8080"
        
        // When: Building URL
        val result = repository.buildVodStreamUrl(vod, serverUrl)
        
        // Then: Default .mp4 extension used
        assert(result.endsWith(".mp4"))
    }
    
    // ========== Integration Tests ==========
    
    @Test
    fun `stream lookup works with mixed cache content`() {
        // Given: Cache with multiple content types
        val liveStream = createLiveStream(streamId = "live1", name = "Channel 1")
        val vodStream = createVodStream(streamId = "vod1", name = "Movie 1")
        val seriesStream = createSeries(seriesId = "series1", name = "Show 1")
        
        val cache = IptvCache(
            timestamp = System.currentTimeMillis(),
            live = LiveCacheData(emptyList(), listOf(liveStream)),
            vod = VodCacheData(emptyList(), listOf(vodStream)),
            series = SeriesCacheData(emptyList(), listOf(seriesStream)),
            epg = null
        )
        
        cacheHelper.writeCache(cache)
        
        // When: Looking up each type
        val live = runBlocking { repository.getLiveStreamById("live1") }
        val vod = runBlocking { repository.getVodById("vod1") }
        val series = runBlocking { repository.getSeriesById("series1") }
        
        // Then: All found correctly
        assertNotNull(live)
        assertNotNull(vod)
        assertNotNull(series)
        assertEquals("Channel 1", live?.name)
        assertEquals("Movie 1", vod?.name)
        assertEquals("Show 1", series?.name)
    }
    
    @Test
    fun `stream lookup handles large cache efficiently`() {
        // Given: Large cache with 1000 streams
        val streams = (1..1000).map { i ->
            createLiveStream(streamId = "stream_$i", name = "Channel $i")
        }
        
        val cache = IptvCache(
            timestamp = System.currentTimeMillis(),
            live = LiveCacheData(emptyList(), streams),
            vod = null,
            series = null,
            epg = null
        )
        
        cacheHelper.writeCache(cache)
        
        // When: Looking up stream
        val startTime = System.currentTimeMillis()
        val result = runBlocking { repository.getLiveStreamById("stream_500") }
        val endTime = System.currentTimeMillis()
        
        // Then: Found and reasonably fast (< 100ms)
        assertNotNull(result)
        assertEquals("Channel 500", result?.name)
        assert((endTime - startTime) < 100) { "Lookup took ${endTime - startTime}ms" }
    }

    private fun createLiveStream(
        streamId: String,
        name: String = "Channel",
        streamIcon: String? = null,
        epgChannelId: String? = null,
        containerExtension: String? = "ts"
    ): XtreamStream {
        return XtreamStream(
            num = 1,
            name = name,
            stream_type = "live",
            stream_id = streamId,
            stream_icon = streamIcon,
            epg_channel_id = epgChannelId,
            added = null,
            category_id = null,
            category_ids = null,
            container_extension = containerExtension,
            custom_sid = null,
            direct_source = null,
            tv_archive = null,
            tv_archive_duration = null
        )
    }

    private fun createVodStream(
        streamId: String,
        name: String = "Movie",
        streamIcon: String? = null,
        containerExtension: String? = "mp4"
    ): XtreamVodInfo {
        return XtreamVodInfo(
            num = 1,
            name = name,
            stream_type = "movie",
            stream_id = streamId,
            stream_icon = streamIcon,
            rating = null,
            rating_5based = null,
            added = null,
            category_id = null,
            category_ids = null,
            container_extension = containerExtension,
            custom_sid = null,
            direct_source = null,
            releaseDate = null,
            duration = null,
            plot = null,
            cast = null,
            director = null,
            genre = null,
            youtube_trailer = null,
            cover = null,
            rating_imdb = null
        )
    }

    private fun createSeries(
        seriesId: String,
        name: String = "Series",
        cover: String? = null
    ): XtreamSeriesInfo {
        return XtreamSeriesInfo(
            num = 1,
            name = name,
            series_id = seriesId,
            cover = cover,
            plot = null,
            cast = null,
            director = null,
            genre = null,
            releaseDate = null,
            rating = null,
            rating_5based = null,
            category_id = null,
            episodes = null,
            category_ids = null,
            backdrop_path = null,
            youtube_trailer = null
        )
    }

    private fun setPerCategoryVodCache(vararg entries: Pair<String, List<XtreamVodInfo>>) {
        val field = XtreamRepository::class.java.getDeclaredField("perCategoryVodCache")
        field.isAccessible = true
        @Suppress("UNCHECKED_CAST")
        val cache = field.get(repository) as MutableMap<String, List<XtreamVodInfo>>
        cache.clear()
        entries.forEach { (categoryId, streams) ->
            cache[categoryId] = streams
        }
    }
}
