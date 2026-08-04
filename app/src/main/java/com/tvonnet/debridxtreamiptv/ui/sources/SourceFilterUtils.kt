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
    val sortLanguage: String? = null,
    /** H9: the secondary priority — ranks when [sortLanguage] does not match a row. */
    val sortLanguage2: String? = null,
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
    // H9: the major European languages the settings priority list now offers.
    PORTUGUESE("Portuguese"),
    DUTCH("Dutch"),
    POLISH("Polish"),
    TURKISH("Turkish"),
    ALL("All"),
    UNKNOWN("Unknown");

    companion object {
        // The old when-chain as data: every accepted spelling maps to its language;
        // anything else (including "unknown" and "") is UNKNOWN via the map miss.
        private val LANGUAGE_BY_CODE: Map<String, StreamLanguage> = mapOf(
            "hi" to HINDI, "hin" to HINDI, "hindi" to HINDI,
            "en" to ENGLISH, "eng" to ENGLISH, "english" to ENGLISH,
            "pa" to PUNJABI, "pun" to PUNJABI, "punjabi" to PUNJABI,
            "de" to GERMAN, "ger" to GERMAN, "german" to GERMAN,
            "fr" to FRENCH, "fre" to FRENCH, "french" to FRENCH,
            "ur" to URDU, "urdu" to URDU,
            "ta" to TAMIL, "tamil" to TAMIL,
            "te" to TELUGU, "telugu" to TELUGU,
            "ml" to MALAYALAM, "malayalam" to MALAYALAM,
            "kn" to KANNADA, "kannada" to KANNADA,
            "it" to ITALIAN, "ita" to ITALIAN, "italian" to ITALIAN,
            "ru" to RUSSIAN, "rus" to RUSSIAN, "russian" to RUSSIAN,
            "es" to SPANISH, "spa" to SPANISH, "spanish" to SPANISH,
            "pt" to PORTUGUESE, "por" to PORTUGUESE, "portuguese" to PORTUGUESE,
            "nl" to DUTCH, "dut" to DUTCH, "nld" to DUTCH, "dutch" to DUTCH,
            "pl" to POLISH, "pol" to POLISH, "polish" to POLISH,
            "tr" to TURKISH, "tur" to TURKISH, "turkish" to TURKISH,
            "multi" to MULTI, "multilanguage" to MULTI, "multi audio" to MULTI,
            "multi-audio" to MULTI, "dual audio" to MULTI, "dual-audio" to MULTI,
            "dualaudio" to MULTI,
            "all" to ALL,
        )

        fun parse(code: String?): StreamLanguage {
            if (code == null) return UNKNOWN
            return LANGUAGE_BY_CODE[code.lowercase().trim()] ?: UNKNOWN
        }
    }
}

object SourceFilterUtils {

