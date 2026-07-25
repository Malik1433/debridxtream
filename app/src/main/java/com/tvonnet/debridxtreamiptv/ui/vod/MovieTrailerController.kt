package com.tvonnet.debridxtreamiptv.ui.vod

import android.os.Handler
import android.os.Looper
import android.widget.Button
import android.widget.ImageView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.bumptech.glide.Glide
import com.bumptech.glide.load.resource.drawable.DrawableTransitionOptions
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
 * MD-1: the movie-trailer concern lifted out of [MovieDetailActivity]. Resolves a YouTube trailer
 * (from the launch intent's value or a TMDB lookup), launches [TrailerActivity], keeps the "Trailer"
 * button's enabled/alpha state in sync, and drives the hover-preview (a 2.5s dwell on the Trailer
 * button crossfades the backdrop to the trailer's YouTube thumbnail). Behaviour byte-identical to the
 * old `launchTrailer` / `updateTrailerButtonState` / `scheduleTrailerPreview` / `cancelTrailerPreview`;
 * the Activity now owns only the button + backdrop references and forwards the trailer url as it
 * learns it (intent → setTrailerUrl / TMDB-details → setTrailerUrl-if-absent). Mirrors
 * `SeriesTrailerController` with the added preview.
 */
class MovieTrailerController(
    private val activity: AppCompatActivity,
    private val tmdbRemoteDataSource: TmdbRemoteDataSource,
    private val button: Button,
    private val backdrop: ImageView,
    private val movieId: () -> String?,
    private val movieBackdrop: () -> String?,
) {
    var trailerUrl: String? = null
        private set
    private var lookupInProgress = false

    private val previewHandler = Handler(Looper.getMainLooper())
    private var previewRunnable: Runnable? = null
    private var previewShown = false

    /** Set the trailer url from the launch intent (may be null). */
    fun setTrailerUrl(url: String?) {
        trailerUrl = url
        refreshButtonState()
    }

    fun launch() {
        val url = trailerUrl
        if (!url.isNullOrBlank() && TrailerValueParser.parse(url) != null) {
            activity.startActivity(TrailerActivity.createIntent(activity, url))
            return
        }

        if (lookupInProgress) return

        val tmdbId = movieId()?.toIntOrNull()
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
                    tmdbRemoteDataSource.getMovieDetails(tmdbId)
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
            } catch (_: Exception) {
                Toast.makeText(activity, R.string.movie_detail_trailer_unavailable, Toast.LENGTH_SHORT).show()
            } finally {
                lookupInProgress = false
                refreshButtonState()
            }
        }
    }

    fun refreshButtonState() {
        val canResolveTrailer = TrailerValueParser.parse(trailerUrl) != null || movieId()?.toIntOrNull() != null
        button.isEnabled = canResolveTrailer && !lookupInProgress
        button.alpha = if (canResolveTrailer) 0.85f else 0.45f
    }

    /**
     * After a 2.5s dwell on the Trailer button, crossfade the backdrop to the
     * trailer's YouTube thumbnail as a lightweight preview.
     */
    fun schedulePreview() {
        cancelPreview()
        val key = TrailerValueParser.parse(trailerUrl)?.youtubeId ?: return
        val runnable = Runnable {
            Glide.with(activity)
                .load("https://img.youtube.com/vi/$key/hqdefault.jpg")
                .centerCrop()
                .transition(DrawableTransitionOptions.withCrossFade(500))
                .into(backdrop)
            previewShown = true
        }
        previewRunnable = runnable
        previewHandler.postDelayed(runnable, 2500L)
    }

    fun cancelPreview() {
        previewRunnable?.let { previewHandler.removeCallbacks(it) }
        previewRunnable = null
        if (previewShown) {
            previewShown = false
            val backdropUrl = movieBackdrop()
            if (!backdropUrl.isNullOrBlank()) {
                Glide.with(activity)
                    .load(backdropUrl)
                    .centerCrop()
                    .transition(DrawableTransitionOptions.withCrossFade(400))
                    .into(backdrop)
            }
        }
    }
}
