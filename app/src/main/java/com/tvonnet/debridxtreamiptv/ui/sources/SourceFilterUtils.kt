package com.tvonnet.debridxtreamiptv.ui.sources

import com.tvonnet.debridxtreamiptv.data.repository.DebridCacheStatus
import com.tvonnet.debridxtreamiptv.data.repository.MovieSource
import java.util.IdentityHashMap

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

/**
 * Strongly-typed enumeration for Video Qualities to prevent brittle raw-string logic.
 */
enum class VideoQuality(val label: String) {
    Q_4K("4K"),
    Q_1080P("1080p"),
    Q_720P("720p"),
    SD("SD"),
    ALL("All"),
    UNKNOWN("Unknown");

    companion object {
        fun parse(raw: String?): VideoQuality {
            if (raw == null) return UNKNOWN
            val q = raw.uppercase()
            if (q.contains("4K") || q.contains("2160")) return Q_4K
            if (q.contains("1080")) return Q_1080P
            if (q.contains("720")) return Q_720P
            if (q.contains("480") || q.contains("360") || q.contains("SD")) return SD
            if (q.equals("ALL", ignoreCase = true)) return ALL
            return UNKNOWN
        }
    }
}

/**
 * Strongly-typed enumeration for Stream Languages.
 * Replaces continuous raw string lookups inside the O(N) scoring loop.
 */
enum class StreamLanguage(val label: String) {
    MULTI("Multi"),
    ENGLISH("English"),
    HINDI("Hindi"),
    PUNJABI("Punjabi"),
    GERMAN("German"),
    FRENCH("French"),
    URDU("Urdu"),
    TAMIL("Tamil"),
    TELUGU("Telugu"),
    MALAYALAM("Malayalam"),
    KANNADA("Kannada"),
    ITALIAN("Italian"),
    RUSSIAN("Russian"),
    SPANISH("Spanish"),
    ALL("All"),
    UNKNOWN("Unknown");

    companion object {
        fun parse(code: String?): StreamLanguage {
            if (code == null) return UNKNOWN
            return when (code.lowercase().trim()) {
                "hi", "hin", "hindi" -> HINDI
                "en", "eng", "english" -> ENGLISH
                "pa", "pun", "punjabi" -> PUNJABI
                "de", "ger", "german" -> GERMAN
                "fr", "fre", "french" -> FRENCH
                "ur", "urdu" -> URDU
                "ta", "tamil" -> TAMIL
                "te", "telugu" -> TELUGU
                "ml", "malayalam" -> MALAYALAM
                "kn", "kannada" -> KANNADA
                "it", "ita", "italian" -> ITALIAN
                "ru", "rus", "russian" -> RUSSIAN
                "es", "spa", "spanish" -> SPANISH
                "multi", "multilanguage", "multi audio", "multi-audio", "dual audio", "dual-audio", "dualaudio" -> MULTI
                "all" -> ALL
                "unknown", "" -> UNKNOWN
                else -> UNKNOWN
            }
        }
    }
}

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

    fun filter(sources: List<MovieSource>, state: SourceFilterState): List<MovieSource> {
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
            val qualEnum = VideoQuality.parse(qual)
            filtered = filtered.filter { source ->
                // Type-safe matching based on strictly parsed Enum
                VideoQuality.parse(source.quality) == qualEnum
            }
        }

        val lang = state.preferredLanguage
        if (lang != null && lang != "All") {
            val langEnum = StreamLanguage.parse(lang)
            filtered = filtered.filter { source ->
                val sourceLangs = source.languages?.map { StreamLanguage.parse(it) } ?: emptyList()
                when (langEnum) {
                    StreamLanguage.MULTI -> sourceLangs.contains(StreamLanguage.MULTI)
                    StreamLanguage.UNKNOWN -> sourceLangs.isEmpty() || sourceLangs.all { it == StreamLanguage.UNKNOWN }
                    else -> sourceLangs.contains(langEnum)
                }
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

        return filtered
    }

    fun apply(sources: List<MovieSource>, state: SourceFilterState): List<MovieSource> {
        val filtered = filter(sources, state)
        val selectedSortLang = state.preferredLanguage
            ?.takeIf { it != "All" }
            ?.let { StreamLanguage.parse(it) }
        val globalSortLang = state.sortLanguage?.let { StreamLanguage.parse(it) }
        val sortLangEnum = selectedSortLang ?: globalSortLang

        // PRE-COMPUTE SCORES (O(N)): Calculate all heavy object mapping operations upfront.
        // By mapping strings to Enums once here, we guarantee O(1) lookups during the O(N log N) sorting phase.
        val cacheScores = IdentityHashMap<MovieSource, Int>(filtered.size)
        val langScores = IdentityHashMap<MovieSource, Int>(filtered.size)
        val sessionScores = IdentityHashMap<MovieSource, Int>(filtered.size)
        val recoveryScores = IdentityHashMap<MovieSource, Int>(filtered.size)

        filtered.forEach { source ->
            // Parse to strictly-typed languages array once per source
            val parsedLanguages = source.languages?.map { StreamLanguage.parse(it) } ?: emptyList()

            cacheScores[source] = getCachePriority(source)
            langScores[source] = if (sortLangEnum != null) getLanguageMatchScore(parsedLanguages, sortLangEnum) else 0
            sessionScores[source] = SessionSourcePreference.score(source)
            recoveryScores[source] = getRecoveryLanguageScore(parsedLanguages)
        }

        return if (selectedSortLang != null) {
            filtered.sortedWith(
                compareByDescending<MovieSource> { langScores[it] ?: 0 }
                    .thenByDescending { cacheScores[it] ?: 0 }
                    .thenByDescending { sessionScores[it] ?: 0 }
                    .thenByDescending { recoveryScores[it] ?: 0 }
                    .thenByDescending { it.seeders ?: -1 }
            )
        } else {
            filtered.sortedWith(
                compareByDescending<MovieSource> { cacheScores[it] ?: 0 }
                    .thenByDescending { langScores[it] ?: 0 }
                    .thenByDescending { sessionScores[it] ?: 0 }
                    .thenByDescending { recoveryScores[it] ?: 0 }
                    .thenByDescending { it.seeders ?: -1 }
            )
        }
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

    private fun getLanguageMatchScore(languages: List<StreamLanguage>, preferred: StreamLanguage): Int {
        if (languages.isEmpty()) return 0
        return when {
            // Type-safe O(1) enum identity check replaces slow string manipulation
            languages.contains(preferred) -> 3
            languages.contains(StreamLanguage.MULTI) -> 1
            else -> 0
        }
    }

    private fun getRecoveryLanguageScore(languages: List<StreamLanguage>): Int {
        if (languages.isEmpty()) return 0
        return when {
            languages.contains(StreamLanguage.HINDI) || languages.contains(StreamLanguage.GERMAN) -> 2
            languages.contains(StreamLanguage.MULTI) -> 1
            else -> 0
        }
    }

    fun mapLanguageCodeToName(code: String): String {
        val parsed = StreamLanguage.parse(code)
        if (parsed != StreamLanguage.UNKNOWN) return parsed.label
        
        return if (code.isNotEmpty()) {
            code.replaceFirstChar { it.uppercase() }
        } else {
            code
        }
    }
}
