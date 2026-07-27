package com.tvonnet.debridxtreamiptv.player.stabilized

import java.util.Locale

/**
 * C10: turning a provider's episode title into something TMDB will actually match.
 *
 * Provider titles are not show names — they are release strings: `"EN | Breaking Bad S01E02 1080p
 * WEB-DL MULTI (2008)"`. Searching TMDB with that returns nothing, so the show falls back to no
 * artwork and no metadata. Everything here exists to get from that to `"Breaking Bad"`.
 *
 * The order of the strips in [cleanSeriesTitleForTmdb] matters: episode markers go before the
 * language and quality words, because `"S01E02"` would otherwise survive as stray digits once the
 * separators around it are gone.
 *
 * Moved verbatim from `PlayerViewModel`.
 */
object SeriesTmdbMatching {

    /** Providers send "null" and "N/A" as strings; both mean there is no artwork. */
    fun hasUsableArtworkUrl(value: String?): Boolean {
        val trimmed = value?.trim().orEmpty()
        return trimmed.isNotEmpty() &&
            !trimmed.equals("null", ignoreCase = true) &&
            !trimmed.equals("n/a", ignoreCase = true)
    }

    /** @return the show name alone, or null when nothing usable is left. */
    fun cleanSeriesTitleForTmdb(rawTitle: String?): String? {
        return rawTitle
            ?.replace(Regex("\\bS\\d{1,2}\\s*E\\d{1,2}\\b", RegexOption.IGNORE_CASE), " ")
            ?.replace(Regex("\\b\\d{1,2}x\\d{1,2}\\b", RegexOption.IGNORE_CASE), " ")
            ?.replace(Regex("\\bSeason\\s+\\d+\\b", RegexOption.IGNORE_CASE), " ")
            ?.replace(Regex("\\bEpisode\\s+\\d+\\b", RegexOption.IGNORE_CASE), " ")
            ?.replace(Regex("^\\[[A-Za-z]{2,3}]\\s*"), " ")
            ?.replace(Regex("^[A-Za-z]{2,3}\\s*[-|]\\s*"), " ")
            ?.replace(Regex("\\b(multi|dual|audio|dubbed|hindi|english|french|spanish|arabic|turkish|portuguese)\\b", RegexOption.IGNORE_CASE), " ")
            ?.replace(Regex("\\b(4k|2160p|1080p|720p|480p|hdr|dv|hevc|x265|x264|h\\.265|h\\.264|web[- ]?dl|webrip|bluray|brrip)\\b", RegexOption.IGNORE_CASE), " ")
            ?.replace(Regex("\\(\\s*\\d{4}\\s*\\)$"), " ")
            ?.replace(Regex("\\s+\\d{4}$"), " ")
            ?.replace(Regex("[^\\p{L}\\p{N} ]"), " ")
            ?.replace(Regex("\\s+"), " ")
            ?.trim()
            ?.takeIf { it.length >= 2 }
    }

    /**
     * Progressively shorter queries to try in order.
     *
     * TMDB indexes `"Star Wars: Andor"` under its full name but plenty of shows are listed without
     * the subtitle the provider adds, so the colon and dash suffixes are dropped as fallbacks.
     */
    fun buildTmdbSearchQueries(title: String): List<String> {
        val withoutColonSuffix = title.substringBefore(":").trim()
        val withoutDashSuffix = title.substringBefore(" - ").trim()
        return listOf(title, withoutColonSuffix, withoutDashSuffix)
            .map { it.trim() }
            .filter { it.length >= 2 }
            .distinct()
    }

    /**
     * Squash a title to a comparison key: lowercase, no leading article, letters and digits only.
     * `"The Office"` and `"office"` have to compare equal, or every candidate loses to itself.
     */
    fun normalizeTmdbTitle(value: String?): String? {
        return value
            ?.lowercase(Locale.ROOT)
            ?.replace(Regex("\\bthe\\b"), " ")
            ?.replace(Regex("[^a-z0-9]"), "")
            ?.takeIf { it.isNotBlank() }
    }

    /**
     * Rough similarity between two normalized titles, used to rank TMDB candidates.
     *
     * ⚠ **Known weakness, documented rather than fixed here.** `chunked(3)` splits at fixed
     * positions instead of producing sliding 3-character shingles, so the score depends on where the
     * character boundaries happen to fall rather than on containment. Measured:
     * `"office"` vs `"theoffice"` scores **1.0**, but `"andor"` vs `"starwarsandor"` scores **0.0** —
     * both are containments. It therefore misses matches whose provider title carries a prefix of
     * the wrong length.
     *
     * It is left exactly as it behaves today because changing it changes which show every series
     * resolves to; that is a behaviour change and belongs in its own commit. The tests pin the
     * current behaviour, including the surprising zero.
     */
    fun tokenOverlapScore(target: String, candidate: String): Float {
        val targetTokens = target.chunked(3).toSet()
        val candidateTokens = candidate.chunked(3).toSet()
        if (targetTokens.isEmpty() || candidateTokens.isEmpty()) return 0f
        return targetTokens.intersect(candidateTokens).size.toFloat() / targetTokens.size.toFloat()
    }
}
