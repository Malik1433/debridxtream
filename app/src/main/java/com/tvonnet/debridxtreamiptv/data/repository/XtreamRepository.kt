package com.tvonnet.debridxtreamiptv.data.repository

import com.tvonnet.debridxtreamiptv.data.local.entity.EpisodeEntity
import com.tvonnet.debridxtreamiptv.data.local.entity.SeasonEntity

import android.content.Context
import android.util.Log
import com.tvonnet.debridxtreamiptv.data.Result
import com.tvonnet.debridxtreamiptv.data.cache.CacheHelper
import com.tvonnet.debridxtreamiptv.data.cache.CacheManager
import com.tvonnet.debridxtreamiptv.data.cache.FavoritesCache
import com.tvonnet.debridxtreamiptv.data.epg.EpgParser
import com.tvonnet.debridxtreamiptv.data.local.dao.EpgDao
import com.tvonnet.debridxtreamiptv.data.local.dao.FavoriteDao
import com.tvonnet.debridxtreamiptv.data.local.dao.SearchHistoryDao
import com.tvonnet.debridxtreamiptv.data.local.entity.EpgEntity
import com.tvonnet.debridxtreamiptv.data.local.entity.FavoriteEntity
import com.tvonnet.debridxtreamiptv.data.local.entity.SearchHistoryEntity
import com.tvonnet.debridxtreamiptv.data.local.dao.VodDao
import com.tvonnet.debridxtreamiptv.data.local.dao.SeriesDao
import com.tvonnet.debridxtreamiptv.data.local.entity.toVodEntity
import com.tvonnet.debridxtreamiptv.data.local.entity.toSeriesEntity
import com.tvonnet.debridxtreamiptv.data.model.*
import com.tvonnet.debridxtreamiptv.data.model.SeriesCategoryState
import com.tvonnet.debridxtreamiptv.data.model.SeriesCategoryStatus
import com.tvonnet.debridxtreamiptv.data.remote.XtreamApiService
import com.tvonnet.debridxtreamiptv.data.remote.XtreamRetrofitClient
import com.tvonnet.debridxtreamiptv.utils.PerformanceMonitor
import com.tvonnet.debridxtreamiptv.utils.memory.MemoryManager
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.isActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import retrofit2.HttpException
import java.util.LinkedHashMap
import java.util.LinkedHashSet
import java.util.Locale
import android.util.Base64
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.abs
import kotlin.coroutines.coroutineContext
import com.tvonnet.debridxtreamiptv.data.local.entity.toSeasonEntity
import com.tvonnet.debridxtreamiptv.data.local.entity.toEpisodeEntity
import com.tvonnet.debridxtreamiptv.data.local.entity.toXtreamSeriesInfo
import com.tvonnet.debridxtreamiptv.data.local.entity.toXtreamVodInfo
import com.tvonnet.debridxtreamiptv.data.local.relation.SeriesWithSeasonsAndEpisodes
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import com.google.gson.JsonSyntaxException
import com.google.gson.JsonElement
import kotlinx.coroutines.flow.flowOf

enum class DebridCacheStatus {
    VERIFIED_CACHED,
    NOT_CACHED,
    UNKNOWN,
    DIRECT_STREAM
}

data class AddableTorrentIdentity(
    val infoHash: String,
    val magnet: String? = null
)

data class MovieSource(
    val stream: XtreamVodInfo,
    val category: XtreamCategory?,
    val label: String,
    val isPrimary: Boolean,
    val languages: List<String>? = null,
    val quality: String? = null,
    val codec: String? = null,
    val sizeBytes: Long? = null,
    val seeders: Int? = null,
    val isCached: Boolean? = null,
    val cacheStatus: DebridCacheStatus = DebridCacheStatus.UNKNOWN,
    val provider: String? = null,
    val sourceType: String? = null,
    val sourceName: String? = null,
    val bingeGroup: String? = null,
    val fileIdx: Int? = null,
    val headers: Map<String, String>? = null,
    val addableTorrentIdentity: AddableTorrentIdentity? = null
)

/**
 * Week 7: Integrated with CacheManager for multi-level caching
 * Week 10: Added Favorites support
 * Week 11: Added EPG (Electronic Program Guide) support
 * Week 14: PerformanceMonitor integration for metrics
 * 
 * Cache Strategy:
 * 1. Check CacheManager (Memory → Room → Network)
 * 2. If cache miss, fetch from network
 * 3. Update CacheManager with new data
 */
