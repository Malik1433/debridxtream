package com.tvonnet.debridxtreamiptv.data.repository


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
import com.tvonnet.debridxtreamiptv.data.model.*
import com.tvonnet.debridxtreamiptv.data.model.SeriesCategoryStatus
import com.tvonnet.debridxtreamiptv.data.remote.XtreamApiService
import com.tvonnet.debridxtreamiptv.utils.PerformanceMonitor
import com.tvonnet.debridxtreamiptv.utils.memory.MemoryManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton
import com.tvonnet.debridxtreamiptv.data.local.relation.SeriesWithSeasonsAndEpisodes
import com.google.gson.Gson

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

    // Option A: how fresh a sync is, is a fact about ONE provider. Shared across servers it says
    // "synced a minute ago" about a catalogue this device has never fetched, and the app then skips
    // the sync that would fetch it.
    private val syncPrefs by lazy {
        com.tvonnet.debridxtreamiptv.data.prefs.ServerScopedPrefs.open(
            context,
            SYNC_PREFS_NAME,
            com.tvonnet.debridxtreamiptv.data.prefs.ServerScopedPrefs.activeFingerprint(context),
        )
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


    val syncProgress: StateFlow<SyncProgress> get() = syncManager.syncProgress
    
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
    
    // Phase 7B-6: full-catalog sync orchestrator moved to SyncManager; the repository delegates.
    suspend fun fetchAllAndCache(): Result<IptvCache> = syncManager.fetchAllAndCache()

    suspend fun syncInitialData(): Result<IptvCache> = syncManager.syncInitialData()

    suspend fun refreshCategoriesAndLatest(): Result<IptvCache> = syncManager.refreshCategoriesAndLatest()

    fun shouldRunBackgroundSync(): Boolean = syncManager.shouldRunBackgroundSync()
    
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
        seriesCategoryStatus.clear()
        cacheHelper.clearMemorySnapshot()
        Log.d(TAG, "Memory cache cleared (Legacy + CacheManager + per-category)")
    }

    /**
     * S2: every file this provider's catalogue was cached into, plus the stamps that say how fresh
     * it is. Owned here because the sync prefs and the CacheHelper are this class's private state.
     *
     * The stamps matter as much as the data: left behind they read "synced a minute ago" about a
     * provider that is gone, and the app then SKIPS the very sync that would repopulate it.
     */
    fun clearCatalogFilesAndSyncStamps() {
        cacheHelper.clearProviderFiles()
        syncPrefs.edit().clear().apply()
        Log.d(TAG, "Catalog cache files and sync stamps cleared")
    }
    
    suspend fun forceRefresh(): Result<IptvCache> = syncManager.forceRefresh()
    
    // ========================================
    // WEEK 10: FAVORITES METHODS
    // ========================================
    
    // Favorites moved to FavoritesRepository (Phase 7); XtreamRepository delegates its public
    // favorites API to it. Same FavoriteDao/FavoritesCache, behaviour unchanged.
    fun getAllFavorites(): Flow<List<FavoriteEntity>> = favoritesRepository.getAllFavorites()

    fun getFavoritesByType(type: String): Flow<List<FavoriteEntity>> =
        favoritesRepository.getFavoritesByType(type)

    suspend fun isFavorite(streamId: String): Boolean = favoritesRepository.isFavorite(streamId)

    suspend fun addFavorite(
        streamId: String,
        type: String,
        name: String,
        iconUrl: String? = null,
        source: String = com.tvonnet.debridxtreamiptv.data.local.entity.WatchedStateEntity.SOURCE_XTREAM,
    ) = favoritesRepository.addFavorite(streamId, type, name, iconUrl, source)

    suspend fun removeFavorite(streamId: String) = favoritesRepository.removeFavorite(streamId)

    suspend fun clearAllFavorites() = favoritesRepository.clearAllFavorites()

    /** S2: clear only one source's favourites — see [FavoritesRepository.addFavorite]. */
    suspend fun clearFavoritesForSource(source: String) =
        favoritesRepository.clearFavoritesForSource(source)

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
    private val syncManager = SyncManager(
        session,
        SyncManager.Caches(catalogCache, cacheHelper, cacheManager, syncPrefs),
        SyncManager.Domains(liveRepository, vodRepository, seriesRepository, searchIndexManager)
    )

    fun scheduleSearchIndexSyncIfStale() = searchIndexManager.scheduleSearchIndexSyncIfStale()

    fun scheduleLiveIndexSyncIfStale() = searchIndexManager.scheduleLiveIndexSyncIfStale()

    // --- End Search Functionality ---
}
