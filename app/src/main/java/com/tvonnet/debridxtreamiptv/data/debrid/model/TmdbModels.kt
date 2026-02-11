package com.tvonnet.debridxtreamiptv.data.debrid.model

import com.google.gson.annotations.SerializedName

/**
 * TMDB API response models for metadata and artwork
 */
data class TmdbMovieResponse(
    @SerializedName("page") val page: Int?,
    @SerializedName("results") val results: List<TmdbMovie>?,
    @SerializedName("total_pages") val totalPages: Int?,
    @SerializedName("total_results") val totalResults: Int?
)

data class TmdbTvShowResponse(
    @SerializedName("page") val page: Int?,
    @SerializedName("results") val results: List<TmdbTvShow>?,
    @SerializedName("total_pages") val totalPages: Int?,
    @SerializedName("total_results") val totalResults: Int?
)

data class TmdbMovie(
    @SerializedName("id") val id: Int?,
    @SerializedName("title") val title: String?,
    @SerializedName("original_title") val originalTitle: String?,
    @SerializedName("overview") val overview: String?,
    @SerializedName("poster_path") val posterPath: String?,
    @SerializedName("backdrop_path") val backdropPath: String?,
    @SerializedName("release_date") val releaseDate: String?,
    @SerializedName("vote_average") val voteAverage: Double?,
    @SerializedName("vote_count") val voteCount: Int?,
    @SerializedName("popularity") val popularity: Double?,
    @SerializedName("genre_ids") val genreIds: List<Int>?,
    @SerializedName("adult") val adult: Boolean?,
    @SerializedName("original_language") val originalLanguage: String?
)

data class TmdbTvShow(
    @SerializedName("id") val id: Int?,
    @SerializedName("name") val name: String?,
    @SerializedName("original_name") val originalName: String?,
    @SerializedName("overview") val overview: String?,
    @SerializedName("poster_path") val posterPath: String?,
    @SerializedName("backdrop_path") val backdropPath: String?,
    @SerializedName("first_air_date") val firstAirDate: String?,
    @SerializedName("vote_average") val voteAverage: Double?,
    @SerializedName("vote_count") val voteCount: Int?,
    @SerializedName("popularity") val popularity: Double?,
    @SerializedName("genre_ids") val genreIds: List<Int>?,
    @SerializedName("origin_country") val originCountry: List<String>?,
    @SerializedName("original_language") val originalLanguage: String?
)

data class TmdbMovieDetails(
    @SerializedName("id") val id: Int?,
    @SerializedName("imdb_id") val imdbId: String?,
    @SerializedName("title") val title: String?,
    @SerializedName("overview") val overview: String?,
    @SerializedName("poster_path") val posterPath: String?,
    @SerializedName("backdrop_path") val backdropPath: String?,
    @SerializedName("release_date") val releaseDate: String?,
    @SerializedName("runtime") val runtime: Int?,
    @SerializedName("vote_average") val voteAverage: Double?,
    @SerializedName("genres") val genres: List<TmdbGenre>?,
    @SerializedName("tagline") val tagline: String?
)

data class TmdbTvShowDetails(
    @SerializedName("id") val id: Int?,
    @SerializedName("name") val name: String?,
    @SerializedName("overview") val overview: String?,
    @SerializedName("poster_path") val posterPath: String?,
    @SerializedName("backdrop_path") val backdropPath: String?,
    @SerializedName("first_air_date") val firstAirDate: String?,
    @SerializedName("number_of_seasons") val numberOfSeasons: Int?,
    @SerializedName("number_of_episodes") val numberOfEpisodes: Int?,
    @SerializedName("vote_average") val voteAverage: Double?,
    @SerializedName("genres") val genres: List<TmdbGenre>?,
    @SerializedName("external_ids") val externalIds: TmdbExternalIds?,
    @SerializedName("seasons") val seasons: List<TmdbSeason>?
)

data class TmdbSeason(
    @SerializedName("id") val id: Int?,
    @SerializedName("name") val name: String?,
    @SerializedName("overview") val overview: String?,
    @SerializedName("poster_path") val posterPath: String?,
    @SerializedName("season_number") val seasonNumber: Int?,
    @SerializedName("episode_count") val episodeCount: Int?,
    @SerializedName("air_date") val airDate: String?
)

data class TmdbSeasonDetails(
    @SerializedName("_id") val _id: String?,
    @SerializedName("id") val id: Int?,
    @SerializedName("name") val name: String?,
    @SerializedName("season_number") val seasonNumber: Int?,
    @SerializedName("episodes") val episodes: List<TmdbEpisode>?
)

data class TmdbEpisode(
    @SerializedName("id") val id: Int?,
    @SerializedName("name") val name: String?,
    @SerializedName("overview") val overview: String?,
    @SerializedName("still_path") val stillPath: String?,
    @SerializedName("episode_number") val episodeNumber: Int?,
    @SerializedName("season_number") val seasonNumber: Int?,
    @SerializedName("air_date") val airDate: String?,
    @SerializedName("vote_average") val voteAverage: Double?,
    @SerializedName("runtime") val runtime: Int?
)

data class TmdbGenre(
    @SerializedName("id") val id: Int?,
    @SerializedName("name") val name: String?
)

data class TmdbExternalIds(
    @SerializedName("imdb_id") val imdbId: String?,
    @SerializedName("tvdb_id") val tvdbId: Int?
)

/**
 * TMDB image URL builder
 */
object TmdbImageUrl {
    private const val BASE_URL = "https://image.tmdb.org/t/p/"
    
    const val POSTER_SIZE_W185 = "w185"
    const val POSTER_SIZE_W342 = "w342"
    const val POSTER_SIZE_W500 = "w500"
    const val POSTER_SIZE_ORIGINAL = "original"
    
    const val BACKDROP_SIZE_W300 = "w300"
    const val BACKDROP_SIZE_W780 = "w780"
    const val BACKDROP_SIZE_W1280 = "w1280"
    const val BACKDROP_SIZE_ORIGINAL = "original"
    
    fun getPosterUrl(path: String?, size: String = POSTER_SIZE_W342): String? {
        return if (!path.isNullOrBlank()) "$BASE_URL$size$path" else null
    }
    
    fun getBackdropUrl(path: String?, size: String = BACKDROP_SIZE_W780): String? {
        return if (!path.isNullOrBlank()) "$BASE_URL$size$path" else null
    }

    fun getProfileUrl(path: String?, size: String = POSTER_SIZE_W185): String? {
        return if (!path.isNullOrBlank()) "$BASE_URL$size$path" else null
    }
}

data class TmdbCredits(
    @SerializedName("id") val id: Int?,
    @SerializedName("cast") val cast: List<TmdbCast>?
)

data class TmdbCast(
    @SerializedName("id") val id: Int?,
    @SerializedName("name") val name: String?,
    @SerializedName("character") val character: String?,
    @SerializedName("profile_path") val profilePath: String?,
    @SerializedName("order") val order: Int?
)

