package com.tvonnet.debridxtreamiptv.ui.series

import android.util.Log
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.AppCompatButton
import androidx.lifecycle.lifecycleScope
import com.tvonnet.debridxtreamiptv.R
import com.tvonnet.debridxtreamiptv.data.debrid.source.TmdbRemoteDataSource
import com.tvonnet.debridxtreamiptv.data.onFailure
import com.tvonnet.debridxtreamiptv.data.onSuccess
import com.tvonnet.debridxtreamiptv.ui.trailer.TrailerActivity
import com.tvonnet.debridxtreamiptv.ui.trailer.TrailerValueParser
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * SD-1: the series-trailer concern lifted out of [SeriesDetailActivity]. Resolves a YouTube
 * trailer — from the launch intent's value or a TMDB lookup — launches [TrailerActivity], and
 * keeps the "Watch Trailer" button's enabled/alpha state in sync. Behaviour byte-identical to
 * the old `launchTrailer` / `updateTrailerButtonState` pair; the Activity now owns only the
 * button reference and forwards the trailer url as it learns it (intent → series-info → lookup).
 */
class SeriesTrailerController(
    private val activity: AppCompatActivity,
    private val tmdbRemoteDataSource: TmdbRemoteDataSource,
    private val button: AppCompatButton,
    private val seriesId: () -> String?,
) {
    var trailerUrl: String? = null
        private set
    private var lookupInProgress = false

    /** Set the trailer url from the launch intent (may be null). */
    fun setTrailerUrl(url: String?) {
        trailerUrl = url
        refreshButtonState()
    }

    /** Keep the existing url unless [url] is a non-blank replacement (series-info path). */
    fun mergeTrailerUrl(url: String?) {
        if (!url.isNullOrBlank()) trailerUrl = url
        refreshButtonState()
    }

    fun launch() {
        val url = trailerUrl
        if (!url.isNullOrBlank() && TrailerValueParser.parse(url) != null) {
            activity.startActivity(TrailerActivity.createIntent(activity, url))
            return
        }

        if (lookupInProgress) return

        val tmdbId = seriesId()?.toIntOrNull()
        if (tmdbId == null) {
            Toast.makeText(activity, R.string.movie_detail_trailer_unavailable, Toast.LENGTH_SHORT).show()
            refreshButtonState()
            return
        }

        activity.lifecycleScope.launch {
            lookupInProgress = true
            button.isEnabled = false
            try {
                val result = withContext(Dispatchers.IO) {
                    tmdbRemoteDataSource.getSeriesDetails(tmdbId)
                }

                result.onSuccess { details ->
                    val trailer = details.videos?.results?.firstOrNull {
                        it.site?.lowercase() == "youtube" && it.type?.lowercase() == "trailer"
                    } ?: details.videos?.results?.firstOrNull {
                        it.site?.lowercase() == "youtube"
                    }

                    if (trailer?.key != null) {
                        trailerUrl = trailer.key
                        activity.startActivity(TrailerActivity.createIntent(activity, trailer.key))
                    } else {
                        Toast.makeText(activity, R.string.movie_detail_trailer_unavailable, Toast.LENGTH_SHORT).show()
                    }
                }.onFailure {
                    Toast.makeText(activity, R.string.movie_detail_trailer_unavailable, Toast.LENGTH_SHORT).show()
                }
            } catch (e: Exception) {
                Log.w("SeriesTrailer", "Trailer lookup failed", e)
                Toast.makeText(activity, R.string.movie_detail_trailer_unavailable, Toast.LENGTH_SHORT).show()
            } finally {
                lookupInProgress = false
                refreshButtonState()
            }
        }
    }

    fun refreshButtonState() {
        val canResolveTrailer = TrailerValueParser.parse(trailerUrl) != null || seriesId()?.toIntOrNull() != null
        button.isEnabled = canResolveTrailer && !lookupInProgress
        button.alpha = if (canResolveTrailer && !lookupInProgress) 1.0f else 0.45f
    }
}
