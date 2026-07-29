package com.tvonnet.debridxtreamiptv.player.stabilized

import com.tvonnet.debridxtreamiptv.data.Result
import com.tvonnet.debridxtreamiptv.data.debrid.model.TmdbImageUrl
import com.tvonnet.debridxtreamiptv.data.debrid.source.TmdbRemoteDataSource
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * C10: fetches what the X-Ray panel shows — who is in this, what it is about, how long it runs.
 *
 * The *panel* is `PlayerXRayController`; this is only the data behind it. Purely additive: it
 * decorates a stream that is already playing, so every failure is swallowed and simply leaves the
 * panel empty. Nothing here may affect playback.
 *
 * Bodies moved verbatim from `PlayerViewModel`; the movie and series paths are now separate methods
 * rather than one nested branch.
 */
internal class XRayMetadataLoader(
    private val tmdbRemote: TmdbRemoteDataSource,
    private val scope: CoroutineScope,
) {
    private companion object {
        const val CAST_LIMIT = 8
    }

    private val _xrayMetadata = MutableStateFlow<XRayMetadataUiState?>(null)
    val xrayMetadata: StateFlow<XRayMetadataUiState?> = _xrayMetadata.asStateFlow()

    fun load(contentId: String?, tmdbId: String?, isMovie: Boolean, title: String?) {
        val lookupId = stripIdPrefix(tmdbId) ?: stripIdPrefix(contentId) ?: return
        val id = lookupId.toIntOrNull() ?: return

        scope.launch {
            try {
                if (isMovie) loadMovie(id, title) else loadSeries(id, title)
            } catch (e: Exception) {
                // Decoration over a playing stream — never surface this.
                android.util.Log.e("PlayerViewModel", "Episode metadata load failed", e)
            }
        }
    }

    fun clear() {
        _xrayMetadata.value = null
    }

    /** Callers pass ids as `tmdb:123` / `imdb:tt123`; TMDB wants the bare number. */
    private fun stripIdPrefix(value: String?): String? =
        value?.replace("tmdb:", "")?.replace("imdb:", "")

    private suspend fun loadMovie(movieId: Int, fallbackTitle: String?) {
        val result = tmdbRemote.getMovieDetails(movieId)
        if (result !is Result.Success) return
        val details = result.data

        val castList = details.credits?.cast?.take(CAST_LIMIT)?.map { cast ->
            XRayCastMember(
                name = cast.name ?: "Unknown",
                character = cast.character,
                avatarUrl = TmdbImageUrl.getProfileUrl(cast.profilePath)
            )
        } ?: emptyList()

        // Note: the director is looked up in the CAST list, not the crew, so it is usually null.
        // Preserved as-is — correcting it would change what the panel shows and belongs in its
        // own commit.
        val directorName = details.credits?.cast?.firstOrNull {
            it.character?.contains("director", ignoreCase = true) == true
        }?.name

        val durationText = details.runtime?.let {
            val h = it / 60
            val m = it % 60
            if (h > 0) "${h}h ${m}m" else "${m}m"
        }
        val metaText = listOfNotNull(details.releaseDate?.take(4), durationText).joinToString(" • ")

        _xrayMetadata.value = XRayMetadataUiState(
            title = details.title ?: fallbackTitle ?: "Movie Details",
            overview = details.overview,
            meta = metaText,
            director = directorName,
            cast = castList
        )
    }

    private suspend fun loadSeries(tvId: Int, fallbackTitle: String?) {
        val result = tmdbRemote.getSeriesDetails(tvId)
        if (result !is Result.Success) return
        val details = result.data
        _xrayMetadata.value = XRayMetadataUiState(
            title = details.name ?: fallbackTitle ?: "Series Details",
            overview = details.overview,
            meta = details.firstAirDate?.take(4),
            director = null,
            cast = emptyList()
        )
    }
}
