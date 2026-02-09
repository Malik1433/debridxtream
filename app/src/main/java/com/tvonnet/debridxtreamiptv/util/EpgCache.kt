package com.tvonnet.debridxtreamiptv.util

import android.util.Log
import com.tvonnet.debridxtreamiptv.data.local.entity.EpgEntity
import com.tvonnet.debridxtreamiptv.data.repository.XtreamRepository
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.util.concurrent.ConcurrentHashMap

/**
 * Phase 2.5: EPG Display Issues - EPG Cache System
 *
 * Provides efficient EPG data caching and retrieval for channel listings:
 * - Memory cache for current/next program data
 * - Background refresh to avoid blocking UI thread
 * - Automatic cleanup of stale data
 * - Performance-optimized for TV channel browsing
 */
object EpgCache {
    private const val TAG = "EpgCache"

    // Memory cache for EPG data: channelId -> Pair(current, next)
    private val epgCache = ConcurrentHashMap<String, Pair<EpgEntity?, EpgEntity?>>()

    // Background job scope for EPG updates
    private val cacheScope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    // Cache expiration time (5 minutes)
    private val cacheExpirationMs = 5 * 60 * 1000L

    // Track last update times
    private val lastUpdateTimes = ConcurrentHashMap<String, Long>()
    private val inFlightRefresh = ConcurrentHashMap<String, Job>()

    // Repository reference
    private var repository: XtreamRepository? = null

    // Notify UI when a channel's EPG cache is updated.
    private val _updates = MutableSharedFlow<String>(extraBufferCapacity = 256)
    val updates: SharedFlow<String> = _updates.asSharedFlow()

    /**
     * Initialize the EPG cache with repository
     */
    fun initialize(repository: XtreamRepository) {
        this.repository = repository
        Log.d(TAG, "EPG Cache initialized")
    }

    /**
     * Get EPG data for a channel with caching
     * Returns cached data if available and fresh, otherwise triggers background refresh
     */
    fun getEpgData(channelKey: String, streamId: String? = null): Pair<EpgEntity?, EpgEntity?> {
        val now = System.currentTimeMillis()
        val lastUpdate = lastUpdateTimes[channelKey] ?: 0L

        // Check if cache is fresh
        if (now - lastUpdate < cacheExpirationMs) {
            val cached = epgCache[channelKey]
            if (cached != null) {
                Log.d(TAG, "EPG cache hit for channel $channelKey")
                return cached
            }
        }

        // Cache miss or stale, return null and trigger background refresh
        Log.d(TAG, "EPG cache miss for channel $channelKey, triggering refresh")
        refreshEpgData(channelKey, streamId)
        return Pair(null, null)
    }

    /**
     * Refresh EPG data for a specific channel in background
     */
    private fun refreshEpgData(channelKey: String, streamId: String?) {
        val repo = repository ?: return

        inFlightRefresh[channelKey]?.takeIf { it.isActive }?.let { return }

        val job = cacheScope.launch {
            try {
                Log.d(TAG, "Refreshing EPG data for channel $channelKey")

                // 1) Try local database (if full XMLTV sync exists)
                val currentLocal = repo.getCurrentProgram(channelKey)
                val nextLocal = repo.getNextProgram(channelKey)

                val (current, next) = if (currentLocal != null || nextLocal != null) {
                    currentLocal to nextLocal
                } else if (!streamId.isNullOrBlank()) {
                    // 2) Fallback to lightweight per-stream short EPG (works even when full XMLTV is disabled).
                    when (val result = repo.fetchShortEpgNowNext(streamId = streamId, channelKey = channelKey)) {
                        is com.tvonnet.debridxtreamiptv.data.Result.Success -> result.data
                        else -> (null to null)
                    }
                } else {
                    null to null
                }

                // Update cache
                val epgData = Pair(current, next)
                epgCache[channelKey] = epgData
                lastUpdateTimes[channelKey] = System.currentTimeMillis()
                _updates.tryEmit(channelKey)

                Log.d(TAG, "EPG data refreshed for channel $channelKey: current=${current?.title}, next=${next?.title}")

            } catch (e: Exception) {
                Log.e(TAG, "Failed to refresh EPG data for channel $channelKey", e)
            }
        }
        inFlightRefresh[channelKey] = job
    }

    /**
     * Preload EPG data for multiple channels (called during app startup)
     */
    fun preloadEpgData(channelIds: List<String>) {
        val repo = repository ?: return

        cacheScope.launch {
            Log.d(TAG, "Preloading EPG data for ${channelIds.size} channels")

            channelIds.forEach { channelId ->
                launch {
                    try {
                        val current = repo.getCurrentProgram(channelId)
                        val next = repo.getNextProgram(channelId)

                        epgCache[channelId] = Pair(current, next)
                        lastUpdateTimes[channelId] = System.currentTimeMillis()

                    } catch (e: Exception) {
                        Log.e(TAG, "Failed to preload EPG for channel $channelId", e)
                    }
                }
            }
        }
    }

    /**
     * Force refresh EPG data for a channel (useful for manual refresh)
     */
    fun forceRefresh(channelId: String) {
        Log.d(TAG, "Force refreshing EPG data for channel $channelId")
        refreshEpgData(channelId, streamId = null)
    }

    /**
     * Clear stale cache entries
     */
    fun cleanupStaleEntries() {
        val now = System.currentTimeMillis()
        val staleKeys = lastUpdateTimes.filter { (_, lastUpdate) ->
            now - lastUpdate > cacheExpirationMs
        }.keys

        staleKeys.forEach { key ->
            epgCache.remove(key)
            lastUpdateTimes.remove(key)
        }

        if (staleKeys.isNotEmpty()) {
            Log.d(TAG, "Cleaned up ${staleKeys.size} stale EPG cache entries")
        }
    }

    /**
     * Clear all cache data
     */
    fun clearCache() {
        epgCache.clear()
        lastUpdateTimes.clear()
        Log.d(TAG, "EPG cache cleared")
    }

    /**
     * Get cache statistics for debugging
     */
    fun getCacheStats(): Map<String, Any> {
        return mapOf(
            "cacheSize" to epgCache.size,
            "lastUpdateTimesSize" to lastUpdateTimes.size,
            "cacheKeys" to epgCache.keys.toList()
        )
    }

    /**
     * Shutdown the cache scope
     */
    fun shutdown() {
        cacheScope.cancel()
        clearCache()
        Log.d(TAG, "EPG cache shutdown")
    }
}
