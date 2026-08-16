package com.tvonnet.debridxtreamiptv.data.cache

import android.content.Context
import android.util.Log
import com.google.gson.Gson
import com.tvonnet.debridxtreamiptv.data.model.IptvCache
import java.io.File
import java.io.FileReader
import java.io.FileWriter
import java.io.BufferedReader
import java.io.BufferedWriter

class CacheHelper(private val context: Context) {
    /**
     * Option A: these files describe ONE provider's catalogue, so they are named after it. Without
     * this the per-provider database would be isolated while the JSON snapshot beside it was not —
     * switching would open the new provider's tables and then show the old provider's cached
     * catalogue over the top of them.
     *
     * The unsuffixed names remain in use while no provider is active, so an install that has never
     * signed in behaves exactly as it did.
     */
    private val scope: String = com.tvonnet.debridxtreamiptv.data.prefs.ServerScopedPrefs
        .activeFingerprint(context)
        .takeIf { it.isNotEmpty() }
        ?.let { "_$it" }
        .orEmpty()

    private val cacheFileName = "iptv_cache$scope.json"
    private val seriesDetailDir = "$SERIES_DETAIL_CACHE_DIR$scope"
    private val allSeriesDir = "$ALL_SERIES_CACHE_DIR$scope"

    init {
        adoptLegacyFiles()
    }

    /**
     * Hands the active provider the cache files this device already has.
     *
     * Found on the emulator: the database was adopted but these were not, so after the update
     * `hasCacheFile()` answered false about a device whose tables were full. That is not a cosmetic
     * miss — the switch screen asks exactly that question to decide whether a provider's library is
     * already here, so every existing install would have re-downloaded a catalogue it was holding.
     *
     * Renamed, like the database and for the same reason: this snapshot runs to tens of megabytes
     * and a copy at launch is not acceptable. Once the scoped file exists the legacy one is never
     * looked at again.
     */
    private fun adoptLegacyFiles() {
        if (scope.isEmpty()) return
        adopt(File(context.filesDir, "iptv_cache.json"), File(context.filesDir, cacheFileName))
        adopt(File(context.filesDir, SERIES_DETAIL_CACHE_DIR), File(context.filesDir, seriesDetailDir))
        adopt(File(context.filesDir, ALL_SERIES_CACHE_DIR), File(context.filesDir, allSeriesDir))
    }

    private fun adopt(from: File, to: File) {
        if (to.exists() || !from.exists()) return
        runCatching { from.renameTo(to) }
            .onSuccess { if (it) Log.i(TAG, "Adopted ${from.name} for the active provider") }
            .onFailure { Log.w(TAG, "Could not adopt ${from.name}", it) }
    }
    // Single reused default Gson (Phase 1): a fresh Gson() per call rebuilt the reflective
    // type-adapter cache every time on the multi-MB catalog. MUST stay an UNCONFIGURED default
    // Gson so the on-disk iptv_cache.json / series caches stay byte-identical for existing users.
    private val gson = Gson()
    
