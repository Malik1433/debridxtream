package com.tvonnet.debridxtreamiptv.data.repository

import android.util.Log
import com.tvonnet.debridxtreamiptv.data.Result
import com.tvonnet.debridxtreamiptv.data.cache.CacheHelper
import com.tvonnet.debridxtreamiptv.data.cache.CacheManager
import com.tvonnet.debridxtreamiptv.data.local.dao.EpgDao
import com.tvonnet.debridxtreamiptv.data.local.entity.EpgEntity
import com.tvonnet.debridxtreamiptv.data.model.IptvCache
import com.tvonnet.debridxtreamiptv.data.model.LiveCacheData
import com.tvonnet.debridxtreamiptv.data.model.XtreamCategory
import com.tvonnet.debridxtreamiptv.data.model.XtreamStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import retrofit2.HttpException

/**
 * Phase 7B-3: the Live TV data domain lifted out of the XtreamRepository god-class. It fetches live
 * categories + per-category channel lists (multi-level cached via CacheManager), enriches channels with
 * their now-airing EPG programme, and answers the guide's batch/now-next/range EPG lookups and the
 * live-channel search/count. It reads the live XtreamSession for the service + credentials and shares
 * the process-wide CatalogCache (memoryCache) with the other domains. Bodies moved verbatim.
 */
internal class LiveRepository(
    private val session: XtreamSession,
    private val cacheManager: CacheManager,
    private val epgDao: EpgDao,
    private val catalogCache: CatalogCache,
    private val cacheHelper: CacheHelper
) {
    private val apiService get() = session.apiService
    private val username: String get() = session.username
    private val password: String get() = session.password
    private var memoryCache: IptvCache?
        get() = catalogCache.memoryCache
        set(value) { catalogCache.memoryCache = value }
    private fun readCache(): IptvCache? = catalogCache.readCache()
    private suspend fun prewarmCache(): IptvCache? = withContext(Dispatchers.IO) { readCache() }

    suspend fun fetchLiveCategoriesAndStreams(): Result<LiveCacheData> {
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

        val cacheLiveCategories = prewarmCache()?.live?.categories.orEmpty()
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
                    cacheFetchedLiveCategories(categories)
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

    private suspend fun cacheFetchedLiveCategories(categories: List<XtreamCategory>) {
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
        withContext(Dispatchers.IO) { cacheHelper.writeCache(updatedCache) }
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

    suspend fun getLiveStreamById(streamId: String): XtreamStream? {
        val cache = memoryCache
            ?: cacheHelper.memorySnapshot()?.also { memoryCache = it }
            ?: withContext(Dispatchers.IO) { cacheHelper.readCache() }?.also { memoryCache = it }
        val memoryMatch = cache?.live?.streams?.find { it.stream_id == streamId }
        if (memoryMatch != null) return memoryMatch

        // Fallback: Check Room via CacheManager
        return cacheManager.getChannelById(streamId)
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

    private companion object {
        private const val TAG = "XtreamRepository"
    }
}
