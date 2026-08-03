package com.tvonnet.debridxtreamiptv.data.repository

import android.util.Log
import com.tvonnet.debridxtreamiptv.data.Result
import com.tvonnet.debridxtreamiptv.data.cache.CacheHelper
import com.tvonnet.debridxtreamiptv.data.cache.CacheManager
import com.tvonnet.debridxtreamiptv.data.local.dao.SeriesDao
import com.tvonnet.debridxtreamiptv.data.local.entity.toEpisodeEntity
import com.tvonnet.debridxtreamiptv.data.local.entity.toSeasonEntity
import com.tvonnet.debridxtreamiptv.data.local.entity.toSeriesEntity
import com.tvonnet.debridxtreamiptv.data.local.entity.toXtreamSeriesInfo
import com.tvonnet.debridxtreamiptv.data.local.relation.SeriesWithSeasonsAndEpisodes
import com.tvonnet.debridxtreamiptv.data.model.*
import com.tvonnet.debridxtreamiptv.data.model.SeriesCategoryState
import com.tvonnet.debridxtreamiptv.data.model.SeriesCategoryStatus
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import com.google.gson.JsonSyntaxException
import com.google.gson.JsonElement
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import retrofit2.HttpException
import kotlin.coroutines.coroutineContext

/**
 * Phase 7B-5: the Series data domain lifted out of the XtreamRepository god-class — the largest and
 * most interdependent slice. Category fetch + caching, per-category lists with the outage/cooldown/
 * confirmed-empty fallback chain and the seriesCategoryStatus state machine, series detail (network +
 * fallback + Room flow), all-series fallback, by-id resolve, episode playback status, and search. Reads
 * the live XtreamSession and shares the process-wide CatalogCache + CacheHelper. Behaviour is frozen by
 * SeriesOutageStatusTest / SeriesCategoryMatchingTest / SeriesCachePersistenceTest. Bodies moved verbatim.
 */
