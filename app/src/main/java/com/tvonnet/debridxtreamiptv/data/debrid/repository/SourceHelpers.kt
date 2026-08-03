package com.tvonnet.debridxtreamiptv.data.debrid.repository

import android.util.Log
import com.tvonnet.debridxtreamiptv.data.debrid.model.AddonSourceType
import com.tvonnet.debridxtreamiptv.data.debrid.model.AddonStream
import com.tvonnet.debridxtreamiptv.data.repository.DebridCacheStatus

private const val TAG = "UnifiedSourceProvider"

/**
 * Phase 7 (UnifiedSourceProvider decomposition), step 1: the pure magnet / info-hash / size / cached-value
 * helpers lifted verbatim out of the UnifiedSourceProvider god-class. They hold no provider state, so they
 * move to top-level functions in the same `data.debrid.repository` package — every existing call site
 * resolves unchanged. Behaviour is byte-for-byte identical.
 */

private val ADDABLE_INFO_HASH = Regex("^(?:[a-fA-F0-9]{40}|[a-zA-Z2-7]{32})$")

internal fun formatFileSize(bytes: Long): String {
    if (bytes < 1024) return "${bytes}B"
    val kb = bytes / 1024.0
    if (kb < 1024) return "%.1fKB".format(kb)
    val mb = kb / 1024.0
    if (mb < 1024) return "%.1fMB".format(mb)
    val gb = mb / 1024.0
    if (gb < 1024) return "%.1fGB".format(gb)
    val tb = gb / 1024.0
    return "%.1fTB".format(tb)
}

internal fun normalizeInfoHash(infoHash: String?): String? {
    val trimmed = infoHash?.trim() ?: return null
    return if (trimmed.length >= 32) trimmed.lowercase() else null
}

internal fun isValidMagnet(magnet: String?): Boolean {
    if (magnet.isNullOrBlank()) return false
    val trimmed = magnet.trim()
    if (!trimmed.startsWith("magnet:", ignoreCase = true)) return false
    val btihIndex = trimmed.indexOf("btih:", ignoreCase = true)
    if (btihIndex == -1) return false
    val hash = trimmed.substring(btihIndex + 5).substringBefore('&').substringBefore('#')
    return hash.length >= 32
}

internal fun extractInfoHashFromMagnet(magnet: String?): String? {
    if (magnet.isNullOrBlank()) return null
    val btihIndex = magnet.indexOf("btih:", ignoreCase = true)
    if (btihIndex == -1) return null
    return normalizeInfoHash(magnet.substring(btihIndex + 5).substringBefore('&').substringBefore('#'))
}

internal fun normalizeAddableInfoHash(infoHash: String?): String? {
    val trimmed = infoHash?.trim() ?: return null
    return trimmed.takeIf { ADDABLE_INFO_HASH.matches(it) }?.lowercase()
}

internal fun isAddableMagnet(magnet: String?): Boolean {
    return extractAddableInfoHashFromMagnet(magnet) != null
}

internal fun extractAddableInfoHashFromMagnet(magnet: String?): String? {
    if (magnet.isNullOrBlank()) return null
    val trimmed = magnet.trim()
    if (!trimmed.startsWith("magnet:", ignoreCase = true)) return null
    val btihIndex = trimmed.indexOf("btih:", ignoreCase = true)
    if (btihIndex == -1) return null
    return normalizeAddableInfoHash(trimmed.substring(btihIndex + 5).substringBefore('&').substringBefore('#'))
}

internal fun DebridCacheStatus.toLegacyCachedFlag(): Boolean? {
    return when (this) {
        DebridCacheStatus.VERIFIED_CACHED,
        DebridCacheStatus.DIRECT_STREAM -> true
        DebridCacheStatus.NOT_CACHED -> false
        DebridCacheStatus.UNKNOWN -> null
    }
}

internal fun parseCachedValue(value: Any?): Boolean? {
    return when (value) {
        is Boolean -> value
        is String -> when (value.trim().lowercase()) {
            "true", "1", "yes" -> true
            "false", "0", "no" -> false
            else -> null
        }
        is Number -> value.toInt() != 0
        else -> null
    }
}

// ── USP-2: pure stream scoring / dedup / label helpers (no provider state) ──

internal fun getSourceLabel(source: AddonSourceType): String {
    return when (source) {
        AddonSourceType.MEDIA_FUSION -> "MediaFusion"
        AddonSourceType.STREMIO -> "Stremio"
        AddonSourceType.ZILEAN -> "Zilean"
        AddonSourceType.TORRENTIO -> "TorrentIO"
        AddonSourceType.DYNAMIC -> "Dynamic"
        AddonSourceType.UNKNOWN -> "PureFire"
    }
}