    /**
     * Human-facing source name used for the source filter and its dropdown options:
     * the addon's own name when it reports one, else the provider label ("MediaFusion",
     * "TorrentIO", "IPTV"…), else "Other".
     */
    fun sourceDisplayName(source: MovieSource): String {
        if (source.sourceType == "IPTV") return "IPTV"
        // `provider` carries the configured addon's name (StremThru, AIO, Debridio…);
        // `sourceName` is per-stream text and useless as a filter bucket.
        return source.provider?.takeIf { it.isNotBlank() }
            ?: source.sourceName?.takeIf { it.isNotBlank() }
            ?: "Other"
    }
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
        filtered = applySizeCap(filtered, state.maxSizeBytes)
        filtered = applyQualityFilter(filtered, state.preferredQuality)
        filtered = applyLanguageFilter(filtered, state.preferredLanguage)
        return applyTypeFilter(filtered, state.preferredType)
    }

    private fun applySizeCap(sources: List<MovieSource>, maxSize: Long?): List<MovieSource> {
        if (maxSize == null) return sources
        return sources.filter { source ->
            val size = source.sizeBytes
            size == null || size <= maxSize
        }
    }

    private fun applyQualityFilter(sources: List<MovieSource>, qual: String?): List<MovieSource> {
        if (qual == null || qual == "All") return sources
        val qualEnum = VideoQuality.parse(qual)
        return sources.filter { source ->
            // Type-safe matching based on strictly parsed Enum
            VideoQuality.parse(source.quality) == qualEnum
        }
    }

    private fun applyLanguageFilter(sources: List<MovieSource>, lang: String?): List<MovieSource> {
        if (lang == null || lang == "All") return sources
        val langEnum = StreamLanguage.parse(lang)
        return sources.filter { source ->
            val sourceLangs = source.languages?.map { StreamLanguage.parse(it) } ?: emptyList()
            when (langEnum) {
                StreamLanguage.MULTI -> sourceLangs.contains(StreamLanguage.MULTI)
                StreamLanguage.UNKNOWN -> sourceLangs.isEmpty() || sourceLangs.all { it == StreamLanguage.UNKNOWN }
                // H3: MULTI means "several languages, unspecified" — it must match any
                // specific language filter (it sorts BELOW exact matches via
                // getLanguageMatchScore). Without this, dropping the invented "en"
                // would make the English filter HIDE rows it used to show.
                else -> sourceLangs.contains(langEnum) || sourceLangs.contains(StreamLanguage.MULTI)
            }
        }
    }

    private fun applyTypeFilter(sources: List<MovieSource>, type: String?): List<MovieSource> {
        if (type == null || type == "All") return sources
        return sources.filter { source ->
            when (type) {
                "RD Cached" -> source.cacheStatus == DebridCacheStatus.VERIFIED_CACHED
                // Everything else filters by the actual source name (addon /
                // provider / IPTV) so users can isolate a single source.
                else -> sourceDisplayName(source).equals(type, ignoreCase = true)
            }
        }
    }

    fun apply(sources: List<MovieSource>, state: SourceFilterState): List<MovieSource> {
        val filtered = filter(sources, state)
        val langs = resolveSortLanguages(state)
        val scores = computeScores(filtered, langs.primary, langs.secondary)

        // A language the user selected leads the sort; otherwise cache priority does.
        val lead = if (langs.userSelected) {
            compareByDescending<MovieSource> { scores.lang[it] ?: 0 }
                .thenByDescending { scores.cache[it] ?: 0 }
        } else {
            compareByDescending<MovieSource> { scores.cache[it] ?: 0 }
                .thenByDescending { scores.lang[it] ?: 0 }
        }
        return filtered.sortedWith(
            lead
                .thenByDescending { scores.session[it] ?: 0 }
                .thenByDescending { scores.recovery[it] ?: 0 }
                .thenByDescending { it.seeders ?: -1 }
        )
    }

    /** Which languages drive the sort, and whether the user picked one explicitly. */
    private class SortLanguages(
        val primary: StreamLanguage?,
        val secondary: StreamLanguage?,
        val userSelected: Boolean,
    )

    private fun resolveSortLanguages(state: SourceFilterState): SortLanguages {
        // A language the user explicitly filtered by is a single deliberate choice —
        // the global priority pair steps aside entirely.
        val selected = state.preferredLanguage
            ?.takeIf { it != "All" }
            ?.let { StreamLanguage.parse(it) }
        if (selected != null) return SortLanguages(selected, null, userSelected = true)
        // H9: the settings pair. ALL ("no preference") and unparseable values (incl.
        // the secondary's "NONE") disable the boost exactly like a null sortLanguage.
        return SortLanguages(parsePriority(state.sortLanguage), parsePriority(state.sortLanguage2), userSelected = false)
    }

    private fun parsePriority(value: String?): StreamLanguage? =
        value?.let { StreamLanguage.parse(it) }
            ?.takeIf { it != StreamLanguage.ALL && it != StreamLanguage.UNKNOWN }

    /** Per-source sort scores, keyed by identity so equal-looking sources stay distinct. */
    private class SourceScores(size: Int) {
        val cache = IdentityHashMap<MovieSource, Int>(size)
        val lang = IdentityHashMap<MovieSource, Int>(size)
        val session = IdentityHashMap<MovieSource, Int>(size)
        val recovery = IdentityHashMap<MovieSource, Int>(size)
    }

    // PRE-COMPUTE SCORES (O(N)): Calculate all heavy object mapping operations upfront.
    // By mapping strings to Enums once here, we guarantee O(1) lookups during the O(N log N) sorting phase.
    private fun computeScores(
        filtered: List<MovieSource>,
        sortLangEnum: StreamLanguage?,
        sortLang2Enum: StreamLanguage? = null
    ): SourceScores {
        val scores = SourceScores(filtered.size)
        filtered.forEach { source ->
            // Parse to strictly-typed languages array once per source
            val parsedLanguages = source.languages?.map { StreamLanguage.parse(it) } ?: emptyList()

            scores.cache[source] = getCachePriority(source)
            scores.lang[source] =
                if (sortLangEnum != null) {
                    getLanguageMatchScore(source, parsedLanguages, sortLangEnum, sortLang2Enum)
                } else {
                    0
                }
            scores.session[source] = SessionSourcePreference.score(source)
            scores.recovery[source] = getRecoveryLanguageScore(parsedLanguages)
        }
        return scores
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

    private fun getLanguageMatchScore(
        source: MovieSource,
        languages: List<StreamLanguage>,
        preferred: StreamLanguage,
        secondary: StreamLanguage? = null
    ): Int {
        if (languages.isEmpty()) return 0
        return when {
            // H4/H9 tiering (agrees with SourceSorter.score — the sheet sorts twice):
            // primary verified > primary claimed > secondary verified > secondary
            // claimed > MULTI. A row that only carries the SECONDARY language still
            // outranks a maybe-MULTI row — "Hindi na mile to German" is a real claim.
            languages.contains(preferred) -> if (source.languagesVerified) 6 else 5
            secondary != null && languages.contains(secondary) ->
                if (source.languagesVerified) 4 else 3
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
