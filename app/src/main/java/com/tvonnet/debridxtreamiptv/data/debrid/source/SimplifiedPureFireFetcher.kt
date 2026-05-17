package com.tvonnet.debridxtreamiptv.data.debrid.source

import android.util.Log
import com.google.gson.Gson
import com.google.gson.JsonElement
import com.google.gson.JsonParser
import com.tvonnet.debridxtreamiptv.data.debrid.model.AddonStream
import com.tvonnet.debridxtreamiptv.data.debrid.model.AddonSourceType
import com.tvonnet.debridxtreamiptv.data.model.ContentType
import com.tvonnet.debridxtreamiptv.data.debrid.util.LanguageParser
import com.tvonnet.debridxtreamiptv.util.SensitiveLogRedactor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Enhanced Simplified PureFire Source Fetcher - REAL TORRENTIO API
 *
 * Uses real Torrentio public API calls instead of mock data
 */
@Singleton
class SimplifiedPureFireFetcher @Inject constructor(
    private val gson: Gson,
    private val debridAccountRepository: com.tvonnet.debridxtreamiptv.data.debrid.repository.DebridAccountRepository
) {

    companion object {
        private const val TAG = "SimplifiedPureFire"
        private const val TORRENTIO_BASE_URL = "https://torrentio.strem.fun"
    }

    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()

    suspend fun fetchMovieSources(
        imdbId: String?,
        title: String?,
        year: Int?,
        contentType: ContentType
    ): List<AddonStream> {
        return withContext(Dispatchers.IO) {
            Log.d(TAG, "🎬 Real Torrentio fetch for: $title ($year) [$imdbId]")

            val streams = mutableListOf<AddonStream>()

            // Use IMDb ID if available, otherwise fall back to title + year search
            val identifier = if (!imdbId.isNullOrBlank()) {
                imdbId
            } else {
                "${title?.replace(" ", " ")}${year?.let { " $it" } ?: ""}"
            }

            try {
                val token = debridAccountRepository.getCachedAuthState()?.accessToken
                val config = if (!token.isNullOrBlank()) {
                    "providers=yts,eztv,rarbg,1337x,thepiratebay,kickasstorrents,torrentgalaxy,magnetdl,horriblesubs,nyaasi,tokyotosho,anidex|sort=qualitysize|quality=4k,1080p,720p,480p|limit=20|realdebrid=$token"
                } else {
                    "providers=yts,eztv,rarbg,1337x,thepiratebay,kickasstorrents,torrentgalaxy,magnetdl,horriblesubs,nyaasi,tokyotosho,anidex|sort=qualitysize|quality=4k,1080p,720p,480p|limit=20"
                }

                Log.d(TAG, "Querying Torrentio (${if (!token.isNullOrBlank()) "Premium" else "Public"}) for id=${SensitiveLogRedactor.describeHash(identifier)}")
                val url = "$TORRENTIO_BASE_URL/$config/stream/movie/$identifier.json"
                val request = Request.Builder()
                    .url(url)
                    .header("User-Agent", "Mozilla/5.0")
                    .build()

                val response = httpClient.newCall(request).execute()

                if (response.isSuccessful) {
                    val responseBody = response.body?.string()
                    if (!responseBody.isNullOrEmpty()) {
                        Log.d(TAG, "✅ Torrentio API response received: ${response.body?.contentLength()} bytes")

                        // Parse Torrentio response
                        val jsonElement = JsonParser.parseString(responseBody)
                        val streamsArray = jsonElement.asJsonObject.get("streams")?.asJsonArray

                        streamsArray?.forEach { streamElement ->
                            try {
                                val stream = parseTorrentioStream(streamElement.asJsonObject)
                                if (stream != null) {
                                    streams.add(stream)
                                }
                            } catch (e: Exception) {
                                Log.w(TAG, "⚠️ Failed to parse Torrentio stream: ${e.message}")
                            }
                        }

                        Log.d(TAG, "🎯 TORRENTIO: Found ${streams.size} real streams")
                    } else {
                        Log.w(TAG, "⚠️ Empty response from Torrentio")
                    }
                } else {
                    Log.w(TAG, "❌ Torrentio HTTP ${response.code}: ${response.message}")
                }
            } catch (e: Exception) {
                Log.e(TAG, "❌ Torrentio API error: ${e.message}", e)

                Log.d(TAG, "Torrentio fetch failed; returning empty source list")
                return@withContext emptyList()
            }

            if (streams.isEmpty()) {
                Log.d(TAG, "No real streams found; returning empty source list")
                return@withContext emptyList()
            }

            return@withContext streams
        }
    }

    suspend fun fetchEpisodeSources(
        imdbId: String?,
        season: Int,
        episode: Int,
        title: String?
    ): List<AddonStream> {
        return withContext(Dispatchers.IO) {
            Log.d(TAG, "🎬 Real Torrentio fetch for Episode: $title S$season:E$episode [$imdbId]")

            val streams = mutableListOf<AddonStream>()

            if (imdbId.isNullOrBlank()) {
                 Log.w(TAG, "⚠️ No IMDb ID provided for episode fetch")
                 return@withContext emptyList()
            }

            val identifier = "$imdbId:$season:$episode"

            try {
                val token = debridAccountRepository.getCachedAuthState()?.accessToken
                val config = if (!token.isNullOrBlank()) {
                    "providers=yts,eztv,rarbg,1337x,thepiratebay,kickasstorrents,torrentgalaxy,magnetdl,horriblesubs,nyaasi,tokyotosho,anidex|sort=qualitysize|quality=4k,1080p,720p,480p|limit=20|realdebrid=$token"
                } else {
                    "providers=yts,eztv,rarbg,1337x,thepiratebay,kickasstorrents,torrentgalaxy,magnetdl,horriblesubs,nyaasi,tokyotosho,anidex|sort=qualitysize|quality=4k,1080p,720p,480p|limit=20"
                }

                Log.d(TAG, "Querying Torrentio (${if (!token.isNullOrBlank()) "Premium" else "Public"}) for id=${SensitiveLogRedactor.describeHash(identifier)}")
                val url = "$TORRENTIO_BASE_URL/$config/stream/series/$identifier.json"
                val request = Request.Builder()
                    .url(url)
                    .header("User-Agent", "Mozilla/5.0")
                    .build()

                val response = httpClient.newCall(request).execute()

                if (response.isSuccessful) {
                    val responseBody = response.body?.string()
                    if (!responseBody.isNullOrEmpty()) {
                        Log.d(TAG, "✅ Torrentio API response received: ${response.body?.contentLength()} bytes")

                        // Parse Torrentio response
                        val jsonElement = JsonParser.parseString(responseBody)
                        val streamsArray = jsonElement.asJsonObject.get("streams")?.asJsonArray

                        streamsArray?.forEach { streamElement ->
                            try {
                                val stream = parseTorrentioStream(streamElement.asJsonObject)
                                if (stream != null) {
                                    streams.add(stream)
                                }
                            } catch (e: Exception) {
                                Log.w(TAG, "⚠️ Failed to parse Torrentio stream: ${e.message}")
                            }
                        }

                        Log.d(TAG, "🎯 TORRENTIO: Found ${streams.size} real streams")
                    } else {
                        Log.w(TAG, "⚠️ Empty response from Torrentio")
                    }
                } else {
                    Log.w(TAG, "❌ Torrentio HTTP ${response.code}: ${response.message}")
                }
            } catch (e: Exception) {
                Log.e(TAG, "❌ Torrentio API error: ${e.message}", e)
                return@withContext emptyList()
            }

            return@withContext streams
        }
    }

    private fun parseTorrentioStream(streamJson: JsonElement): AddonStream? {
        return try {
            val stream = streamJson.asJsonObject
            val title = stream.get("title")?.asString ?: "Unknown"
            val name = stream.get("name")?.asString ?: ""
            val infoHash = stream.get("infoHash")?.asString?.trim()?.takeIf { it.length >= 32 }
            val url = stream.get("url")?.asString?.trim()?.takeIf { it.isNotBlank() }
            val magnetFromUrl = url?.takeIf { it.startsWith("magnet:", ignoreCase = true) }
            val magnet = when {
                !infoHash.isNullOrBlank() -> "magnet:?xt=urn:btih:$infoHash"
                !magnetFromUrl.isNullOrBlank() -> magnetFromUrl
                else -> null
            }

            // Extract size from title if available (format: "💾 18 GB")
            val sizeBytes = extractSizeFromTitle(title)
            val quality = extractQuality(title + " " + name)
            val languages = LanguageParser.extractLanguages(title + " " + name)

            // Extract seeders from title if available (format: "👤 228")
            val seeders = extractSeedersFromTitle(title)

            AddonStream(
                source = AddonSourceType.TORRENTIO,
                title = title,
                url = url,
                infoHash = infoHash,
                magnet = magnet,
                sizeBytes = sizeBytes,
                seeders = seeders,
                peers = 0, // Not provided in current format
                quality = quality,
                languages = languages,
                subtitles = emptyList()
            )
        } catch (e: Exception) {
            Log.w(TAG, "Failed to parse Torrentio stream: ${e.message}")
            null
        }
    }

    private fun extractSizeFromTitle(title: String): Long {
        try {
            // Look for pattern like "💾 18 GB" or "💾 1.96 GB"
            val sizePattern = Regex("""💾\s*([\d.,]+)\s*(GB|MB)""")
            val match = sizePattern.find(title)
            if (match != null) {
                val size = match.groupValues[1].replace(",", "").toDouble()
                val unit = match.groupValues[2]
                return when (unit) {
                    "GB" -> (size * 1024 * 1024 * 1024).toLong()
                    "MB" -> (size * 1024 * 1024).toLong()
                    else -> 0L
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to extract size from title: $title")
        }
        return 0L
    }

    private fun extractSeedersFromTitle(title: String): Int {
        try {
            // Look for pattern like "👤 228"
            val seederPattern = Regex("""👤\s*(\d+)""")
            val match = seederPattern.find(title)
            if (match != null) {
                return match.groupValues[1].toInt()
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to extract seeders from title: $title")
        }
        return 0
    }

    private fun extractQuality(title: String): String {
        return when {
            title.contains("4K", ignoreCase = true) || title.contains("2160p", ignoreCase = true) -> "4K"
            title.contains("1080p", ignoreCase = true) -> "1080p"
            title.contains("720p", ignoreCase = true) -> "720p"
            title.contains("480p", ignoreCase = true) -> "480p"
            else -> "Unknown"
        }
    }

}
