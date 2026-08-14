package com.tvonnet.debridxtreamiptv.ui.detail.phone

/**
 * What the phone Detail screen draws — and nothing about where it came from.
 *
 * There are FOUR detail hosts in this app: the IPTV movie and series fragments and the debrid
 * movie and series activities, each with its own state shape and its own ViewModel. Rather than
 * teach the screen four dialects, each host translates into this one model. That keeps the design
 * in a single place, and it is why a fix to the header or the episode row lands on all four at
 * once instead of three times out of four.
 *
 * Everything optional is genuinely optional: `backdrop` is missing often, `trailer` usually is,
 * IPTV has no `similar` and no `sources` at all. The renderer degrades on null rather than
 * drawing an empty shape.
 */
data class PhoneDetailModel(
    val title: String,
    val posterUrl: String?,
    val backdropUrl: String?,
    val rating: String?,
    val year: String?,
    val runtimeMinutes: Int?,
    val certification: String?,
    val genres: String?,
    val plot: String?,
    val creditsLabel: String?,
    val creditsName: String?,
    val badges: List<Badge> = emptyList(),
    val cast: List<CastMember> = emptyList(),
    val similar: List<SimilarItem> = emptyList(),
    /** Null means never started, so the button reads Play rather than Resume. */
    val playback: Playback? = null,
    val isFavourite: Boolean = false,
    val isWatched: Boolean = false,
    val hasTrailer: Boolean = false,
    /** Null for IPTV: no source list exists, so the action row flexes to three. */
    val sourceCount: Int? = null,
    val isSeries: Boolean = false,
    val seasons: List<Season> = emptyList(),
    val selectedSeason: Int? = null,
    val episodes: List<Episode> = emptyList(),
    /** Drives the NEXT UP badge and the initial scroll. */
    val nextUpEpisode: String? = null,
    /** Episodes are fetched after the screen opens; while that runs the section says so. */
    val episodesLoading: Boolean = false,
    /** IPTV has no similar data — say so once rather than draw an empty carousel. */
    val similarUnavailableNote: String? = null,
) {
    data class Badge(val text: String, val tone: Tone) {
        enum class Tone { CYAN, NEUTRAL, GREEN }
    }

    data class CastMember(val name: String, val role: String?, val photoUrl: String?)

    data class SimilarItem(val id: String, val title: String, val posterUrl: String?)

    data class Playback(val positionMs: Long, val durationMs: Long) {
        val percent: Int
            get() = if (durationMs <= 0) 0 else ((positionMs * 100) / durationMs).toInt().coerceIn(0, 100)
    }

    data class Season(val number: Int, val episodeCount: Int, val watchedCount: Int)

    data class Episode(
        val id: String,
        val number: Int,
        val title: String,
        val plot: String?,
        val stillUrl: String?,
        val runtimeMinutes: Int?,
        val watched: Boolean,
        val positionMs: Long?,
        val durationMs: Long?,
    )
}

/** What the screen can DO, handed in by whichever host built the model. */
data class PhoneDetailActions(
    val onPlay: () -> Unit,
    val onBack: () -> Unit,
    val onToggleFavourite: () -> Unit,
    val onToggleWatched: () -> Unit,
    val onTrailer: () -> Unit,
    val onSources: () -> Unit,
    val onSeasonPicked: (Int) -> Unit,
    val onEpisode: (PhoneDetailModel.Episode) -> Unit,
    val onEpisodeOverflow: (PhoneDetailModel.Episode) -> Unit,
    val onSimilar: (PhoneDetailModel.SimilarItem) -> Unit,
    val onRetry: () -> Unit,
)
