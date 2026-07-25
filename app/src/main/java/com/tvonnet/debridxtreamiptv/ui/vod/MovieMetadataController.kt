package com.tvonnet.debridxtreamiptv.ui.vod

import android.view.View
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.RecyclerView
import com.tvonnet.debridxtreamiptv.R
import com.tvonnet.debridxtreamiptv.data.debrid.source.TmdbRemoteDataSource
import com.tvonnet.debridxtreamiptv.data.omdb.OmdbClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * MD-5: the remote **metadata enrichment** concern lifted out of [MovieDetailActivity] — the TMDB
 * movie-details fetch, the OMDb IMDb/Rotten-Tomatoes ratings lookup, and the TMDB "similar movies"
 * recommendations row. Cohesive single responsibility: pull movie metadata from the remote APIs and
 * populate the detail views.
 *
 * The movie metadata *fields* (plot/rating/year/duration/genre/director/age-rating/imdbId) are read
 * many places across the Activity (`displayMovieDetails`, `getMovieDataFromIntent`, resume, the
 * playback controller's imdbId getter), so per the decomposition rule they STAY in the Activity;
 * this controller writes them back through the setter lambdas and re-renders via [onMetadataApplied].
 * Behaviour byte-identical to the old private methods (verbatim relocation, exact ordering); only
 * `this`/field refs were rebound to [activity], the injected deps, the getters/setters and callbacks.
 */
class MovieMetadataController(
    private val activity: AppCompatActivity,
    private val tmdbRemoteDataSource: TmdbRemoteDataSource,
    private val trailerController: MovieTrailerController,
    private val castAdapter: CastAdapter,
    private val tvCastTitle: TextView,
    private val rvCast: RecyclerView,
    private val tvImdbRating: TextView,
    private val badgeImdb: View,
    private val tvRtRating: TextView,
    private val badgeRt: View,
    private val tvAgeRating: TextView,
    private val layoutMetadataRow: View,
    private val similarAdapter: SimilarMoviesAdapter,
    private val layoutSimilarRow: View,
    private val movieName: () -> String?,
    private val currentImdbId: () -> String?,
    private val setCurrentImdbId: (String?) -> Unit,
    private val movieAgeRating: () -> String?,
    private val setMovieAgeRating: (String?) -> Unit,
    private val setMoviePlot: (String?) -> Unit,
    private val setMovieRating: (String?) -> Unit,
    private val setMovieYear: (String?) -> Unit,
    private val setMovieDuration: (String?) -> Unit,
    private val setMovieGenre: (String?) -> Unit,
    private val setMovieDirector: (String?) -> Unit,
    private val onMetadataApplied: () -> Unit,
    private val onFetchComplete: (String?) -> Unit,
) {
    fun fetchTmdbDetails(tmdbId: String) {
        activity.lifecycleScope.launch {
            var imdbId: String? = null
            try {
                // Check if it's a valid integer ID
                val id = tmdbId.toIntOrNull()

                if (id != null) {
                    val result = withContext(Dispatchers.IO) {
                        tmdbRemoteDataSource.getMovieDetails(id)
                    }

                    when (result) {
                        is com.tvonnet.debridxtreamiptv.data.Result.Success -> {
                            val details = result.data
                            imdbId = details.imdbId
                            setCurrentImdbId(imdbId)
                            loadExternalRatings()

                            setMoviePlot(details.overview)
                            setMovieRating(details.voteAverage?.toString())
                            setMovieYear(details.releaseDate?.take(4))
                            setMovieDuration(details.runtime?.let { (it * 60).toString() })
                            setMovieGenre(details.genres?.joinToString(", ") { it.name ?: "" })
                            setMovieDirector(details.credits?.crew
                                ?.firstOrNull { it.job?.equals("Director", ignoreCase = true) == true }?.name)
                            setMovieAgeRating(extractCertification(details.releaseDates))

                            val castList = details.credits?.cast
                            if (!castList.isNullOrEmpty()) {
                                castAdapter.submitList(castList.take(5))
                                tvCastTitle.visibility = View.VISIBLE
                                rvCast.visibility = View.VISIBLE
                            } else {
                                tvCastTitle.visibility = View.GONE
                                rvCast.visibility = View.GONE
                            }
                            // Cache a trailer key so the Trailer button dwell-preview
                            // and enabled state work without a second lookup.
                            if (trailerController.trailerUrl.isNullOrBlank()) {
                                val key = details.videos?.results?.firstOrNull {
                                    it.site?.lowercase() == "youtube" && it.type?.lowercase() == "trailer"
                                }?.key ?: details.videos?.results?.firstOrNull {
                                    it.site?.lowercase() == "youtube"
                                }?.key
                                if (!key.isNullOrBlank()) trailerController.setTrailerUrl(key)
                            }
                            trailerController.refreshButtonState()

                            onMetadataApplied()
                            loadSimilarMovies(id)
                        }
                        is com.tvonnet.debridxtreamiptv.data.Result.Error -> {
                            android.util.Log.e("MovieDetailActivity", "Failed to fetch TMDB details", result.exception)
                        }
                        else -> {}
                    }
                }
            } catch (e: Exception) {
                android.util.Log.e("MovieDetailActivity", "Error fetching details", e)
            } finally {
                // Always load sources, even if details fetch failed
                onFetchComplete(imdbId)
            }
        }
    }

    /** Fetches IMDb + Rotten Tomatoes scores from OMDb and shows them in the ratings row. */
    private fun loadExternalRatings() {
        val imdb = currentImdbId() ?: return
        activity.lifecycleScope.launch {
            val ratings = OmdbClient.fetchRatings(imdb) ?: return@launch
            ratings.imdbRating?.let {
                tvImdbRating.text = it
                badgeImdb.visibility = View.VISIBLE
            }
            ratings.rottenTomatoes?.let {
                tvRtRating.text = it
                badgeRt.visibility = View.VISIBLE
            }
            // OMDb age rating as a fallback when TMDB certification is missing.
            if (movieAgeRating().isNullOrBlank() && !ratings.rated.isNullOrBlank()) {
                setMovieAgeRating(ratings.rated)
                tvAgeRating.text = ratings.rated
                tvAgeRating.visibility = View.VISIBLE
                layoutMetadataRow.visibility = View.VISIBLE
            }
        }
    }

    private fun loadSimilarMovies(tmdbId: Int) {
        activity.lifecycleScope.launch {
            try {
                val result = withContext(Dispatchers.IO) {
                    tmdbRemoteDataSource.getMovieRecommendations(tmdbId)
                }
                when (result) {
                    is com.tvonnet.debridxtreamiptv.data.Result.Success -> {
                        val movies = result.data.results?.take(6) ?: emptyList()
                        if (movies.isNotEmpty()) {
                            similarAdapter.submitList(movies)
                            val title = movieName()?.trim()
                            activity.findViewById<TextView>(R.id.header_similar).text =
                                if (!title.isNullOrBlank()) {
                                    "BECAUSE YOU WATCHED ${title.uppercase(java.util.Locale.getDefault())}"
                                } else {
                                    "SIMILAR MOVIES"
                                }
                            layoutSimilarRow.visibility = View.VISIBLE
                        }
                    }
                    else -> {}
                }
            } catch (_: Exception) {}
        }
    }
}
