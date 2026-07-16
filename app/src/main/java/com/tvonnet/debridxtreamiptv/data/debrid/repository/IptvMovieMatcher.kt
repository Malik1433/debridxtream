package com.tvonnet.debridxtreamiptv.data.debrid.repository

import com.tvonnet.debridxtreamiptv.data.debrid.util.LanguageParser
import com.tvonnet.debridxtreamiptv.data.model.XtreamVodInfo
import com.tvonnet.debridxtreamiptv.data.repository.DebridCacheStatus
import com.tvonnet.debridxtreamiptv.data.repository.MovieSource
import com.tvonnet.debridxtreamiptv.features.seriesv2.ui.model.StreamQuality
import java.util.Locale

/**
 * Matches a movie to its IPTV (Xtream) listings for the source picker — shared by the
 * detail screen and the in-player Sources panel so both surface the same rows.
 *
 * The Xtream index is searched with a loose `name LIKE %title%`, which pulls in unrelated
 * films that merely contain the word ("Secret Obsession", "A Fatal Obsession" for
 * "Obsession"). We match the CORE title (language/category prefix + year/quality tags
 * stripped) START-anchored — not a substring — and require the year when both sides carry
 * one; a year-less listing must match the core title exactly.
 */
object IptvMovieMatcher {

    private val prefixRegex = Regex("^\\s*(?:\\|[^|]{1,12}\\|\\s*|[A-Za-z0-9/]{1,8}\\s*-\\s*)")
    private val tagCutRegex = Regex(
        "(?i)[\\s(\\[]+((19|20)\\d{2}|4k|hdcam|hdts|cam|web[- ]?dl|1080p|720p|2160p|480p|multi|x264|x265|hevc)\\b"
    )
    private val yearInParensRegex = Regex("\\((19|20)\\d{2}\\)")

    private val prefixLanguages = mapOf(
        "EN" to "en", "FR" to "fr", "DE" to "de", "NL" to "nl", "ES" to "es",
        "IT" to "it", "PT" to "pt", "BR" to "pt", "PL" to "pl", "TR" to "tr",
        "RU" to "ru", "AR" to "ar", "HI" to "hi", "UR" to "ur", "PA" to "pa",
        "IN" to "hi", "SE" to "sv", "NO" to "no", "DK" to "da", "FI" to "fi",
        "GR" to "el", "RO" to "ro", "HU" to "hu", "CZ" to "cs", "AL" to "sq",
        "EX-YU" to "sr", "BG" to "bg"
    )

    /** Clean query for the local VOD index (the movie title without a "(YYYY)" tag). */
    fun searchQuery(title: String?): String? =
        title?.replace(yearInParensRegex, " ")?.trim()?.takeIf { it.length >= 2 }

    /** Build IPTV [MovieSource]s from VOD search results for [title] ([year] optional). */
    fun buildSources(searchResults: List<XtreamVodInfo>, title: String?, year: String?): List<MovieSource> {
        val coreTitle = searchQuery(title) ?: return emptyList()
        val normTitle = normalize(coreTitle) ?: return emptyList()
        val targetYear = year?.trim()?.take(4)?.toIntOrNull()
        return searchResults
            .filter { matches(it, normTitle, targetYear) }
            .distinctBy { it.stream_id }
            .take(30)
            .map { vod ->
                MovieSource(
                    stream = vod,
                    category = null,
                    label = vod.name ?: coreTitle,
                    isPrimary = false,
                    languages = languagesFromName(vod.name),
                    quality = StreamQuality.fromName(vod.name).label,
                    // Xtream streams play instantly from the provider — honest DIRECT_STREAM.
                    cacheStatus = DebridCacheStatus.DIRECT_STREAM,
                    isCached = true,
                    provider = "IPTV",
                    sourceType = "IPTV",
                    sourceName = vod.name
                )
            }
    }

    private fun matches(vod: XtreamVodInfo, normTarget: String, targetYear: Int?): Boolean {
        if (vod.stream_id == null) return false
        val coreNorm = normalize(coreTitle(vod.name)) ?: return false
        val listingYear = listingYear(vod)
        return when {
            listingYear != null && targetYear != null ->
                coreNorm.startsWith(normTarget) && listingYear == targetYear
            listingYear == null -> coreNorm == normTarget
            else -> coreNorm.startsWith(normTarget)
        }
    }

    /** Bare movie title of a listing (language/category prefix + year/quality tags removed). */
    private fun coreTitle(name: String?): String? {
        if (name.isNullOrBlank()) return null
        var t = prefixRegex.replaceFirst(name.trim(), "")
        tagCutRegex.find(t)?.let { t = t.substring(0, it.range.first) }
        return t.trim(' ', '-', '|', ':', '·').takeIf { it.isNotBlank() }
    }

    private fun listingYear(vod: XtreamVodInfo): Int? {
        val fromName = yearInParensRegex.find(vod.name.orEmpty())?.value?.filter { it.isDigit() }?.toIntOrNull()
        return fromName ?: vod.releaseDate?.take(4)?.toIntOrNull()
    }

    private fun normalize(value: String?): String? {
        if (value.isNullOrBlank()) return null
        return value.lowercase(Locale.US).replace(Regex("[^a-z0-9]+"), "").takeIf { it.isNotBlank() }
    }

    /** Language from the name's category prefix ("FR - …", "|DE| …"), else the release tags. */
    private fun languagesFromName(name: String?): List<String>? {
        if (name.isNullOrBlank()) return null
        val prefix = name.substringBefore("-").trim().trim('|', '[', ']').uppercase(Locale.US)
        prefixLanguages[prefix]?.let { return listOf(it) }
        val parsed = LanguageParser.extractLanguages(name)
        return parsed.takeIf { it.isNotEmpty() && it != listOf("en") }
    }
}
