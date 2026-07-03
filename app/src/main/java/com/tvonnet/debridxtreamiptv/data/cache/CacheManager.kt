package com.tvonnet.debridxtreamiptv.data.cache

import android.util.Log
import androidx.collection.LruCache
import com.tvonnet.debridxtreamiptv.data.local.dao.CategoryDao
import com.tvonnet.debridxtreamiptv.data.local.dao.ChannelDao
import com.tvonnet.debridxtreamiptv.data.local.entity.CategoryEntity
import com.tvonnet.debridxtreamiptv.data.local.entity.toChannelEntity
import com.tvonnet.debridxtreamiptv.data.local.entity.toXtreamStream
import com.tvonnet.debridxtreamiptv.data.local.entity.toXtreamCategory
import com.tvonnet.debridxtreamiptv.data.model.XtreamCategory
import com.tvonnet.debridxtreamiptv.data.model.XtreamStream
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Week 7: Multi-Level Caching Strategy
 * 
 * CacheManager implements 3-level caching:
 * - Level 1: Memory Cache (LruCache) - Fastest, limited to 10MB
 * - Level 2: Room Database - Fast, persistent storage
 * - Level 3: Network - Slowest, fetched on cache miss
 * 
 * This significantly improves performance by:
 * 1. Reducing database queries (memory cache)
 * 2. Reducing network calls (Room database)
 * 3. Providing offline capability
 */
