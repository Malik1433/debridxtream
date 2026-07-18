package com.tvonnet.debridxtreamiptv.util

import android.util.Log
import com.tvonnet.debridxtreamiptv.data.Result
import com.tvonnet.debridxtreamiptv.data.local.entity.EpgEntity
import com.tvonnet.debridxtreamiptv.data.repository.XtreamRepository
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

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

    // Caps concurrent get_short_epg HTTP calls so warming a big channel list can't storm the
    // provider (the old per-row fetch fired one call per visible row → most timed out).
    private val shortEpgGate = Semaphore(4)

    /** One channel to warm: [cacheKey] is what the row reads, [epgChannelId] hits the XMLTV DB. */
    data class WarmEntry(val cacheKey: String, val epgChannelId: String?, val streamId: String?)

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
        val cached = epgCache[channelKey]

        // Treat only real EPG data as a fresh cache hit.
        // Empty/null pairs are a miss so late XMLTV syncs or short-EPG retries can repopulate.
        if (now - lastUpdate < cacheExpirationMs && cached != null && (cached.first != null || cached.second != null)) {
            Log.d(TAG, "EPG cache hit for channel $channelKey")
            return cached
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
                    // 2) Fallback to lightweight per-stream short EPG (works even when full XMLTV is
                    // disabled). Gated so a burst of row binds can't fire dozens of HTTP calls at once.
                    shortEpgGate.withPermit {
                        when (val result = repo.fetchShortEpgNowNext(streamId = streamId, channelKey = channelKey)) {
                            is Result.Success -> result.data
                            else -> (null to null)
                        }
                    }
                } else {
                    null to null
                }

                // Only cache positive results. Empty results stay eligible for future retries
                // so late XMLTV syncs or provider data refreshes can be picked up.
                if (current != null || next != null) {
                    val epgData = Pair(current, next)
                    epgCache[channelKey] = epgData
                    lastUpdateTimes[channelKey] = System.currentTimeMillis()
                    _updates.tryEmit(channelKey)
                } else {
                    epgCache.remove(channelKey)
                    lastUpdateTimes.remove(channelKey)
                }

                Log.d(TAG, "EPG data refreshed for channel $channelKey: current=${current?.title}, next=${next?.title}")

            } catch (e: Exception) {
                Log.e(TAG, "Failed to refresh EPG data for channel $channelKey", e)
            } finally {
                inFlightRefresh.remove(channelKey)
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

                        if (current != null || next != null) {
                            epgCache[channelId] = Pair(current, next)
                            lastUpdateTimes[channelId] = System.currentTimeMillis()
                            _updates.tryEmit(channelId)
                        } else {
                            epgCache.remove(channelId)
                            lastUpdateTimes.remove(channelId)
                        }

                    } catch (e: Exception) {
                        Log.e(TAG, "Failed to preload EPG for channel $channelId", e)
                    }
                }
            }
        }
    }

    /**
     * Batch-warm now/next for a window of channels — the world-standard path used while
     * browsing the channel list. One DB query covers every channel that has synced XMLTV
     * (fast, reliable, lenient now/next like the full-screen player), and only the leftover
     * channels (provider short-EPG only) fall back to get_short_epg, rate-limited by
     * [shortEpgGate] so a large list can't storm the provider.
     */
    fun warmNowNext(entries: List<WarmEntry>) {
        val repo = repository ?: return
        if (entries.isEmpty()) return

        cacheScope.launch {
            val now = System.currentTimeMillis()

            // 1) One batched DB read for everything with a real XMLTV channel id.
            val epgIds = entries.mapNotNull { it.epgChannelId?.takeIf { id -> id.isNotBlank() } }.distinct()
            val byChannel: Map<String, List<EpgEntity>> = if (epgIds.isNotEmpty()) {
                runCatching {
                    repo.getProgramsByEpgIdInRange(
                        epgIds,
                        now - TimeUnit.HOURS.toMillis(1),
                        now + TimeUnit.HOURS.toMillis(6)
                    )
                }.getOrNull().orEmpty()
            } else {
                emptyMap()
            }

            val needShortEpg = mutableListOf<WarmEntry>()
            for (e in entries) {
                val list = e.epgChannelId?.let { byChannel[it] }
                val (current, next) = nowNextFrom(list, now)
                if (current != null || next != null) {
                    epgCache[e.cacheKey] = current to next
                    lastUpdateTimes[e.cacheKey] = now
                    _updates.tryEmit(e.cacheKey)
                } else if (!hasRealEpg(e.cacheKey)) {
                    needShortEpg.add(e)
                }
            }

            // 2) Provider short-EPG only for the leftovers, capped concurrency.
            needShortEpg.forEach { e ->
                val streamId = e.streamId?.takeIf { it.isNotBlank() } ?: return@forEach
                launch {
                    shortEpgGate.withPermit {
                        if (hasRealEpg(e.cacheKey)) return@withPermit
                        val result = runCatching {
                            repo.fetchShortEpgNowNext(streamId = streamId, channelKey = e.cacheKey)
                        }.getOrNull()
                        if (result is Result.Success) {
                            val (current, next) = result.data
                            if (current != null || next != null) {
                                epgCache[e.cacheKey] = current to next
                                lastUpdateTimes[e.cacheKey] = System.currentTimeMillis()
                                _updates.tryEmit(e.cacheKey)
                            }
                        }
                    }
                }
            }
        }
    }

    /** Current = programme covering now (or its most recent), next = first upcoming. Lenient. */
    private fun nowNextFrom(programs: List<EpgEntity>?, now: Long): Pair<EpgEntity?, EpgEntity?> {
        if (programs.isNullOrEmpty()) return null to null
        val sorted = programs.sortedBy { it.start }
        val current = sorted.lastOrNull { it.start <= now && it.stop > now }
        val next = sorted.firstOrNull { it.start > now }
        return current to next
    }

    private fun hasRealEpg(cacheKey: String): Boolean =
        epgCache[cacheKey]?.let { it.first != null || it.second != null } == true

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
