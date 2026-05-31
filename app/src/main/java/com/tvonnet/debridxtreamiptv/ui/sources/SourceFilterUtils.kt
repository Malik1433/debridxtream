package com.tvonnet.debridxtreamiptv.ui.sources

import com.tvonnet.debridxtreamiptv.data.repository.DebridCacheStatus
import com.tvonnet.debridxtreamiptv.data.repository.MovieSource

data class SourceFilterState(
    val cachedOnly: Boolean = false,
    val maxSizeBytes: Long? = null,
    val preferredLanguage: String? = null,
    val preferredQuality: String? = null,
    val preferredType: String? = null,
    val sortLanguage: String? = null
)

data class SizeFilterOption(
    val label: String,
    val maxSizeBytes: Long?
)

object SourceFilterUtils {
    private const val GB_BYTES = 1024L * 1024L * 1024L

    val SIZE_OPTIONS = listOf(
        SizeFilterOption("No cap", null),
        SizeFilterOption("2 GB", 2L * GB_BYTES),
        SizeFilterOption("5 GB", 5L * GB_BYTES),
        SizeFilterOption("10 GB", 10L * GB_BYTES),
        SizeFilterOption("25 GB", 25L * GB_BYTES)
    )

    val QUALITY_OPTIONS = arrayOf("All", "4K", "1080p", "720p", "SD")
    val LANGUAGE_OPTIONS = arrayOf("All", "Multi", "English", "Hindi", "Punjabi", "German", "French", "Urdu", "Unknown")
    val TYPE_OPTIONS = arrayOf("All", "RD Cached", "Direct", "Torrent/Add-on", "Unknown")

    fun apply(sources: List<MovieSource>, state: SourceFilterState): List<MovieSource> {
        var filtered = sources

        if (state.cachedOnly) {
            filtered = filtered.filter { isPlaybackReady(it) }
        }

        val maxSize = state.maxSizeBytes
        if (maxSize != null) {
            filtered = filtered.filter { source ->
                val size = source.sizeBytes
                size == null || size <= maxSize
            }
        }

        val qual = state.preferredQuality
        if (qual != null && qual != "All") {
            filtered = filtered.filter { source ->
                val q = source.quality?.uppercase() ?: "UNKNOWN"
                when (qual) {
                    "4K" -> q.contains("4K") || q.contains("2160")
                    "1080p" -> q.contains("1080")
                    "720p" -> q.contains("720")
                    "SD" -> q.contains("480") || q.contains("360") || q.contains("SD")
                    else -> true
                }
            }
        }

        val lang = state.preferredLanguage
        if (lang != null && lang != "All") {
            val languageMatches = filtered.filter { source ->
                hasExactLanguageMatch(source, lang)
            }
            if (languageMatches.isNotEmpty()) {
                filtered = languageMatches
            }
        }

        val type = state.preferredType
        if (type != null && type != "All") {
            filtered = filtered.filter { source ->
                when (type) {
                    "RD Cached" -> source.cacheStatus == DebridCacheStatus.VERIFIED_CACHED
                    "Direct" -> source.cacheStatus == DebridCacheStatus.DIRECT_STREAM
                    "Torrent/Add-on" -> source.cacheStatus == DebridCacheStatus.NOT_CACHED
                    "Unknown" -> source.cacheStatus == DebridCacheStatus.UNKNOWN
                    else -> true
                }
            }
        }

        val selectedSortLang = state.preferredLanguage
            ?.takeIf { it != "All" }
            ?.lowercase()
        val globalSortLang = state.sortLanguage?.lowercase()
        val sortLang = selectedSortLang ?: globalSortLang

        filtered = if (selectedSortLang != null) {
            filtered.sortedWith(
                compareByDescending<MovieSource> { getLanguageMatchScore(it, selectedSortLang) }
                    .thenByDescending { getCachePriority(it) }
                    .thenByDescending { SessionSourcePreference.score(it) }
                    .thenByDescending { getRecoveryLanguageScore(it) }
                    .thenByDescending { it.seeders ?: -1 }
            )
        } else {
            filtered.sortedWith(
                compareByDescending<MovieSource> { getCachePriority(it) }
                    .thenByDescending { if (sortLang != null) getLanguageMatchScore(it, sortLang) else 0 }
                    .thenByDescending { SessionSourcePreference.score(it) }
                    .thenByDescending { getRecoveryLanguageScore(it) }
                    .thenByDescending { it.seeders ?: -1 }
            )
        }

        return filtered
    }

    fun hasCacheConfidence(source: MovieSource): Boolean {
        return isPlaybackReady(source)
    }

    fun isPlaybackReady(source: MovieSource): Boolean {
        return source.cacheStatus == DebridCacheStatus.VERIFIED_CACHED
    }

    private fun getCachePriority(source: MovieSource): Int {
        return when (source.cacheStatus) {
            DebridCacheStatus.VERIFIED_CACHED -> 4
            DebridCacheStatus.DIRECT_STREAM -> 3
            DebridCacheStatus.UNKNOWN -> 1
            DebridCacheStatus.NOT_CACHED -> 0
        }
    }

    private fun getLanguageMatchScore(source: MovieSource, preferred: String): Int {
        val mappedPreferred = mapLanguageCodeToName(preferred.lowercase())
        val languages = source.languages?.map { it.lowercase() } ?: return 0
        return when {
            languages.any { mapLanguageCodeToName(it) == mappedPreferred } -> 3
            languages.contains("multi") -> 1
            else -> 0
        }
    }

    private fun hasExactLanguageMatch(source: MovieSource, preferred: String): Boolean {
        val languages = source.languages?.map { it.lowercase().trim() } ?: emptyList()
        return when (preferred) {
            "Multi" -> languages.any { mapLanguageCodeToName(it) == "Multi" }
            "Unknown" -> languages.isEmpty()
            else -> languages.any { mapLanguageCodeToName(it) == preferred }
        }
    }

    private fun getRecoveryLanguageScore(source: MovieSource): Int {
        val languages = source.languages?.map { it.lowercase() } ?: return 0
        return when {
            languages.contains("hi") || languages.contains("de") -> 2
            languages.contains("multi") -> 1
            else -> 0
        }
    }

    fun mapLanguageCodeToName(code: String): String {
        return when (code) {
            "hi", "hin", "hindi" -> "Hindi"
            "en", "eng", "english" -> "English"
            "pa", "pun", "punjabi" -> "Punjabi"
            "de", "ger", "german" -> "German"
            "fr", "fre", "french" -> "French"
            "ur", "urdu" -> "Urdu"
            "ta", "tamil" -> "Tamil"
            "te", "telugu" -> "Telugu"
            "ml", "malayalam" -> "Malayalam"
            "kn", "kannada" -> "Kannada"
            "it", "ita", "italian" -> "Italian"
            "ru", "rus", "russian" -> "Russian"
            "es", "spa", "spanish" -> "Spanish"
            "multi", "multilanguage", "multi audio", "multi-audio", "dual audio", "dual-audio", "dualaudio" -> "Multi"
            else -> {
                if (code.isNotEmpty()) {
                    code.replaceFirstChar { it.uppercase() }
                } else {
                    code
                }
            }
        }
    }
}
