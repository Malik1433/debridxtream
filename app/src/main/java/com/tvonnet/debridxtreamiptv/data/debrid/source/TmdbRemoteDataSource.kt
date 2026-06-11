package com.tvonnet.debridxtreamiptv.data.debrid.source

import com.tvonnet.debridxtreamiptv.BuildConfig
import com.tvonnet.debridxtreamiptv.data.Result
import com.tvonnet.debridxtreamiptv.data.debrid.api.TmdbApiService
import com.tvonnet.debridxtreamiptv.data.debrid.model.TmdbGenreResponse
import com.tvonnet.debridxtreamiptv.data.debrid.model.TmdbMovieResponse
import com.tvonnet.debridxtreamiptv.data.debrid.model.TmdbTvShowResponse
import com.tvonnet.debridxtreamiptv.data.debrid.model.TmdbVideoResponse
import com.tvonnet.debridxtreamiptv.data.resultOf
import javax.inject.Inject

/**
 * Remote data source for TMDB API
 */
class TmdbRemoteDataSource @Inject constructor(
    private val tmdbService: TmdbApiService
) {
    
    private fun requireApiKey(): String {
        val apiKey = BuildConfig.TMDB_API_KEY
        check(apiKey.isNotBlank()) {
            "Missing TMDB API key. Set TMDB_API_KEY in local.properties to enable TMDB features."
        }
        return apiKey
    }
    
    suspend fun getTrendingMovies(page: Int = 1): Result<TmdbMovieResponse> = resultOf {
        tmdbService.getTrendingMovies(requireApiKey(), page)
    }
    
    suspend fun getTrendingTvShows(page: Int = 1): Result<TmdbTvShowResponse> = resultOf {
        tmdbService.getTrendingTvShows(requireApiKey(), page)
    }
    
    suspend fun getPopularMovies(page: Int = 1): Result<TmdbMovieResponse> = resultOf {
        tmdbService.getPopularMovies(requireApiKey(), page)
    }
    
    suspend fun getPopularTvShows(page: Int = 1): Result<TmdbTvShowResponse> = resultOf {
        tmdbService.getPopularTvShows(requireApiKey(), page)
    }
    
    suspend fun searchMovies(query: String, page: Int = 1): Result<TmdbMovieResponse> = resultOf {
        tmdbService.searchMovies(requireApiKey(), query, page)
    }
    
    suspend fun searchTvShows(query: String, page: Int = 1): Result<TmdbTvShowResponse> = resultOf {
        tmdbService.searchTvShows(requireApiKey(), query, page)
    }

    suspend fun getMovieDetails(movieId: Int): Result<com.tvonnet.debridxtreamiptv.data.debrid.model.TmdbMovieDetails> = resultOf {
        tmdbService.getMovieDetails(movieId.toString(), requireApiKey())
    }

    suspend fun getMovieVideos(movieId: Int): Result<TmdbVideoResponse> = resultOf {
        tmdbService.getMovieVideos(movieId.toString(), requireApiKey())
    }

    suspend fun getSeriesDetails(tvId: Int): Result<com.tvonnet.debridxtreamiptv.data.debrid.model.TmdbTvShowDetails> = resultOf {
        tmdbService.getTvShowDetails(tvId.toString(), requireApiKey())
    }

    suspend fun getSeasonDetails(tvId: Int, seasonNumber: Int): Result<com.tvonnet.debridxtreamiptv.data.debrid.model.TmdbSeasonDetails> = resultOf {
        tmdbService.getTvSeasonDetails(tvId.toString(), seasonNumber, requireApiKey())
    }

    suspend fun discoverMovies(
        sortBy: String = "popularity.desc",
        page: Int = 1,
        withOriginalLanguage: String? = null,
        withWatchProviders: String? = null,
        watchRegion: String? = "US",
        releaseDateLte: String? = null,
        releaseDateGte: String? = null,
        voteCountGte: Int? = null,
        withGenres: String? = null
    ): Result<TmdbMovieResponse> = resultOf {
        tmdbService.discoverMovies(
            apiKey = requireApiKey(),
            sortBy = sortBy,
            page = page,
            withOriginalLanguage = withOriginalLanguage,
            withWatchProviders = withWatchProviders,
            watchRegion = watchRegion,
            primaryReleaseDateLte = releaseDateLte,
            primaryReleaseDateGte = releaseDateGte,
            voteCountGte = voteCountGte,
            withGenres = withGenres
        )
    }

    suspend fun discoverTvShows(
        sortBy: String = "popularity.desc",
        page: Int = 1,
        withOriginalLanguage: String? = null,
        withWatchProviders: String? = null,
        watchRegion: String? = "US",
        firstAirDateLte: String? = null,
        firstAirDateGte: String? = null,
        voteCountGte: Int? = null,
        withGenres: String? = null
    ): Result<TmdbTvShowResponse> = resultOf {
        tmdbService.discoverTvShows(
            apiKey = requireApiKey(),
            sortBy = sortBy,
            page = page,
            withOriginalLanguage = withOriginalLanguage,
            withWatchProviders = withWatchProviders,
            watchRegion = watchRegion,
            firstAirDateLte = firstAirDateLte,
            firstAirDateGte = firstAirDateGte,
            voteCountGte = voteCountGte,
            withGenres = withGenres
        )
    }

    suspend fun getMovieGenres(): Result<TmdbGenreResponse> = resultOf {
        tmdbService.getMovieGenres(requireApiKey())
    }

    suspend fun getTvGenres(): Result<TmdbGenreResponse> = resultOf {
        tmdbService.getTvGenres(requireApiKey())
    }
}
