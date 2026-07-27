package com.tvonnet.debridxtreamiptv.features.seriesv2.data.repository

/**
 * C5: the pure name decisions behind multi-category stream aggregation.
 *
 * The same show is listed several times by a provider under decorated names — "EN - The Walking
 * Dead (2010)", "NL| THE WALKING DEAD", "[SE] The Walking Dead 4K" — and every one of those has to
 * collapse onto the same show without also merging genuinely different shows. That is entirely a
 * string problem, so it lives here as pure functions rather than inside the repository, where it
 * could only be exercised against a live provider.
 *
 * Moved verbatim from `XtreamSeriesRepositoryV2`; the only change is that the shared
 * decoration-stripping prelude of [normalizeCoreTitle] and [likeSearchToken] is now one function
 * instead of two identical copies.
 */
object SeriesTitleMatching {

    /** Quality / language / codec tokens that are never part of a real title. */
    private val NOISE_TOKENS = setOf(
        "4k", "uhd", "fhd", "hd", "sd", "2160p", "1080p", "720p", "480p",
        "hevc", "x264", "x265", "h264", "h265", "10bit",
        "multi", "vostfr", "vosta", "vf", "vo", "dual", "dubbed", "sub", "subs"
    )

    private val BRACKETED = Regex("\\[[^\\]]*\\]|\\([^)]*\\)|\\{[^}]*\\}")

    /**
     * Leading short tag prefixes: "en - ", "nl- ", "nf -", "lat - "…
     *
     * Dash separators only (a colon would eat real titles like "FBI: Most Wanted"), and whitespace
     * is required after the dash so "9-1-1" survives.
     */
    private val LEADING_TAG = Regex("^[a-z0-9+]{1,4}\\s*[-–]\\s+")

    /**
     * Lowercase, drop bracketed decorations, keep the longest pipe-separated segment, then strip
     * leading short tags twice (they stack: "EN - NF - Title").
     */
    private fun stripDecorations(raw: String): String {
        var s = raw.lowercase()
        // Drop bracketed decorations: years "(2010)", quality "(4K)", region "(US)", "[SE]"…
        s = s.replace(BRACKETED, " ")
        // "EN| Title" / "4K | Title" — keep the longest pipe-separated segment
        if (s.contains('|')) {
            s = s.split('|').maxByOrNull { it.trim().length }?.trim() ?: s
        }
        repeat(2) {
            s = s.trim().replace(LEADING_TAG, "")
        }
        return s
    }

    /**
     * Normalize a series name down to its core title so decorated variants match.
     * Real provider examples that must all normalize to "the walking dead":
     *   "EN - The Walking Dead (2010)", "NL - THE WALKING DEAD (2010)",
     *   "FR - The Walking Dead", "EN-  The Walking Dead", "[SE] The Walking Dead"
     */
    fun normalizeCoreTitle(raw: String): String {
        if (raw.isBlank()) return ""
        // Strip quality / language / codec tokens appearing as standalone words
        val s = stripDecorations(raw).replace(Regex("[^a-z0-9]+"), " ")
        val words = s.split(" ").filter { it.isNotBlank() && it !in NOISE_TOKENS }
        return words.joinToString(" ").trim()
    }

    /**
     * A punctuation-free fragment of the title used for the SQL LIKE candidate pool.
     * Raw sibling names must contain it verbatim, so it comes from the cleaned title's
     * longest punctuation-free run: "NL - The Walking Dead: Dead City (2023)" →
     * "the walking dead" (matches every variant regardless of its own decorations).
     */
    fun likeSearchToken(raw: String): String {
        if (raw.isBlank()) return ""
        // Split on anything that isn't a letter/digit/space; keep the longest run.
        return stripDecorations(raw).split(Regex("[^a-z0-9 ]+"))
            .map { it.trim().replace(Regex("\\s+"), " ") }
            .maxByOrNull { it.length }
            ?.trim().orEmpty()
    }

    /** Release year embedded in a listing name, e.g. "Nemesis (2024)" → 2024. */
    fun yearOf(raw: String): Int? =
        Regex("\\((19|20)\\d{2}\\)").find(raw)?.value?.trim('(', ')')?.toIntOrNull()

    /**
     * Different explicit years = different shows; a missing year matches anything.
     * ±1 year is tolerated — regional first-air years routinely differ by one
     * (strict equality wiped every listing for shows where the TMDB year and the
     * provider's label year disagree).
     */
    fun yearsCompatible(a: Int?, b: Int?): Boolean =
        a == null || b == null || kotlin.math.abs(a - b) <= 1

    /** Derive a short category badge (EN, NL, MULTI…) from the category / stream name. */
    fun shortCategoryTag(categoryName: String?, streamName: String?): String =
        tagFromCategoryMarkup(categoryName)
            ?: tagFromNamePrefix(streamName)
            ?: tagFromKeywords(categoryName, streamName)

    /**
     * Providers commonly embed the tag in the category name: "|EN| TOP SERIES",
     * "[FR] ACTION", "MULTI| NETFLIX 4K"…
     */
    private fun tagFromCategoryMarkup(categoryName: String?): String? = categoryName?.let { cat ->
        Regex("[|\\[]\\s*([A-Za-z]{2,5})\\s*[|\\]]").find(cat)?.groupValues?.get(1)?.uppercase()
    }

    /** …or as a name prefix on the listing itself: "EN - Title", "NL- Title". */
    private fun tagFromNamePrefix(streamName: String?): String? = streamName?.let { name ->
        Regex("^\\s*([A-Za-z]{2,4})\\s*[-–]\\s+").find(name)?.groupValues?.get(1)?.uppercase()
    }

    private fun tagFromKeywords(categoryName: String?, streamName: String?): String {
        val hay = (categoryName.orEmpty() + " " + streamName.orEmpty()).lowercase()
        return when {
            hay.contains("multi") -> "MULTI"
            hay.contains("vostfr") || hay.contains("french") || hay.contains("français") ||
                hay.contains("séries") -> "FR"
            hay.contains("arab") || hay.contains("ramadan") -> "AR"
            hay.contains("hindi") -> "HI"
            hay.contains("span") || hay.contains("espa") -> "ES"
            hay.contains("english") -> "EN"
            else -> "SRV"
        }
    }

    /** Heuristic: which variants tend to carry real episode data. Higher = try sooner. */
    fun siblingEpisodeLikelihood(name: String?): Int {
        val n = name?.uppercase().orEmpty()
        var score = 0
        if (n.contains("MULTI")) score += 5
        if (Regex("\\|\\s*EN\\s*\\|").containsMatchIn(n) || n.startsWith("EN")) score += 4
        if (n.contains("NETFLIX") || n.contains("4K") || n.contains("UHD")) score += 2
        // Regional-dub prefixes are usually the dead references.
        if (Regex("^(SOM|PH|AR|EXYU|ALB|PL|NL|PT|DE|ES|FR)\\b").containsMatchIn(n) ||
            Regex("^\\|\\s*(SOM|PH|AR|EXYU|ALB|PL|NL|PT|DE|ES|FR)\\s*\\|").containsMatchIn(n)) score -= 2
        return score
    }
}
