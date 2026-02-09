package com.tvonnet.debridxtreamiptv.data.debrid.source

import android.util.Log
import com.tvonnet.debridxtreamiptv.data.debrid.api.AddonCatalogService
import com.tvonnet.debridxtreamiptv.data.debrid.model.AddonStream
import com.tvonnet.debridxtreamiptv.data.debrid.model.AddonStreamMapper
import com.tvonnet.debridxtreamiptv.data.model.ContentType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Fetcher for MediaFusion (ElfHosted)
 * 
 * Uses the specific configuration from PureFire.json
 */
@Singleton
class MediaFusionFetcher @Inject constructor(
    private val service: AddonCatalogService?,
    private val preferences: com.tvonnet.debridxtreamiptv.data.prefs.DebridPreferences
) {

    companion object {
        private const val TAG = "MediaFusionFetcher"
        // Fallback URL (Deprecated/Expired but kept as default)
        private const val DEFAULT_BASE_URL = "https://mediafusion.elfhosted.com/D-mgiVOlhrq7BpXFyvhkNZOZxt3Tlc0eC0WoZeRkwi9CJdlVJ5ybgEQncIW5yi3sbiUxVmxkObwz883q-l-xH9QtGkjI4mMLP7omPTqEQq3PRsCFlwOPPhb3AUEVw8jRWb/stream"
    }


    suspend fun fetchMovieSources(imdbId: String?): List<AddonStream> {
        return withContext(Dispatchers.IO) {
            if (imdbId.isNullOrBlank()) return@withContext emptyList()
            
            val url = buildUrl(imdbId, null, null, ContentType.MOVIE)
            fetchInternal(url)
        }
    }

    suspend fun fetchEpisodeSources(imdbId: String?, season: Int, episode: Int): List<AddonStream> {
        return withContext(Dispatchers.IO) {
            if (imdbId.isNullOrBlank()) return@withContext emptyList()

            val url = buildUrl(imdbId, season, episode, ContentType.SERIES)
            fetchInternal(url)
        }
    }

    private suspend fun fetchInternal(url: String): List<AddonStream> {
        return try {
            Log.d(TAG, "🌐 Querying MediaFusion: $url")
            if (service == null) return emptyList() // For unit testing without mock

            val response = service.fetchMediaFusionStreams(url)
            val rawStreamCount = response.streams.size
            val rawLinkCount = response.streams.sumOf { stream ->
                val urlCount = if (stream.url.isNullOrBlank()) 0 else 1
                urlCount + (stream.sources?.size ?: 0)
            }
            val streams = response.streams.flatMap { stream ->
                try {
                    AddonStreamMapper.fromMediaFusionExpanded(stream)
                } catch (e: Exception) {
                    Log.w(TAG, "?s??,? Skipping malformed MediaFusion stream: ${stream.infoHash ?: "unknown"}", e)
                    emptyList()
                }
            }
            
            Log.d(TAG, "?o. MediaFusion returned $rawStreamCount streams ($rawLinkCount links), expanded to ${streams.size}")
            streams
        } catch (e: Exception) {
            Log.e(TAG, "⚠️ MediaFusion fetch failed", e)
            emptyList()
        }
    }

    fun buildUrl(imdbId: String, season: Int?, episode: Int?, type: ContentType): String {
        val storedUrl = preferences.getMediaFusionUrl()
        val baseUrl = if (!storedUrl.isNullOrBlank()) {
             // Convert manifest URL to stream base URL
             // Example: https://.../manifest.json -> https://.../stream
             val sanitizedUrl = storedUrl.trim().replace("stremio://", "https://")
             val cleanUrl = sanitizedUrl.replace("/manifest.json", "")
             val finalBase = if (cleanUrl.endsWith("/stream")) cleanUrl else "$cleanUrl/stream"
             Log.e(TAG, "🔧 Built Base URL: $finalBase (from $storedUrl)")
             finalBase
        } else {
            DEFAULT_BASE_URL
        }

        return when (type) {
            ContentType.MOVIE -> "$baseUrl/movie/$imdbId.json"
            ContentType.SERIES -> "$baseUrl/series/$imdbId:$season:$episode.json"
            else -> ""
        }
    }
}
