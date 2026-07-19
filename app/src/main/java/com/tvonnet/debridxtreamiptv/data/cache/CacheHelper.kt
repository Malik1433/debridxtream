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
    private val cacheFileName = "iptv_cache.json"
    
    fun writeCache(cache: IptvCache) {
        var writer: BufferedWriter? = null
        try {
            val gson = Gson()
            val file = File(context.filesDir, cacheFileName)
            writer = BufferedWriter(FileWriter(file))
            gson.toJson(cache, writer)
            writer.flush()
            inMemoryCache = cache
            Log.d(TAG, "Cache written successfully (size: ${file.length() / 1024} KB)")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to write cache", e)
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
            val gson = Gson()
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
        private const val SERIES_CACHE_TTL_MS = 24 * 60 * 60 * 1000L // 24 hours
        @Volatile private var inMemoryCache: IptvCache? = null
    }

    fun writeSeriesDetail(seriesId: String, detail: com.tvonnet.debridxtreamiptv.data.model.XtreamSeriesDetailResponse) {
        try {
            val dir = File(context.filesDir, SERIES_DETAIL_CACHE_DIR)
            if (!dir.exists()) dir.mkdirs()
            
            val file = File(dir, "$seriesId.json")
            val gson = Gson()
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
            val dir = File(context.filesDir, SERIES_DETAIL_CACHE_DIR)
            val file = File(dir, "$seriesId.json")
            
            if (!file.exists()) return null
            
            // Check TTL
            if (System.currentTimeMillis() - file.lastModified() > SERIES_CACHE_TTL_MS) {
                file.delete()
                return null
            }
            
            val gson = Gson()
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
            val dir = File(context.filesDir, "series_cache")
            if (!dir.exists()) dir.mkdirs()
            
            val file = File(dir, "all_series.json")
            val gson = Gson()
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
            val dir = File(context.filesDir, "series_cache")
            val file = File(dir, "all_series.json")
            
            if (!file.exists()) return null
            
            // Check TTL (24 hours)
            if (System.currentTimeMillis() - file.lastModified() > SERIES_CACHE_TTL_MS) {
                file.delete()
                return null
            }
            
            val gson = Gson()
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
