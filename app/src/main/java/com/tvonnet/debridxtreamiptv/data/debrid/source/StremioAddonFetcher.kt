package com.tvonnet.debridxtreamiptv.data.debrid.source

import android.util.Log
import com.google.gson.Gson
import com.google.gson.JsonElement
import com.tvonnet.debridxtreamiptv.data.debrid.model.AddonSourceType
import com.tvonnet.debridxtreamiptv.data.debrid.model.AddonStream
import com.tvonnet.debridxtreamiptv.data.debrid.model.StremioManifest
import com.tvonnet.debridxtreamiptv.data.debrid.model.StremioStream
import com.tvonnet.debridxtreamiptv.data.debrid.model.StremioStreamResponse
import com.tvonnet.debridxtreamiptv.data.debrid.util.LanguageParser
import com.tvonnet.debridxtreamiptv.util.SensitiveLogRedactor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class StremioAddonFetcher @Inject constructor(
    private val gson: Gson
) {

    companion object {
        private const val TAG = "StremioAddonFetcher"
        private const val TIMEOUT_SECONDS = 20L

        fun normalizeManifestUrl(url: String): String {
            return url.trim()
                .replaceFirst("stremio://", "https://", ignoreCase = true)
                .trimEnd('/')
        }

        fun buildStreamUrl(manifestUrl: String, type: String, streamId: String): String {
            val normalized = normalizeManifestUrl(manifestUrl)
            val manifestIndex = normalized.indexOf("manifest.json", ignoreCase = true)
            if (manifestIndex >= 0) {
                val base = normalized.substring(0, manifestIndex).trimEnd('/')
                val suffix = normalized.substring(manifestIndex + "manifest.json".length)
                return "$base/stream/$type/$streamId.json$suffix"
            }

            return "${normalized.trimEnd('/')}/stream/$type/$streamId.json"
        }
    }

    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(TIMEOUT_SECONDS, TimeUnit.SECONDS)
        .readTimeout(TIMEOUT_SECONDS, TimeUnit.SECONDS)
        .build()

    suspend fun fetchMovieSources(manifestUrl: String, imdbId: String?): List<AddonStream> = withContext(Dispatchers.IO) {
        if (imdbId.isNullOrBlank()) return@withContext emptyList()
        fetchSources(manifestUrl, "movie", imdbId)
    }

    suspend fun fetchEpisodeSources(
        manifestUrl: String,
        imdbId: String?,
        season: Int,
        episode: Int
    ): List<AddonStream> = withContext(Dispatchers.IO) {
        if (imdbId.isNullOrBlank()) return@withContext emptyList()
        fetchSources(manifestUrl, "series", "$imdbId:$season:$episode")
    }

    private fun fetchSources(manifestUrl: String, type: String, streamId: String): List<AddonStream> {
        val normalizedManifestUrl = normalizeManifestUrl(manifestUrl)
        if (!isValidManifestUrl(normalizedManifestUrl)) {
            Log.w(TAG, "Invalid Stremio manifest URL: ${SensitiveLogRedactor.describeUrl(normalizedManifestUrl)}")
            return emptyList()
        }

        return try {
            val manifest = fetchManifest(normalizedManifestUrl) ?: return emptyList()
            if (!manifestSupports(manifest, type)) {
                Log.d(TAG, "Manifest does not support $type streams: ${manifest.name ?: manifest.id ?: "Unknown"}")
                return emptyList()
            }

            val streamUrl = buildStreamUrl(normalizedManifestUrl, type, streamId)
            val request = Request.Builder()
                .url(streamUrl)
                .header("User-Agent", "Stremio/1.0")
                .header("Accept", "application/json")
                .build()

            httpClient.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    Log.w(TAG, "Stremio stream fetch HTTP ${response.code}: ${SensitiveLogRedactor.describeUrl(streamUrl)}")
                    return emptyList()
                }

                val body = response.body?.string()
                if (body.isNullOrBlank()) return emptyList()

                val parsed = gson.fromJson(body, StremioStreamResponse::class.java)
                parsed.streams.mapNotNull { stream ->
                    mapStream(manifest, stream)
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Stremio addon failed: ${SensitiveLogRedactor.describeUrl(normalizedManifestUrl)} ${e.javaClass.simpleName}")
            emptyList()
        }
    }

    private fun fetchManifest(manifestUrl: String): StremioManifest? {
        val request = Request.Builder()
            .url(manifestUrl)
            .header("User-Agent", "Stremio/1.0")
            .header("Accept", "application/json")
            .build()

        httpClient.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                Log.w(TAG, "Stremio manifest HTTP ${response.code}: ${SensitiveLogRedactor.describeUrl(manifestUrl)}")
                return null
            }

            val body = response.body?.string()
            if (body.isNullOrBlank()) return null
            return gson.fromJson(body, StremioManifest::class.java)
        }
    }

    private fun manifestSupports(manifest: StremioManifest, type: String): Boolean {
        val typeSupported = manifest.types.isNullOrEmpty() ||
            manifest.types.any { it.equals(type, ignoreCase = true) }
        if (!typeSupported) return false

        val resources = manifest.resources ?: return true
        if (resources.isEmpty()) return true

        return resources.any { resourceSupportsStream(it, type) }
    }

    private fun resourceSupportsStream(element: JsonElement, type: String): Boolean {
        return when {
            element.isJsonPrimitive -> element.asString.equals("stream", ignoreCase = true)
            element.isJsonObject -> {
                val obj = element.asJsonObject
                val name = obj.get("name")?.asString
                if (!name.equals("stream", ignoreCase = true)) return false
                val types = obj.getAsJsonArray("types") ?: return true
                types.any { it.asString.equals(type, ignoreCase = true) }
            }
            else -> false
        }
    }

    private fun mapStream(manifest: StremioManifest, stream: StremioStream): AddonStream? {
        val infoHash = stream.infoHash?.trim()?.takeIf { it.length >= 32 }
        val normalizedUrl = stream.url
            ?.trim()
            ?.takeIf { it.isNotBlank() }
            ?.replaceFirst("stremio://", "https://", ignoreCase = true)

        val magnet = when {
            !infoHash.isNullOrBlank() -> "magnet:?xt=urn:btih:$infoHash"
            !normalizedUrl.isNullOrBlank() && normalizedUrl.startsWith("magnet:", ignoreCase = true) -> normalizedUrl
            else -> null
        }

        if (infoHash == null && normalizedUrl.isNullOrBlank() && magnet == null) {
            return null
        }

        val displayTitle = listOfNotNull(
            stream.behaviorHints?.filename,
            stream.title,
            stream.name,
            stream.description
        ).firstOrNull { it.isNotBlank() } ?: "Stremio Stream"

        val textForParsing = listOfNotNull(
            stream.behaviorHints?.filename,
            stream.title,
            stream.name,
            stream.description
        ).joinToString(" ")

        val extras = buildMap<String, Any?> {
            put("providerName", manifest.name ?: manifest.id ?: "Stremio")
            put("definitionType", "stremio_manifest")
            stream.description?.let { put("description", it) }
            stream.name?.let { put("sourceName", it) }
            stream.fileIdx?.let { put("fileIdx", it) }
            stream.behaviorHints?.bingeGroup?.let { put("bingeGroup", it) }
            manifest.id?.let { put("stremioAddonId", it) }
        }

        return AddonStream(
            source = AddonSourceType.STREMIO,
            title = displayTitle,
            url = normalizedUrl?.takeUnless { it.startsWith("magnet:", ignoreCase = true) },
            infoHash = infoHash,
            magnet = magnet,
            sizeBytes = stream.behaviorHints?.videoSize ?: stream.videoSize,
            seeders = extractSeeders(textForParsing),
            peers = null,
            quality = extractQuality(textForParsing),
            languages = LanguageParser.extractLanguages(textForParsing),
            subtitles = emptyList(),
            extras = extras,
            headers = stream.behaviorHints?.proxyHeaders ?: stream.behaviorHints?.headers
        )
    }

    private fun isValidManifestUrl(url: String): Boolean {
        val lower = url.lowercase()
        return lower.startsWith("https://") && lower.contains("manifest.json")
    }

    private fun extractQuality(text: String): String {
        return when {
            text.contains("2160p", ignoreCase = true) || text.contains("4k", ignoreCase = true) -> "4K"
            text.contains("1080p", ignoreCase = true) -> "1080p"
            text.contains("720p", ignoreCase = true) -> "720p"
            text.contains("480p", ignoreCase = true) -> "480p"
            else -> "Unknown"
        }
    }

    private fun extractSeeders(text: String): Int? {
        val match = Regex("""(?i)\b(\d{1,6})\s*(seeders|seeds|seeder)\b""").find(text)
        return match?.groupValues?.getOrNull(1)?.toIntOrNull()
    }
}
