package com.tvonnet.debridxtreamiptv.data.repository

import com.tvonnet.debridxtreamiptv.data.model.XtreamCategory
import com.tvonnet.debridxtreamiptv.data.model.XtreamVodInfo
import java.util.Locale

/**
 * Phase 7: source/cache models lifted verbatim out of the XtreamRepository god-class. They stay in
 * the same `data.repository` package, so every existing reference resolves unchanged — this is a
 * pure relocation with no behaviour change.
 */

enum class DebridCacheStatus {
    VERIFIED_CACHED,
    NOT_CACHED,
    UNKNOWN,
    DIRECT_STREAM
}

data class AddableTorrentIdentity(
    val infoHash: String,
    val magnet: String? = null
)

/**
 * H1: another addon's copy of the SAME file, retained behind the collapsed picker row.
 * Deliberately NOT a nested [MovieSource] — a recursive data class breaks DiffUtil
 * equality and doubles list memory. Holds exactly what the H2 failover needs to swap in.
 */
data class SourceAlternate(
    val provider: String?,
    val sourceType: String?,
    val sourceName: String?,
    val directSource: String?,
    val headers: Map<String, String>? = null,
    val cacheStatus: DebridCacheStatus = DebridCacheStatus.UNKNOWN,
    val identityKey: String? = null,
)

data class MovieSource(
    val stream: XtreamVodInfo,
    val category: XtreamCategory?,
    val label: String,
    val isPrimary: Boolean,
    val languages: List<String>? = null,
    val quality: String? = null,
    val codec: String? = null,
    val sizeBytes: Long? = null,
    val seeders: Int? = null,
    val isCached: Boolean? = null,
    val cacheStatus: DebridCacheStatus = DebridCacheStatus.UNKNOWN,
    val provider: String? = null,
    val sourceType: String? = null,
    val sourceName: String? = null,
    val bingeGroup: String? = null,
    val fileIdx: Int? = null,
    val headers: Map<String, String>? = null,
    val addableTorrentIdentity: AddableTorrentIdentity? = null,
    /** H1: other addons' copies of this same file (failover path, count badge). */
    val alternates: List<SourceAlternate> = emptyList(),
    /** H4: true when [languages] were HEARD by the player, not parsed from the title. */
    val languagesVerified: Boolean = false,
) {
    /** How many addons offered this file — the "4 sources" badge. */
    val addonCount: Int get() = alternates.size + 1
}

// Phase 7: pure VOD title-matching helpers lifted verbatim out of XtreamRepository.getMovieSources.
// They carry their own regexes (previously the repository's companion NON_ALPHANUMERIC_REGEX /
// YEAR_REGEX) so the behaviour is byte-for-byte identical.
private val NON_ALPHANUMERIC_REGEX = Regex("[^a-z0-9]")
private val YEAR_REGEX = Regex("(19|20)\\d{2}")

fun normalizeTitle(value: String?): String? {
    if (value.isNullOrBlank()) return null
    return NON_ALPHANUMERIC_REGEX.replace(value.lowercase(Locale.US), "").takeIf { it.isNotEmpty() }
}

fun extractYear(text: String?): Int? {
    if (text.isNullOrBlank()) return null
    val match = YEAR_REGEX.find(text)
    return match?.value?.toIntOrNull()
}

fun buildMovieSourceLabel(category: XtreamCategory?, stream: XtreamVodInfo): String {
    val parts = mutableListOf<String>()
    category?.category_name?.takeIf { !it.isNullOrBlank() }?.let { parts.add(it.trim()) }
    stream.container_extension?.takeIf { !it.isNullOrBlank() }?.let {
        parts.add(it.uppercase(Locale.US))
    }
    extractYear(stream.releaseDate)?.let { parts.add(it.toString()) }
    return parts.joinToString(" • ").ifBlank { stream.name ?: "Source" }
}
