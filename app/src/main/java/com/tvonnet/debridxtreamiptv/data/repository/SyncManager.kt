package com.tvonnet.debridxtreamiptv.data.repository

import android.content.SharedPreferences
import android.util.Log
import com.tvonnet.debridxtreamiptv.data.Result
import com.tvonnet.debridxtreamiptv.data.cache.CacheHelper
import com.tvonnet.debridxtreamiptv.data.cache.CacheManager
import com.tvonnet.debridxtreamiptv.data.model.IptvCache
import com.tvonnet.debridxtreamiptv.data.model.LiveCacheData
import com.tvonnet.debridxtreamiptv.data.model.SeriesCacheData
import com.tvonnet.debridxtreamiptv.data.model.SyncProgress
import com.tvonnet.debridxtreamiptv.data.model.SyncState
import com.tvonnet.debridxtreamiptv.data.model.SyncType
import com.tvonnet.debridxtreamiptv.data.model.VodCacheData
import com.tvonnet.debridxtreamiptv.utils.PerformanceMonitor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Phase 7B-6: the full-catalog sync ORCHESTRATOR lifted out of the XtreamRepository god-class. It runs
 * the initial/background sync by fanning out to the Live/VOD/Series domain collaborators (each stage
 * latency-bounded), applies the SY-1 all-empty guard so a failed sync never overwrites a good cache with
 * empties, writes the IptvCache + warms CacheManager, and publishes the observable SyncProgress. It
 * reads the live XtreamSession, shares the process-wide CatalogCache + CacheHelper, and kicks the
 * background search index. Bodies moved verbatim (the domain calls already went through the collaborators).
 */
internal class SyncManager(
    private val session: XtreamSession,
    private val catalogCache: CatalogCache,
    private val cacheHelper: CacheHelper,
    private val cacheManager: CacheManager,
    private val syncPrefs: SharedPreferences,
    private val liveRepository: LiveRepository,
    private val vodRepository: VodRepository,
    private val seriesRepository: SeriesRepository,
    private val searchIndexManager: SearchIndexManager
) {
    private val syncMutex = Mutex()
    private val _syncProgress = MutableStateFlow(SyncProgress.idle())
    val syncProgress: StateFlow<SyncProgress> = _syncProgress.asStateFlow()

    private val apiService get() = session.apiService
    private var memoryCache: IptvCache?
        get() = catalogCache.memoryCache
        set(value) { catalogCache.memoryCache = value }
    private fun clearMemoryCache() = catalogCache.clearMemoryCache()
    private fun scheduleSearchIndexSyncIfStale() = searchIndexManager.scheduleSearchIndexSyncIfStale()

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

    suspend fun forceRefresh(): Result<IptvCache> {
        // Clear memory cache before fetching new data
        clearMemoryCache()
        return refreshCategoriesAndLatest()
    }

    private companion object {
        private const val TAG = "XtreamRepository"
        private const val KEY_LAST_SYNC_TIME = "last_sync_time"
        private const val KEY_LAST_SYNC_TYPE = "last_sync_type"
        private const val BACKGROUND_SYNC_SKIP_AFTER_INITIAL_MS = 2 * 60 * 1000L
        private const val SYNC_STAGE_TIMEOUT_MS = 25_000L
    }
}
