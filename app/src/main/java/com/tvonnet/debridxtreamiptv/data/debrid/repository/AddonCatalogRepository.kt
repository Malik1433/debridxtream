package com.tvonnet.debridxtreamiptv.data.debrid.repository

import com.tvonnet.debridxtreamiptv.data.Result
import com.tvonnet.debridxtreamiptv.data.Result.Error
import com.tvonnet.debridxtreamiptv.data.Result.Success
import com.tvonnet.debridxtreamiptv.data.debrid.model.AddonDefinition
import com.tvonnet.debridxtreamiptv.data.debrid.model.AddonStream
import com.tvonnet.debridxtreamiptv.data.debrid.model.DebridRowConfig
import com.tvonnet.debridxtreamiptv.data.debrid.model.TmdbGenre
import com.tvonnet.debridxtreamiptv.data.debrid.model.TmdbImageUrl
import com.tvonnet.debridxtreamiptv.data.debrid.source.AddonRemoteDataSource
import com.tvonnet.debridxtreamiptv.data.debrid.source.TmdbRemoteDataSource
import com.tvonnet.debridxtreamiptv.data.prefs.DebridPreferences
import com.tvonnet.debridxtreamiptv.data.model.ContinueWatchingItem
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Repository orchestrating MediaFusion/Zilean add-on discovery and user selections.
 */