internal fun deduplicateStreams(streams: List<AddonStream>): List<AddonStream> {
    if (streams.size <= 1) return streams

    val seen = mutableMapOf<String, AddonStream>()
    val noHashStreams = mutableListOf<AddonStream>()

    for (stream in streams) {
        val hash = stream.infoHash?.trim()?.lowercase()
        if (hash.isNullOrBlank()) {
            noHashStreams.add(stream)
            continue
        }
        val dedupKey = buildStreamVariantKey(hash, stream)
        val existing = seen[dedupKey]
        if (existing == null) {
            seen[dedupKey] = stream
        } else {
            // Keep the one with more useful metadata; cache is verified later.
            val existingScore = metadataScore(existing)
            val newScore = metadataScore(stream)
            if (newScore > existingScore) {
                seen[dedupKey] = stream
            }
        }
    }

    val result = seen.values.toList() + noHashStreams
    val removed = streams.size - result.size
    if (removed > 0) {
        Log.d(TAG, "🔄 Dedup removed $removed duplicate streams (${streams.size} → ${result.size})")
    }
    return result
}

internal fun buildStreamVariantKey(hash: String, stream: AddonStream): String {
    val languageKey = normalizeVariantPart(
        stream.languages?.joinToString(",")?.lowercase()
    )
    // The same torrent offered by two providers is only a real choice when the
    // DELIVERY differs: an addon-proxy direct URL streams immediately, while a
    // magnet goes through the RD resolution chain. Provider names alone must
    // not duplicate picker rows for an identical torrent.
    val deliveryKey = if (stream.url.isNullOrBlank()) "magnet" else "direct"
    val fileIdxKey = (stream.extras["fileIdx"] as? Number)?.toString()
        ?: (stream.extras["fileIdx"] as? String)?.trim()?.takeIf { it.isNotBlank() }
    val bingeGroupKey = normalizeVariantPart(stream.extras["bingeGroup"] as? String)

    return buildString {
        append(hash)
        append('|')
        append(languageKey)
        append('|')
        append(deliveryKey)
        append('|')
        append(fileIdxKey.orEmpty())
        append('|')
        append(bingeGroupKey)
    }
}

internal fun normalizeVariantPart(value: String?): String {
    return value?.trim()?.lowercase()?.takeIf { it.isNotBlank() } ?: ""
}

internal fun getAddonLanguageScore(stream: AddonStream, preferredLanguages: List<String>): Int {
    val languages = stream.languages?.map { it.lowercase() } ?: return 0
    val preferredScore = preferredLanguages.firstOrNull { languages.contains(it) }?.let { lang ->
        when (lang) {
            preferredLanguages.firstOrNull() -> 4
            "hi", "de" -> 3
            else -> 2
        }
    } ?: 0
    val multiScore = if (languages.contains("multi")) 2 else 0
    return maxOf(preferredScore, multiScore)
}

internal fun sourcePriority(source: AddonSourceType): Int {
    return when (source) {
        AddonSourceType.MEDIA_FUSION -> 4
        AddonSourceType.STREMIO -> 4
        AddonSourceType.DYNAMIC -> 3
        AddonSourceType.TORRENTIO -> 2
        AddonSourceType.ZILEAN -> 1
        AddonSourceType.UNKNOWN -> 0
    }
}

internal fun qualityPriority(quality: String?): Int {
    return when (quality?.lowercase()) {
        "4k", "2160p" -> 4
        "1080p" -> 3
        "720p" -> 2
        "480p" -> 1
        else -> 0
    }
}

internal fun metadataScore(stream: AddonStream): Int {
    var score = 0
    if (stream.sizeBytes != null && stream.sizeBytes > 0) score += 2
    if (stream.seeders != null && stream.seeders > 0) score += 1
    if (!stream.quality.isNullOrBlank() && stream.quality != "Unknown") score += 1
    if (!stream.url.isNullOrBlank()) score += 1
    return score
}

// ── USP-3: pure content-detection / identity helpers ──

    internal fun stableStreamIdentity(id: String?): String? {
        if (id.isNullOrBlank()) return null
        return id.trim()
            .replace(Regex("_\\d+$"), "")
            .lowercase()
            .takeIf { it.isNotBlank() }
    }

    internal fun streamMatchesStableIdentity(stream: AddonStream, stableId: String): Boolean {
        val hash = normalizeInfoHash(stream.infoHash) ?: extractInfoHashFromMagnet(stream.magnet)
        if (hash == stableId) return true
        // Sources without an infoHash use title.hashCode() as the stable id part.
        return stream.title?.hashCode()?.toString() == stableId
    }

    /**
     * Check if content should be treated as debrid content
     * Improved logic to better identify movies that should use PureFire sources
     * - Uses explicit debrid category flag when available
     * - Applies intelligent detection based on title patterns and categories
     * - Avoids routing obvious TV/live content to debrid
     */
