package com.tvonnet.debridxtreamiptv.ui.debrid

/**
 * The catalogue rows on the Debrid home, in display order - ONE list, read by both the home
 * (DebridViewModel) and See All (DebridSeeAllViewModel).
 *
 * They used to be two hand-maintained copies, and they had already drifted: See All carried an
 * "apple_*" pair keyed to provider 2 (the iTunes STORE, not Apple TV+) that the home never showed,
 * and the home had rows See All could not page. A row that exists in one place and not the other
 * is a See All that opens empty. Same lesson as the favourites routing: one copy, or it drifts.
 *
 * Each row is one TMDB discover query. `watchProviders` are TMDB watch-provider ids and only mean
 * something together with `watchRegion` - Zee5 is 232 in IN, not in US. `originalLanguage` rows
 * carry the region only for consistency; discover ignores it without a provider filter.
 *
 * A query that returns nothing is dropped from the home by the caller, so a wrong provider id
 * shows up as a MISSING row, never an empty one. Verify a new id on the television, not here.
 */
data class DebridRowSpec(
    val id: String,
    val title: String,
    val type: String,
    val originalLanguage: String? = null,
    val watchProviders: String? = null,
    val watchRegion: String? = "US",
    val releaseDateGte: String? = null,
)

object DebridCatalogRows {

    private const val RECENT_SINCE = "2024-01-01"

    val specs: List<DebridRowSpec> = listOf(
        // ── Indian languages ──────────────────────────────────────────────────────────────────
        lang("bollywood_movies", "Bollywood New Popular Movies", "movie", "hi", "IN"),
        lang("bollywood_series", "Bollywood New Popular Series", "series", "hi", "IN"),
        lang("punjabi_movies", "Punjabi New Popular Movies", "movie", "pa", "IN"),
        lang("tamil_movies", "Tamil New Popular Movies", "movie", "ta", "IN"),
        lang("tamil_series", "Tamil New Popular Series", "series", "ta", "IN"),
        lang("telugu_movies", "Telugu New Popular Movies", "movie", "te", "IN"),
        lang("telugu_series", "Telugu New Popular Series", "series", "te", "IN"),
        lang("malayalam_movies", "Malayalam New Popular Movies", "movie", "ml", "IN"),
        lang("malayalam_series", "Malayalam New Popular Series", "series", "ml", "IN"),
        lang("kannada_movies", "Kannada New Popular Movies", "movie", "kn", "IN"),
        // ── Pakistani and Arabic ──────────────────────────────────────────────────────────────
        lang("urdu_series", "Pakistani Dramas", "series", "ur", "PK"),
        lang("urdu_movies", "Pakistani Movies", "movie", "ur", "PK"),
        lang("arabic_series", "Arabic New Popular Series", "series", "ar", "AE"),
        lang("arabic_movies", "Arabic New Popular Movies", "movie", "ar", "AE"),
        // ── Indian OTT platforms (TMDB provider ids, region IN) ──────────────────────────────
        ott("zee5_movies", "ZEE5 New Popular Movies", "movie", "232", "IN"),
        ott("zee5_series", "ZEE5 New Popular Series", "series", "232", "IN"),
        // 122 is Hotstar; 2336 is JioHotstar, the 2025 merger. With 122 alone the MOVIE row came
        // back empty on the television while the series row filled - TMDB had moved the film
        // catalogue to the new id. Unknown ids in an OR list are ignored, so listing both is safe.
        ott("hotstar_movies", "Hotstar New Popular Movies", "movie", "122|2336", "IN"),
        ott("hotstar_series", "Hotstar New Popular Series", "series", "122|2336", "IN"),
        ott("sonyliv_series", "SonyLIV New Popular Series", "series", "237", "IN"),
        ott("sunnxt_movies", "Sun NXT New Popular Movies", "movie", "309", "IN"),
        ott("aha_movies", "aha New Popular Movies", "movie", "532", "IN"),
        // ── Global OTT platforms (region US) ─────────────────────────────────────────────────
        ott("netflix_movies", "Netflix New Popular Movies", "movie", "8", "US"),
        ott("netflix_series", "Netflix New Popular Series", "series", "8", "US"),
        ott("prime_movies", "Prime Video New Popular Movies", "movie", "9|119", "US"),
        ott("prime_series", "Prime Video New Popular Series", "series", "9|119", "US"),
        ott("disney_movies", "Disney+ New Popular Movies", "movie", "337", "US"),
        ott("disney_series", "Disney+ New Popular Series", "series", "337", "US"),
        // 384 was "HBO Max"; TMDB re-keyed the service as "Max" (1899) and the old id went quiet -
        // this row had been silently missing from the home. Both ids, same reason as Hotstar.
        ott("hbo_series", "HBO / Max New Popular Series", "series", "1899|384", "US"),
        ott("appletv_series", "Apple TV+ New Popular Series", "series", "350", "US"),
        ott("paramount_series", "Paramount+ New Popular Series", "series", "531", "US"),
        // ── Hollywood ────────────────────────────────────────────────────────────────────────
        lang("hollywood_movies", "Hollywood New Popular Movies", "movie", "en", "US"),
        lang("hollywood_series", "Hollywood New Popular Series", "series", "en", "US"),
    )

    fun byId(id: String): DebridRowSpec? = specs.firstOrNull { it.id == id }

    private fun lang(id: String, title: String, type: String, language: String, region: String) =
        DebridRowSpec(id, title, type, originalLanguage = language, watchRegion = region, releaseDateGte = RECENT_SINCE)

    private fun ott(id: String, title: String, type: String, providers: String, region: String) =
        DebridRowSpec(id, title, type, watchProviders = providers, watchRegion = region, releaseDateGte = RECENT_SINCE)
}
