package com.tvonnet.debridxtreamiptv.player.stabilized

import com.tvonnet.debridxtreamiptv.features.seriesv2.data.model.EpisodeEntityV2

/**
 * The decision + presentation rules of in-player episode browsing (Phase P13).
 *
 * Scope note: switching episodes stays in [PlayerActivity] (it drives the player
 * lifecycle: history finalisation, debrid re-resolution, initializePlayer). What
 * moves here is the part that was duplicated between the playlist observer and
 * showEpisodeBrowser, plus the one rule that has already caused a real bug.
 */

/** What the browser should display for a given playlist state. */
internal sealed interface EpisodeBrowserView {
    object Loading : EpisodeBrowserView
    data class Message(val text: String) : EpisodeBrowserView
    data class Episodes(val episodes: List<EpisodeEntityV2>) : EpisodeBrowserView
}

/**
 * Loading -> spinner, empty -> the error (or a default message), otherwise the list.
 * Both call sites used to inline this; a null state means "nothing loaded yet".
 */
internal fun episodeBrowserViewFor(
    isLoading: Boolean,
    episodes: List<EpisodeEntityV2>,
    error: String?
): EpisodeBrowserView = when {
    isLoading -> EpisodeBrowserView.Loading
    episodes.isEmpty() -> EpisodeBrowserView.Message(error ?: "No episodes available")
    else -> EpisodeBrowserView.Episodes(episodes)
}

/** The browser header: the series title, else "Season N". */
internal fun episodeBrowserSeasonTitle(seriesTitle: String?, seasonNumber: Int?): String =
    seriesTitle ?: "Season ${seasonNumber ?: ""}"

/**
 * A passive playlist load must NOT hijack the stream the user is already watching.
 *
 * When playback started from a SIBLING category (multi-source panel), the playing
 * stream id isn't in the PRIMARY series' episode list, so the playlist number-matches
 * it to the PRIMARY's own episode — a DIFFERENT source. Switching to that made every
 * category selection play the same (primary) stream ("select NL/DE -> same file").
 * The intent already started the correct chosen stream, and next/prev/browser-pick
 * switch episodes explicitly, so the playlist may only drive playback when nothing is
 * playing yet — and never for debrid, which resolves its own links.
 */
internal fun shouldPlaylistDrivePlayback(
    isLoading: Boolean,
    playlistEpisodeId: String?,
    currentEpisodeId: String?,
    currentUrl: String?,
    playbackSource: PlaybackSource
): Boolean =
    !isLoading &&
        playlistEpisodeId != null &&
        currentEpisodeId != playlistEpisodeId &&
        currentUrl.isNullOrBlank() &&
        playbackSource != PlaybackSource.DEBRID

/**
 * The episode id to load an IPTV playlist around. The content id doubles as the
 * stream url on some launch paths; when they are the same value it is a url, not an
 * episode id, and there is nothing to look up.
 */
internal fun iptvPlaylistEpisodeId(contentId: String?, currentUrl: String?): String? {
    val id = contentId?.takeIf { it.isNotBlank() } ?: return null
    val currentStreamUrl = currentUrl?.takeIf { it.isNotBlank() }
    return id.takeUnless { it == currentStreamUrl }
}

/** Poster first, then backdrop — skipping the literal "null" some providers send. */
internal fun episodeBrowserFallbackImageUrl(posterUrl: String?, backdropUrl: String?): String? =
    posterUrl?.takeIf { it.isNotBlank() && !it.equals("null", ignoreCase = true) }
        ?: backdropUrl?.takeIf { it.isNotBlank() && !it.equals("null", ignoreCase = true) }
