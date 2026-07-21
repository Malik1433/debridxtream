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
    private val seriesRepository = SeriesRepository(session, cacheManager, seriesDao, catalogCache, cacheHelper)


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

                    val seriesResult = withTimeoutOrNull(SYNC_STAGE_TIMEOUT_MS) { seriesRepository.fetchSeriesCategoriesAndStreams() }
                    val seriesData = seriesResult?.getOrNull() ?: SeriesCacheData(emptyList(), emptyList())
                    val seriesStreams = if (includeLatest) {
                        seriesRepository.fetchLatestSeriesStreams(seriesData.categories)
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

    // Phase 7B-5: Series domain moved to SeriesRepository; the repository delegates its public API.
    // fetchSeriesCategoriesAndStreams/fetchLatestSeriesStreams stay reachable for syncContent via seriesRepository.
    suspend fun ensureSeriesCategories(): List<XtreamCategory> = seriesRepository.ensureSeriesCategories()

    suspend fun getMovieSources(
        streamId: String?,
        title: String?,
        primaryCategoryId: String?,
        yearHint: String?
    ): List<MovieSource> = vodRepository.getMovieSources(streamId, title, primaryCategoryId, yearHint)

    suspend fun fetchSeriesForCategory(categoryId: String): Result<List<XtreamSeriesInfo>> =
        seriesRepository.fetchSeriesForCategory(categoryId)

    fun getSeriesCategoryStatus(categoryId: String): SeriesCategoryStatus? =
        seriesRepository.getSeriesCategoryStatus(categoryId)

    suspend fun refreshSeriesCategories(): Result<List<XtreamCategory>> =
        seriesRepository.refreshSeriesCategories()

    suspend fun fetchSeriesDetail(seriesId: String, forceRefresh: Boolean = false): Result<XtreamSeriesDetailResponse> =
        seriesRepository.fetchSeriesDetail(seriesId, forceRefresh)

    fun getSeriesDetailFlow(seriesId: String): Flow<SeriesWithSeasonsAndEpisodes?> =
        seriesRepository.getSeriesDetailFlow(seriesId)

    suspend fun syncSeriesDetail(seriesId: String): Result<XtreamSeriesDetailResponse> =
        seriesRepository.syncSeriesDetail(seriesId)


    
    
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
    suspend fun getSeriesById(streamId: String): XtreamSeriesInfo? = seriesRepository.getSeriesById(streamId)
    
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
    suspend fun updateEpisodePlaybackStatus(episodeId: String, isWatched: Boolean, resumePosition: Long, duration: Long) =
        seriesRepository.updateEpisodePlaybackStatus(episodeId, isWatched, resumePosition, duration)


    
    companion object {
        private const val TAG = "XtreamRepository"
        private const val DEFAULT_EPG_REFRESH_INTERVAL_MS = 6 * 60 * 60 * 1000L
        private const val SYNC_PREFS_NAME = "sync_prefs"
        private const val KEY_LAST_SYNC_TIME = "last_sync_time"
        private const val KEY_LAST_SYNC_TYPE = "last_sync_type"
        private const val BACKGROUND_SYNC_SKIP_AFTER_INITIAL_MS = 2 * 60 * 1000L
        // SY-2: per-stage bound for the initial sync — a hung provider on one stage no
        // longer parks the user for the raw ~30s socket timeout ×3 (up to ~90-180s).
        private const val SYNC_STAGE_TIMEOUT_MS = 25_000L
        // Phase 2: per-request bound so a hung provider can't stall the series-detail
        // screen. On timeout we fall through to the fallback fetch instead of blocking.
    }
    // --- Search Functionality ---

    suspend fun searchSeries(query: String, categoryId: String? = null): List<XtreamSeriesInfo> =
        seriesRepository.searchSeries(query, categoryId)

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
