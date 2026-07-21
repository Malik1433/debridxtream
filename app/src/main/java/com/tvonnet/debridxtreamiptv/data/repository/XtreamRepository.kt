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
import com.tvonnet.debridxtreamiptv.utils.PerformanceMonitor
import com.tvonnet.debridxtreamiptv.utils.memory.MemoryManager
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
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
import kotlinx.coroutines.withTimeoutOrNull
import retrofit2.HttpException
import java.util.LinkedHashMap
import java.util.LinkedHashSet
import java.util.Locale
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
    // Phase 7: provider session (creds + built service + URL) extracted into XtreamSession. The four
    // former fields are now read-only views onto it, so every existing read site is unchanged.
    private val session = XtreamSession(context)
    // @get:JvmName avoids a JVM platform clash with the public getApiService()/getUsername()/
    // getPassword() functions below (a Kotlin `val apiService` would otherwise also emit getApiService()).
    @get:JvmName("apiServiceView")
    private val apiService: XtreamApiService? get() = session.apiService
    @get:JvmName("usernameView")
    private val username: String get() = session.username
    @get:JvmName("passwordView")
    private val password: String get() = session.password
    private val baseUrl: String get() = session.baseUrl

    // V2 Rescue Patch: Expose service for V2 components
    fun getApiService(): XtreamApiService? = session.apiService
    fun getUsername(): String = session.username
    fun getPassword(): String = session.password
    fun getServerUrl(): String = session.baseUrl

    private val cacheHelper = CacheHelper(context)
    private val favoritesRepository = FavoritesRepository(favoriteDao, favoritesCache)
    private val searchHistoryRepository = SearchHistoryRepository(searchHistoryDao)
    private val epgQueryRepository = EpgQueryRepository(epgDao)
    private val epgSyncManager = EpgSyncManager(session, epgDao, memoryManager, context)

    private val syncPrefs by lazy {
        context.getSharedPreferences(SYNC_PREFS_NAME, Context.MODE_PRIVATE)
    }

    // Phase 7B-0: the shared catalog caches (the glue across Sync/Live/VOD/Series) live in CatalogCache.
    // The repository keeps get/set views onto them so every existing read/write site is unchanged.
    private val catalogCache = CatalogCache(cacheHelper, cacheManager)
    private var memoryCache: IptvCache?
        get() = catalogCache.memoryCache
        set(value) { catalogCache.memoryCache = value }
    private val seriesDetailCache get() = catalogCache.seriesDetailCache
    private val perCategorySeriesCache get() = catalogCache.perCategorySeriesCache
    private var allSeriesCacheFallback: List<XtreamSeriesInfo>?
        get() = catalogCache.allSeriesCacheFallback
        set(value) { catalogCache.allSeriesCacheFallback = value }
    private val perCategoryVodCache get() = catalogCache.perCategoryVodCache
    private val vodFetchLocks get() = catalogCache.vodFetchLocks
    private val seriesCategoryStatus get() = catalogCache.seriesCategoryStatus
    private val liveRepository = LiveRepository(session, cacheManager, epgDao, catalogCache, cacheHelper)
    private val vodRepository = VodRepository(session, cacheManager, vodDao, catalogCache, cacheHelper)


    private val syncMutex = Mutex()
    private val _syncProgress = MutableStateFlow(SyncProgress.idle())
    val syncProgress: StateFlow<SyncProgress> = _syncProgress.asStateFlow()
    
    // Phase 4: @Synchronized so concurrent first-callers (MainActivity + HomeFragment + every
    // browse ViewModel all call initialize()) can't both pass the idempotency guard while
    // apiService is still null and rebuild the whole Retrofit/OkHttp stack twice (a race that
    // could also leave apiService/baseUrl briefly inconsistent). The section is short and, after
    // the first init, returns immediately — so it stays safe to call from the main thread.
    @Synchronized
    fun initialize(baseUrl: String, username: String, password: String) {
        // ST-3: idempotency + the Retrofit/OkHttp rebuild + GlobalConfig publish now live in
        // XtreamSession; it returns true only on a real (re)build so the repository-side post-init
        // hooks below run exactly when the old code ran them (inside the same try, never on skip).
        session.initialize(baseUrl, username, password) {
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
        }
    }

    fun ensureInitialized(serverUrl: String?, username: String?, password: String?) {
        if (serverUrl.isNullOrBlank() || username.isNullOrBlank() || password.isNullOrBlank()) return
        // Already built for exactly these credentials -> nothing to do (fast path, no lock).
        if (session.matches(serverUrl, username, password)) return
        // Bug fix (Phase 7): previously this only ran when NOT initialized, so calling it with a
        // DIFFERENT provider's credentials was a silent no-op and the app kept the stale session.
        // Now a credential change (re-login / provider switch) actually rebuilds the session.
        // Same-credential callers are still cheap: initialize()'s idempotency guard returns immediately.
        initialize(serverUrl, username, password)
    }

    fun isInitialized(): Boolean = session.isInitialized()
    
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

                    // SY-2: bound each stage so one hung provider stage can't stall the
                    // whole sync. A timeout falls to empty for that stage; if ALL stages
                    // come back empty, the SY-1 all-empty guard below reports it honestly.
                    val liveResult = withTimeoutOrNull(SYNC_STAGE_TIMEOUT_MS) { liveRepository.fetchLiveCategoriesAndStreams() }
                    val liveData = liveResult?.getOrNull() ?: LiveCacheData(emptyList(), emptyList())
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

                    val vodResult = withTimeoutOrNull(SYNC_STAGE_TIMEOUT_MS) { vodRepository.fetchVodCategoriesAndStreams() }
                    val vodData = vodResult?.getOrNull() ?: VodCacheData(emptyList(), emptyList())
                    val vodStreams = if (includeLatest) {
                        vodRepository.fetchLatestVodStreams(vodData.categories)
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

                    val seriesResult = withTimeoutOrNull(SYNC_STAGE_TIMEOUT_MS) { fetchSeriesCategoriesAndStreams() }
                    val seriesData = seriesResult?.getOrNull() ?: SeriesCacheData(emptyList(), emptyList())
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

                    // SY-1: the stage fetchers swallow network/auth failures into empty
                    // results (no throw), so a totally-failed sync would otherwise write an
                    // EMPTY cache and report SUCCESS — the user lands on Home with "No
                    // categories". Zero categories across ALL three types is a strong
                    // failure signal (a real account always has some). In that case do NOT
                    // overwrite a good cache: keep the existing one, or report ERROR so the
                    // sync screen offers a retry instead of a silent empty success.
                    val allCategoriesEmpty = liveData.categories.isEmpty() &&
                        vodData.categories.isEmpty() &&
                        seriesData.categories.isEmpty()
                    if (allCategoriesEmpty) {
                        Log.w(TAG, "Sync produced zero categories across all types — treating as failure, not overwriting cache")
                        val existing = memoryCache ?: cacheHelper.readCache()
                        val existingHasContent = existing != null && (
                            (existing.live?.categories?.isNotEmpty() == true) ||
                            (existing.vod?.categories?.isNotEmpty() == true) ||
                            (existing.series?.categories?.isNotEmpty() == true)
                        )
                        if (existingHasContent) {
                            updateSyncProgress(
                                type = syncType,
                                state = SyncState.SUCCESS,
                                percent = 100,
                                liveCount = existing!!.live?.streams?.size ?: 0,
                                vodCount = existing.vod?.streams?.size ?: 0,
                                seriesCount = existing.series?.streams?.size ?: 0,
                                stage = "Using cached data"
                            )
                            return@withContext Result.Success(existing)
                        }
                        updateSyncProgress(
                            type = syncType,
                            state = SyncState.ERROR,
                            percent = 0,
                            liveCount = 0,
                            vodCount = 0,
                            seriesCount = 0,
                            stage = "Sync failed",
                            errorMessage = "Could not load your content. Check your connection and try again."
                        )
                        return@withContext Result.Error(Exception("Sync returned no categories (network/auth failure)"))
                    }

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
    
    // Phase 7B-3: Live TV domain moved to LiveRepository; the repository delegates its public API.
    // fetchLiveCategoriesAndStreams stays reachable for syncContent via liveRepository (below).
    suspend fun ensureLiveCategories(): List<XtreamCategory> = liveRepository.ensureLiveCategories()

    suspend fun fetchLiveStreamsForCategory(categoryId: String): Result<List<XtreamStream>> =
        liveRepository.fetchLiveStreamsForCategory(categoryId)

    suspend fun fetchLiveStreamsForCategoryStrict(categoryId: String): Result<List<XtreamStream>> =
        liveRepository.fetchLiveStreamsForCategoryStrict(categoryId)

    suspend fun getCachedLiveChannelCount(categoryId: String): Int? =
        liveRepository.getCachedLiveChannelCount(categoryId)

    suspend fun getCachedLiveStreams(categoryId: String): List<XtreamStream>? =
        liveRepository.getCachedLiveStreams(categoryId)

    suspend fun enrichChannelsWithCurrentEpg(channels: List<XtreamStream>): List<XtreamStream> =
        liveRepository.enrichChannelsWithCurrentEpg(channels)

    suspend fun getCurrentProgramsByEpgId(epgChannelIds: List<String>): Map<String, EpgEntity> =
        liveRepository.getCurrentProgramsByEpgId(epgChannelIds)

    suspend fun getProgramsByEpgIdInRange(
        epgChannelIds: List<String>,
        startTime: Long,
        endTime: Long
    ): Map<String, List<EpgEntity>> = liveRepository.getProgramsByEpgIdInRange(epgChannelIds, startTime, endTime)

    // Phase 7B-4: VOD (movies) domain moved to VodRepository; the repository delegates its public API.
    // fetchVodCategoriesAndStreams/fetchLatestVodStreams stay reachable for syncContent via vodRepository.
    suspend fun fetchVodStreamsForCategory(categoryId: String): Result<List<XtreamVodInfo>> =
        vodRepository.fetchVodStreamsForCategory(categoryId)

    suspend fun ensureVodCategories(): List<XtreamCategory> = vodRepository.ensureVodCategories()

    suspend fun ensureSeriesCategories(): List<XtreamCategory> {
        cacheManager?.getCategories("series")?.let { cached ->
            if (cached.isNotEmpty()) {
                return cached
            }
        }

        val cacheSeriesCategories = prewarmCache()?.series?.categories.orEmpty()
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

    private suspend fun updateSeriesCategoriesCache(categories: List<XtreamCategory>) {
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
            withContext(Dispatchers.IO) { cacheHelper.writeCache(updatedCache) }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to persist Series categories to cache", e)
        }
    }

    suspend fun getMovieSources(
        streamId: String?,
        title: String?,
        primaryCategoryId: String?,
        yearHint: String?
    ): List<MovieSource> = vodRepository.getMovieSources(streamId, title, primaryCategoryId, yearHint)

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
                    val filtered = allSeries.filter { it.matchesCategory(categoryId) }

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

                    val filtered = allResult.data.filter { it.matchesCategory(categoryId) }

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
            val response = withTimeoutOrNull(SERIES_DETAIL_REQUEST_TIMEOUT_MS) {
                apiService!!.getSeriesInfo(username, password, seriesId = seriesId)
            } ?: run {
                Log.w("XtreamDebug", "Series detail $seriesId timed out after ${SERIES_DETAIL_REQUEST_TIMEOUT_MS}ms; trying fallback")
                return fetchFallbackSeriesDetail(seriesId)
            }
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


    
    
    fun readCache(): IptvCache? {
        // Return from memory cache if available (avoid repeated Gson parsing)
        if (memoryCache != null) {
            return memoryCache
        }

        // Otherwise read from file and cache in memory
        memoryCache = cacheHelper.readCache()
        return memoryCache
    }

    /**
     * ST-1: parse the on-disk catalog OFF the main thread and warm [memoryCache].
     * Home calls this before its synchronous readCache() so the multi-MB Gson parse
     * never runs on the UI thread. Signature-safe: readCache() itself is unchanged,
     * so all 20+ existing callers keep working — they just hit a warm memoryCache.
     */
    suspend fun prewarmCache(): IptvCache? =
        withContext(Dispatchers.IO) { readCache() }

    /**
     * Cheap "is there cache to show" check for the Home collect-condition —
     * in-memory hit or a file stat, never a main-thread Gson parse (ST-1).
     */
    fun hasCache(): Boolean = memoryCache != null || cacheHelper.hasCacheFile()
    
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
    
    // Favorites moved to FavoritesRepository (Phase 7); XtreamRepository delegates its public
    // favorites API to it. Same FavoriteDao/FavoritesCache, behaviour unchanged.
    fun getAllFavorites(): Flow<List<FavoriteEntity>> = favoritesRepository.getAllFavorites()

    fun getFavoritesByType(type: String): Flow<List<FavoriteEntity>> =
        favoritesRepository.getFavoritesByType(type)

    suspend fun isFavorite(streamId: String): Boolean = favoritesRepository.isFavorite(streamId)

    suspend fun addFavorite(streamId: String, type: String, name: String, iconUrl: String? = null) =
        favoritesRepository.addFavorite(streamId, type, name, iconUrl)

    suspend fun removeFavorite(streamId: String) = favoritesRepository.removeFavorite(streamId)

    suspend fun clearAllFavorites() = favoritesRepository.clearAllFavorites()

    // ========== Search History Operations ==========
    // Moved to SearchHistoryRepository (Phase 7); XtreamRepository delegates. Same DAO, behaviour unchanged.

    fun getRecentSearches(limit: Int = 10): Flow<List<SearchHistoryEntity>> =
        searchHistoryRepository.getRecentSearches(limit)

    suspend fun saveSearchQuery(query: String) = searchHistoryRepository.saveSearchQuery(query)

    suspend fun removeSearchQuery(query: String) = searchHistoryRepository.removeSearchQuery(query)

    suspend fun clearSearchHistory() = searchHistoryRepository.clearSearchHistory()

    // ========================================
    // Week 11: EPG OPERATIONS
    // ========================================
    
    /**
     * Fetch and save EPG data from Xtream API
     * Parses XML and stores in Room database
     */
    suspend fun fetchAndSaveEpg(): Result<Int> = epgSyncManager.fetchAndSaveEpg()

    /**
     * Ensure EPG data exists locally and is fresh enough for UI usage.
     * Returns true if a new sync was performed.
     */
    suspend fun ensureEpgData(
        force: Boolean = false,
        maxAgeMs: Long = DEFAULT_EPG_REFRESH_INTERVAL_MS
    ): Boolean = epgSyncManager.ensureEpgData(force, maxAgeMs)
    
    // EPG read/query methods moved to EpgQueryRepository (Phase 7); repository delegates. epgDao-only,
    // behaviour unchanged. (Network EPG fetch/sync stays below — it needs apiService + credentials.)
    fun getEpgByChannel(channelId: String): Flow<List<EpgEntity>>? =
        epgQueryRepository.getEpgByChannel(channelId)

    suspend fun getCurrentProgram(channelId: String): EpgEntity? =
        epgQueryRepository.getCurrentProgram(channelId)

    suspend fun getNextProgram(channelId: String): EpgEntity? =
        epgQueryRepository.getNextProgram(channelId)

    /**
     * Lightweight per-channel EPG (now/next) using Xtream `get_short_epg`.
     * Safer on low-memory TV devices than downloading/parsing full XMLTV.
     */
    suspend fun fetchShortEpgPrograms(
        streamId: String,
        channelKey: String,
        limit: Int = 12
    ): Result<List<EpgEntity>> = epgSyncManager.fetchShortEpgPrograms(streamId, channelKey, limit)

    suspend fun fetchShortEpgNowNext(
        streamId: String,
        channelKey: String,
        limit: Int = 2
    ): Result<Pair<EpgEntity?, EpgEntity?>> = epgSyncManager.fetchShortEpgNowNext(streamId, channelKey, limit)

    // XtreamEpgListing.toEpgEntityOrNull / decodeBase64IfPossible moved to EpgMapping.kt (Phase 7) —
    // pure leaf-mappers, same package, resolved by wildcard import. Behaviour unchanged.

    fun getUpcomingPrograms(channelId: String): Flow<List<EpgEntity>>? =
        epgQueryRepository.getUpcomingPrograms(channelId)

    suspend fun hasEpgData(channelId: String): Boolean = epgQueryRepository.hasEpgData(channelId)

    suspend fun clearOldEpg() = epgQueryRepository.clearOldEpg()
    
    // ========== Week 12: Stream Lookup for Favorites Playback ==========
    
    /**
     * Get live stream by stream ID from cache
     * Week 12: Used for favorites playback
     */
    suspend fun getLiveStreamById(streamId: String): XtreamStream? = liveRepository.getLiveStreamById(streamId)
    
    /**
     * Get VOD stream by stream ID from cache
     * Week 12: Used for favorites playback
     */
    suspend fun getVodById(streamId: String): XtreamVodInfo? = vodRepository.getVodById(streamId)
    
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
            ?: withContext(Dispatchers.IO) { cacheHelper.readCache() }?.also { memoryCache = it }
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
        // NOTE: preserve the original `${vod.stream_id}` template semantics — a null id renders
        // the literal "null" (not ""), so keep .toString() here rather than orEmpty().
        return buildVodStreamUrl(baseServerUrl, username, password, vod.stream_id.toString(), vod.container_extension)
    }

    fun buildSeriesEpisodeStreamUrl(episodeId: String, containerExtension: String?, baseServerUrl: String): String {
        return buildSeriesEpisodeStreamUrl(baseServerUrl, username, password, episodeId, containerExtension)
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

        val updatedCache = existingCache.withSeriesData(updatedSeriesData, System.currentTimeMillis())

        memoryCache = updatedCache
        withContext(Dispatchers.IO) { cacheHelper.writeCache(updatedCache) }
        cacheManager?.putCategories(categories, "series")
        
        // Clear per-category cache so it can repopulate with fresh series data
        perCategorySeriesCache.clear()
        allSeriesCacheFallback = null
    }
    
    /**
     * Update per-category series cache
     * Called after fetching series for a category
     */
    private suspend fun updateSeriesCacheForCategory(categoryId: String, series: List<XtreamSeriesInfo>) {
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
            val filtered = cachedAll.filter { it.matchesCategory(categoryId) }
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
                
                val filtered = cachedStreams.filter { it.matchesCategory(categoryId) }

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
                val filtered = allSeriesResult.data.filter { it.matchesCategory(categoryId) }
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

    private suspend fun persistSeriesStreamsForCategory(categoryId: String, series: List<XtreamSeriesInfo>) {
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

        val updatedCache = existingCache.withSeriesData(updatedSeriesData, System.currentTimeMillis())

        memoryCache = updatedCache
        // Do NOT update allSeriesCacheFallback here. It should only be updated when we fetch ALL series.
        // allSeriesCacheFallback = updatedSeriesData.streams
        withContext(Dispatchers.IO) { cacheHelper.writeCache(updatedCache) }
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


    
    companion object {
        private const val TAG = "XtreamRepository"
        private const val DEFAULT_EPG_REFRESH_INTERVAL_MS = 6 * 60 * 60 * 1000L
        private const val SERIES_CATEGORY_FAILURE_COOLDOWN_MS = 2 * 60 * 1000L
        private const val LATEST_SERIES_LIMIT = 24
        private const val SYNC_PREFS_NAME = "sync_prefs"
        private const val KEY_LAST_SYNC_TIME = "last_sync_time"
        private const val KEY_LAST_SYNC_TYPE = "last_sync_type"
        private const val BACKGROUND_SYNC_SKIP_AFTER_INITIAL_MS = 2 * 60 * 1000L
        // SY-2: per-stage bound for the initial sync — a hung provider on one stage no
        // longer parks the user for the raw ~30s socket timeout ×3 (up to ~90-180s).
        private const val SYNC_STAGE_TIMEOUT_MS = 25_000L
        // Phase 2: per-request bound so a hung provider can't stall the series-detail
        // screen. On timeout we fall through to the fallback fetch instead of blocking.
        private const val SERIES_DETAIL_REQUEST_TIMEOUT_MS = 15_000L
    }
    // --- Search Functionality ---

    suspend fun searchSeries(query: String, categoryId: String? = null): List<XtreamSeriesInfo> {
        return seriesDao?.searchSeries(query, categoryId)?.map { it.toXtreamSeriesInfo() } ?: emptyList()
    }

    suspend fun searchVod(query: String, categoryId: String? = null): List<XtreamVodInfo> =
        vodRepository.searchVod(query, categoryId)

    suspend fun searchLive(query: String): List<XtreamStream> = liveRepository.searchLive(query)

    suspend fun getAllLiveChannels(): List<XtreamStream> = liveRepository.getAllLiveChannels()

    suspend fun countAllLiveChannels(): Int = liveRepository.countAllLiveChannels()

    suspend fun searchEpg(query: String): List<com.tvonnet.debridxtreamiptv.data.local.entity.EpgEntity> =
        epgQueryRepository.searchEpg(query)

    // Phase 7B-1: the background search-index sync moved to SearchIndexManager. The repository
    // delegates the two externally-called entry points; the manager reads the live session, the
    // shared sync prefs, and the DAOs. Declared here (after syncPrefs) so its deps are initialised.
    private val searchIndexManager =
        SearchIndexManager(session, syncPrefs, vodDao, seriesDao, cacheManager)

    fun scheduleSearchIndexSyncIfStale() = searchIndexManager.scheduleSearchIndexSyncIfStale()

    fun scheduleLiveIndexSyncIfStale() = searchIndexManager.scheduleLiveIndexSyncIfStale()

    // --- End Search Functionality ---
}