    fun writeCache(cache: IptvCache) {
        // SY-8: write to a temp file then atomically rename over the real one. The old
        // direct FileWriter left a half-written iptv_cache.json if the process died
        // mid-write (multi-MB catalog) — a later read then failed and wiped the cache.
        val file = File(context.filesDir, cacheFileName)
        val tmp = File(context.filesDir, "$cacheFileName.tmp")
        var writer: BufferedWriter? = null
        try {
            writer = BufferedWriter(FileWriter(tmp))
            gson.toJson(cache, writer)
            writer.flush()
            writer.close()
            writer = null
            if (tmp.renameTo(file)) {
                inMemoryCache = cache
                Log.d(TAG, "Cache written successfully (size: ${file.length() / 1024} KB)")
            } else {
                // Fallback: copy tmp over file if rename failed (rare cross-fs case).
                tmp.copyTo(file, overwrite = true)
                inMemoryCache = cache
                tmp.delete()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to write cache", e)
            runCatching { tmp.delete() }
        } finally {
            try {
                writer?.close()
            } catch (e: Exception) {
                Log.e(TAG, "Failed to close writer", e)
            }
        }
    }
    
    fun readCache(): IptvCache? {
        var reader: BufferedReader? = null
        return try {
            val file = File(context.filesDir, cacheFileName)
            if (!file.exists()) {
                Log.d(TAG, "Cache file does not exist")
                return null
            }
            
            val fileSizeKB = file.length() / 1024 // KB
            val fileSizeMB = fileSizeKB / 1024 // MB
            Log.d(TAG, "Reading cache file (size: $fileSizeKB KB / $fileSizeMB MB)")
            
            // Safety check: If cache file is too large (>50MB), clear it
            if (fileSizeKB > MAX_CACHE_SIZE_KB) {
                Log.w(TAG, "Cache file too large (${fileSizeMB}MB > ${MAX_CACHE_SIZE_KB/1024}MB). Clearing cache.")
                clearCache()
                return null
            }
            
            // Use streaming to avoid loading entire file into memory
            reader = BufferedReader(FileReader(file), 8192) // 8KB buffer
            val cache = gson.fromJson(reader, IptvCache::class.java)
            Log.d(TAG, "Cache read successfully")
            inMemoryCache = cache
            cache
        } catch (e: OutOfMemoryError) {
            Log.e(TAG, "OutOfMemoryError reading cache - file too large. Clearing cache.", e)
            closeQuietly(reader)
            reader = null
            clearCache()
            null
        } catch (e: Exception) {
            Log.e(TAG, "Failed to read cache", e)
            // If parsing fails, clear corrupt cache; close first so Windows allows the delete
            closeQuietly(reader)
            reader = null
            clearCache()
            null
        } finally {
            try {
                reader?.close()
            } catch (e: Exception) {
                Log.e(TAG, "Failed to close reader", e)
            }
        }
    }

    private fun closeQuietly(reader: BufferedReader?) {
        try {
            reader?.close()
        } catch (e: Exception) {
            Log.e(TAG, "Failed to close reader", e)
        }
    }

    fun clearCache() {
        try {
            val file = File(context.filesDir, cacheFileName)
            if (file.exists()) {
                file.delete()
            }
            inMemoryCache = null
        } catch (e: Exception) {
            Log.e(TAG, "Failed to clear cache", e)
        }
    }
    
    /**
     * S2: every file on disk that describes ONE provider's catalogue.
     *
     * [clearCache] only ever removed `iptv_cache.json`, so the series-detail JSONs and the
     * all-series fallback outlived it. That did not matter while a device only ever had one
     * provider; the moment it can change, a leftover `series_details/1234.json` is the OLD
     * provider's show being served for the NEW provider's series 1234.
     */
    fun clearProviderFiles() {
        clearCache()
        runCatching { File(context.filesDir, seriesDetailDir).deleteRecursively() }
            .onFailure { Log.e(TAG, "Failed to clear series detail cache", it) }
        runCatching { File(context.filesDir, allSeriesDir).deleteRecursively() }
            .onFailure { Log.e(TAG, "Failed to clear all-series cache", it) }
    }

    fun memorySnapshot(): IptvCache? = inMemoryCache

    /**
     * Cheap "do we have cache to show" check — an in-memory hit or a file stat,
     * never a Gson parse. Lets the Home collect-condition avoid a main-thread
     * disk parse (ST-1) when deciding whether to load.
     */
    fun hasCacheFile(): Boolean =
        inMemoryCache != null || File(context.filesDir, cacheFileName).exists()
    
    fun clearMemorySnapshot() {
        inMemoryCache = null
    }
    
    companion object {
        private const val TAG = "CacheHelper"
        private const val MAX_CACHE_SIZE_KB = 51200 // 50 MB max cache size
        private const val SERIES_DETAIL_CACHE_DIR = "series_details"
        private const val ALL_SERIES_CACHE_DIR = "series_cache"
        private const val SERIES_CACHE_TTL_MS = 24 * 60 * 60 * 1000L // 24 hours
        @Volatile private var inMemoryCache: IptvCache? = null
    }

    fun writeSeriesDetail(seriesId: String, detail: com.tvonnet.debridxtreamiptv.data.model.XtreamSeriesDetailResponse) {
        try {
            val dir = File(context.filesDir, seriesDetailDir)
            if (!dir.exists()) dir.mkdirs()
            
            val file = File(dir, "$seriesId.json")
            val writer = BufferedWriter(FileWriter(file))
            gson.toJson(detail, writer)
            writer.flush()
            writer.close()
            Log.d(TAG, "Series detail cached: $seriesId")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to cache series detail $seriesId", e)
        }
    }

    fun readSeriesDetail(seriesId: String): com.tvonnet.debridxtreamiptv.data.model.XtreamSeriesDetailResponse? {
        try {
            val dir = File(context.filesDir, seriesDetailDir)
            val file = File(dir, "$seriesId.json")
            
            if (!file.exists()) return null
            
            // Check TTL
            if (System.currentTimeMillis() - file.lastModified() > SERIES_CACHE_TTL_MS) {
                file.delete()
                return null
            }
            
            val reader = BufferedReader(FileReader(file))
            val detail = gson.fromJson(reader, com.tvonnet.debridxtreamiptv.data.model.XtreamSeriesDetailResponse::class.java)
            reader.close()
            return detail
        } catch (e: Exception) {
            Log.e(TAG, "Failed to read series detail cache $seriesId", e)
            return null
        }
    }

    fun writeAllSeries(series: List<com.tvonnet.debridxtreamiptv.data.model.XtreamSeriesInfo>) {
        try {
            val dir = File(context.filesDir, allSeriesDir)
            if (!dir.exists()) dir.mkdirs()
            
            val file = File(dir, "all_series.json")
            val writer = BufferedWriter(FileWriter(file))
            gson.toJson(series, writer)
            writer.flush()
            writer.close()
            Log.d(TAG, "All series cached: ${series.size} items")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to cache all series", e)
        }
    }

    fun readAllSeries(): List<com.tvonnet.debridxtreamiptv.data.model.XtreamSeriesInfo>? {
        try {
            val dir = File(context.filesDir, allSeriesDir)
            val file = File(dir, "all_series.json")
            
            if (!file.exists()) return null
            
            // Check TTL (24 hours)
            if (System.currentTimeMillis() - file.lastModified() > SERIES_CACHE_TTL_MS) {
                file.delete()
                return null
            }
            
            val reader = BufferedReader(FileReader(file))
            val type = object : com.google.gson.reflect.TypeToken<List<com.tvonnet.debridxtreamiptv.data.model.XtreamSeriesInfo>>() {}.type
            val series = gson.fromJson<List<com.tvonnet.debridxtreamiptv.data.model.XtreamSeriesInfo>>(reader, type)
            reader.close()
            return series
        } catch (e: Exception) {
            Log.e(TAG, "Failed to read all series cache", e)
            return null
        }
    }
}
