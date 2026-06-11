package com.tvonnet.debridxtreamiptv.data.debrid.api

import com.tvonnet.debridxtreamiptv.data.debrid.model.TmdbGenreResponse
import com.tvonnet.debridxtreamiptv.data.debrid.model.TmdbMovieResponse
import com.tvonnet.debridxtreamiptv.data.debrid.model.TmdbTvShowResponse
import com.tvonnet.debridxtreamiptv.data.debrid.model.TmdbVideoResponse
import retrofit2.http.GET
import retrofit2.http.Path
import retrofit2.http.Query

/**
 * TMDB API service for metadata and artwork
 * API Key is public and rate-limited - suitable for TV app usage
 */
interface TmdbApiService {
    
    @GET("trending/movie/week")
    suspend fun getTrendingMovies(
        @Query("api_key") apiKey: String,
        @Query("page") page: Int = 1
    ): TmdbMovieResponse
    
    @GET("trending/tv/week")
    suspend fun getTrendingTvShows(
        @Query("api_key") apiKey: String,
        @Query("page") page: Int = 1
    ): TmdbTvShowResponse
    
    @GET("movie/popular")
    suspend fun getPopularMovies(
        @Query("api_key") apiKey: String,
        @Query("page") page: Int = 1
    ): TmdbMovieResponse
    
    @GET("tv/popular")
    suspend fun getPopularTvShows(
        @Query("api_key") apiKey: String,
        @Query("page") page: Int = 1
    ): TmdbTvShowResponse
    
    @GET("search/movie")
    suspend fun searchMovies(
        @Query("api_key") apiKey: String,
        @Query("query") query: String,
        @Query("page") page: Int = 1
    ): TmdbMovieResponse
    
    @GET("search/tv")
    suspend fun searchTvShows(
        @Query("api_key") apiKey: String,
        @Query("query") query: String,
        @Query("page") page: Int = 1
    ): TmdbTvShowResponse
    
    @GET("movie/{movie_id}")
    suspend fun getMovieDetails(
        @Path("movie_id") movieId: String,
        @Query("api_key") apiKey: String,
        @Query("append_to_response") appendToResponse: String = "videos,credits"
    ): com.tvonnet.debridxtreamiptv.data.debrid.model.TmdbMovieDetails

    @GET("movie/{movie_id}/videos")
    suspend fun getMovieVideos(
        @Path("movie_id") movieId: String,
        @Query("api_key") apiKey: String
    ): TmdbVideoResponse
    
    @GET("tv/{tv_id}")
    suspend fun getTvShowDetails(
        @Path("tv_id") tvId: String,
        @Query("api_key") apiKey: String,
        @Query("append_to_response") appendToResponse: String = "external_ids,videos,credits"
    ): com.tvonnet.debridxtreamiptv.data.debrid.model.TmdbTvShowDetails

    @GET("tv/{tv_id}/season/{season_number}")
    suspend fun getTvSeasonDetails(
        @Path("tv_id") tvId: String,
        @Path("season_number") seasonNumber: Int,
        @Query("api_key") apiKey: String
    ): com.tvonnet.debridxtreamiptv.data.debrid.model.TmdbSeasonDetails

    @GET("discover/movie")
    suspend fun discoverMovies(
        @Query("api_key") apiKey: String,
        @Query("sort_by") sortBy: String? = "popularity.desc",
        @Query("page") page: Int = 1,
        @Query("with_original_language") withOriginalLanguage: String? = null,
        @Query("with_watch_providers") withWatchProviders: String? = null,
        @Query("watch_region") watchRegion: String? = "US",
        @Query("primary_release_date.lte") primaryReleaseDateLte: String? = null,
        @Query("primary_release_date.gte") primaryReleaseDateGte: String? = null,
        @Query("vote_count.gte") voteCountGte: Int? = null,
        @Query("with_genres") withGenres: String? = null
    ): TmdbMovieResponse

    @GET("discover/tv")
    suspend fun discoverTvShows(
        @Query("api_key") apiKey: String,
        @Query("sort_by") sortBy: String? = "popularity.desc",
        @Query("page") page: Int = 1,
        @Query("with_original_language") withOriginalLanguage: String? = null,
        @Query("with_watch_providers") withWatchProviders: String? = null,
        @Query("watch_region") watchRegion: String? = "US",
        @Query("first_air_date.lte") firstAirDateLte: String? = null,
        @Query("first_air_date.gte") firstAirDateGte: String? = null,
        @Query("vote_count.gte") voteCountGte: Int? = null,
        @Query("with_genres") withGenres: String? = null
    ): TmdbTvShowResponse

    @GET("genre/movie/list")
    suspend fun getMovieGenres(
        @Query("api_key") apiKey: String
    ): TmdbGenreResponse

    @GET("genre/tv/list")
    suspend fun getTvGenres(
        @Query("api_key") apiKey: String
    ): TmdbGenreResponse
}