@Singleton
class XtreamRepository @Inject constructor(
    private val context: Context,
    private val cacheManager: CacheManager,
    private val favoriteDao: FavoriteDao,
    private val searchHistoryDao: SearchHistoryDao,
    private val epgDao: EpgDao,
    private val vodDao: VodDao,
    private val seriesDao: SeriesDao,
    private val favoritesCache: FavoritesCache,
    private val memoryManager: MemoryManager
) {
    private var apiService: XtreamApiService? = null
    
    // V2 Rescue Patch: Expose service for V2 components
    fun getApiService(): XtreamApiService? = apiService
    fun getUsername(): String = username
    fun getPassword(): String = password
    fun getServerUrl(): String = baseUrl

    private val cacheHelper = CacheHelper(context)
    private var username: String = "username"
    private var password: String = "password"
    private var baseUrl: String = ""

    private val epgPrefs by lazy {
        context.getSharedPreferences("epg_prefs", Context.MODE_PRIVATE)
    }

    private val syncPrefs by lazy {
        context.getSharedPreferences(SYNC_PREFS_NAME, Context.MODE_PRIVATE)
    }

    // Legacy memory cache to avoid repeated Gson parsing (for full IptvCache object)
    private var memoryCache: IptvCache? = null
    private val seriesDetailCache = mutableMapOf<String, XtreamSeriesDetailResponse>()

    // Per-category series cache for detail screen fallback
    private val perCategorySeriesCache = mutableMapOf<String, List<XtreamSeriesInfo>>()
    
    // Cache for ALL series (fallback strategy)
    private var allSeriesCacheFallback: List<XtreamSeriesInfo>? = null
    // Per-category VOD cache (lazy loaded)
    private val perCategoryVodCache = mutableMapOf<String, List<XtreamVodInfo>>()
    private val vodFetchLocks = ConcurrentHashMap<String, Mutex>()
    private val vodFetchSemaphore = Semaphore(MAX_CONCURRENT_VOD_FETCHES)

    private val syncMutex = Mutex()
    private val epgSyncMutex = Mutex()
    private val _syncProgress = MutableStateFlow(SyncProgress.idle())
    val syncProgress: StateFlow<SyncProgress> = _syncProgress.asStateFlow()

    private val seriesCategoryStatus = ConcurrentHashMap<String, SeriesCategoryStatus>()
    
    fun initialize(baseUrl: String, username: String, password: String) {
        try {
            this.username = username
            this.password = password
            this.baseUrl = baseUrl.trimEnd('/') + "/"
            com.tvonnet.debridxtreamiptv.util.GlobalConfig.baseUrl = this.baseUrl
            val normalizedUrl = this.baseUrl
            // Week 8: Pass context for HTTP caching support
            apiService = XtreamRetrofitClient.create(normalizedUrl, context)

            // Initialize EpgParser with MemoryManager
            EpgParser.initialize(context)

            // App start with existing credentials: make sure the search index
            // (full VOD/series/live catalog in Room) exists and is fresh —
            // initial/background content syncs do not run on a normal relaunch,
            // so this is the trigger that covers everyday app opens.
            scheduleSearchIndexSyncIfStale()

            // Start memory monitoring
            memoryManager.startMonitoring()

            Log.d(TAG, "Repository initialized with memory management support")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to initialize API service", e)
            apiService = null
        }
    }
    
    fun ensureInitialized(serverUrl: String?, username: String?, password: String?) {
        if (!isInitialized() && !serverUrl.isNullOrBlank() && !username.isNullOrBlank() && !password.isNullOrBlank()) {
            initialize(serverUrl, username, password)
        }
    }
    
    fun isInitialized(): Boolean = apiService != null
    
    suspend fun login(username: String, password: String): Result<XtreamLoginResponse> {
        return try {
            if (apiService == null) {
                return Result.Error(Exception("API service not initialized"))
            }
            val response = apiService!!.login(username, password)
            val body = response.body()
            if (response.isSuccessful && body != null) {
                val userInfo = body.user_info
                val isAuthenticated = userInfo?.auth == 1 ||
                    userInfo?.status.equals("Active", ignoreCase = true)
                if (userInfo != null && isAuthenticated) {
                    Result.Success(body)
                } else {
                    val message = userInfo?.message?.takeIf { it.isNotBlank() }
                        ?: "Account is not active or authorized"
                    Result.Error(Exception(message))
                }
            } else {
                Result.Error(Exception("Login failed: ${response.code()}"))
            }
        } catch (e: Exception) {
            Log.e(TAG, "Login error", e)
            Result.Error(e)
        }
    }
    
    suspend fun fetchAllAndCache(): Result<IptvCache> {
        return syncContent(syncType = SyncType.BACKGROUND, includeLatest = false)
    }

    suspend fun syncInitialData(): Result<IptvCache> {
        return syncContent(syncType = SyncType.INITIAL, includeLatest = true)
    }

    suspend fun refreshCategoriesAndLatest(): Result<IptvCache> {
        if (_syncProgress.value.state == SyncState.RUNNING) {
            return Result.Error(Exception("Sync already running"))
        }
        return syncContent(syncType = SyncType.BACKGROUND, includeLatest = true)
    }

    fun shouldRunBackgroundSync(): Boolean {
        val lastType = syncPrefs.getString(KEY_LAST_SYNC_TYPE, null)
        val lastTime = syncPrefs.getLong(KEY_LAST_SYNC_TIME, 0L)
        if (lastType == SyncType.INITIAL.name && lastTime > 0L) {
            val elapsed = System.currentTimeMillis() - lastTime
            if (elapsed < BACKGROUND_SYNC_SKIP_AFTER_INITIAL_MS) {
                return false
            }
        }
        return true
    }

    private suspend fun syncContent(
        syncType: SyncType,
        includeLatest: Boolean
    ): Result<IptvCache> {
        return syncMutex.withLock {
            updateSyncProgress(
                type = syncType,
                state = SyncState.RUNNING,
                percent = 0,
                liveCount = 0,
                vodCount = 0,
                seriesCount = 0,
                stage = "Preparing data"
            )

            try {
                withContext(Dispatchers.IO) {
                    if (apiService == null) {
                        val cached = cacheHelper.readCache()
                        if (cached != null) {
                            updateSyncProgress(
                                type = syncType,
                                state = SyncState.SUCCESS,
                                percent = 100,
                                liveCount = cached.live?.streams?.size ?: 0,
                                vodCount = cached.vod?.streams?.size ?: 0,
                                seriesCount = cached.series?.streams?.size ?: 0,
                                stage = "Using cached data"
                            )
                            return@withContext Result.Success(cached)
                        }
                        updateSyncProgress(
                            type = syncType,
                            state = SyncState.ERROR,
                            percent = 0,
                            liveCount = 0,
                            vodCount = 0,
                            seriesCount = 0,
                            stage = "Sync failed",
                            errorMessage = "API service not initialized"
                        )
                        return@withContext Result.Error(Exception("API service not initialized and no cache available"))
                    }

                    Log.d(TAG, "Sync started (type=$syncType, includeLatest=$includeLatest)")

                    val liveResult = fetchLiveCategoriesAndStreams()
                    val liveData = liveResult.getOrNull() ?: LiveCacheData(emptyList(), emptyList())
                    val liveCount = liveData.streams.size
                    updateSyncProgress(
                        type = syncType,
                        state = SyncState.RUNNING,
                        percent = 25,
                        liveCount = liveCount,
                        vodCount = 0,
                        seriesCount = 0,
                        stage = "Downloading live channels"
                    )

                    val vodResult = fetchVodCategoriesAndStreams()
                    val vodData = vodResult.getOrNull() ?: VodCacheData(emptyList(), emptyList())
                    val vodStreams = if (includeLatest) {
                        fetchLatestVodStreams(vodData.categories)
                    } else {
                        vodData.streams
                    }
                    val vodFinal = vodData.copy(streams = vodStreams)
                    updateSyncProgress(
                        type = syncType,
                        state = SyncState.RUNNING,
                        percent = 50,
                        liveCount = liveCount,
                        vodCount = vodStreams.size,
                        seriesCount = 0,
                        stage = "Downloading movies"
                    )

                    val seriesResult = fetchSeriesCategoriesAndStreams()
                    val seriesData = seriesResult.getOrNull() ?: SeriesCacheData(emptyList(), emptyList())
                    val seriesStreams = if (includeLatest) {
                        fetchLatestSeriesStreams(seriesData.categories)
                    } else {
                        seriesData.streams
                    }
                    val seriesFinal = seriesData.copy(streams = seriesStreams)
                    updateSyncProgress(
                        type = syncType,
                        state = SyncState.RUNNING,
                        percent = 75,
                        liveCount = liveCount,
                        vodCount = vodStreams.size,
                        seriesCount = seriesStreams.size,
                        stage = "Downloading series"
                    )

                    val cache = IptvCache(
                        timestamp = System.currentTimeMillis(),
                        live = liveData,
                        vod = vodFinal,
                        series = seriesFinal,
                        epg = null
                    )

                    updateSyncProgress(
                        type = syncType,
                        state = SyncState.RUNNING,
                        percent = 90,
                        liveCount = liveCount,
                        vodCount = vodStreams.size,
                        seriesCount = seriesStreams.size,
                        stage = "Finalizing cache"
                    )

                    cacheHelper.writeCache(cache)
                    memoryCache = cache

                    if (cacheManager != null) {
                        liveData.let { live ->
                            cacheManager.putCategories(live.categories, "live")
                            if (live.streams.isNotEmpty()) {
                                val firstCategoryId = live.categories.firstOrNull()?.category_id ?: ""
                                if (firstCategoryId.isNotEmpty()) {
                                    cacheManager.putChannels(firstCategoryId, live.streams, "live")
                                }
                            }
                        }
                        cacheManager.putCategories(vodFinal.categories, "vod")
                        cacheManager.putCategories(seriesFinal.categories, "series")
                    }

                    PerformanceMonitor.trackMemory("afterFetchAllAndCache")
                    saveSyncMetadata(syncType)
                    // Fill the search index (full catalog → Room) in the
                    // background so Search covers categories the user never
                    // opened. Fire-and-forget; idempotent via REPLACE upserts.
                    scheduleSearchIndexSyncIfStale()
                    updateSyncProgress(
                        type = syncType,
                        state = SyncState.SUCCESS,
                        percent = 100,
                        liveCount = liveCount,
                        vodCount = vodStreams.size,
                        seriesCount = seriesStreams.size,
                        stage = "Complete"
                    )

                    Result.Success(cache)
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to fetch data", e)
                val cached = cacheHelper.readCache()
                if (cached != null) {
                    updateSyncProgress(
                        type = syncType,
                        state = SyncState.SUCCESS,
                        percent = 100,
                        liveCount = cached.live?.streams?.size ?: 0,
                        vodCount = cached.vod?.streams?.size ?: 0,
                        seriesCount = cached.series?.streams?.size ?: 0,
                        stage = "Using cached data"
                    )
                    Result.Success(cached)
                } else {
                    updateSyncProgress(
                        type = syncType,
                        state = SyncState.ERROR,
                        percent = 0,
                        liveCount = 0,
                        vodCount = 0,
                        seriesCount = 0,
                        stage = "Sync failed",
                        errorMessage = e.message
                    )
                    Result.Error(e)
                }
            }
        }
    }

    private fun updateSyncProgress(
        type: SyncType,
        state: SyncState,
        percent: Int,
        liveCount: Int,
        vodCount: Int,
        seriesCount: Int,
        stage: String,
        errorMessage: String? = null
    ) {
        _syncProgress.value = SyncProgress(
            type = type,
            state = state,
            percent = percent.coerceIn(0, 100),
            liveCount = liveCount,
            vodCount = vodCount,
            seriesCount = seriesCount,
            stage = stage,
            errorMessage = errorMessage
        )
    }

    private fun saveSyncMetadata(syncType: SyncType) {
        syncPrefs.edit()
            .putLong(KEY_LAST_SYNC_TIME, System.currentTimeMillis())
            .putString(KEY_LAST_SYNC_TYPE, syncType.name)
            .apply()
    }
    
    private suspend fun fetchLiveCategoriesAndStreams(): Result<LiveCacheData> {
        return try {
            val categoriesResponse = apiService?.getLiveCategories(username, password)
            val categories = if (categoriesResponse?.isSuccessful == true) {
                categoriesResponse.body() ?: emptyList()
            } else {
                emptyList()
            }
            
            Log.d(TAG, "Live categories fetched: ${categories.size}")
            
            // OPTIMIZATION: Don't fetch ALL live streams (18k+) at login
            // Instead: Fetch only first category for instant display
            // User can switch categories to load more (lazy loading)
            val firstCategory = categories.firstOrNull()?.category_id
            val streams = if (firstCategory != null) {
                Log.d(TAG, "Fetching streams for first category: $firstCategory")
                val streamsResponse = apiService?.getLiveStreams(username, password, categoryId = firstCategory)
                if (streamsResponse?.isSuccessful == true) {
                    Log.d(TAG, "First category streams fetched: ${streamsResponse.body()?.size ?: 0}")
                    streamsResponse.body() ?: emptyList()
                } else {
                    emptyList()
                }
            } else {
                emptyList()
            }
            
            Result.Success(LiveCacheData(categories, streams))
        } catch (e: Exception) {
            Log.e(TAG, "Failed to fetch live data", e)
            Result.Error(e)
        }
    }

    suspend fun ensureLiveCategories(): List<XtreamCategory> {
        Log.d(TAG, "ensureLiveCategories called")
        cacheManager?.getCategories("live")?.let { cached ->
            if (cached.isNotEmpty()) {
                Log.d(TAG, "Returning cached categories from CacheManager: ${cached.size}")
                return cached
            }
        }

        val cacheLiveCategories = readCache()?.live?.categories.orEmpty()
        if (cacheLiveCategories.isNotEmpty()) {
            try {
                cacheManager?.putCategories(cacheLiveCategories, "live")
            } catch (e: Exception) {
                Log.w(TAG, "Failed to store cached Live categories into CacheManager", e)
            }
            return cacheLiveCategories
        }

        val service = apiService ?: return emptyList()
        return try {
            val response = service.getLiveCategories(username, password)
            if (response.isSuccessful) {
                val categories = response.body().orEmpty()
                if (categories.isNotEmpty()) {
                    try {
                        cacheManager?.putCategories(categories, "live")
                    } catch (e: Exception) {
                        Log.w(TAG, "Failed to persist Live categories into CacheManager", e)
                    }
                    // Update memory cache
                    val existingCache = readCache()
                    val existingStreams = existingCache?.live?.streams ?: emptyList()
                    val updatedLiveData = LiveCacheData(categories, existingStreams)
                    val updatedCache = if (existingCache != null) {
                        existingCache.copy(timestamp = System.currentTimeMillis(), live = updatedLiveData)
                    } else {
                        IptvCache(System.currentTimeMillis(), updatedLiveData, null, null, null)
                    }
                    memoryCache = updatedCache
                    cacheHelper.writeCache(updatedCache)
                }
                categories
            } else {
                Log.e(TAG, "Failed to fetch Live categories: ${response.code()}")
                emptyList()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error fetching Live categories", e)
            emptyList()
        }
    }
    
    /**
     * Week 7: Fetch live streams for specific category with multi-level caching
     * 
     * Strategy:
     * 1. Check CacheManager (Memory → Room) if available
     * 2. If cache hit, return cached data
     * 3. If cache miss, fetch from network
     * 4. Update CacheManager with new data if available
     */
    suspend fun fetchLiveStreamsForCategory(categoryId: String): Result<List<XtreamStream>> {
        return try {
            // Level 1 & 2: Try cache first (Memory → Room) if CacheManager available
            if (cacheManager != null) {
                val cachedStreams = cacheManager.getChannels(categoryId, "live")
                if (cachedStreams != null) {
                    Log.d(TAG, "✅ Cache HIT: Live streams for category $categoryId (${cachedStreams.size} channels)")
                    return Result.Success(cachedStreams)
                }
            }
            
            // Level 3: Cache miss - fetch from network
            if (apiService == null) {
                return Result.Error(Exception("API service not initialized"))
            }
            
            Log.d(TAG, "⬇️ Network fetch: Live streams for category $categoryId")
            val response = apiService!!.getLiveStreams(username, password, categoryId = categoryId)
            
            if (response.isSuccessful && response.body() != null) {
                val streams = response.body()!!
                Log.d(TAG, "✅ Network fetch successful: ${streams.size} channels")
                
                // Update cache for future access (if CacheManager available)
                cacheManager?.putChannels(categoryId, streams, "live")
                
                Result.Success(streams)
            } else {
                Log.e(TAG, "❌ Failed to fetch live streams: ${response.code()}")
                Result.Success(emptyList())
            }
        } catch (e: Exception) {
            Log.e(TAG, "❌ Error fetching live streams for category $categoryId", e)
            Result.Success(emptyList())
        }
    }

    /**
     * Live TV strict fetch: never converts failures into "empty list".
     * Used by Live paging so UI can show errors + allow retry.
     */
    suspend fun fetchLiveStreamsForCategoryStrict(categoryId: String): Result<List<XtreamStream>> {
        return try {
            // Cache first
            cacheManager?.getChannels(categoryId, "live")?.let { cached ->
                return Result.Success(cached)
            }

            val service = apiService ?: return Result.Error(Exception("API service not initialized"))
            val response = service.getLiveStreams(username, password, categoryId = categoryId)

            if (response.isSuccessful) {
                val streams = response.body().orEmpty()
                cacheManager?.putChannels(categoryId, streams, "live")
                Result.Success(streams)
            } else {
                Result.Error(HttpException(response))
            }
        } catch (e: Exception) {
            Result.Error(e)
        }
    }

    suspend fun getCachedLiveChannelCount(categoryId: String): Int? {
        return cacheManager?.getChannelCount(categoryId, "live")
    }

    suspend fun getCachedLiveStreams(categoryId: String): List<XtreamStream>? {
        return cacheManager?.getChannels(categoryId, "live")
    }
    
    suspend fun enrichChannelsWithCurrentEpg(channels: List<XtreamStream>): List<XtreamStream> {
        if (epgDao == null || channels.isEmpty()) return channels
        
        try {
            val channelIds = channels.mapNotNull { it.epg_channel_id }.distinct()
            if (channelIds.isEmpty()) return channels
            
            val matches = epgDao.getCurrentProgramsForChannels(channelIds, System.currentTimeMillis())
            if (matches.isEmpty()) return channels
            
            val map = matches.associateBy { it.channelId }
            
            return channels.map { stream ->
                // Create copy if needed, but since we modified data class to have var, we can set it.
                // However, caching framework might return immutable list or similar.
                // Best to modify the item if mutable or copy.
                // Since I added 'var', I can try setting it.
                val epgId = stream.epg_channel_id
                if (epgId != null) {
                    map[epgId]?.let { prog ->
                        stream.currentProgramTitle = prog.title
                    }
                }
                stream
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to enrich channels with EPG", e)
            return channels
        }
    }
    
    /**
     * Batch current-program lookup for the Live Player surf list / mini-EPG.
     * Returns a map keyed by EPG channel id → the program on air right now.
     */
    suspend fun getCurrentProgramsByEpgId(epgChannelIds: List<String>): Map<String, EpgEntity> {
        val dao = epgDao ?: return emptyMap()
        val ids = epgChannelIds.filter { it.isNotBlank() }.distinct()
        if (ids.isEmpty()) return emptyMap()
        return try {
            dao.getCurrentProgramsForChannels(ids, System.currentTimeMillis())
                .associateBy { it.channelId }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to batch-load current EPG", e)
            emptyMap()
        }
    }

    /**
     * Windowed EPG for the in-player TV Guide grid: programmes for the given EPG
     * channel ids overlapping [startTime, endTime), grouped by channelId.
     */
    suspend fun getProgramsByEpgIdInRange(
        epgChannelIds: List<String>,
        startTime: Long,
        endTime: Long
    ): Map<String, List<EpgEntity>> {
        val dao = epgDao ?: return emptyMap()
        val ids = epgChannelIds.filter { it.isNotBlank() }.distinct()
        if (ids.isEmpty()) return emptyMap()
        return try {
            dao.getProgramsForChannelsInRange(ids, startTime, endTime)
                .groupBy { it.channelId }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to batch-load windowed EPG", e)
            emptyMap()
        }
    }

    private suspend fun fetchVodCategoriesAndStreams(): Result<VodCacheData> {
        return try {
            val categoriesResponse = apiService?.getVodCategories(username, password)
            val categories = if (categoriesResponse?.isSuccessful == true) {
                categoriesResponse.body() ?: emptyList()
            } else {
                emptyList()
            }
            
            Log.d(TAG, "VOD categories fetched: ${categories.size}")
            
            // Don't fetch ALL streams at login - too slow and memory intensive
            // Instead, fetch streams per category when needed (lazy loading)
            // For now, return empty streams list
            Result.Success(VodCacheData(categories, emptyList()))
        } catch (e: Exception) {
            Log.e(TAG, "Failed to fetch VOD data", e)
            Result.Success(VodCacheData(emptyList(), emptyList())) // Return empty, don't fail
        }
    }

    private suspend fun fetchLatestVodStreams(categories: List<XtreamCategory>): List<XtreamVodInfo> {
        val firstCategoryId = categories.firstOrNull()?.category_id ?: return emptyList()
        val result = fetchVodStreamsForCategory(firstCategoryId)
        return when (result) {
            is Result.Success -> {
                result.data
                    .sortedByDescending { it.added?.toLongOrNull() ?: 0L }
                    .take(LATEST_VOD_LIMIT)
            }
            else -> emptyList()
        }
    }
    
    // New method: Fetch VOD streams for specific category (lazy loading)
    suspend fun fetchVodStreamsForCategory(categoryId: String): Result<List<XtreamVodInfo>> {
        coroutineContext.ensureActive()
        perCategoryVodCache[categoryId]?.let { cached ->
            return Result.Success(cached)
        }

        val lock = vodFetchLocks.getOrPut(categoryId) { Mutex() }
        return lock.withLock {
            perCategoryVodCache[categoryId]?.let { cached ->
                return Result.Success(cached)
            }

            val service = apiService ?: return Result.Error(Exception("API service not initialized"))

            return try {
                coroutineContext.ensureActive()
                vodFetchSemaphore.withPermit {
                    Log.d(TAG, "Fetching VOD streams for category: $categoryId")
                    val response = service.getVodStreams(username, password, categoryId = categoryId)
                    if (response.isSuccessful) {
                        val streams = response.body().orEmpty()
                        perCategoryVodCache[categoryId] = streams
                        
                        // Save to DB
                        try {
                            vodDao?.let { dao ->
                                val entities = streams.map { it.toVodEntity(categoryId) }
                                dao.replaceMoviesForCategory(categoryId, entities)
                                Log.d(TAG, "Saved ${entities.size} movies to DB for category $categoryId (Atomic Replace)")
                            }
                        } catch (e: Exception) {
                            Log.e(TAG, "Failed to save movies to DB", e)
                        }

                        Log.d(TAG, "VOD streams fetched for category $categoryId: ${streams.size} movies")
                        Result.Success(streams)
                    } else {
                        Log.e(TAG, "Failed to fetch VOD streams: ${response.code()}")
                        Result.Error(HttpException(response))
                    }
                }
            } catch (e: CancellationException) {
                Log.d(TAG, "VOD fetch for category $categoryId cancelled")
                throw e
            } catch (e: Exception) {
                Log.e(TAG, "Error fetching VOD streams for category $categoryId", e)
                Result.Error(e)
            }
        }
    }
    
    suspend fun ensureVodCategories(): List<XtreamCategory> {
        cacheManager?.getCategories("vod")?.let { cached ->
            if (cached.isNotEmpty()) {
                return cached
            }
        }

        val cacheVodCategories = readCache()?.vod?.categories.orEmpty()
        if (cacheVodCategories.isNotEmpty()) {
            try {
                cacheManager?.putCategories(cacheVodCategories, "vod")
            } catch (e: Exception) {
                Log.w(TAG, "Failed to store cached VOD categories into CacheManager", e)
            }
            return cacheVodCategories
        }

        val service = apiService ?: return emptyList()
        return try {
            val response = service.getVodCategories(username, password)
            if (response.isSuccessful) {
                val categories = response.body().orEmpty()
                if (categories.isNotEmpty()) {
                    try {
                        cacheManager?.putCategories(categories, "vod")
                    } catch (e: Exception) {
                        Log.w(TAG, "Failed to persist VOD categories into CacheManager", e)
                    }
                    updateVodCategoriesCache(categories)
                }
                categories
            } else {
                Log.e(TAG, "Failed to fetch VOD categories: ${response.code()}")
                emptyList()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error fetching VOD categories", e)
            emptyList()
        }
    }

    private fun updateVodCategoriesCache(categories: List<XtreamCategory>) {
        try {
            val existingCache = readCache()
            val existingVodStreams = existingCache?.vod?.streams ?: emptyList()
            val updatedVodData = VodCacheData(
                categories = categories,
                streams = existingVodStreams
            )
            val updatedCache = if (existingCache != null) {
                existingCache.copy(
                    timestamp = System.currentTimeMillis(),
                    vod = updatedVodData
                )
            } else {
                IptvCache(
                    timestamp = System.currentTimeMillis(),
                    live = null,
                    vod = updatedVodData,
                    series = null,
                    epg = null
                )
            }
            memoryCache = updatedCache
            cacheHelper.writeCache(updatedCache)
        } catch (e: Exception) {
            Log.w(TAG, "Failed to persist VOD categories to cache", e)
        }
    }

    suspend fun ensureSeriesCategories(): List<XtreamCategory> {
        cacheManager?.getCategories("series")?.let { cached ->
            if (cached.isNotEmpty()) {
                return cached
            }
        }

        val cacheSeriesCategories = readCache()?.series?.categories.orEmpty()
        if (cacheSeriesCategories.isNotEmpty()) {
            try {
                cacheManager?.putCategories(cacheSeriesCategories, "series")
            } catch (e: Exception) {
                Log.w(TAG, "Failed to store cached Series categories into CacheManager", e)
            }
            return cacheSeriesCategories
        }

        val service = apiService ?: return emptyList()
        return try {
            val response = service.getSeriesCategories(username, password)
            if (response.isSuccessful) {
                val categories = response.body().orEmpty()
                if (categories.isNotEmpty()) {
                    try {
                        cacheManager?.putCategories(categories, "series")
                    } catch (e: Exception) {
                        Log.w(TAG, "Failed to persist Series categories into CacheManager", e)
                    }
                    updateSeriesCategoriesCache(categories)
                }
                categories
            } else {
                Log.e(TAG, "Failed to fetch Series categories: ${response.code()}")
                emptyList()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error fetching Series categories", e)
            emptyList()
        }
    }

    private fun updateSeriesCategoriesCache(categories: List<XtreamCategory>) {
        try {
            val existingCache = readCache()
            val existingSeriesStreams = existingCache?.series?.streams ?: emptyList()
            val updatedSeriesData = SeriesCacheData(
                categories = categories,
                streams = existingSeriesStreams
            )
            val updatedCache = if (existingCache != null) {
                existingCache.copy(
                    timestamp = System.currentTimeMillis(),
                    series = updatedSeriesData
                )
            } else {
                IptvCache(
                    timestamp = System.currentTimeMillis(),
                    live = null,
                    vod = null,
                    series = updatedSeriesData,
                    epg = null
                )
            }
            memoryCache = updatedCache
            cacheHelper.writeCache(updatedCache)
        } catch (e: Exception) {
            Log.w(TAG, "Failed to persist Series categories to cache", e)
        }
    }

    private suspend fun getVodStreamsForCategoryCached(categoryId: String): List<XtreamVodInfo> {
        perCategoryVodCache[categoryId]?.let { return it }
        return when (val result = fetchVodStreamsForCategory(categoryId)) {
            is Result.Success -> result.data.also { perCategoryVodCache[categoryId] = it }
            is Result.Error -> emptyList()
            is Result.Loading -> emptyList()
        }
    }

    private suspend fun findVodById(
        streamId: String,
        hintCategoryId: String?,
        categories: List<XtreamCategory>
    ): XtreamVodInfo? {
        coroutineContext.ensureActive()
        perCategoryVodCache.values.forEach { list ->
            list.find { it.stream_id == streamId }?.let { return it }
        }

        memoryCache?.vod?.streams?.find { it.stream_id == streamId }?.let { return it }

        if (!hintCategoryId.isNullOrBlank()) {
            getVodStreamsForCategoryCached(hintCategoryId).find { it.stream_id == streamId }?.let {
                return it
            }
        }

        for (category in categories) {
            coroutineContext.ensureActive()
            val categoryId = category.category_id ?: continue
            if (categoryId == hintCategoryId) continue
            val streams = getVodStreamsForCategoryCached(categoryId)
            streams.find { it.stream_id == streamId }?.let { return it }
        }
        return null
    }

    suspend fun getMovieSources(
        streamId: String?,
        title: String?,
        primaryCategoryId: String?,
        yearHint: String?
    ): List<MovieSource> {
        val categories = ensureVodCategories()
        if (categories.isEmpty()) return emptyList()

        val categoriesById = categories.associateBy { it.category_id.orEmpty() }
        val candidateOrder = LinkedHashSet<String>()
        primaryCategoryId?.takeIf { it.isNotBlank() }?.let { candidateOrder.add(it) }

        val primaryStream = streamId?.takeIf { it.isNotBlank() }?.let {
            findVodById(it, primaryCategoryId, categories)
        }

        primaryStream?.category_id?.takeIf { !it.isNullOrBlank() }?.let { candidateOrder.add(it) }
        primaryStream?.category_ids?.orEmpty()?.forEach { candidateOrder.add(it.toString()) }

        val normalizedTargetName = normalizeTitle(primaryStream?.name ?: title)
        val targetYear = extractYear(primaryStream?.releaseDate) ?: extractYear(yearHint)

        if (candidateOrder.isEmpty()) {
            categories.mapNotNull { it.category_id }.forEach { candidateOrder.add(it) }
        } else {
            categories.mapNotNull { it.category_id }
                .filterNot { candidateOrder.contains(it) }
                .forEach { candidateOrder.add(it) }
        }

        val sources = LinkedHashMap<String, MovieSource>()
        val comparator = compareBy<MovieSource> { !it.isPrimary }
            .thenBy { it.label.lowercase(Locale.US) }

        var lookedUpCategories = 0
        for (categoryId in candidateOrder) {
            coroutineContext.ensureActive()
            if (lookedUpCategories >= MAX_MOVIE_SOURCE_LOOKUPS && sources.isNotEmpty()) {
                break
            }
            lookedUpCategories++
            if (categoryId.isBlank()) continue
            val streams = getVodStreamsForCategoryCached(categoryId)
            if (streams.isEmpty()) continue
            val category = categoriesById[categoryId]
            for (stream in streams) {
                val candidateId = stream.stream_id ?: continue
                if (sources.containsKey(candidateId)) continue

                val matchesId = streamId != null && streamId == candidateId
                val candidateNormalized = normalizeTitle(stream.name)
                val matchesName = normalizedTargetName != null && normalizedTargetName == candidateNormalized

                if (!matchesId && !matchesName) continue

                if (!matchesId) {
                    val candidateYear = extractYear(stream.releaseDate)
                    if (targetYear != null && candidateYear != null && abs(candidateYear - targetYear) > 1) {
                        continue
                    }
                }

                val label = buildMovieSourceLabel(category, stream)
                sources[candidateId] = MovieSource(
                    stream = stream,
                    category = category,
                    label = label,
                    isPrimary = matchesId
                )
            }
        }

        if (sources.isEmpty() && primaryStream != null) {
            val category = primaryStream.category_id?.let { categoriesById[it] }
            val label = buildMovieSourceLabel(category, primaryStream)
            val key = primaryStream.stream_id ?: primaryStream.name ?: "primary"
            sources[key] = MovieSource(
                stream = primaryStream,
                category = category,
                label = label,
                isPrimary = true
            )
        }

        return sources.values.sortedWith(comparator)
    }

    private fun normalizeTitle(value: String?): String? {
        if (value.isNullOrBlank()) return null
        return NON_ALPHANUMERIC_REGEX.replace(value.lowercase(Locale.US), "").takeIf { it.isNotEmpty() }
    }

    private fun extractYear(text: String?): Int? {
        if (text.isNullOrBlank()) return null
        val match = YEAR_REGEX.find(text)
        return match?.value?.toIntOrNull()
    }

    private fun buildMovieSourceLabel(category: XtreamCategory?, stream: XtreamVodInfo): String {
        val parts = mutableListOf<String>()
        category?.category_name?.takeIf { !it.isNullOrBlank() }?.let { parts.add(it.trim()) }
        stream.container_extension?.takeIf { !it.isNullOrBlank() }?.let {
            parts.add(it.uppercase(Locale.US))
        }
        extractYear(stream.releaseDate)?.let { parts.add(it.toString()) }
        return parts.joinToString(" • ").ifBlank { stream.name ?: "Source" }
    }

    private suspend fun fetchSeriesCategoriesAndStreams(): Result<SeriesCacheData> {
        return try {
            val categoriesResponse = apiService?.getSeriesCategories(username, password)
            val categories = if (categoriesResponse?.isSuccessful == true) {
                categoriesResponse.body() ?: emptyList()
            } else {
                emptyList()
            }
            
            Log.d(TAG, "Series categories fetched: ${categories.size}")
            
            // Don't fetch ALL series at login - too slow and memory intensive
            // Instead, fetch series per category when needed (lazy loading)
            // For now, return empty streams list
            Result.Success(SeriesCacheData(categories, emptyList()))
        } catch (e: Exception) {
            Log.e(TAG, "Failed to fetch series data", e)
            Result.Success(SeriesCacheData(emptyList(), emptyList())) // Return empty, don't fail
        }
    }

    private suspend fun fetchLatestSeriesStreams(categories: List<XtreamCategory>): List<XtreamSeriesInfo> {
        val firstCategoryId = categories.firstOrNull()?.category_id ?: return emptyList()
        val result = fetchSeriesForCategory(firstCategoryId)
        return when (result) {
            is Result.Success -> result.data.take(LATEST_SERIES_LIMIT)
            else -> emptyList()
        }
    }
    


    // New method: Fetch series for specific category (lazy loading)
    suspend fun fetchSeriesForCategory(categoryId: String): Result<List<XtreamSeriesInfo>> {
        maybeReturnSeriesDuringCooldown(categoryId)?.let { cachedDuringCooldown ->
            Log.w(TAG, "Series category $categoryId is cooling down. Returning cached/empty result.")
            return Result.Success(cachedDuringCooldown)
        }

        return try {
            if (apiService == null) {
                return Result.Error(Exception("API service not initialized"))
            }
            
        Log.d(TAG, "Fetching series for category: $categoryId")
            
            // Level 2 Cache: Check if we have "All Series" cached and pre-load DB
            // This prevents the 10s delay if the category returns 404 but we already have the data
            try {
                if (allSeriesCacheFallback == null) {
                    allSeriesCacheFallback = cacheHelper.readAllSeries()
                }
                
                allSeriesCacheFallback?.let { allSeries ->
                    val filtered = allSeries.filter { 
                        val catIdStr = it.category_id?.trim()
                        val targetIdStr = categoryId.trim()
                        val stringMatch = catIdStr == targetIdStr
                        val listMatch = it.category_ids?.contains(targetIdStr.toIntOrNull()) == true
                        val intMatch = catIdStr?.toIntOrNull() == targetIdStr.toIntOrNull()
                        stringMatch || listMatch || (intMatch && targetIdStr.toIntOrNull() != null)
                    }
                    
                    if (filtered.isNotEmpty()) {
                        Log.d(TAG, "⚡ Level 2 Cache HIT: Pre-loading ${filtered.size} series for category $categoryId")
                        // Save to DB immediately so UI shows data while network call runs
                        seriesDao?.let { dao ->
                            val entities = filtered.map { it.toSeriesEntity(categoryId) }
                            dao.replaceSeriesForCategory(categoryId, entities)
                        }
                    }
                }
            } catch (e: Exception) {
                Log.w(TAG, "Failed to pre-load from Level 2 cache", e)
            }

            val response = apiService!!.getSeries(username, password, categoryId = categoryId)
            
            if (response.isSuccessful) {
                val streams = response.body() ?: emptyList()
                if (streams.isNotEmpty()) {
                    Log.d(TAG, "Series fetched for category $categoryId: ${streams.size} series")
                    updateSeriesCacheForCategory(categoryId, streams)
                    
                    // Phase 2: Save to Database
                    try {
                        seriesDao?.let { dao ->
                            val entities = streams.mapIndexed { index, item -> 
                                item.copy(num = index).toSeriesEntity(categoryId) 
                            }
                            // Use atomic transaction to replace series (Delete + Insert) prevents empty list flicker
                            dao.replaceSeriesForCategory(categoryId, entities)
                            Log.d(TAG, "Saved ${entities.size} series to DB for category $categoryId (Atomic Replace)")
                        }
                    } catch (e: Exception) {
                        Log.e(TAG, "Failed to save series to DB", e)
                    }
                    markSeriesCategoryHealthy(categoryId)
                    Result.Success(streams)
                } else {
                    handleSeriesFallback(
                        categoryId = categoryId,
                        warningMessage = "Series category $categoryId returned empty list. Attempting fallback fetch.",
                        failureState = SeriesCategoryState.EMPTY
                    )
                }
            } else if (response.code() == 404) {
                Log.w(TAG, "Series category $categoryId returned 404. Attempting to fetch ALL series and filter.")
                
                // Try fetching all series
                val allResult = fetchAllSeries()
                if (allResult is Result.Success) {
                    Log.d(TAG, "Filtering ${allResult.data.size} series for category $categoryId")
                    
                    // Debug: Print first 5 series to check structure
                    allResult.data.take(5).forEach { 
                        Log.d(TAG, "Sample Series: name=${it.name}, cat_id=${it.category_id}, cat_ids=${it.category_ids}") 
                    }

                    val filtered = allResult.data.filter { 
                        val catIdStr = it.category_id?.trim()
                        val targetIdStr = categoryId.trim()
                        
                        val stringMatch = catIdStr == targetIdStr
                        val listMatch = it.category_ids?.contains(targetIdStr.toIntOrNull()) == true
                        
                        // Also try loose integer matching
                        val intMatch = catIdStr?.toIntOrNull() == targetIdStr.toIntOrNull()
                        
                        stringMatch || listMatch || (intMatch && targetIdStr.toIntOrNull() != null)
                    }
                    
                    if (filtered.isNotEmpty()) {
                        Log.d(TAG, "Found ${filtered.size} series for category $categoryId in ALL list")
                        updateSeriesCacheForCategory(categoryId, filtered)
                        
                         // Save to DB
                        try {
                            seriesDao?.let { dao ->
                                val entities = filtered.mapIndexed { index, item -> 
                                    item.copy(num = index).toSeriesEntity(categoryId) 
                                }
                                dao.replaceSeriesForCategory(categoryId, entities)
                            }
                        } catch (e: Exception) {
                            Log.e(TAG, "Failed to save fallback series to DB", e)
                        }
                        
                        // Mark as healthy since we found data
                        markSeriesCategoryHealthy(categoryId)
                        return Result.Success(filtered)
                    }
                }
                
                handleSeriesFallback(
                    categoryId = categoryId,
                    warningMessage = "Series category $categoryId returned 404 and not found in ALL list. Attempting cache fallback.",
                    failureState = SeriesCategoryState.TEMPORARILY_UNAVAILABLE
                )
            } else {
                val error = HttpException(response)
                updateSeriesCategoryStatus(
                    categoryId,
                    SeriesCategoryState.TEMPORARILY_UNAVAILABLE,
                    "Failed to fetch series: ${response.code()}"
                )
                Log.e(TAG, "Failed to fetch series: ${response.code()}")
                Result.Error(error)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error fetching series for category $categoryId", e)
            updateSeriesCategoryStatus(
                categoryId,
                SeriesCategoryState.TEMPORARILY_UNAVAILABLE,
                e.message ?: "Error fetching series"
            )
            val fallbackResult = fallbackSeriesFetch(categoryId)
            return if (fallbackResult is Result.Success && fallbackResult.data.isNotEmpty()) {
                markSeriesCategoryHealthy(categoryId)
                fallbackResult
            } else {
                Result.Error(e)
            }
        }
    }

    fun getSeriesCategoryStatus(categoryId: String): SeriesCategoryStatus? {
        return seriesCategoryStatus[categoryId]
    }
    
    /**
     * Refresh series categories from the network without triggering a full cache refresh.
     * This avoids fetching heavy payloads (e.g., EPG) that can cause OOM on low-memory devices.
     */
    suspend fun refreshSeriesCategories(): Result<List<XtreamCategory>> {
        return try {
            if (apiService == null) {
                return Result.Error(Exception("API service not initialized"))
            }
            
            Log.d(TAG, "Refreshing series categories from network")
            val response = apiService!!.getSeriesCategories(username, password)
            
            if (response.isSuccessful) {
                val categories = response.body() ?: emptyList()
                Log.d(TAG, "Series categories refresh successful: ${categories.size} categories")
                persistSeriesCategories(categories)
                Result.Success(categories)
            } else if (response.code() == 404) {
                Log.w(TAG, "Series categories endpoint returned 404. Keeping existing cache.")
                Result.Error(HttpException(response))
            } else {
                Log.e(TAG, "Failed to refresh series categories: ${response.code()}")
                Result.Error(HttpException(response))
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error refreshing series categories", e)
            Result.Error(e)
        }
    }
    
    suspend fun fetchSeriesDetail(seriesId: String, forceRefresh: Boolean = false): Result<XtreamSeriesDetailResponse> {
        if (!forceRefresh) {
            // 1. Check Memory Cache
            seriesDetailCache[seriesId]?.let { cached ->
                if (!cached.episodes.isNullOrEmpty()) {
                    Log.d(TAG, "✅ Memory Cache HIT: Series detail $seriesId")
                    return Result.Success(cached)
                }
            }
            
            // 2. Check Disk Cache (File) - Week 14 Optimization
            try {
                val diskCached = cacheHelper.readSeriesDetail(seriesId)
                if (diskCached != null && !diskCached.episodes.isNullOrEmpty()) {
                    Log.d(TAG, "✅ Disk Cache HIT: Series detail $seriesId")
                    // Update memory cache
                    seriesDetailCache[seriesId] = diskCached
                    return Result.Success(diskCached)
                }
            } catch (e: Exception) {
                Log.w(TAG, "Failed to read series detail from disk cache", e)
            }
        } else {
            Log.d(TAG, "♻️ Forced refresh of series detail $seriesId requested")
        }
        return syncSeriesDetail(seriesId)
    }



    /**
     * Observe series details from database
     */
    fun getSeriesDetailFlow(seriesId: String): Flow<SeriesWithSeasonsAndEpisodes?> {
        return seriesDao?.getSeriesWithSeasonsAndEpisodesFlow(seriesId) ?: kotlinx.coroutines.flow.flowOf(null)
    }

    /**
     * Syncs series details from network to local database
     * Uses the deep fallback logic of fetchSeriesDetailFromNetwork
     */


    suspend fun syncSeriesDetail(seriesId: String): Result<XtreamSeriesDetailResponse> {
        val start = System.currentTimeMillis()
        Log.d("XtreamDebug", "syncSeriesDetail: Starting for $seriesId")
        val result = fetchSeriesDetailFromNetwork(seriesId)
        if (result is Result.Success) {
            val detail = result.data
            if (seriesDao != null) {
                try {
                    Log.d("XtreamDebug", "syncSeriesDetail: Saving to DB...")
                    val seasonEntities = detail.seasons?.map { it.toSeasonEntity(seriesId) } ?: emptyList()
                    val episodeEntities = mutableListOf<com.tvonnet.debridxtreamiptv.data.local.entity.EpisodeEntity>()
                    detail.episodes?.forEach { entry ->
                         val seasonKey = entry.key
                         val episodes = entry.value
                         val seasonNum = seasonKey.filter { it.isDigit() }.toIntOrNull() ?: 0
                         episodes.forEach { episode ->
                             episodeEntities.add(episode.toEpisodeEntity(seriesId, seasonNum))
                         }
                    }
                    
                    seriesDao.saveSeriesDetails(seriesId, seasonEntities, episodeEntities)
                    Log.d("XtreamDebug", "✅ Series $seriesId details synced to database in ${System.currentTimeMillis() - start}ms")
                } catch (e: Exception) {
                    Log.e("XtreamDebug", "❌ Failed to save series details to database. Data will still show in UI.", e)
                }
            }
        } else {
             Log.e("XtreamDebug", "syncSeriesDetail: Fetch failed")
        }
        return result
    }

    private suspend fun fetchSeriesDetailFromNetwork(seriesId: String): Result<XtreamSeriesDetailResponse> {
        return try {
            if (apiService == null) {
                return Result.Error(Exception("API service not initialized"))
            }
            Log.d("XtreamDebug", "⬇️ Network fetch: Series detail $seriesId")
            val start = System.currentTimeMillis()
            val response = apiService!!.getSeriesInfo(username, password, seriesId = seriesId)
            Log.d("XtreamDebug", "Response received in ${System.currentTimeMillis() - start}ms. Code: ${response.code()}")
            
            if (response.isSuccessful && response.body() != null) {
                val detail = response.body()!!
                Log.d("XtreamDebug", "Parsed Body. Episodes: ${detail.episodes?.size ?: 0} seasons, Info: ${detail.info?.name}")
                if (!detail.episodes.isNullOrEmpty()) {
                    seriesDetailCache[seriesId] = detail
                } else {
                    Log.w(TAG, "Series detail $seriesId returned without episodes")
                }
                Result.Success(detail)
            } else {
                Log.e("XtreamDebug", "❌ Failed to fetch series detail: ${response.code()}. Trying fallback...")
                // Fallback Strategy
                return fetchFallbackSeriesDetail(seriesId)
            }
        } catch (e: Exception) {
            Log.e("XtreamDebug", "❌ Error fetching series detail $seriesId: ${e.message}", e)
            Result.Error(e)
        }
    }

    private suspend fun fetchFallbackSeriesDetail(seriesId: String): Result<XtreamSeriesDetailResponse> {
        return try {
            Log.d("XtreamDebug", "⚠️ Starting Fallback for Series $seriesId")
            
            // 1. Fetch info using simple get_series (often works when get_series_info fails)
            val infoResponse = apiService!!.getSeries(username, password, seriesId = seriesId)
            val infoList = infoResponse.body()
            
            var info: XtreamSeriesDetailInfo? = null
            
            if (!infoResponse.isSuccessful || infoList.isNullOrEmpty()) {
                Log.w("XtreamDebug", "Fallback 1 (Info) failed/empty. Continuing to fetch episodes...")
            } else {
                 info = infoList.first().let {
                     XtreamSeriesDetailInfo(
                         name = it.name,
                         series_id = it.series_id,
                         cover = it.cover,
                         plot = it.plot,
                         cast = it.cast,
                         director = it.director,
                         genre = it.genre,
                         releaseDate = it.releaseDate,
                         rating = it.rating,
                         rating_5based = it.rating_5based,
                         backdrop_path = it.backdropPathList.firstOrNull(),
                         youtube_trailer = it.youtube_trailer
                     )
                }
            }
            
            // 2. Fetch episodes using get_series_episodes (get_show_episodes)
            val episodesResponse = apiService!!.getSeriesEpisodes(username, password, seriesId = seriesId)
             var episodesMap: Map<String, List<XtreamEpisodeInfo>>? = null
             
            if (episodesResponse.isSuccessful && episodesResponse.body() != null) {
                 val json = episodesResponse.body()!!
                 
                 // Handle dynamic JSON (Map vs List)
                 if (json.isJsonObject) {
                     val type = object : TypeToken<Map<String, List<XtreamEpisodeInfo>>>() {}.type
                     // We need to parse manually or strict because Gson might fail on "info"
                      try {
                          episodesMap = Gson().fromJson(json, type)
                      } catch(e: Exception) {
                          Log.e("XtreamDebug", "Fallback 2 JSON Parse Error: ${e.message}")
                      }
                 } else if (json.isJsonArray) {
                      // Sometimes it returns a list of episodes directly? Or list of objects?
                      // Standard Xtream get_show_episodes returns Map "1": [episodes], "2": [episodes]
                      // If it is array, likely empty []
                      Log.w("XtreamDebug", "Fallback 2 returned Array, constructing empty map")
                      episodesMap = emptyMap()
                 }
            }
            
            val combined = XtreamSeriesDetailResponse(
                info = info,
                episodes = episodesMap,
                seasons = null // We can infer seasons from keys if needed
            )
            
            Log.d("XtreamDebug", "✅ Fallback Successful. Info: ${info?.name ?: "Unknown"}, Episodes: ${episodesMap?.size}")
            Result.Success(combined)
            
        } catch (e: Exception) {
            Log.e("XtreamDebug", "Fallback failed critically", e)
             Result.Error(e)
        }
    }


    
    private suspend fun fetchEpg(): Result<Map<String, List<EpgProgram>>> {
        return try {
            val epgResponse = apiService?.getEpg(username, password)
            if (epgResponse?.isSuccessful == true) {
                val body = epgResponse.body()
                if (body != null) {
                    body.use { responseBody ->
                        val parsedEpg = responseBody.charStream().use { reader ->
                            EpgParser.parse(reader)
                        }
                        return Result.Success(parsedEpg)
                    }
                }
            }
            Result.Error(Exception("EPG fetch failed but continuing..."))
        } catch (e: Exception) {
            Log.w(TAG, "EPG fetch failed, continuing without EPG", e)
            Result.Error(e) // EPG failure is non-critical
        }
    }
    
    fun readCache(): IptvCache? {
        // Return from memory cache if available (avoid repeated Gson parsing)
        if (memoryCache != null) {
            return memoryCache
        }
        
        // Otherwise read from file and cache in memory
        memoryCache = cacheHelper.readCache()
        return memoryCache
    }
    
    fun clearMemoryCache() {
        memoryCache = null
        // Week 7: Also clear CacheManager's memory cache if available
        cacheManager?.clearMemoryCache()
        seriesDetailCache.clear()
        perCategorySeriesCache.clear()
        allSeriesCacheFallback = null
        perCategoryVodCache.clear()
        cacheHelper.clearMemorySnapshot()
        Log.d(TAG, "Memory cache cleared (Legacy + CacheManager + per-category)")
    }
    
    suspend fun forceRefresh(): Result<IptvCache> {
        // Clear memory cache before fetching new data
        clearMemoryCache()
        return refreshCategoriesAndLatest()
    }
    
    // ========================================
    // WEEK 10: FAVORITES METHODS
    // ========================================
    
    /**
     * Get all favorites as Flow (reactive)
     */
    fun getAllFavorites(): Flow<List<FavoriteEntity>> {
        return favoriteDao?.getAllFavorites() 
            ?: throw IllegalStateException("FavoriteDao not initialized")
    }
    
    /**
     * Get favorites filtered by type (live, vod, series)
     */
    fun getFavoritesByType(type: String): Flow<List<FavoriteEntity>> {
        return favoriteDao?.getFavoritesByType(type)
            ?: throw IllegalStateException("FavoriteDao not initialized")
    }
    
    /**
     * Check if a stream is favorited
     */
    suspend fun isFavorite(streamId: String): Boolean {
        // Week 12: Use performance cache if available (O(1) lookup)
        if (favoritesCache != null && favoritesCache.isInitialized()) {
            return favoritesCache.isFavorite(streamId)
        }
        // Fallback to database query
        return favoriteDao?.isFavorite(streamId) ?: false
    }
    
    /**
     * Add a stream to favorites
     * @param streamId The unique ID of the stream
     * @param type The type: "live", "vod", or "series"
     * @param name Display name of the stream
     * @param iconUrl Optional icon/thumbnail URL
     * Week 12: Updated to include name and iconUrl
     */
    suspend fun addFavorite(streamId: String, type: String, name: String, iconUrl: String? = null) {
        val favorite = FavoriteEntity(
            streamId = streamId,
            type = type,
            name = name,
            iconUrl = iconUrl,
            addedAt = System.currentTimeMillis()
        )
        favoriteDao?.insertFavorite(favorite)
        // Week 12: Update performance cache (optimistic)
        favoritesCache?.addToCache(streamId)
        Log.d(TAG, "Added favorite: $streamId (name: $name, type: $type)")
    }
    
    /**
     * Remove a stream from favorites
     */
    suspend fun removeFavorite(streamId: String) {
        favoriteDao?.deleteFavoriteByStreamId(streamId)
        // Week 12: Update performance cache (optimistic)
        favoritesCache?.removeFromCache(streamId)
        Log.d(TAG, "Removed favorite: $streamId")
    }
    
    /**
     * Remove all favorites
     */
    suspend fun clearAllFavorites() {
        favoriteDao?.deleteAllFavorites()
        Log.d(TAG, "Cleared all favorites")
    }

    // ========== Search History Operations ==========

    /**
     * Get recent search queries as Flow (reactive)
     */
    fun getRecentSearches(limit: Int = 10): Flow<List<SearchHistoryEntity>> {
        return searchHistoryDao?.getRecentSearches(limit)
            ?: throw IllegalStateException("SearchHistoryDao not initialized")
    }

    /**
     * Save a search query to history
     */
    suspend fun saveSearchQuery(query: String) {
        if (query.isBlank()) return

        val search = SearchHistoryEntity(query = query.trim())
        searchHistoryDao?.insertSearch(search)

        // Clean old searches periodically
        try {
            searchHistoryDao?.cleanOldSearches()
        } catch (e: Exception) {
            Log.w(TAG, "Failed to clean old searches", e)
        }

        Log.d(TAG, "Saved search query: $query")
    }

    /**
     * Remove a specific search query from history
     */
    suspend fun removeSearchQuery(query: String) {
        searchHistoryDao?.deleteSearchByQuery(query)
        Log.d(TAG, "Removed search query: $query")
    }

    /**
     * Clear all search history
     */
    suspend fun clearSearchHistory() {
        searchHistoryDao?.deleteAllSearches()
        Log.d(TAG, "Cleared all search history")
    }

    // ========================================
    // Week 11: EPG OPERATIONS
    // ========================================
    
    /**
     * Fetch and save EPG data from Xtream API
     * Parses XML and stores in Room database
     */
    suspend fun fetchAndSaveEpg(): Result<Int> {
        return epgSyncMutex.withLock {
            withContext(Dispatchers.IO) {
            try {
                // Check memory pressure before starting EPG fetch
                val memoryPressure = memoryManager.checkMemoryPressure()
                if (memoryPressure == MemoryManager.MemoryPressure.CRITICAL) {
                    Log.w(TAG, "Critical memory pressure detected, skipping EPG fetch")
                    return@withContext Result.Error(Exception("Memory pressure too high for EPG fetch"))
                }

                if (apiService == null) {
                    return@withContext Result.Error(Exception("API service not initialized"))
                }

                Log.d(TAG, "Fetching EPG data from Xtream API... Memory pressure: $memoryPressure")

                // Track memory usage before operation
                val initialMemory = memoryManager.getCurrentMemoryUsage()
                memoryManager.trackMemoryUsage("epg_fetch_start", initialMemory.usedMemoryMB)

                val response = try {
                    apiService!!.getEpg(username, password)
                } catch (e: Exception) {
                    Log.e(TAG, "Network error during EPG fetch", e)
                    return@withContext Result.Error(e)
                }

                if (!response.isSuccessful || response.body() == null) {
                    Log.e(TAG, "EPG fetch failed: ${response.code()}")
                    return@withContext Result.Error(Exception("EPG fetch failed: ${response.code()}"))
                }

                if (epgDao == null) {
                    Log.w(TAG, "EpgDao not available, skipping database save")
                    return@withContext Result.Success(0)
                }

                val batchSize = when (memoryPressure) {
                    MemoryManager.MemoryPressure.CRITICAL -> EPG_BATCH_SIZE / 4
                    MemoryManager.MemoryPressure.WARNING -> EPG_BATCH_SIZE / 2
                    MemoryManager.MemoryPressure.LOW -> EPG_BATCH_SIZE
                    MemoryManager.MemoryPressure.NONE -> EPG_BATCH_SIZE
                }

                val batch = ArrayList<EpgEntity>(batchSize)
                var totalPrograms = 0
                var lastMemoryCheck = System.currentTimeMillis()

                epgDao.clearAll()

                response.body()!!.use { responseBody ->
                    responseBody.charStream().buffered(memoryManager.getOptimalBufferSize()).use { reader ->
                        totalPrograms = EpgParser.parseStream(reader) { program ->
                            // Check memory pressure periodically during parsing
                            val currentTime = System.currentTimeMillis()
                            if (currentTime - lastMemoryCheck > 2000) { // Check every 2 seconds
                                val currentPressure = memoryManager.checkMemoryPressure()
                                if (currentPressure == MemoryManager.MemoryPressure.CRITICAL) {
                                    Log.w(TAG, "Critical memory pressure during EPG parsing, stopping")
                                    throw CancellationException("EPG parsing stopped due to memory pressure")
                                }
                                lastMemoryCheck = currentTime
                            }

                            batch.add(
                                EpgEntity(
                                    channelId = program.channelId,
                                    start = program.start,
                                    stop = program.stop,
                                    title = program.title,
                                    description = program.desc,
                                    category = program.category
                                )
                            )

                            if (batch.size >= batchSize) {
                                epgDao.insertAll(batch)
                                batch.clear()

                                // Check memory pressure after batch insert
                                val batchPressure = memoryManager.checkMemoryPressure()
                                if (batchPressure == MemoryManager.MemoryPressure.CRITICAL) {
                                    Log.w(TAG, "Critical memory pressure after batch insert, stopping")
                                    memoryManager.requestGarbageCollection()
                                    throw CancellationException("EPG parsing stopped due to memory pressure")
                                }
                            }

                            // Ensure coroutine is still active
                            coroutineContext[kotlinx.coroutines.Job]?.ensureActive()
                        }
                    }
                }

                if (batch.isNotEmpty()) {
                    epgDao.insertAll(batch)
                }

                Log.d(TAG, "EPG saved to database in streaming mode: $totalPrograms programs")

                // Clean up old expired programs (older than 24 hours)
                val cutoffTime = System.currentTimeMillis() - (24 * 60 * 60 * 1000)
                epgDao.clearOldPrograms(cutoffTime)

                // Week 14: Track memory usage after EPG save
                PerformanceMonitor.trackMemory("afterEpgSave")
                val finalMemory = memoryManager.getCurrentMemoryUsage()
                memoryManager.trackMemoryUsage("epg_fetch_complete", finalMemory.usedMemoryMB)

                saveEpgLastSync()

                Result.Success(totalPrograms)
            } catch (e: CancellationException) {
                Log.i(TAG, "EPG fetch cancelled: ${e.message}")
                memoryManager.requestGarbageCollection()
                Result.Error(e)
            } catch (e: OutOfMemoryError) {
                Log.e(TAG, "EPG fetch failed due to memory constraints", e)
                memoryManager.requestGarbageCollection()
                Result.Error(Exception("Insufficient memory for EPG processing"))
            } catch (e: Exception) {
                Log.e(TAG, "Failed to fetch/save EPG", e)
                Result.Error(e)
            }
        }
        }
    }

    /**
     * Ensure EPG data exists locally and is fresh enough for UI usage.
     * Returns true if a new sync was performed.
     */
    suspend fun ensureEpgData(
        force: Boolean = false,
        maxAgeMs: Long = DEFAULT_EPG_REFRESH_INTERVAL_MS
    ): Boolean {
        val dao = epgDao ?: return false
        val lastSync = epgPrefs.getLong("last_sync_time", 0L)
        val now = System.currentTimeMillis()
        val totalPrograms = dao.getTotalProgramCount()
        val shouldRefresh = force || totalPrograms == 0 || now - lastSync > maxAgeMs

        if (!shouldRefresh) return false

        return when (val result = fetchAndSaveEpg()) {
            is Result.Success -> {
                if (result.data > 0) {
                    saveEpgLastSync()
                    true
                } else {
                    false
                }
            }
            else -> false
        }
    }
    
    /**
     * Get all EPG programs for a specific channel (reactive)
     */
    fun getEpgByChannel(channelId: String): Flow<List<EpgEntity>>? {
        return epgDao?.getProgramsByChannel(channelId)
    }
    
    /**
     * Get current program for a channel (one-shot)
     */
    suspend fun getCurrentProgram(channelId: String): EpgEntity? {
        return epgDao?.getCurrentProgram(channelId, System.currentTimeMillis())
    }
    
    /**
     * Get next program for a channel (one-shot)
     */
    suspend fun getNextProgram(channelId: String): EpgEntity? {
        return epgDao?.getNextProgram(channelId, System.currentTimeMillis())
    }

    /**
     * Lightweight per-channel EPG (now/next) using Xtream `get_short_epg`.
     * Safer on low-memory TV devices than downloading/parsing full XMLTV.
     */
    suspend fun fetchShortEpgPrograms(
        streamId: String,
        channelKey: String,
        limit: Int = 12
    ): Result<List<EpgEntity>> {
        return try {
            val service = apiService ?: return Result.Error(Exception("API service not initialized"))

            val response = service.getShortEpg(username, password, streamId = streamId, limit = limit)
            if (!response.isSuccessful) {
                return Result.Error(HttpException(response))
            }

            val listings = response.body()?.listings.orEmpty()
            if (listings.isEmpty()) return Result.Success(emptyList())

            val mapped = listings.mapNotNull { listing -> listing.toEpgEntityOrNull(channelKey) }
                .sortedBy { it.start }
            Result.Success(mapped)
        } catch (e: Exception) {
            Result.Error(e)
        }
    }

    suspend fun fetchShortEpgNowNext(
        streamId: String,
        channelKey: String,
        limit: Int = 2
    ): Result<Pair<EpgEntity?, EpgEntity?>> {
        return when (val result = fetchShortEpgPrograms(streamId = streamId, channelKey = channelKey, limit = limit.coerceAtLeast(2))) {
            is Result.Success -> {
                val list = result.data
                if (list.isEmpty()) return Result.Success(null to null)
                val nowMs = System.currentTimeMillis()
                val now = list.firstOrNull { nowMs in it.start..it.stop } ?: list.firstOrNull()
                val next = list.firstOrNull { it.start > nowMs }
                Result.Success(now to next)
            }
            is Result.Error -> result
            else -> Result.Error(Exception("Unknown EPG result"))
        }
    }

    private fun XtreamEpgListing.toEpgEntityOrNull(channelKey: String): EpgEntity? {
        val startSec = startTimestamp?.toLongOrNull() ?: return null
        val stopSec = stopTimestamp?.toLongOrNull() ?: return null
        val startMs = startSec * 1000L
        val stopMs = stopSec * 1000L
        if (stopMs <= 0L || stopMs <= startMs) return null

        return EpgEntity(
            channelId = channelKey,
            start = startMs,
            stop = stopMs,
            title = decodeBase64IfPossible(title),
            description = decodeBase64IfPossible(description),
            category = null
        )
    }

    private fun decodeBase64IfPossible(value: String?): String? {
        val raw = value?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        return try {
            val decoded = String(Base64.decode(raw, Base64.DEFAULT), Charsets.UTF_8)
            decoded.takeIf { it.isNotBlank() } ?: raw
        } catch (_: Exception) {
            raw
        }
    }
    
    /**
     * Get upcoming programs for a channel (next 12 hours)
     */
    fun getUpcomingPrograms(channelId: String): Flow<List<EpgEntity>>? {
        val now = System.currentTimeMillis()
        val twelveHoursLater = now + (12 * 60 * 60 * 1000)
        return epgDao?.getUpcomingPrograms(channelId, now, twelveHoursLater)
    }
    
    /**
     * Check if EPG data exists for a channel
     */
    suspend fun hasEpgData(channelId: String): Boolean {
        return epgDao?.hasEpgData(channelId) ?: false
    }
    
    /**
     * Clear old EPG programs (older than 24 hours)
     */
    suspend fun clearOldEpg() {
        val cutoffTime = System.currentTimeMillis() - (24 * 60 * 60 * 1000)
        epgDao?.clearOldPrograms(cutoffTime)
    }
    
    // ========== Week 12: Stream Lookup for Favorites Playback ==========
    
    /**
     * Get live stream by stream ID from cache
     * Week 12: Used for favorites playback
     */
    suspend fun getLiveStreamById(streamId: String): XtreamStream? {
        val cache = memoryCache
            ?: cacheHelper.memorySnapshot()?.also { memoryCache = it }
            ?: cacheHelper.readCache()?.also { memoryCache = it }
        val memoryMatch = cache?.live?.streams?.find { it.stream_id == streamId }
        if (memoryMatch != null) return memoryMatch

        // Fallback: Check Room via CacheManager
        return cacheManager.getChannelById(streamId)
    }
    
    /**
     * Get VOD stream by stream ID from cache
     * Week 12: Used for favorites playback
     */
    suspend fun getVodById(streamId: String): XtreamVodInfo? {
        val cache = memoryCache
            ?: cacheHelper.memorySnapshot()?.also { memoryCache = it }
            ?: cacheHelper.readCache()?.also { memoryCache = it }
        val memoryMatch = cache?.vod?.streams?.find { it.stream_id == streamId }
        if (memoryMatch != null) return memoryMatch

        // Fallback: Check local per-category cache
        perCategoryVodCache.values.flatten().find { it.stream_id == streamId }?.let { return it }

        // Fallback: Check Room
        return vodDao?.getVodById(streamId)?.toXtreamVodInfo()
    }
    
    /**
     * Get series by stream ID from cache
     * Week 12: Used for favorites playback
     * Updated: Also checks per-category cache for recently loaded series
     */
    suspend fun getSeriesById(streamId: String): XtreamSeriesInfo? {
        // First check per-category cache (more up-to-date)
        perCategorySeriesCache.values.forEach { seriesList ->
            seriesList.find { it.series_id == streamId }?.let { return it }
        }
        
        // Fallback to global cache
        val cache = memoryCache
            ?: cacheHelper.memorySnapshot()?.also { memoryCache = it }
            ?: cacheHelper.readCache()?.also { memoryCache = it }
        val memoryMatch = cache?.series?.streams?.find { it.series_id == streamId }
        if (memoryMatch != null) return memoryMatch

        // Fallback: Check Room
        return seriesDao?.getSeriesById(streamId)?.toXtreamSeriesInfo()
    }
    
    /**
     * Build stream URL for live TV
     * Week 12: Helper for playback
     */
    fun buildLiveStreamUrl(stream: XtreamStream, baseServerUrl: String): String {
        return stream.toLiveStreamUrl(baseServerUrl, username, password)
    }
    
    /**
     * Build stream URL for VOD
     * Week 12: Helper for playback
     */
    fun buildVodStreamUrl(vod: XtreamVodInfo, baseServerUrl: String): String {
        // Format: http://server:port/movie/username/password/streamId.ext
        val url = baseServerUrl.trimEnd('/')
        return "$url/movie/$username/$password/${vod.stream_id}.${vod.container_extension ?: "mp4"}"
    }
    
    fun buildSeriesEpisodeStreamUrl(episodeId: String, containerExtension: String?, baseServerUrl: String): String {
        val url = baseServerUrl.trimEnd('/')
        val ext = containerExtension?.takeIf { it.isNotBlank() } ?: "mp4"
        return "$url/series/$username/$password/$episodeId.$ext"
    }
    
    /**
     * Persist refreshed series categories into memory + disk cache and clear stale series caches.
     */
    private suspend fun persistSeriesCategories(categories: List<XtreamCategory>) {
        val existingCache = readCache()
        val existingStreams = existingCache?.series?.streams ?: emptyList()
        if (categories.isEmpty()) {
            Log.w(TAG, "PersistSeriesCategories called with empty list. Retaining previous categories.")
        }

        val updatedSeriesData = SeriesCacheData(
            categories = categories,
            streams = existingStreams
        )
        
        val updatedCache = if (existingCache != null) {
            existingCache.copy(
                timestamp = System.currentTimeMillis(),
                series = updatedSeriesData
            )
        } else {
            IptvCache(
                timestamp = System.currentTimeMillis(),
                live = null,
                vod = null,
                series = updatedSeriesData,
                epg = null
            )
        }
        
        memoryCache = updatedCache
        cacheHelper.writeCache(updatedCache)
        cacheManager?.putCategories(categories, "series")
        
        // Clear per-category cache so it can repopulate with fresh series data
        perCategorySeriesCache.clear()
        allSeriesCacheFallback = null
    }
    
    /**
     * Update per-category series cache
     * Called after fetching series for a category
     */
    private fun updateSeriesCacheForCategory(categoryId: String, series: List<XtreamSeriesInfo>) {
        perCategorySeriesCache[categoryId] = series
        persistSeriesStreamsForCategory(categoryId, series)
        Log.d(TAG, "Updated per-category cache for $categoryId: ${series.size} series (persisted)")
    }

    private suspend fun fallbackSeriesFetch(categoryId: String): Result<List<XtreamSeriesInfo>> {
        perCategorySeriesCache[categoryId]?.takeIf { it.isNotEmpty() }?.let {
            Log.d(TAG, "Fallback (memory per-category) already populated for $categoryId: ${it.size} series")
            return Result.Success(it)
        }

        // Attempt to use cached all-series list first with RELAXED filtering
        allSeriesCacheFallback?.let { cachedAll ->
            val filtered = cachedAll.filter { 
                val catIdStr = it.category_id?.trim()
                val targetIdStr = categoryId.trim()
                val stringMatch = catIdStr == targetIdStr
                val listMatch = it.category_ids?.contains(targetIdStr.toIntOrNull()) == true
                val intMatch = catIdStr?.toIntOrNull() == targetIdStr.toIntOrNull()
                stringMatch || listMatch || (intMatch && targetIdStr.toIntOrNull() != null)
            }
            if (filtered.isNotEmpty()) {
                Log.d(TAG, "Fallback (cached all-series) succeeded for category $categoryId: ${filtered.size} series (Relaxed Filter)")
                updateSeriesCacheForCategory(categoryId, filtered)
                return Result.Success(filtered)
            }
        }

        // Use persisted cache on disk if available
        readCache()?.series?.streams
            ?.takeIf { it.isNotEmpty() }
            ?.let { cachedStreams ->
                // Promote disk snapshot into memory for future fallbacks
                allSeriesCacheFallback = cachedStreams
                
                val filtered = cachedStreams.filter { 
                    val catIdStr = it.category_id?.trim()
                    val targetIdStr = categoryId.trim()
                    val stringMatch = catIdStr == targetIdStr
                    val listMatch = it.category_ids?.contains(targetIdStr.toIntOrNull()) == true
                    val intMatch = catIdStr?.toIntOrNull() == targetIdStr.toIntOrNull()
                    stringMatch || listMatch || (intMatch && targetIdStr.toIntOrNull() != null)
                }
                
                if (filtered.isNotEmpty()) {
                    Log.d(TAG, "Fallback (disk cache all-series) succeeded for category $categoryId: ${filtered.size} series (Relaxed Filter)")
                    updateSeriesCacheForCategory(categoryId, filtered)
                    return Result.Success(filtered)
                }
            }

        // Fetch all series from server and filter client-side
        val allSeriesResult = fetchAllSeries()
        return when (allSeriesResult) {
            is Result.Success -> {
                val filtered = allSeriesResult.data.filter { 
                    val catIdStr = it.category_id?.trim()
                    val targetIdStr = categoryId.trim()
                    val stringMatch = catIdStr == targetIdStr
                    val listMatch = it.category_ids?.contains(targetIdStr.toIntOrNull()) == true
                    val intMatch = catIdStr?.toIntOrNull() == targetIdStr.toIntOrNull()
                    stringMatch || listMatch || (intMatch && targetIdStr.toIntOrNull() != null)
                }
                if (filtered.isNotEmpty()) {
                    Log.d(TAG, "Fallback (network all-series) succeeded for category $categoryId: ${filtered.size} series")
                    updateSeriesCacheForCategory(categoryId, filtered)
                    Result.Success(filtered)
                } else {
                    Log.w(TAG, "Fallback all-series fetch returned empty for category $categoryId")
                    Result.Success(emptyList())
                }
            }
            is Result.Error -> {
                Log.e(TAG, "Fallback all-series fetch failed for category $categoryId", allSeriesResult.exception)
                Result.Error(allSeriesResult.exception)
            }
            else -> Result.Success(emptyList())
        }
    }

    private fun maybeReturnSeriesDuringCooldown(categoryId: String): List<XtreamSeriesInfo>? {
        val status = seriesCategoryStatus[categoryId] ?: return null
        if (status.state != SeriesCategoryState.TEMPORARILY_UNAVAILABLE) {
            return null
        }

        val elapsed = System.currentTimeMillis() - status.lastUpdatedMillis
        if (elapsed < SERIES_CATEGORY_FAILURE_COOLDOWN_MS) {
            val cachedSeries = perCategorySeriesCache[categoryId]
            return if (!cachedSeries.isNullOrEmpty()) {
                Log.d(TAG, "Returning cached series for $categoryId during cooldown (${cachedSeries.size} items)")
                cachedSeries
            } else {
                Log.w(TAG, "Category $categoryId still cooling down and no cached data is available.")
                emptyList()
            }
        }

        // Cooldown expired, allow next fetch attempt.
        seriesCategoryStatus.remove(categoryId)
        return null
    }

    private suspend fun handleSeriesFallback(
        categoryId: String,
        warningMessage: String,
        failureState: SeriesCategoryState
    ): Result<List<XtreamSeriesInfo>> {
        Log.w(TAG, warningMessage)
        val fallback = fallbackSeriesFetch(categoryId)
        return when (fallback) {
            is Result.Success -> {
                if (fallback.data.isNotEmpty()) {
                    markSeriesCategoryHealthy(categoryId)
                } else {
                    updateSeriesCategoryStatus(categoryId, failureState, warningMessage)
                }
                fallback
            }
            is Result.Error -> {
                updateSeriesCategoryStatus(
                    categoryId,
                    failureState,
                    fallback.exception.message ?: warningMessage
                )
                fallback
            }
            else -> {
                updateSeriesCategoryStatus(categoryId, failureState, warningMessage)
                fallback
            }
        }
    }

    private fun markSeriesCategoryHealthy(categoryId: String) {
        seriesCategoryStatus.remove(categoryId)
    }

    private fun updateSeriesCategoryStatus(
        categoryId: String,
        state: SeriesCategoryState,
        message: String?
    ) {
        if (state == SeriesCategoryState.HEALTHY) {
            markSeriesCategoryHealthy(categoryId)
            return
        }
        seriesCategoryStatus[categoryId] = SeriesCategoryStatus(
            categoryId = categoryId,
            state = state,
            message = message,
            lastUpdatedMillis = System.currentTimeMillis()
        )
    }

    private fun persistSeriesStreamsForCategory(categoryId: String, series: List<XtreamSeriesInfo>) {
        val seriesToPersist = series.takeIf { it.isNotEmpty() } ?: return
        val existingCache = readCache()
        val existingSeriesData = existingCache?.series
        val currentCategories = existingSeriesData?.categories ?: emptyList()
        val existingStreams = existingSeriesData?.streams ?: emptyList()
        val mergedStreams = existingStreams.toMutableList().apply {
            if (isNotEmpty()) {
                removeAll { it.category_id == categoryId }
            }
            addAll(seriesToPersist)
        }.toList()

        val updatedSeriesData = SeriesCacheData(
            categories = currentCategories,
            streams = mergedStreams
        )

        val updatedCache = existingCache?.copy(
            timestamp = System.currentTimeMillis(),
            series = updatedSeriesData
        ) ?: IptvCache(
            timestamp = System.currentTimeMillis(),
            live = null,
            vod = null,
            series = updatedSeriesData,
            epg = null
        )

        memoryCache = updatedCache
        // Do NOT update allSeriesCacheFallback here. It should only be updated when we fetch ALL series.
        // allSeriesCacheFallback = updatedSeriesData.streams
        cacheHelper.writeCache(updatedCache)
        Log.d(
            TAG,
            "Persisted ${seriesToPersist.size} series for category $categoryId (cached total: ${updatedSeriesData.streams.size})"
        )
    }

    private suspend fun fetchAllSeries(): Result<List<XtreamSeriesInfo>> {
        allSeriesCacheFallback?.let {
            return Result.Success(it)
        }

        return try {
            if (apiService == null) {
                return Result.Error(Exception("API service not initialized"))
            }

            Log.d(TAG, "Fetching all series for fallback (Strategy 1: No Category)")
            var response = apiService!!.getSeries(username, password)
            var seriesList = response.body() ?: emptyList()

            // Strategy 2: Try with empty category_id
            if (!response.isSuccessful || seriesList.isEmpty()) {
                Log.d(TAG, "Strategy 1 failed/empty. Trying Strategy 2: Empty Category")
                response = apiService!!.getSeries(username, password, categoryId = "")
                seriesList = response.body() ?: emptyList()
            }

            // Strategy 3: Try with category_id = "0"
            if (!response.isSuccessful || seriesList.isEmpty()) {
                Log.d(TAG, "Strategy 2 failed/empty. Trying Strategy 3: Category '0'")
                response = apiService!!.getSeries(username, password, categoryId = "0")
                seriesList = response.body() ?: emptyList()
            }

            if (seriesList.isNotEmpty()) {
                allSeriesCacheFallback = seriesList
                // Save to Disk Cache - Level 2 Cache
                cacheHelper.writeAllSeries(seriesList)
                Log.d(TAG, "Fetched ${seriesList.size} total series for fallback (and cached to disk)")
                Result.Success(seriesList)
            } else {
                Log.e(TAG, "Failed to fetch all series (All strategies failed). Last code: ${response.code()}")
                Result.Error(HttpException(response))
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error fetching all series for fallback", e)
            Result.Error(e)
        }
    }
    
    suspend fun updateEpisodePlaybackStatus(episodeId: String, isWatched: Boolean, resumePosition: Long, duration: Long) {
        seriesDao?.updatePlaybackStatus(episodeId, isWatched, resumePosition, duration)
    }

    private fun saveEpgLastSync(timestamp: Long = System.currentTimeMillis()) {
        epgPrefs.edit().putLong("last_sync_time", timestamp).apply()
    }

    
    companion object {
        private const val TAG = "XtreamRepository"
        private val NON_ALPHANUMERIC_REGEX = Regex("[^a-z0-9]")
        private val YEAR_REGEX = Regex("(19|20)\\d{2}")
        private const val EPG_BATCH_SIZE = 500
        private const val DEFAULT_EPG_REFRESH_INTERVAL_MS = 6 * 60 * 60 * 1000L
        private const val SERIES_CATEGORY_FAILURE_COOLDOWN_MS = 2 * 60 * 1000L
        private const val MAX_CONCURRENT_VOD_FETCHES = 4
        private const val MAX_MOVIE_SOURCE_LOOKUPS = 10
        private const val LATEST_VOD_LIMIT = 24
        private const val LATEST_SERIES_LIMIT = 24
        private const val SYNC_PREFS_NAME = "sync_prefs"
        private const val KEY_LAST_SYNC_TIME = "last_sync_time"
        private const val KEY_LAST_SYNC_TYPE = "last_sync_type"
        private const val BACKGROUND_SYNC_SKIP_AFTER_INITIAL_MS = 2 * 60 * 1000L
        private const val SEARCH_INDEX_CHUNK = 2000
        private const val KEY_SEARCH_INDEX_VOD_TIME = "search_index_vod_time"
        private const val KEY_SEARCH_INDEX_SERIES_TIME = "search_index_series_time"
        private const val KEY_SEARCH_INDEX_LIVE_TIME = "search_index_live_time"
        private const val SEARCH_INDEX_REFRESH_INTERVAL_MS = 24 * 60 * 60 * 1000L
        private const val SEARCH_INDEX_CATEGORY_FETCH_DELAY_MS = 150L
    }
    // --- Search Functionality ---

    suspend fun searchSeries(query: String, categoryId: String? = null): List<XtreamSeriesInfo> {
        return seriesDao?.searchSeries(query, categoryId)?.map { it.toXtreamSeriesInfo() } ?: emptyList()
    }

    suspend fun searchVod(query: String, categoryId: String? = null): List<XtreamVodInfo> {
        return vodDao?.searchMovies(query, categoryId)?.map { it.toXtreamVodInfo() } ?: emptyList()
    }

    suspend fun searchLive(query: String): List<XtreamStream> {
        return cacheManager?.searchChannels(query) ?: emptyList()
    }

    /** Full live-channel catalog from the index (backs the guide's "All" tab). */
    suspend fun getAllLiveChannels(): List<XtreamStream> {
        return cacheManager?.getAllLiveChannels() ?: emptyList()
    }

    /** Deduped live-channel count for the "All" badge (cheaper than materializing the list). */
    suspend fun countAllLiveChannels(): Int {
        return cacheManager?.countAllLiveChannels() ?: 0
    }

    suspend fun searchEpg(query: String): List<com.tvonnet.debridxtreamiptv.data.local.entity.EpgEntity> {
        return epgDao?.searchPrograms(query, System.currentTimeMillis()) ?: emptyList()
    }

    /**
     * Search-index sync: fills Room with the FULL VOD/series catalog so search
     * covers categories the user never opened (those tables are otherwise only
     * populated lazily per browsed category). Runs in the background after a
     * successful content sync; REPLACE upserts on stable stream/series ids keep
     * it idempotent and never delete the lazily-synced per-category rows.
     */
    private val searchIndexScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val searchIndexMutex = Mutex()

    fun scheduleSearchIndexSyncIfStale() {
        val anyStale = isIndexStageStale(KEY_SEARCH_INDEX_VOD_TIME) ||
            isIndexStageStale(KEY_SEARCH_INDEX_SERIES_TIME) ||
            isIndexStageStale(KEY_SEARCH_INDEX_LIVE_TIME)
        if (anyStale) {
            scheduleSearchIndexSync()
        }
    }

    fun scheduleSearchIndexSync() {
        searchIndexScope.launch {
            try {
                syncSearchIndex()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "Search index sync failed: ${e.javaClass.simpleName}")
            }
        }
    }

    /**
     * Live-only index refresh for the Live TV guide. Unlike the full search
     * index it fetches ONLY the live catalog (no VOD/series) so it stays light
     * and never competes with channel playback for the server's connections.
     */
    fun scheduleLiveIndexSyncIfStale() {
        if (!isIndexStageStale(KEY_SEARCH_INDEX_LIVE_TIME)) return
        searchIndexScope.launch {
            val service = apiService ?: return@launch
            if (!searchIndexMutex.tryLock()) return@launch
            try {
                if (isIndexStageStale(KEY_SEARCH_INDEX_LIVE_TIME) && indexLiveCatalog(service)) {
                    markIndexStageFresh(KEY_SEARCH_INDEX_LIVE_TIME)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "Live index sync failed: ${e.javaClass.simpleName}")
            } finally {
                searchIndexMutex.unlock()
            }
        }
    }

    private fun isIndexStageStale(stageKey: String): Boolean {
        return System.currentTimeMillis() - syncPrefs.getLong(stageKey, 0L) >= SEARCH_INDEX_REFRESH_INTERVAL_MS
    }

    private fun markIndexStageFresh(stageKey: String) {
        syncPrefs.edit().putLong(stageKey, System.currentTimeMillis()).apply()
    }

    private suspend fun syncSearchIndex() {
        val service = apiService ?: return
        if (!searchIndexMutex.tryLock()) return
        try {
            // Log.i so it is visible on devices that suppress Log.d. Each stage
            // is gated separately: a stage whose fetch failed (or returned
            // empty on a panel that rejects uncategorized calls) retries on the
            // next app open without re-running the stages that succeeded.
            Log.i(TAG, "Search index sync started")
            if (isIndexStageStale(KEY_SEARCH_INDEX_VOD_TIME) && indexVodCatalog(service)) {
                markIndexStageFresh(KEY_SEARCH_INDEX_VOD_TIME)
            }
            if (isIndexStageStale(KEY_SEARCH_INDEX_SERIES_TIME) && indexSeriesCatalog(service)) {
                markIndexStageFresh(KEY_SEARCH_INDEX_SERIES_TIME)
            }
            if (isIndexStageStale(KEY_SEARCH_INDEX_LIVE_TIME) && indexLiveCatalog(service)) {
                markIndexStageFresh(KEY_SEARCH_INDEX_LIVE_TIME)
            }
            Log.i(TAG, "Search index sync finished")
        } finally {
            searchIndexMutex.unlock()
        }
    }

    private suspend fun indexVodCatalog(service: com.tvonnet.debridxtreamiptv.data.remote.XtreamApiService): Boolean {
        val dao = vodDao ?: return false
        suspend fun insertAll(streams: List<XtreamVodInfo>, categoryIdOf: (XtreamVodInfo) -> String) {
            streams.chunked(SEARCH_INDEX_CHUNK).forEach { chunk ->
                dao.insertMovies(chunk.map { it.toVodEntity(categoryIdOf(it)) })
            }
        }
        return try {
            val full = try {
                service.getVodStreams(username, password, categoryId = null)
                    .takeIf { it.isSuccessful }?.body().orEmpty()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                emptyList()
            }
            if (full.isNotEmpty()) {
                insertAll(full) { it.category_id ?: "" }
                Log.i(TAG, "Search index: vod full-catalog upserted ${full.size} movies")
                return true
            }
            // Some panels reject the uncategorized call — iterate categories.
            Log.i(TAG, "Search index: vod full fetch empty — per-category fallback")
            val categories = try {
                service.getVodCategories(username, password).takeIf { it.isSuccessful }?.body().orEmpty()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                emptyList()
            }
            if (categories.isEmpty()) return false
            var indexedAny = false
            var failures = 0
            for (category in categories) {
                val catId = category.category_id ?: continue
                val streams = try {
                    service.getVodStreams(username, password, categoryId = catId)
                        .takeIf { it.isSuccessful }?.body().orEmpty()
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    failures++
                    emptyList()
                }
                if (streams.isNotEmpty()) {
                    insertAll(streams) { catId }
                    indexedAny = true
                }
                delay(SEARCH_INDEX_CATEGORY_FETCH_DELAY_MS)
            }
            Log.i(TAG, "Search index: vod per-category done (categories=${categories.size}, failures=$failures)")
            indexedAny && failures < categories.size / 2
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "Search index: vod stage error: ${e.javaClass.simpleName}")
            false
        }
    }

    private suspend fun indexSeriesCatalog(service: com.tvonnet.debridxtreamiptv.data.remote.XtreamApiService): Boolean {
        val dao = seriesDao ?: return false
        suspend fun insertAll(streams: List<XtreamSeriesInfo>, categoryIdOf: (XtreamSeriesInfo) -> String) {
            streams.chunked(SEARCH_INDEX_CHUNK).forEach { chunk ->
                dao.insertSeries(chunk.map { it.toSeriesEntity(categoryIdOf(it)) })
            }
        }
        return try {
            val full = try {
                service.getSeries(username, password, categoryId = null)
                    .takeIf { it.isSuccessful }?.body().orEmpty()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                emptyList()
            }
            if (full.isNotEmpty()) {
                insertAll(full) { it.category_id ?: "" }
                Log.i(TAG, "Search index: series full-catalog upserted ${full.size} series")
                return true
            }
            Log.i(TAG, "Search index: series full fetch empty — per-category fallback")
            val categories = try {
                service.getSeriesCategories(username, password).takeIf { it.isSuccessful }?.body().orEmpty()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                emptyList()
            }
            if (categories.isEmpty()) return false
            var indexedAny = false
            var failures = 0
            for (category in categories) {
                val catId = category.category_id ?: continue
                val streams = try {
                    service.getSeries(username, password, categoryId = catId)
                        .takeIf { it.isSuccessful }?.body().orEmpty()
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    failures++
                    emptyList()
                }
                if (streams.isNotEmpty()) {
                    insertAll(streams) { catId }
                    indexedAny = true
                }
                delay(SEARCH_INDEX_CATEGORY_FETCH_DELAY_MS)
            }
            Log.i(TAG, "Search index: series per-category done (categories=${categories.size}, failures=$failures)")
            indexedAny && failures < categories.size / 2
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "Search index: series stage error: ${e.javaClass.simpleName}")
            false
        }
    }

    private suspend fun indexLiveCatalog(service: com.tvonnet.debridxtreamiptv.data.remote.XtreamApiService): Boolean {
        val cm = cacheManager ?: return false
        return try {
            val full = try {
                service.getLiveStreams(username, password, categoryId = null)
                    .takeIf { it.isSuccessful }?.body().orEmpty()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                emptyList()
            }
            if (full.isNotEmpty()) {
                // Insert-only-new under a synthetic category id — never touches
                // the lazy per-category rows (see CacheManager).
                cm.indexChannelsForSearch(full, "live")
                Log.i(TAG, "Search index: live full-catalog ${full.size} channels")
                return true
            }
            Log.i(TAG, "Search index: live full fetch empty — per-category fallback")
            val categories = try {
                service.getLiveCategories(username, password).takeIf { it.isSuccessful }?.body().orEmpty()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                emptyList()
            }
            if (categories.isEmpty()) return false
            var indexedAny = false
            var failures = 0
            for (category in categories) {
                val catId = category.category_id ?: continue
                val streams = try {
                    service.getLiveStreams(username, password, categoryId = catId)
                        .takeIf { it.isSuccessful }?.body().orEmpty()
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    failures++
                    emptyList()
                }
                if (streams.isNotEmpty()) {
                    cm.indexChannelsForSearch(streams, "live")
                    indexedAny = true
                }
                delay(SEARCH_INDEX_CATEGORY_FETCH_DELAY_MS)
            }
            Log.i(TAG, "Search index: live per-category done (categories=${categories.size}, failures=$failures)")
            indexedAny && failures < categories.size / 2
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "Search index: live stage error: ${e.javaClass.simpleName}")
            false
        }
    }

    // --- End Search Functionality ---
}