internal class SeriesRepository(
    private val session: XtreamSession,
    private val cacheManager: CacheManager,
    private val seriesDao: SeriesDao,
    private val catalogCache: CatalogCache,
    private val cacheHelper: CacheHelper
) {
    private val apiService get() = session.apiService
    private val username: String get() = session.username
    private val password: String get() = session.password

    /** SY-3: the bounded whole-catalog fallback, kept out of this class deliberately — see there. */
    private val fallbackCatalog = SeriesFallbackCatalog(cacheHelper)
    private var memoryCache: IptvCache?
        get() = catalogCache.memoryCache
        set(value) { catalogCache.memoryCache = value }
    private val seriesDetailCache get() = catalogCache.seriesDetailCache
    private val perCategorySeriesCache get() = catalogCache.perCategorySeriesCache
    private var allSeriesCacheFallback: List<XtreamSeriesInfo>?
        get() = catalogCache.allSeriesCacheFallback
        set(value) { catalogCache.allSeriesCacheFallback = value }
    private val seriesCategoryStatus get() = catalogCache.seriesCategoryStatus
    private fun readCache(): IptvCache? = catalogCache.readCache()
    private suspend fun prewarmCache(): IptvCache? = withContext(Dispatchers.IO) { readCache() }

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
                    cacheFetchedSeriesCategories(categories)
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

    private suspend fun cacheFetchedSeriesCategories(categories: List<XtreamCategory>) {
        try {
            cacheManager?.putCategories(categories, "series")
        } catch (e: Exception) {
            Log.w(TAG, "Failed to persist Series categories into CacheManager", e)
        }
        updateSeriesCategoriesCache(categories)
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

    suspend fun fetchSeriesCategoriesAndStreams(): Result<SeriesCacheData> {
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

    suspend fun fetchLatestSeriesStreams(categories: List<XtreamCategory>): List<XtreamSeriesInfo> {
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

        serveFreshSeriesFromDb(categoryId)?.let { return it }

        return try {
            if (apiService == null) {
                return Result.Error(Exception("API service not initialized"))
            }

        Log.d(TAG, "Fetching series for category: $categoryId")

            preloadSeriesFromLevel2Cache(categoryId)

            handleSeriesResponse(
                categoryId,
                apiService!!.getSeries(username, password, categoryId = categoryId)
            )
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

    // B-8: a fresh synced copy in Room is served as-is — no network refetch, no L2 preload
    // rewrite, no delete+insert of an unchanged category. Stale/empty (or a failed check)
    // returns null so the caller falls through to the existing network + fallback chain.
    private suspend fun serveFreshSeriesFromDb(categoryId: String): Result<List<XtreamSeriesInfo>>? {
        try {
            val freshness = seriesDao.getCategoryFreshness(categoryId)
            if (CategoryFetchFreshness.isFresh(
                    freshness.rowCount, freshness.newestCachedAt, System.currentTimeMillis()
                )
            ) {
                val cached = seriesDao.getSeriesByCategorySync(categoryId).map { it.toXtreamSeriesInfo() }
                if (cached.isNotEmpty()) {
                    perCategorySeriesCache[categoryId] = cached
                    Log.d(TAG, "B-8: category $categoryId fresh in DB (${cached.size} series), skipping refetch")
                    return Result.Success(cached)
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "B-8 freshness check failed for category $categoryId, falling through to network", e)
        }
        return null
    }

    // The network response arms, verbatim: a populated list caches + persists; an empty list
    // and a 404 both fall back rather than surfacing "no series"; other codes are errors.
    private suspend fun handleSeriesResponse(
        categoryId: String,
        response: retrofit2.Response<List<XtreamSeriesInfo>>
    ): Result<List<XtreamSeriesInfo>> {
        if (response.isSuccessful) {
            val streams = response.body() ?: emptyList()
            if (streams.isNotEmpty()) {
                Log.d(TAG, "Series fetched for category $categoryId: ${streams.size} series")
                updateSeriesCacheForCategory(categoryId, streams)

                // Phase 2: Save to Database
                saveSeriesPageToDb(categoryId, streams)
                markSeriesCategoryHealthy(categoryId)
                return Result.Success(streams)
            }
            return handleSeriesFallback(
                categoryId = categoryId,
                warningMessage = "Series category $categoryId returned empty list. Attempting fallback fetch.",
                failureState = SeriesCategoryState.EMPTY
            )
        }
        if (response.code() == 404) {
            return handleSeries404Fallback(categoryId)
        }
        val error = HttpException(response)
        updateSeriesCategoryStatus(
            categoryId,
            SeriesCategoryState.TEMPORARILY_UNAVAILABLE,
            "Failed to fetch series: ${response.code()}"
        )
        Log.e(TAG, "Failed to fetch series: ${response.code()}")
        return Result.Error(error)
    }

    // Level 2 Cache: Check if we have "All Series" cached and pre-load DB
    // This prevents the 10s delay if the category returns 404 but we already have the data
    private suspend fun preloadSeriesFromLevel2Cache(categoryId: String) {
        try {
            if (allSeriesCacheFallback == null) {
                allSeriesCacheFallback = cacheHelper.readAllSeries()
            }
            val allSeries = allSeriesCacheFallback ?: return
            val filtered = allSeries.filter { it.matchesCategory(categoryId) }
            if (filtered.isEmpty()) return
            Log.d(TAG, "⚡ Level 2 Cache HIT: Pre-loading ${filtered.size} series for category $categoryId")
            // Save to DB immediately so UI shows data while network call runs
            seriesDao?.let { dao ->
                val entities = filtered.map { it.toSeriesEntity(categoryId) }
                dao.replaceSeriesForCategory(categoryId, entities)
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to pre-load from Level 2 cache", e)
        }
    }

    private suspend fun saveSeriesPageToDb(categoryId: String, streams: List<XtreamSeriesInfo>) {
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
    }

    private suspend fun handleSeries404Fallback(categoryId: String): Result<List<XtreamSeriesInfo>> {
        Log.w(TAG, "Series category $categoryId returned 404. Attempting to fetch ALL series and filter.")

        // Try fetching all series
        val allResult = fetchAllSeries()
        if (allResult is Result.Success) {
            Log.d(TAG, "Filtering ${allResult.data.size} series for category $categoryId")
            // Removed: a leftover debug loop that logged the first 5 series on every 404
            // fallback. Same shape as the runBlocking DB counts deleted for B-4 — diagnostic
            // scaffolding that outlived the diagnosis and now runs on a user-facing path.
            val filtered = allResult.data.filter { it.matchesCategory(categoryId) }

            if (filtered.isNotEmpty()) {
                Log.d(TAG, "Found ${filtered.size} series for category $categoryId in ALL list")
                updateSeriesCacheForCategory(categoryId, filtered)

                // Save to DB
                saveFallbackSeriesToDb(categoryId, filtered)

                // Mark as healthy since we found data
                markSeriesCategoryHealthy(categoryId)
                return Result.Success(filtered)
            }
        }

        return handleSeriesFallback(
            categoryId = categoryId,
            warningMessage = "Series category $categoryId returned 404 and not found in ALL list. Attempting cache fallback.",
            failureState = SeriesCategoryState.TEMPORARILY_UNAVAILABLE
        )
    }

    private suspend fun saveFallbackSeriesToDb(categoryId: String, filtered: List<XtreamSeriesInfo>) {
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
            persistSeriesDetailToDb(seriesId, result.data, start)
        } else {
             Log.e("XtreamDebug", "syncSeriesDetail: Fetch failed")
        }
        return result
    }

    private suspend fun persistSeriesDetailToDb(
        seriesId: String,
        detail: XtreamSeriesDetailResponse,
        start: Long
    ) {
        if (seriesDao == null) return
        try {
            Log.d("XtreamDebug", "syncSeriesDetail: Saving to DB...")
            val seasonEntities = detail.seasons?.map { it.toSeasonEntity(seriesId) } ?: emptyList()
            val episodeEntities = buildEpisodeEntities(seriesId, detail)

            seriesDao.saveSeriesDetails(seriesId, seasonEntities, episodeEntities)
            Log.d("XtreamDebug", "✅ Series $seriesId details synced to database in ${System.currentTimeMillis() - start}ms")
        } catch (e: Exception) {
            Log.e("XtreamDebug", "❌ Failed to save series details to database. Data will still show in UI.", e)
        }
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
                 episodesMap = parseFallbackEpisodesJson(episodesResponse.body()!!)
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

    /** SY-3: bounded + disk-degrading, in [SeriesFallbackCatalog]. This owns only the memory cache. */
    private suspend fun fetchAllSeries(): Result<List<XtreamSeriesInfo>> {
        allSeriesCacheFallback?.let { return Result.Success(it) }
        val api = apiService ?: return Result.Error(Exception("API service not initialized"))
        return fallbackCatalog.fetchAll(api, username, password)
            .also { if (it is Result.Success) allSeriesCacheFallback = it.data }
    }
    
    suspend fun updateEpisodePlaybackStatus(episodeId: String, isWatched: Boolean, resumePosition: Long, duration: Long) {
        seriesDao?.updatePlaybackStatus(episodeId, isWatched, resumePosition, duration)
    }

    suspend fun searchSeries(query: String, categoryId: String? = null): List<XtreamSeriesInfo> {
        return seriesDao?.searchSeries(query, categoryId)?.map { it.toXtreamSeriesInfo() } ?: emptyList()
    }

    private companion object {
        private const val TAG = "XtreamRepository"
        private const val SERIES_CATEGORY_FAILURE_COOLDOWN_MS = 2 * 60 * 1000L
        private const val LATEST_SERIES_LIMIT = 24
        private const val SERIES_DETAIL_REQUEST_TIMEOUT_MS = 15_000L
    }
}

// Pure helpers for the series-detail sync path, deliberately outside the class: they touch no
// repository state, and SeriesRepository sits right at the LargeClass ceiling.

private fun buildEpisodeEntities(
    seriesId: String,
    detail: XtreamSeriesDetailResponse
): List<com.tvonnet.debridxtreamiptv.data.local.entity.EpisodeEntity> {
    val episodeEntities = mutableListOf<com.tvonnet.debridxtreamiptv.data.local.entity.EpisodeEntity>()
    detail.episodes?.forEach { entry ->
         val seasonKey = entry.key
         val episodes = entry.value
         val seasonNum = seasonKey.filter { it.isDigit() }.toIntOrNull() ?: 0
         episodes.forEach { episode ->
             episodeEntities.add(episode.toEpisodeEntity(seriesId, seasonNum))
         }
    }
    return episodeEntities
}

// Handle dynamic JSON (Map vs List)
private fun parseFallbackEpisodesJson(json: JsonElement): Map<String, List<XtreamEpisodeInfo>>? {
    var episodesMap: Map<String, List<XtreamEpisodeInfo>>? = null
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
    return episodesMap
}
