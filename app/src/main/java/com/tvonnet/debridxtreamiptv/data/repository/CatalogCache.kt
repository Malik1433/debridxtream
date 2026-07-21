package com.tvonnet.debridxtreamiptv.data.repository

import android.util.Log
import com.tvonnet.debridxtreamiptv.data.cache.CacheHelper
import com.tvonnet.debridxtreamiptv.data.cache.CacheManager
import com.tvonnet.debridxtreamiptv.data.model.IptvCache
import com.tvonnet.debridxtreamiptv.data.model.SeriesCategoryStatus
import com.tvonnet.debridxtreamiptv.data.model.XtreamSeriesDetailResponse
import com.tvonnet.debridxtreamiptv.data.model.XtreamSeriesInfo
import com.tvonnet.debridxtreamiptv.data.model.XtreamVodInfo
import kotlinx.coroutines.sync.Mutex
import java.util.concurrent.ConcurrentHashMap

/**
 * Phase 7B-0: the shared, in-memory catalog cache substrate lifted out of the XtreamRepository
 * god-class. These caches are the glue that couples the Sync / Live / VOD / Series domains — the full
 * IptvCache snapshot plus the per-category and all-series fallback maps and the series-status map. They
 * are extracted here FIRST so each future domain collaborator can share one holder instead of owning a
 * slice. The repository exposes get/set views onto these fields, so every existing read/write site is
 * unchanged and behaviour is byte-for-byte identical.
 *
 * Concurrency notes are preserved verbatim from the repository:
 *  - @Volatile on the snapshot fields (cross-thread reads/writes on Dispatchers.IO),
 *  - ConcurrentHashMap for the maps (concurrent coroutine read+write; no TTL/size cap on purpose —
 *    some are outage fallbacks that must NOT be evicted when the network is down).
 */
internal class CatalogCache(
    private val cacheHelper: CacheHelper,
    private val cacheManager: CacheManager
) {
    // Legacy memory cache to avoid repeated Gson parsing (for the full IptvCache object).
    @Volatile
    var memoryCache: IptvCache? = null

    val seriesDetailCache = ConcurrentHashMap<String, XtreamSeriesDetailResponse>()

    // Per-category series cache for detail screen fallback
    val perCategorySeriesCache = ConcurrentHashMap<String, List<XtreamSeriesInfo>>()

    // Cache for ALL series (fallback strategy)
    @Volatile
    var allSeriesCacheFallback: List<XtreamSeriesInfo>? = null

    // Per-category VOD cache (lazy loaded)
    val perCategoryVodCache = ConcurrentHashMap<String, List<XtreamVodInfo>>()
    val vodFetchLocks = ConcurrentHashMap<String, Mutex>()

    val seriesCategoryStatus = ConcurrentHashMap<String, SeriesCategoryStatus>()

    fun readCache(): IptvCache? {
        // Return from memory cache if available (avoid repeated Gson parsing)
        if (memoryCache != null) {
            return memoryCache
        }
        // Otherwise read from file and cache in memory
        memoryCache = cacheHelper.readCache()
        return memoryCache
    }

    /** Cheap "is there cache to show" check — in-memory hit or a file stat, never a Gson parse. */
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

    private companion object {
        private const val TAG = "XtreamRepository"
    }
}
