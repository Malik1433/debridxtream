package com.tvonnet.debridxtreamiptv.data.debrid.source

import android.util.Log
import com.google.gson.Gson
import com.google.gson.JsonElement
import com.google.gson.JsonParser
import com.tvonnet.debridxtreamiptv.data.debrid.model.AddonDefinition
import com.tvonnet.debridxtreamiptv.data.debrid.model.AddonSourceType
import com.tvonnet.debridxtreamiptv.data.debrid.model.AddonStream
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
 * Generic, data-driven scraper engine.
 *
 * Instead of hard-coding each provider's API quirks, this fetcher reads the
 * metadata inside an [AddonDefinition] (url template, search pattern, JSON
 * keys) to construct requests, parse results, and emit [AddonStream] objects.
 *
 * Supports:
 * - `json_imdb` type — Stremio-compatible addons queried by IMDb ID.
 * - `reg_hash` type  — MediaFusion-style addons with encoded config paths.
 *
 * Args:
 *   gson: Shared Gson instance for JSON parsing.
 *
 * Returns:
 *   Lists of [AddonStream] parsed from remote JSON payloads.
 */
@Singleton
class DynamicAddonFetcher @Inject constructor(
    private val gson: Gson
) {

    companion object {
        private const val TAG = "DynamicAddonFetcher"
        private const val PER_SCRAPER_TIMEOUT_SECONDS = 8L
    }

    /** Shared OkHttp client with strict per-scraper timeout. */
    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(PER_SCRAPER_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        .readTimeout(PER_SCRAPER_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        .build()

    // ── Movie Sources ────────────────────────────────────────────────────

    /**
     * Fetch movie streams from a single addon definition.
     *
     * @param definition The scraper configuration (URL template, keys, etc.).
     * @param imdbId     IMDb ID for the movie (e.g. "tt1375666").
     * @return Parsed list of [AddonStream], or empty if the provider failed.
     */
    suspend fun fetchMovieSources(
        definition: AddonDefinition,
        imdbId: String?
    ): List<AddonStream> = withContext(Dispatchers.IO) {
        if (imdbId.isNullOrBlank()) {
            Log.w(TAG, "⚠️ [${definition.name}] Skipped — no IMDb ID")
            return@withContext emptyList()
        }

        val searchPart = definition.searchPattern?.movie
            ?.replace("%imdbId", imdbId)
            ?: "movie/$imdbId"

        val url = buildFinalUrl(definition, searchPart)
        Log.d(TAG, "Movie fetch [${definition.name}]: ${SensitiveLogRedactor.describeUrl(url)}")
        fetchAndParse(definition, url)
    }

    // ── Series/Episode Sources ───────────────────────────────────────────

    /**
     * Fetch episode streams from a single addon definition.
     *
     * @param definition The scraper configuration.
     * @param imdbId     IMDb ID for the series (e.g. "tt0944947").
     * @param season     Season number.
     * @param episode    Episode number.
     * @return Parsed list of [AddonStream], or empty if the provider failed.
     */
    suspend fun fetchEpisodeSources(
        definition: AddonDefinition,
        imdbId: String?,
        season: Int,
        episode: Int
    ): List<AddonStream> = withContext(Dispatchers.IO) {
        if (imdbId.isNullOrBlank()) {
            Log.w(TAG, "⚠️ [${definition.name}] Skipped — no IMDb ID")
            return@withContext emptyList()
        }

        val searchPart = definition.searchPattern?.tv
            ?.replace("%imdbId", imdbId)
            ?.replace("%season", season.toString())
            ?.replace("%episode", episode.toString())
            ?: "series/$imdbId:$season:$episode"

        val url = buildFinalUrl(definition, searchPart)
        Log.d(TAG, "Episode fetch [${definition.name}]: ${SensitiveLogRedactor.describeUrl(url)}")
        fetchAndParse(definition, url)
    }

    // ── Internal Machinery ───────────────────────────────────────────────

    /**
     * Builds the fully-resolved URL from the definition's template and
     * the computed search path.
     */
    private fun buildFinalUrl(definition: AddonDefinition, searchPath: String): String {
        val template = definition.urlTemplate

        // The template may contain %searchPattern which we substitute directly
        return if (template.contains("%searchPattern")) {
            template.replace("%searchPattern", searchPath)
        } else {
            // Fallback: append the search pattern as a path
            val base = template.trimEnd('/')
            "$base/$searchPath.json"
        }
    }

    /**
     * Executes the HTTP request and parses the JSON response using the
     * metadata encoded in the [AddonDefinition].
     *
     * @param definition The scraper configuration.
     * @param url        Fully-resolved endpoint URL.
     * @return Parsed streams.
     */
    private fun fetchAndParse(definition: AddonDefinition, url: String): List<AddonStream> {
        return try {
            val request = Request.Builder()
                .url(url)
                .header("User-Agent", "Mozilla/5.0")
                .build()

            val response = httpClient.newCall(request).execute()

            if (!response.isSuccessful) {
                Log.w(TAG, "❌ [${definition.name}] HTTP ${response.code}: ${response.message}")
                return emptyList()
            }

            val body = response.body?.string()
            if (body.isNullOrEmpty()) {
                Log.w(TAG, "⚠️ [${definition.name}] Empty response body")
                return emptyList()
            }

            val rootElement = JsonParser.parseString(body)
            val resultsKey = definition.jsonResultsKey ?: "streams"
            val streamsArray = if (rootElement.isJsonObject) {
                rootElement.asJsonObject.get(resultsKey)?.asJsonArray
            } else if (rootElement.isJsonArray) {
                rootElement.asJsonArray
            } else {
                null
            }

            if (streamsArray == null || streamsArray.size() == 0) {
                Log.d(TAG, "📭 [${definition.name}] No streams in response")
                return emptyList()
            }

            val streams = streamsArray.mapNotNull { element ->
                try {
                    parseStream(definition, element)
                } catch (e: Exception) {
                    Log.w(TAG, "⚠️ [${definition.name}] Parse error: ${e.message}")
                    null
                }
            }

            Log.d(TAG, "✅ [${definition.name}] Parsed ${streams.size} streams")
            streams
        } catch (e: Exception) {
            Log.e(TAG, "❌ [${definition.name}] Fetch failed: ${e.message}")
            emptyList()
        }
    }

    /**
     * Parses a single JSON element from the streams array into an [AddonStream].
     *
     * Uses the definition's target keys to navigate the JSON structure.
     * Supports both flat keys ("infoHash") and nested dot-notation keys
     * ("behaviorHints.filename").
     */
    private fun parseStream(definition: AddonDefinition, element: JsonElement): AddonStream? {
        val obj = element.asJsonObject

        // ── Extract info hash or URL ─────────────────────────────────────
        val infoHash = definition.infoHashTargetKey?.let { key ->
            resolveJsonPath(obj, key)?.asString?.trim()?.takeIf { it.length >= 32 }
        }

        val httpUrl = definition.httpUrlTargetKey?.let { key ->
            resolveJsonPath(obj, key)?.asString?.trim()?.takeIf { it.isNotBlank() }
        }

        // Also check for a top-level "url" regardless of definition settings
        val directUrl = httpUrl
            ?: obj.get("url")?.asString?.trim()?.takeIf { it.isNotBlank() }

        // ── Require at least one link source ──────────────────────────────
        if (infoHash == null && directUrl.isNullOrBlank()) {
            return null
        }

        // ── Title / filename ─────────────────────────────────────────────
        val torrentName = definition.torrentNameTargetKey?.let { key ->
            resolveJsonPath(obj, key)?.asString
        }
        val title = obj.get("title")?.asString
        val name = obj.get("name")?.asString
        val displayTitle = torrentName ?: title ?: name ?: "Unknown"

        // ── Size in bytes ────────────────────────────────────────────────
        val sizeBytes = extractSize(definition, obj, displayTitle)

        // ── Quality ──────────────────────────────────────────────────────
        val quality = extractQuality(displayTitle + " " + (name ?: ""))

        // ── Languages ────────────────────────────────────────────────────
        val languages = LanguageParser.extractLanguages(displayTitle + " " + (name ?: ""))

        // ── Seeders ──────────────────────────────────────────────────────
        val seeders = obj.get("seeders")?.asInt ?: extractSeedersFromText(displayTitle)

        // ── Magnet link ──────────────────────────────────────────────────
        val magnet = when {
            !infoHash.isNullOrBlank() -> "magnet:?xt=urn:btih:$infoHash"
            !directUrl.isNullOrBlank() && directUrl.startsWith("magnet:", ignoreCase = true) -> directUrl
            else -> null
        }

        // ── URL (for direct http streams, e.g. MediaFusion) ──────────────
        val streamUrl = directUrl?.replace("stremio://", "https://")

        // ── Extras ───────────────────────────────────────────────────────
        val extras = mutableMapOf<String, Any?>(
            "providerName" to definition.name,
            "definitionType" to definition.type
        )
        obj.get("description")?.asString?.let { extras["description"] = it }

        return AddonStream(
            source = AddonSourceType.DYNAMIC,
            title = displayTitle,
            url = streamUrl,
            infoHash = infoHash,
            magnet = magnet,
            sizeBytes = sizeBytes,
            seeders = seeders,
            peers = obj.get("peers")?.asInt ?: 0,
            quality = quality,
            languages = languages,
            subtitles = emptyList(),
            extras = extras
        )
    }

    // ── JSON Helpers ─────────────────────────────────────────────────────

    /**
     * Navigates a dot-separated path (e.g. "behaviorHints.filename") inside
     * a JsonObject and returns the leaf element or null.
     */
    private fun resolveJsonPath(root: JsonElement, path: String): JsonElement? {
        val segments = path.split(".")
        var current: JsonElement? = root
        for (segment in segments) {
            current = (current as? com.google.gson.JsonObject)?.get(segment) ?: return null
        }
        return current?.takeIf { !it.isJsonNull }
    }

    // ── Size Extraction ──────────────────────────────────────────────────

    /**
     * Attempts to extract file size from the JSON object using:
     * 1. The definition's `sizeAttr` key (e.g. "behaviorHints.videoSize").
     * 2. The definition's `sizePattern` regex against the title text.
     * 3. Fallback emoji pattern (💾 18 GB).
     */
    private fun extractSize(
        definition: AddonDefinition,
        obj: JsonElement,
        titleText: String
    ): Long {
        // Strategy 1: Structured JSON attribute
        definition.sizeAttr?.let { attr ->
            val sizeElement = resolveJsonPath(obj, attr)
            if (sizeElement != null && !sizeElement.isJsonNull) {
                try {
                    return sizeElement.asLong
                } catch (_: Exception) { /* fall through */ }
            }
        }

        // Strategy 2: Regex from definition (e.g. "💾\\s*([\\d.]+)\\s*(GB|MB)")
        definition.sizePattern?.let { pattern ->
            try {
                val regex = Regex(pattern)
                val match = regex.find(titleText)
                if (match != null) {
                    return parseSizeMatch(match)
                }
            } catch (_: Exception) { /* invalid regex, fall through */ }
        }

        // Strategy 3: Fallback common emoji pattern
        return extractSizeFromEmojiPattern(titleText)
    }

    /**
     * Parses a regex match with groups (value, unit) into bytes.
     *
     * @param match Regex match containing groups: [1]=number, [2]=unit.
     * @return Size in bytes.
     */
    private fun parseSizeMatch(match: MatchResult): Long {
        val sizeValue = match.groupValues[1].replace(",", "").toDoubleOrNull() ?: return 0L
        val unit = match.groupValues[2].uppercase()
        return when (unit) {
            "GB" -> (sizeValue * 1024 * 1024 * 1024).toLong()
            "MB" -> (sizeValue * 1024 * 1024).toLong()
            "KB" -> (sizeValue * 1024).toLong()
            else -> 0L
        }
    }

    /** Fallback: look for 💾 emoji followed by size. */
    private fun extractSizeFromEmojiPattern(text: String): Long {
        return try {
            val regex = Regex("""💾\s*([\d.,]+)\s*(GB|MB)""")
            val match = regex.find(text) ?: return 0L
            parseSizeMatch(match)
        } catch (_: Exception) {
            0L
        }
    }

    // ── Metadata Extraction ──────────────────────────────────────────────

    /** Extracts seeders from text patterns like "👤 228". */
    private fun extractSeedersFromText(text: String): Int {
        return try {
            val regex = Regex("""👤\s*(\d+)""")
            regex.find(text)?.groupValues?.get(1)?.toIntOrNull() ?: 0
        } catch (_: Exception) {
            0
        }
    }

    /** Extracts video quality from text. */
    private fun extractQuality(text: String): String {
        return when {
            text.contains("4K", ignoreCase = true) || text.contains("2160p", ignoreCase = true) -> "4K"
            text.contains("1080p", ignoreCase = true) -> "1080p"
            text.contains("720p", ignoreCase = true) -> "720p"
            text.contains("480p", ignoreCase = true) -> "480p"
            else -> "Unknown"
        }
    }
}
