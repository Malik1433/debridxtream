package com.tvonnet.debridxtreamiptv.features.vodv2.ui

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.tvonnet.debridxtreamiptv.data.debrid.source.TmdbRemoteDataSource
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject

data class MovieDetailV2UiState(
    val isLoading: Boolean = false,
    val trailerValue: String? = null,
    val plot: String? = null,
    val year: String? = null,
    val genre: String? = null,
    val rating: String? = null,
    val backdropUrl: String? = null,
    val posterUrl: String? = null
)

@HiltViewModel
class MovieDetailViewModelV2 @Inject constructor(
    private val savedStateHandle: SavedStateHandle,
    private val tmdbRemoteDataSource: TmdbRemoteDataSource
) : ViewModel() {

    private val _uiState = MutableStateFlow(MovieDetailV2UiState())
    val uiState: StateFlow<MovieDetailV2UiState> = _uiState.asStateFlow()

    private var started = false

    init {
        // Best-effort auto-start (may be missing args depending on how the fragment is created).
        start(
            title = savedStateHandle.get<String>("title"),
            yearHint = savedStateHandle.get<String>("year"),
            existingTrailer = savedStateHandle.get<String>("trailer")
        )
    }

    fun start(title: String?, yearHint: String?, existingTrailer: String?) {
        if (started) return
        started = true

        if (!existingTrailer.isNullOrBlank()) {
            _uiState.value = MovieDetailV2UiState(trailerValue = existingTrailer)
            return
        }

        val safeTitle = title?.trim().orEmpty()
        if (safeTitle.isBlank()) return
        val safeYearHint = yearHint?.trim()

        viewModelScope.launch {
            android.util.Log.d("MovieDetailV2", "TMDB enrich start: title=$safeTitle year=$safeYearHint")
            _uiState.value = _uiState.value.copy(isLoading = true)
            try {
                val match = withContext(Dispatchers.IO) { findBestTmdbMatch(safeTitle, safeYearHint) }
                if (match == null) {
                    android.util.Log.w("MovieDetailV2", "TMDB enrich: no match")
                    _uiState.value = _uiState.value.copy(isLoading = false)
                    return@launch
                }

                val (tmdbId, backdropPath, posterPath, overview, releaseDate, genres, voteAverage) = match
                android.util.Log.d("MovieDetailV2", "TMDB match id=$tmdbId release=$releaseDate")
                val trailerKey = withContext(Dispatchers.IO) { fetchBestTrailerKey(tmdbId) }
                android.util.Log.d("MovieDetailV2", "TMDB trailer key=${trailerKey ?: "null"}")

                _uiState.value = MovieDetailV2UiState(
                    isLoading = false,
                    trailerValue = trailerKey,
                    plot = overview,
                    year = releaseDate?.take(4),
                    genre = genres?.takeIf { it.isNotBlank() },
                    rating = voteAverage?.takeIf { it.isNotBlank() },
                    backdropUrl = backdropPath,
                    posterUrl = posterPath
                )
            } catch (e: Exception) {
                android.util.Log.e("MovieDetailV2", "TMDB enrich failed", e)
                _uiState.value = _uiState.value.copy(isLoading = false)
            }
        }
    }

    private data class TmdbMatch(
        val id: Int,
        val backdropUrl: String?,
        val posterUrl: String?,
        val overview: String?,
        val releaseDate: String?,
        val genres: String?,
        val voteAverage: String?
    )

    private suspend fun findBestTmdbMatch(title: String, yearHint: String?): TmdbMatch? {
        val targetYear = yearHint?.take(4)?.toIntOrNull()
        val queries = buildSearchQueries(title).take(4)

        for (query in queries) {
            val search = tmdbRemoteDataSource.searchMovies(query = query)
            val response = search.getOrNull() ?: continue
            val results = response.results.orEmpty()
            if (results.isEmpty()) continue

            val normalizedTarget = normalizeTitle(query)

            val best = results
                .asSequence()
                .mapNotNull { movie ->
                    val id = movie.id ?: return@mapNotNull null
                    val candidateYear = movie.releaseDate?.take(4)?.toIntOrNull()
                    val candidateTitle = movie.title ?: movie.originalTitle ?: ""
                    val normalizedCandidate = normalizeTitle(candidateTitle)
                    val yearScore =
                        if (targetYear != null && candidateYear != null) kotlin.math.abs(targetYear - candidateYear) else 99
                    val titleScore =
                        if (normalizedTarget != null && normalizedCandidate != null && normalizedTarget == normalizedCandidate) 0 else 1
                    Triple(titleScore, yearScore, id)
                }
                .minWithOrNull(compareBy<Triple<Int, Int, Int>> { it.first }.thenBy { it.second })
                ?: continue

            val details = tmdbRemoteDataSource.getMovieDetails(best.third)
            val detailsData = details.getOrNull() ?: continue

            val backdropUrl = detailsData.backdropPath?.let { "https://image.tmdb.org/t/p/w1280$it" }
            val posterUrl = detailsData.posterPath?.let { "https://image.tmdb.org/t/p/w500$it" }
            val genres = detailsData.genres?.joinToString(", ") { it.name.orEmpty() }?.trim()?.takeIf { it.isNotBlank() }
            val voteAverage = detailsData.voteAverage?.toString()

            return TmdbMatch(
                id = detailsData.id ?: best.third,
                backdropUrl = backdropUrl,
                posterUrl = posterUrl,
                overview = detailsData.overview,
                releaseDate = detailsData.releaseDate,
                genres = genres,
                voteAverage = voteAverage
            )
        }

        return null
    }

    private suspend fun fetchBestTrailerKey(tmdbMovieId: Int): String? {
        val result = tmdbRemoteDataSource.getMovieVideos(tmdbMovieId)
        val response = result.getOrNull() ?: return null
        val videos = response.results.orEmpty()
        if (videos.isEmpty()) return null

        // Best practice: prefer YouTube official trailers.
        return videos
            .asSequence()
            .filter { it.site.equals("YouTube", true) }
            .sortedWith(
                compareByDescending<com.tvonnet.debridxtreamiptv.data.debrid.model.TmdbVideo> { it.official == true }
                    .thenByDescending { it.type.equals("Trailer", true) }
                    .thenByDescending { it.size ?: 0 }
            )
            .firstOrNull()
            ?.key
    }

    private fun normalizeTitle(value: String?): String? {
        if (value.isNullOrBlank()) return null
        return value.lowercase()
            .replace(Regex("[^a-z0-9]"), "")
            .takeIf { it.isNotBlank() }
    }

    private fun buildSearchQueries(title: String): List<String> {
        val base = title.trim()

        // Remove common IPTV prefixes like "EN|" or "[EN]" or "EN -"
        val noPrefix = base
            .replace(Regex("^\\[[A-Za-z]{2,3}]\\s*"), "")
            .replace(Regex("^[A-Za-z]{2,3}\\s*\\|\\s*"), "")
            .replace(Regex("^[A-Za-z]{2,3}\\s*-\\s*"), "")
            .replace(Regex("^\\|?\\s*MULTI\\s*\\|?\\s*", RegexOption.IGNORE_CASE), "")
            .trim()

        // Remove common quality/release tokens.
        val strippedTokens = noPrefix
            .replace(Regex("\\b(multi|dual|audio|dubbed|hindi|english|french|spanish|arabic|turkish|portuguese)\\b", RegexOption.IGNORE_CASE), "")
            .replace(Regex("\\b(4k|2160p|1080p|720p|480p|hdr|dv|hevc|x265|x264|h\\.265|h\\.264|web[- ]?dl|webrip|bluray|brrip|dvdrip|cam|ts|tc|aac|dts|atmos)\\b", RegexOption.IGNORE_CASE), "")
            .replace(Regex("\\s+"), " ")
            .trim()

        // Remove trailing year in parentheses or as last token.
        val noYearSuffix = strippedTokens
            .replace(Regex("\\(\\s*\\d{4}\\s*\\)$"), "")
            .replace(Regex("\\s+\\d{4}$"), "")
            .trim()

        val cleaned = noYearSuffix
            .replace(Regex("[^\\p{L}\\p{N} ]"), " ")
            .replace(Regex("\\s+"), " ")
            .trim()

        val firstWords = cleaned.split(" ").take(6).joinToString(" ").trim()

        return listOfNotNull(
            cleaned.takeIf { it.isNotBlank() },
            firstWords.takeIf { it.isNotBlank() && it.length < cleaned.length },
            noYearSuffix.takeIf { it.isNotBlank() && it != cleaned },
            strippedTokens.takeIf { it.isNotBlank() && it != cleaned },
            noPrefix.takeIf { it.isNotBlank() && it != cleaned }
        ).distinct()
    }
}