@Singleton
class CacheManager @Inject constructor(
    private val channelDao: ChannelDao,
    private val categoryDao: CategoryDao
) {
    companion object {
        private const val TAG = "CacheManager"
        const val SEARCH_INDEX_CATEGORY_ID = "search_index"
        private const val MEMORY_CACHE_SIZE = 10 * 1024 * 1024 // 10MB
        
        // Cache keys
        private const val KEY_CHANNELS_PREFIX = "channels_"
        private const val KEY_CHANNEL_COUNT_PREFIX = "channel_count_"
        private const val KEY_CATEGORIES_PREFIX = "categories_"
        private const val KEY_ALL_CHANNELS = "all_channels"
    }
    
    // Level 1: Memory cache (fastest)
    // LruCache automatically manages memory by evicting least recently used items
    private val memoryCache = object : LruCache<String, Any>(MEMORY_CACHE_SIZE) {
        override fun sizeOf(key: String, value: Any): Int {
            // Rough estimate: 1KB per item
            return when (value) {
                is List<*> -> value.size * 1024
                else -> 1024
            }
        }
    }
    
    /**
     * Get channels by category with 3-level caching + TTL (Week 8)
     * 
     * Flow:
     * 1. Try memory cache (instant) - check TTL
     * 2. Try Room database (fast) - check TTL
     * 3. Return null if not found or expired (caller should fetch from network)
     */
    suspend fun getChannels(categoryId: String, streamType: String = "live"): List<XtreamStream>? {
        val cacheKey = "$KEY_CHANNELS_PREFIX${categoryId}_$streamType"
        
        // Level 1: Try memory cache first
        memoryCache.get(cacheKey)?.let { cached ->
            @Suppress("UNCHECKED_CAST")
            val cachedData = cached as? CachedData<List<XtreamStream>>
            if (cachedData != null) {
                // Week 8: Check if cached data is still fresh
                if (cachedData.isFresh()) {
                    Log.d(TAG, "✅ Cache HIT (Memory): $categoryId - ${cachedData.data.size} channels (Age: ${cachedData.getAge() / 1000}s)")
                    return cachedData.data
                } else {
                    Log.d(TAG, "⚠️ Cache EXPIRED (Memory): $categoryId - Removing stale data")
                    memoryCache.remove(cacheKey)
                }
            }
        }
        
        // Level 2: Try Room database (Week 8: with TTL checking)
        try {
            // Calculate expiry timestamp (24 hours ago for channels)
            val expiryTimestamp = System.currentTimeMillis() - CachedData.TTL_24_HOURS
            
            // Get only non-expired channels
            var entities = channelDao.getChannelsByCategoryNotExpired(categoryId, expiryTimestamp)
            
            // Week 14: Fallback to stale data if fresh data is missing (Instant Load)
            if (entities.isEmpty()) {
                Log.d(TAG, "⚠️ Fresh cache missing for $categoryId, checking for stale data...")
                entities = channelDao.getChannelsByCategory(categoryId)
                if (entities.isNotEmpty()) {
                    Log.d(TAG, "✅ Stale Cache HIT (Room): $categoryId - ${entities.size} channels (Expired but usable)")
                }
            }

            if (entities.isNotEmpty()) {
                val streams = entities
                    .filter { it.streamType == streamType }
                    .map { it.toXtreamStream() }
                
                if (streams.isNotEmpty()) {
                    // Update memory cache for future access (wrap in CachedData)
                    // Note: We reset TTL here effectively, or we could keep it expired?
                    // For Instant Load, we treat it as valid for this session.
                    val cachedData = CachedData(data = streams, ttl = CachedData.TTL_24_HOURS)
                    memoryCache.put(cacheKey, cachedData)
                    Log.d(TAG, "✅ Cache HIT (Room): $categoryId - ${streams.size} channels")
                    return streams
                }
            } else {
                Log.d(TAG, "⚠️ Room cache EXPIRED or empty: $categoryId")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to read from Room database", e)
        }
        
        // Level 3: Cache miss - caller should fetch from network
        Log.d(TAG, "Cache MISS: $categoryId")
        return null
    }

    /**
     * Get a single channel by its ID from memory or Room.
     */
    suspend fun getChannelById(channelId: String): XtreamStream? {
        // Level 1: Check memory cache (optional, could iterate over all categories but maybe slow)
        // For simplicity, we'll go straight to Room as this is a specific lookup.
        
        // Level 2: Room database
        return try {
            channelDao.getChannelById(channelId)?.toXtreamStream()
        } catch (e: Exception) {
            Log.e(TAG, "Failed to get channel by ID: $channelId", e)
            null
        }
    }

    /**
     * Get channel count by category (fast path for UI badges/count labels).
     *
     * Returns null when the category has never been cached (unknown).
     */
    suspend fun getChannelCount(categoryId: String, streamType: String = "live"): Int? {
        val cacheKey = "$KEY_CHANNEL_COUNT_PREFIX${categoryId}_$streamType"

        // Level 1: memory cache
        memoryCache.get(cacheKey)?.let { cached ->
            @Suppress("UNCHECKED_CAST")
            val cachedData = cached as? CachedData<Int>
            if (cachedData != null) {
                if (cachedData.isFresh()) return cachedData.data
                memoryCache.remove(cacheKey)
            }
        }

        // Level 2: Room database (counts are cheaper than loading full lists)
        return try {
            val expiryTimestamp = System.currentTimeMillis() - CachedData.TTL_24_HOURS
            val freshCount = channelDao.countChannelsByCategoryNotExpired(categoryId, streamType, expiryTimestamp)
            if (freshCount > 0) {
                memoryCache.put(cacheKey, CachedData(data = freshCount, ttl = CachedData.TTL_24_HOURS))
                return freshCount
            }

            val staleCount = channelDao.countChannelsByCategory(categoryId, streamType)
            if (staleCount > 0) {
                memoryCache.put(cacheKey, CachedData(data = staleCount, ttl = CachedData.TTL_24_HOURS))
                return staleCount
            }

            null
        } catch (e: Exception) {
            Log.e(TAG, "Failed to get channel count for $categoryId", e)
            null
        }
    }
    
    /**
     * Put channels into cache (both memory and Room) with TTL (Week 8)
     * 
     * This is called after fetching from network to populate all cache levels.
     * Default TTL: 24 hours
     */
    suspend fun putChannels(
        categoryId: String, 
        channels: List<XtreamStream>, 
        streamType: String = "live",
        ttl: Long = CachedData.TTL_24_HOURS
    ) {
        try {
            val cacheKey = "$KEY_CHANNELS_PREFIX${categoryId}_$streamType"
            val countKey = "$KEY_CHANNEL_COUNT_PREFIX${categoryId}_$streamType"
            
            // Week 8: Wrap data in CachedData with TTL
            val cachedData = CachedData(data = channels, ttl = ttl)
            val countData = CachedData(data = channels.size, ttl = ttl)
            
            // Level 1: Update memory cache
            memoryCache.put(cacheKey, cachedData)
            memoryCache.put(countKey, countData)
            Log.d(TAG, "💾 Memory cache updated: $categoryId - ${channels.size} channels (TTL: ${ttl / 1000}s)")
            
            // Level 2: Update Room database
            val entities = channels.map { it.toChannelEntity(categoryId, streamType) }
            channelDao.insertChannels(entities)
            Log.d(TAG, "Room database updated: $categoryId - ${entities.size} channels")
            
        } catch (e: Exception) {
            Log.e(TAG, "Failed to cache channels", e)
        }
    }
    
    /**
     * Get all channels (for search functionality)
     */
    suspend fun getAllChannels(): List<XtreamStream>? {
        // Try memory cache first
        memoryCache.get(KEY_ALL_CHANNELS)?.let { cached ->
            @Suppress("UNCHECKED_CAST")
            val channels = cached as? List<XtreamStream>
            if (channels != null) {
                Log.d(TAG, "Cache HIT (Memory): All channels - ${channels.size}")
                return channels
            }
        }
        
        // Try Room database
        try {
            // Note: This uses Flow, so we need to collect it
            // For now, we'll return null and let caller handle it differently
            Log.d(TAG, "Cache MISS: All channels (use Flow-based query instead)")
            return null
        } catch (e: Exception) {
            Log.e(TAG, "Failed to get all channels", e)
            return null
        }
    }
    
    /**
     * Get categories by type with 3-level caching + TTL (Week 8)
     */
    suspend fun getCategories(type: String): List<XtreamCategory>? {
        val cacheKey = "$KEY_CATEGORIES_PREFIX$type"
        
        // Level 1: Try memory cache
        memoryCache.get(cacheKey)?.let { cached ->
            @Suppress("UNCHECKED_CAST")
            val cachedData = cached as? CachedData<List<XtreamCategory>>
            if (cachedData != null) {
                // Week 8: Check TTL
                if (cachedData.isFresh()) {
                    Log.d(TAG, "✅ Cache HIT (Memory): Categories $type - ${cachedData.data.size}")
                    return cachedData.data
                } else {
                    Log.d(TAG, "⚠️ Cache EXPIRED (Memory): Categories $type")
                    memoryCache.remove(cacheKey)
                }
            }
        }
        
        // Level 2: Try Room database (Week 8: with TTL checking)
        try {
            // Calculate expiry timestamp (7 days ago for categories)
            val expiryTimestamp = System.currentTimeMillis() - CachedData.TTL_7_DAYS
            
            // Get only non-expired categories
            var entities = categoryDao.getCategoriesByTypeNotExpired(type, expiryTimestamp)
            
            // Week 14: Fallback to stale data if fresh data is missing (Instant Load)
            if (entities.isEmpty()) {
                Log.d(TAG, "⚠️ Fresh cache missing for categories $type, checking for stale data...")
                entities = categoryDao.getCategoriesByTypeSync(type)
                if (entities.isNotEmpty()) {
                    Log.d(TAG, "✅ Stale Cache HIT (Room): Categories $type - ${entities.size} (Expired but usable)")
                }
            }

            if (entities.isNotEmpty()) {
                val categories = entities.map { it.toXtreamCategory() }
                
                // Update memory cache (wrap in CachedData)
                val cachedData = CachedData(data = categories, ttl = CachedData.TTL_7_DAYS)
                memoryCache.put(cacheKey, cachedData)
                Log.d(TAG, "✅ Cache HIT (Room): Categories $type - ${categories.size}")
                return categories
            } else {
                Log.d(TAG, "⚠️ Room cache EXPIRED or empty: Categories $type")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to read categories from Room", e)
        }
        
        // Cache miss
        Log.d(TAG, "Cache MISS: Categories $type")
        return null
    }
    
    /**
     * Put categories into cache with TTL (Week 8)
     * Categories typically don't change often, so longer TTL (7 days)
     */
    suspend fun putCategories(
        categories: List<XtreamCategory>, 
        type: String,
        ttl: Long = CachedData.TTL_7_DAYS
    ) {
        try {
            val cacheKey = "$KEY_CATEGORIES_PREFIX$type"
            
            // Week 8: Wrap in CachedData
            val cachedData = CachedData(data = categories, ttl = ttl)
            
            // Level 1: Update memory cache
            memoryCache.put(cacheKey, cachedData)
            Log.d(TAG, "💾 Memory cache updated: Categories $type - ${categories.size} (TTL: ${ttl / (24*60*60*1000)} days)")
            
            // Level 2: Update Room database
            val entities = categories.map {
                CategoryEntity(
                    categoryId = it.category_id ?: "",
                    categoryName = it.category_name ?: "",
                    type = type
                )
            }
            categoryDao.insertCategories(entities)
            Log.d(TAG, "Room database updated: Categories $type - ${entities.size}")
            
        } catch (e: Exception) {
            Log.e(TAG, "Failed to cache categories", e)
        }
    }
    
    /**
     * Clear only memory cache (Level 1)
     * Useful for freeing memory without losing persistent data
     */
    fun clearMemoryCache() {
        memoryCache.evictAll()
        Log.d(TAG, "Memory cache cleared")
    }
    
    /**
     * Clear all caches (Memory + Room)
     * Use this when user logs out or wants to refresh all data
     */
    suspend fun clearAllCaches() {
        try {
            // Clear memory cache
            memoryCache.evictAll()
            Log.d(TAG, "Memory cache cleared")
            
            // Clear Room database
            channelDao.deleteAllChannels()
            categoryDao.deleteAllCategories()
            Log.d(TAG, "Room database cleared")
            
        } catch (e: Exception) {
            Log.e(TAG, "Failed to clear all caches", e)
        }
    }
    
    /**
     * Get cache statistics for monitoring
     */
    fun getCacheStats(): CacheStats {
        return CacheStats(
            memoryCacheSize = memoryCache.size(),
            memoryCacheMaxSize = memoryCache.maxSize(),
            memoryCacheHitCount = memoryCache.hitCount(),
            memoryCacheMissCount = memoryCache.missCount(),
            memoryCacheEvictionCount = memoryCache.evictionCount()
        )
    }
    /**
     * Search channels regardless of category
     */
    suspend fun searchChannels(query: String): List<XtreamStream> {
        return try {
            channelDao.searchChannels(query).map { it.toXtreamStream() }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to search channels", e)
            emptyList()
        }
    }

    /**
     * Search-index support: add channels the table doesn't know yet so global
     * search covers never-opened categories. Rows are inserted under a
     * synthetic category id that no browse query uses, and EXISTING rows are
     * never touched — so per-category lists, counts, TTLs, and favorites from
     * the lazy browse path stay exactly as they were. When the user later
     * opens a channel's real category, putChannels() REPLACEs the indexed row
     * with the properly categorized one (same primary key).
     */
    suspend fun indexChannelsForSearch(channels: List<XtreamStream>, streamType: String = "live") {
        try {
            val existingIds = channelDao.getAllChannelIds().toHashSet()
            val newEntities = channels
                .map { it.toChannelEntity(SEARCH_INDEX_CATEGORY_ID, streamType) }
                .filter { it.streamId.isNotBlank() && it.streamId !in existingIds }
            if (newEntities.isNotEmpty()) {
                newEntities.chunked(2000).forEach { chunk ->
                    channelDao.insertChannels(chunk)
                }
            }
            // Log.i so it is visible on devices that suppress Log.d
            Log.i(TAG, "Search index: added ${newEntities.size} channels (of ${channels.size} fetched)")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to index channels for search", e)
        }
    }
}

/**
 * Cache statistics for monitoring and debugging
 */
data class CacheStats(
    val memoryCacheSize: Int,
    val memoryCacheMaxSize: Int,
    val memoryCacheHitCount: Int,
    val memoryCacheMissCount: Int,
    val memoryCacheEvictionCount: Int
) {
    val hitRate: Float
        get() {
            val total = memoryCacheHitCount + memoryCacheMissCount
            return if (total > 0) {
                memoryCacheHitCount.toFloat() / total.toFloat()
            } else {
                0f
            }
        }
    
    override fun toString(): String {
        return """
            Cache Statistics:
            - Memory Size: $memoryCacheSize / $memoryCacheMaxSize bytes
            - Hit Count: $memoryCacheHitCount
            - Miss Count: $memoryCacheMissCount
            - Hit Rate: ${(hitRate * 100).toInt()}%
            - Eviction Count: $memoryCacheEvictionCount
        """.trimIndent()
    }
}
