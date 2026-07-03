package com.tvonnet.debridxtreamiptv.data.debrid.source

import android.util.Log
import com.tvonnet.debridxtreamiptv.data.debrid.api.AddonCatalogService
import com.tvonnet.debridxtreamiptv.data.debrid.model.AddonStream
import com.tvonnet.debridxtreamiptv.data.debrid.model.AddonStreamMapper
import com.tvonnet.debridxtreamiptv.data.model.ContentType
import com.tvonnet.debridxtreamiptv.util.SensitiveLogRedactor
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
        
        // Dynamic remote fallback configuration nodes
        private val FALLBACK_NODES = listOf(
            "https://mediafusion.elfhosted.com/stream",
            "https://mediafusion.strem.fun/stream"
        )
    }

    suspend fun fetchMovieSources(imdbId: String?): List<AddonStream> {
        return withContext(Dispatchers.IO) {
            if (imdbId.isNullOrBlank()) return@withContext emptyList()
            
            fetchWithFailover(imdbId, null, null, ContentType.MOVIE)
        }
    }

    suspend fun fetchEpisodeSources(imdbId: String?, season: Int, episode: Int): List<AddonStream> {
        return withContext(Dispatchers.IO) {
            if (imdbId.isNullOrBlank()) return@withContext emptyList()

            fetchWithFailover(imdbId, season, episode, ContentType.SERIES)
        }
    }

    private suspend fun fetchWithFailover(imdbId: String, season: Int?, episode: Int?, type: ContentType): List<AddonStream> {
        val urlsToTry = mutableListOf<String>()
        val primaryStoredUrl = preferences.getMediaFusionUrl()

        // 1. Attempt user-defined configuration first
        if (!primaryStoredUrl.isNullOrBlank()) {
             val sanitizedUrl = primaryStoredUrl.trim().replace("stremio://", "https://")
             val cleanUrl = sanitizedUrl.replace("/manifest.json", "")
             val finalBase = if (cleanUrl.endsWith("/stream")) cleanUrl else "$cleanUrl/stream"
             urlsToTry.add(finalBase)
        }

        // 2. Append active remote fallback nodes
        urlsToTry.addAll(FALLBACK_NODES)

        // 3. Graceful cascade over available endpoints
        for (baseUrl in urlsToTry.distinct()) {
            val url = buildUrlFromBase(baseUrl, imdbId, season, episode, type)
            try {
                Log.d(TAG, "Attempting fetch from node: ${SensitiveLogRedactor.describeUrl(url)}")
                val streams = fetchInternal(url)
                
                // If successful (even if empty, meaning no streams found but network was OK), return it.
                // We assume fetchInternal throws on network/host lookup failure.
                return streams
            } catch (e: Exception) {
                Log.w(TAG, "⚠️ Host lookup or fetch failed for node: $baseUrl. Cascading to next backup...", e)
                // Continue to the next backup URL
            }
        }
        
        Log.e(TAG, "❌ All MediaFusion endpoints failed for $imdbId")
        return emptyList()
    }

    private suspend fun fetchInternal(url: String): List<AddonStream> {
        if (service == null) return emptyList() // For unit testing without mock

        // Exceptions here (e.g. UnknownHostException, SocketTimeoutException) will bubble up to fetchWithFailover
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
                Log.w(TAG, "Skipping malformed MediaFusion stream: infoHash=${SensitiveLogRedactor.describeHash(stream.infoHash)}", e)
                emptyList()
            }
        }
        
        Log.d(TAG, "?o. MediaFusion returned $rawStreamCount streams ($rawLinkCount links), expanded to ${streams.size}")
        return streams
    }

    internal fun buildUrlFromBase(baseUrl: String, imdbId: String, season: Int?, episode: Int?, type: ContentType): String {
        return when (type) {
            ContentType.MOVIE -> "$baseUrl/movie/$imdbId.json"
            ContentType.SERIES -> "$baseUrl/series/$imdbId:$season:$episode.json"
            else -> ""
        }
    }
}