@Singleton
class AddonCatalogRepository @Inject constructor(
    private val remote: AddonRemoteDataSource,
    private val tmdbRemote: TmdbRemoteDataSource,
    private val preferences: DebridPreferences,
    private val watchHistoryPrefs: com.tvonnet.debridxtreamiptv.data.prefs.WatchHistoryPreferences,
    private val favoriteDao: com.tvonnet.debridxtreamiptv.data.local.dao.FavoriteDao
) {

    suspend fun fetchAddonDefinitions(registryUrl: String = DEFAULT_ADDON_REGISTRY_URL): Result<List<AddonDefinition>> {
        val result = remote.fetchAddonDefinitions(registryUrl)
        if (result is Success && result.data.isNotEmpty() && preferences.getSelectedAddonIds().isEmpty()) {
            // Seed default selection with all available addon names to ensure rows render.
            preferences.saveSelectedAddonIds(result.data.map { it.name }.toSet())
        }
        return result
    }

    suspend fun fetchMediaFusionStreams(url: String): Result<List<AddonStream>> {
        return remote.fetchMediaFusionStreams(url).sanitize()
    }

    suspend fun fetchZileanStreams(url: String): Result<List<AddonStream>> {
        return remote.fetchZileanStreams(url).sanitize()
    }

    fun getSelectedAddonIds(): Set<String> = preferences.getSelectedAddonIds()

    fun updateSelectedAddonIds(ids: Set<String>) {
        preferences.saveSelectedAddonIds(ids)
    }

    fun getRowConfigurations(): List<DebridRowConfig> = preferences.getRowConfigurations()

    fun saveRowConfigurations(rows: List<DebridRowConfig>) {
        preferences.saveRowConfigurations(rows)
    }

    /**
     * Catalog browsing with TMDB integration
     */
    suspend fun getTrendingMovies(): Result<List<CatalogItem>> {
        return when (val result = tmdbRemote.getTrendingMovies()) {
            is Success -> {
                val items = result.data.results?.mapNotNull { movie ->
                    movie.title?.let {
                        CatalogItem(
                            id = movie.id?.toString() ?: return@mapNotNull null,
                            title = movie.title,
                            posterUrl = TmdbImageUrl.getPosterUrl(movie.posterPath),
                            backdropUrl = TmdbImageUrl.getBackdropUrl(movie.backdropPath),
                            type = "movie",
                            year = movie.releaseDate?.take(4),
                            rating = movie.voteAverage?.toString(),
                            overview = movie.overview,
                            genreIds = movie.genreIds
                        )
                    }
                } ?: emptyList()
                Success(items)
            }
            is Error -> result
            else -> Error(Exception("Unknown error"))
        }
    }

    suspend fun getTrendingSeries(): Result<List<CatalogItem>> {
        return when (val result = tmdbRemote.getTrendingTvShows()) {
            is Success -> {
                val items = result.data.results?.mapNotNull { show ->
                    show.name?.let {
                        CatalogItem(
                            id = show.id?.toString() ?: return@mapNotNull null,
                            title = show.name,
                            posterUrl = TmdbImageUrl.getPosterUrl(show.posterPath),
                            backdropUrl = TmdbImageUrl.getBackdropUrl(show.backdropPath),
                            type = "series",
                            year = show.firstAirDate?.take(4),
                            rating = show.voteAverage?.toString(),
                            overview = show.overview,
                            genreIds = show.genreIds
                        )
                    }
                } ?: emptyList()
                Success(items)
            }
            is Error -> result
            else -> Error(Exception("Unknown error"))
        }
    }



    /**
     * Unified search for Movies and TV Series
     */
    suspend fun searchContent(query: String): Result<List<CatalogItem>> {
        // Fetch both concurrently or sequentially
        val moviesResult = tmdbRemote.searchMovies(query)
        val showsResult = tmdbRemote.searchTvShows(query)
        
        val items = mutableListOf<CatalogItem>()
        val errors = mutableListOf<Exception>()
        
        // Process Movies
        if (moviesResult is Success) {
            moviesResult.data.results?.mapNotNull { movie ->
                movie.title?.let {
                    CatalogItem(
                        id = movie.id?.toString() ?: return@mapNotNull null,
                        title = movie.title,
                        posterUrl = TmdbImageUrl.getPosterUrl(movie.posterPath),
                        backdropUrl = TmdbImageUrl.getBackdropUrl(movie.backdropPath),
                        type = "movie",
                        year = movie.releaseDate?.take(4),
                        rating = movie.voteAverage?.toString(),
                        overview = movie.overview,
                        genreIds = movie.genreIds
                    )
                }
            }?.let { items.addAll(it) }
        } else if (moviesResult is Error) {
            errors.add(moviesResult.exception)
        }
        
        // Process Shows
        if (showsResult is Success) {
            showsResult.data.results?.mapNotNull { show ->
                show.name?.let {
                    CatalogItem(
                        id = show.id?.toString() ?: return@mapNotNull null,
                        title = show.name,
                        posterUrl = TmdbImageUrl.getPosterUrl(show.posterPath),
                        backdropUrl = TmdbImageUrl.getBackdropUrl(show.backdropPath),
                        type = "series",
                        year = show.firstAirDate?.take(4),
                        rating = show.voteAverage?.toString(),
                        overview = show.overview,
                        genreIds = show.genreIds
                    )
                }
            }?.let { items.addAll(it) }
        } else if (showsResult is Error) {
            errors.add(showsResult.exception)
        }
        
        // Return success if at least one succeeded or list is empty (valid empty search)
        // Only return error if BOTH failed
        return if (errors.size < 2) {
            // Sort by something relevant? For now, mix or keep as is.
            // Maybe prioritize by popularity/rating if available, but search results usually come sorted by relevance from API.
            // We just appended shows after movies. A simple shuffle or just basic concatenation is fine for strict relevance.
            Success(items)
        } else {
            Error(errors.firstOrNull() ?: Exception("Search failed"))
        }
    }

    suspend fun getDiscoveryContent(
        type: String, // "movie" or "series"
        page: Int = 1,
        sortBy: String = "popularity.desc",
        originalLanguage: String? = null,
        watchProviders: String? = null,
        watchRegion: String? = "US",
        releaseDateGte: String? = null,
        withGenres: String? = null
    ): Result<List<CatalogItem>> {
        val today = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date())

        return if (type == "movie") {
            when (val result = tmdbRemote.discoverMovies(
                sortBy = sortBy,
                page = page,
                withOriginalLanguage = originalLanguage,
                withWatchProviders = watchProviders,
                watchRegion = watchRegion,
                releaseDateLte = today,
                releaseDateGte = releaseDateGte,
                withGenres = withGenres
            )) {
                is Success -> {
                     val items = result.data.results?.mapNotNull { movie ->
                        movie.title?.let {
                            CatalogItem(
                                id = movie.id?.toString() ?: return@mapNotNull null,
                                title = movie.title,
                                posterUrl = TmdbImageUrl.getPosterUrl(movie.posterPath),
                                backdropUrl = TmdbImageUrl.getBackdropUrl(movie.backdropPath),
                                type = "movie",
                                year = movie.releaseDate?.take(4),
                                rating = movie.voteAverage?.toString(),
                                overview = movie.overview,
                                genreIds = movie.genreIds
                            )
                        }
                    } ?: emptyList()
                    Success(items)
                }
                is Error -> result
                else -> Error(Exception("Unknown error"))
            }
        } else {
             when (val result = tmdbRemote.discoverTvShows(
                sortBy = sortBy,
                page = page,
                withOriginalLanguage = originalLanguage,
                withWatchProviders = watchProviders,
                watchRegion = watchRegion,
                firstAirDateLte = today,
                firstAirDateGte = releaseDateGte,
                withGenres = withGenres
            )) {
                is Success -> {
                    val items = result.data.results?.mapNotNull { show ->
                        show.name?.let {
                            CatalogItem(
                                id = show.id?.toString() ?: return@mapNotNull null,
                                title = show.name,
                                posterUrl = TmdbImageUrl.getPosterUrl(show.posterPath),
                                backdropUrl = TmdbImageUrl.getBackdropUrl(show.backdropPath),
                                type = "series",
                                year = show.firstAirDate?.take(4),
                                rating = show.voteAverage?.toString(),
                                overview = show.overview,
                                genreIds = show.genreIds
                            )
                        }
                    } ?: emptyList()
                    Success(items)
                }
                is Error -> result
                else -> Error(Exception("Unknown error"))
            }
        }
    }

    /**
     * Fetches the list of official genres for movies from TMDB.
     * @return A Result containing a list of TmdbGenre items.
     */
    suspend fun getMovieGenres(): Result<List<TmdbGenre>> {
        return when (val result = tmdbRemote.getMovieGenres()) {
            is Success -> Success(result.data.genres ?: emptyList())
            is Error -> result
            else -> Error(Exception("Unknown error"))
        }
    }

    /**
     * Fetches the list of official genres for TV shows from TMDB.
     * @return A Result containing a list of TmdbGenre items.
     */
    suspend fun getTvGenres(): Result<List<TmdbGenre>> {
        return when (val result = tmdbRemote.getTvGenres()) {
            is Success -> Success(result.data.genres ?: emptyList())
            is Error -> result
            else -> Error(Exception("Unknown error"))
        }
    }

    private fun Result<List<AddonStream>>.sanitize(): Result<List<AddonStream>> {
        return when (this) {
            is Success -> Success(data.filter { !it.infoHash.isNullOrBlank() || !it.magnet.isNullOrBlank() })
            is Error -> this
            else -> this
        }
    }

    suspend fun getContinueWatching(): List<ContinueWatchingItem> {
        return watchHistoryPrefs.getContinueWatchingList()
            .filter { it.source == "debrid" }
    }

    suspend fun getLibraryItems(): List<CatalogItem> {
        return try {
            val favorites = favoriteDao.getFavoritesSync()
            favorites.filter { it.type == "vod" || it.type == "series" }
                .map { favorite ->
                    CatalogItem(
                        id = favorite.streamId,
                        title = favorite.name,
                        posterUrl = favorite.iconUrl,
                        backdropUrl = favorite.iconUrl, // Use iconUrl as backdrop fallback for library items
                        type = favorite.type,
                        year = null,
                        rating = null
                    )
                }
        } catch (e: Exception) {
            emptyList()
        }
    }

    companion object {
        const val DEFAULT_ADDON_REGISTRY_URL =
            "https://raw.githubusercontent.com/codebutter-bit/scraper/refs/heads/main/addons-en.json"
    }
}

/**
 * Catalog item model for browsing
 */
@kotlinx.parcelize.Parcelize
data class CatalogItem(
    val id: String,
    val title: String,
    val posterUrl: String?,
    val backdropUrl: String?,
    val type: String,
    val year: String?,
    val rating: String?,
    val overview: String? = null,
    val genreIds: List<Int>? = null
) : android.os.Parcelable


