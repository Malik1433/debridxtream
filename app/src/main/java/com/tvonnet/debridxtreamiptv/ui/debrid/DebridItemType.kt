package com.tvonnet.debridxtreamiptv.ui.debrid

/**
 * What a Debrid card actually IS — movie or series — from the type string it carries.
 *
 * Three vocabularies meet in this section and nobody reconciled them: TMDB rows say `movie` and
 * `series`, some addons say `show`, and the favourites table says **`vod`** because it is the same
 * table the IPTV screens have always written. Every routing decision in the section was a bare
 * `type == "movie"` with `else` meaning series, so a favourited MOVIE — stored as `vod` — opened the
 * SERIES detail, which found no episodes and told the customer the title was not available. That is
 * the "I can favourite it but I cannot open it" report, and it applied to every favourite they had.
 *
 * So the question is answered in one place, and the fallback is MOVIE rather than series: an
 * unknown type shown as a film degrades to "no sources for this", while the same guess in the other
 * direction is a season list that can never fill.
 */
object DebridItemType {

    /** Series, show, tv — the same thing under three names. */
    fun isSeries(type: String?): Boolean = when (type?.lowercase()?.trim()) {
        "series", "show", "tv", "tvshow", "tv_show" -> true
        else -> false
    }

    /** Movie, vod, film — including the favourites table's `vod`. */
    fun isMovie(type: String?): Boolean = when (type?.lowercase()?.trim()) {
        "movie", "vod", "film" -> true
        else -> false
    }

    /** The canonical spelling, for rows we build ourselves. */
    const val MOVIE = "movie"
    const val SERIES = "series"

    /** Normalises any dialect to [MOVIE] or [SERIES]. Unknown reads as a movie — see the class note. */
    fun canonical(type: String?): String = if (isSeries(type)) SERIES else MOVIE
}
