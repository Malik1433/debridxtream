package com.tvonnet.debridxtreamiptv.util

import android.content.Context
import androidx.media3.common.util.UnstableApi
import androidx.media3.database.StandaloneDatabaseProvider
import androidx.media3.datasource.cache.LeastRecentlyUsedCacheEvictor
import androidx.media3.datasource.cache.SimpleCache
import java.io.File

/**
 * Singleton manager for ExoPlayer Disk Caching.
 * Provides a shared SimpleCache instance to enable Stremio-like buffering performance.
 */
@UnstableApi
object PlayerCacheManager {
    private var cache: SimpleCache? = null
    private const val CACHE_DIR_NAME = "media_cache"
    private const val MAX_CACHE_SIZE = 500L * 1024L * 1024L // 500MB sliding window

    @Synchronized
    fun getCache(context: Context): SimpleCache {
        if (cache == null) {
            val cacheDir = File(context.cacheDir, CACHE_DIR_NAME)
            val evictor = LeastRecentlyUsedCacheEvictor(MAX_CACHE_SIZE)
            val databaseProvider = StandaloneDatabaseProvider(context)
            cache = SimpleCache(cacheDir, evictor, databaseProvider)
        }
        return cache!!
    }

    /**
     * Clear the cache if space is needed manualy or during maintenance
     */
    fun releaseCache() {
        cache?.release()
        cache = null
    }
}