// Enhanced category-based detection
private val MOVIE_CATEGORIES = listOf(
    "movies", "cinema", "film", "movies_vod", "vod_movies"
)
private val TV_CATEGORIES = listOf(
    "live_tv", "live", "sports", "news", "tv_channels", "series", "shows"
)

// Skip obvious non-debrid content based on title patterns
private val NON_DEBRID_PATTERNS = listOf(
    "live ", " news", " weather", " sports channel", " tv channel",
    " documentary series", " reality show", " talk show", " podcast",
    // NOTE: " s" and " e" were removed here — those 2-char substrings matched almost
    // any multi-word movie title (e.g. "Toy Story" -> " s", "The Empire..." -> " e"),
    // misrouting real movies to IPTV. Season/episode detection is already covered by
    // " season ", " episode ", " ep " below and " s01".." s05" in TV_CONTENT_PATTERNS.
    " season ", " episode ", " ep ", " ptv ", " channel "
)

// Skip titles that indicate TV/series content
private val TV_CONTENT_PATTERNS = listOf(
    " season ", " episode ", " s01", " s02", " s03", " s04", " s05",
    " ep ", " part ", " series", " show", " channel ", " tv ",
    " live ", " news ", " weather "
)

// More specific movie detection patterns
private val MOVIE_INDICATORS = listOf(
    "(", ")", "movie", "film", "cinema", "hd", "4k", "bluray", "dvd"
)

// Year patterns (common in movies)
private val MOVIE_YEAR_PATTERN = Regex("\\b(19|20)\\d{2}\\b")

private fun isNonDebridTitle(titleLower: String): Boolean =
    NON_DEBRID_PATTERNS.any { titleLower.contains(it) } ||
        TV_CONTENT_PATTERNS.any { titleLower.contains(it) }

private fun hasStrongMovieIndicators(titleLower: String): Boolean =
    MOVIE_INDICATORS.any { titleLower.contains(it) } &&
        MOVIE_YEAR_PATTERN.containsMatchIn(titleLower)

internal fun isDebridContent(
        categoryId: String?,
        title: String?
    ): Boolean {
        val titleLower = title?.lowercase() ?: ""

        // Explicit debrid category
        if (categoryId == "debrid") {
            Log.d(TAG, "🚨 DEBRID DETECTION SUCCESS: explicit flag = true - WILL SKIP IPTV API")
            return true
        }

        // Check if category suggests this should use IPTV
        if (categoryId != null && TV_CATEGORIES.any { categoryId.lowercase().contains(it) }) {
            Log.d(TAG, "📺 IPTV CATEGORY DETECTED: $categoryId - will use IPTV sources")
            return false
        }

        if (isNonDebridTitle(titleLower)) {
            Log.d(TAG, "📺 NON-DEBRID CONTENT DETECTED: '$title' - will use IPTV sources")
            return false
        }

        // Check title length (movies tend to have longer, more descriptive titles)
        val hasReasonableTitleLength = titleLower.length > 5

        // Final decision logic
        val shouldUseDebrid = when {
            // Skip if title is empty or too short
            titleLower.isBlank() || !hasReasonableTitleLength -> {
                Log.d(TAG, "📺 INVALID TITLE: '$title' - will use IPTV sources")
                false
            }

            // Use debrid for content with strong movie indicators (incl. a year)
            hasStrongMovieIndicators(titleLower) -> {
                Log.d(TAG, "🎬 STRONG MOVIE INDICATORS: '$title' - will try PureFire sources first")
                true
            }

            // Use debrid for movie category content
            MOVIE_CATEGORIES.any { categoryId?.lowercase()?.contains(it) == true } -> {
                Log.d(TAG, "🎬 MOVIE CATEGORY: '$categoryId' - will try PureFire sources first")
                true
            }

            // Default to IPTV for ambiguous content
            else -> {
                Log.d(TAG, "📺 AMBIGUOUS CONTENT: '$title' (category: $categoryId) - defaulting to IPTV sources")
                false
            }
        }

        Log.d(TAG, "🤖 Debrid detection for '$title': $shouldUseDebrid (category: $categoryId)")
        return shouldUseDebrid
    }
