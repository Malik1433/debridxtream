package com.tvonnet.debridxtreamiptv.ui.sources

import com.tvonnet.debridxtreamiptv.data.repository.DebridCacheStatus
import com.tvonnet.debridxtreamiptv.data.repository.MovieSource

/**
 * Orders sources by the user's preferred audio language before any quality /
 * language / type filters are applied. Higher score = shown first.
 *
 * ```
 * score(source) =
 *   +100  if source.languages contains preferredLang
 *   +50   if source.isCached (DIRECT)
 *   +20   if quality == 4K
 *   +10   if quality == 1080p
 *   +5    otherwise
 *   -0.3 × fileSizeGB   (prefer smaller within the same tier)
 * ```
 *
 * `preferredLang` is a short code (EN / HI / FR / ES / MULTI). "ALL" (or blank)
 * disables the language boost so ordering falls back to cache + quality + size.
 */
object SourceSorter {

    private const val GB_BYTES = 1024.0 * 1024.0 * 1024.0

    fun sort(sources: List<MovieSource>, preferredLang: String): List<MovieSource> {
        if (sources.size <= 1) return sources
        // Stable descending sort keeps original relative order for equal scores.
        return sources.sortedByDescending { score(it, preferredLang) }
    }

    fun score(source: MovieSource, preferredLang: String): Double {
        var score = 0.0

        // H3/H4 tiering (must agree with SourceFilterUtils.getLanguageMatchScore — the
        // sheet sorts twice and the two would otherwise fight): VERIFIED match 100
        // (the player heard it) > exact CLAIMED match 80 (title said so) >
        // MULTI-claimed 60 > nothing. MULTI now MATCHES specific language filters,
        // so it needs a real (but lower) boost here too.
        if (matchesPreferredLanguage(source, preferredLang)) {
            score += if (source.languagesVerified) 100.0 else 80.0
        } else if (claimsMultiFor(source, preferredLang)) {
            score += 60.0
        }
        if (isCached(source)) score += 50.0

        score += when (VideoQuality.parse(source.quality)) {
            VideoQuality.Q_4K -> 20.0
            VideoQuality.Q_1080P -> 10.0
            else -> 5.0
        }

        val fileSizeGb = (source.sizeBytes ?: 0L).coerceAtLeast(0L) / GB_BYTES
        score -= 0.3 * fileSizeGb

        return score
    }

    fun isCached(source: MovieSource): Boolean {
        return source.isCached == true ||
            source.cacheStatus == DebridCacheStatus.VERIFIED_CACHED ||
            source.cacheStatus == DebridCacheStatus.DIRECT_STREAM
    }

    private fun matchesPreferredLanguage(source: MovieSource, preferredLang: String): Boolean {
        val pref = StreamLanguage.parse(preferredLang)
        if (pref == StreamLanguage.ALL || pref == StreamLanguage.UNKNOWN) return false
        val sourceLangs = source.languages?.map { StreamLanguage.parse(it) } ?: return false
        return sourceLangs.contains(pref)
    }

    // H3: a MULTI row might carry the preferred language — rank it above rows that
    // claim nothing, below rows that claim the language explicitly.
    private fun claimsMultiFor(source: MovieSource, preferredLang: String): Boolean {
        val pref = StreamLanguage.parse(preferredLang)
        if (pref == StreamLanguage.ALL || pref == StreamLanguage.UNKNOWN || pref == StreamLanguage.MULTI) return false
        val sourceLangs = source.languages?.map { StreamLanguage.parse(it) } ?: return false
        return sourceLangs.contains(StreamLanguage.MULTI)
    }
}
