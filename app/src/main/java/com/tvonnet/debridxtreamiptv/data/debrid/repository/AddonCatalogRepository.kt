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
                val items = result.data.results?.filter { it.adult != true }?.mapNotNull { movie ->
                    movie.title?.let {
                        CatalogItem(
                            id = movie.id?.toString() ?: return@mapNotNull null,
                            title = movie.title,
                            posterUrl = TmdbImageUrl.getPosterUrl(movie.posterPath),
                            backdropUrl = TmdbImageUrl.getBackdropUrl(movie.backdropPath),
                            type = "movie",
                            year = movie.releaseDate?.take(4), releaseDate = movie.releaseDate,
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
                            year = show.firstAirDate?.take(4), releaseDate = show.firstAirDate,
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
            moviesResult.data.results?.filter { it.adult != true }?.mapNotNull { movie ->
                movie.title?.let {
                    CatalogItem(
                        id = movie.id?.toString() ?: return@mapNotNull null,
                        title = movie.title,
                        posterUrl = TmdbImageUrl.getPosterUrl(movie.posterPath),
                        backdropUrl = TmdbImageUrl.getBackdropUrl(movie.backdropPath),
                        type = "movie",
                        year = movie.releaseDate?.take(4), releaseDate = movie.releaseDate,
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
                        year = show.firstAirDate?.take(4), releaseDate = show.firstAirDate,
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
        sortBy: String? = null,
        catalogue: String = CATALOGUE_POPULAR,
        originalLanguage: String? = null,
        watchProviders: String? = null,
        watchRegion: String? = "US",
        releaseDateGte: String? = null,
        year: Int? = null,
        withGenres: String? = null
    ): Result<List<CatalogItem>> {
        val today = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date())
        val effectiveSortBy = sortBy ?: resolveDiscoverSort(type = type, catalogue = catalogue)
        val yearDateGte = year?.let { "%04d-01-01".format(Locale.US, it) } ?: releaseDateGte
        val voteCountGte = when {
            // Regional catalogs are voted far more thinly on TMDB than the global one —
            // at the global floor of 200 ALL of Bollywood is ~107 titles, Tamil is 5 and
            // Punjabi is ZERO. A language pick is already a deliberate filter, so a much
            // lower floor keeps the junk out without starving the catalog.
            catalogue == CATALOGUE_TOP_RATED ->
                if (originalLanguage == null) TOP_RATED_MIN_VOTE_COUNT else REGIONAL_TOP_RATED_MIN_VOTE_COUNT
            // The all-years "Popular" grid (Discover "Trending Everywhere") has no date bound, so obscure
            // old titles — e.g. softcore films TMDB doesn't flag `adult` — ride inflated popularity into it
            // with almost no votes. Require a real vote count there. Date-scoped rows (regional/new, 2024+)
            // keep no floor so genuinely new films still show.
            catalogue == CATALOGUE_POPULAR && yearDateGte == null ->
                if (originalLanguage == null) POPULAR_MIN_VOTE_COUNT else REGIONAL_POPULAR_MIN_VOTE_COUNT
            else -> null
        }
        val yearDateLte = year?.let { "%04d-12-31".format(Locale.US, it) }

        val params = DiscoverParams(
            sortBy = effectiveSortBy,
            page = page,
            originalLanguage = originalLanguage,
            watchProviders = watchProviders,
            watchRegion = watchRegion,
            dateLte = yearDateLte ?: today,
            dateGte = yearDateGte,
            voteCountGte = voteCountGte,
            withGenres = withGenres,
        )
        return if (type == "movie") discoverMovieItems(params) else discoverSeriesItems(params)
    }

    /** The resolved TMDB discover query, shared verbatim by the movie and series arms. */
    private data class DiscoverParams(
        val sortBy: String,
        val page: Int,
        val originalLanguage: String?,
        val watchProviders: String?,
        val watchRegion: String?,
        val dateLte: String,
        val dateGte: String?,
        val voteCountGte: Int?,
        val withGenres: String?,
    )

    private suspend fun discoverMovieItems(p: DiscoverParams): Result<List<CatalogItem>> {
        return when (val result = tmdbRemote.discoverMovies(
            sortBy = p.sortBy,
            page = p.page,
            withOriginalLanguage = p.originalLanguage,
            withWatchProviders = p.watchProviders,
            watchRegion = p.watchRegion,
            releaseDateLte = p.dateLte,
            releaseDateGte = p.dateGte,
            voteCountGte = p.voteCountGte,
            withGenres = p.withGenres,
            withoutKeywords = EXCLUDED_ADULT_KEYWORDS
        )) {
            is Success -> {
                 val items = result.data.results?.filter { it.adult != true }?.mapNotNull { movie ->
                    movie.title?.let {
                        CatalogItem(
                            id = movie.id?.toString() ?: return@mapNotNull null,
                            title = movie.title,
                            posterUrl = TmdbImageUrl.getPosterUrl(movie.posterPath),
                            backdropUrl = TmdbImageUrl.getBackdropUrl(movie.backdropPath),
                            type = "movie",
                            year = movie.releaseDate?.take(4), releaseDate = movie.releaseDate,
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

    private suspend fun discoverSeriesItems(p: DiscoverParams): Result<List<CatalogItem>> {
        return when (val result = tmdbRemote.discoverTvShows(
            sortBy = p.sortBy,
            page = p.page,
            withOriginalLanguage = p.originalLanguage,
            withWatchProviders = p.watchProviders,
            watchRegion = p.watchRegion,
            firstAirDateLte = p.dateLte,
            firstAirDateGte = p.dateGte,
            voteCountGte = p.voteCountGte,
            withGenres = p.withGenres,
            withoutKeywords = EXCLUDED_ADULT_KEYWORDS
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
                            year = show.firstAirDate?.take(4), releaseDate = show.firstAirDate,
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

    private fun resolveDiscoverSort(type: String, catalogue: String): String {
        return when (catalogue) {
            CATALOGUE_TOP_RATED -> "vote_average.desc"
            CATALOGUE_NEWEST -> if (type == "series") "first_air_date.desc" else "primary_release_date.desc"
            else -> "popularity.desc"
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
            android.util.Log.w("AddonCatalogRepository", "Addon catalog fetch/parse failed", e)
            emptyList()
        }
    }

    companion object {
        const val DEFAULT_ADDON_REGISTRY_URL =
            "https://raw.githubusercontent.com/codebutter-bit/scraper/refs/heads/main/addons-en.json"
        const val CATALOGUE_POPULAR = "popular"
        const val CATALOGUE_TOP_RATED = "top_rated"
        const val CATALOGUE_NEWEST = "newest"
        private const val TOP_RATED_MIN_VOTE_COUNT = 200
        private const val POPULAR_MIN_VOTE_COUNT = 200
        // Language-scoped grids. Measured on TMDB live: Punjabi has 14 titles at a floor
        // of 10 but 129 at 3; Tamil ~675 -> ~1.5k. Three votes still parks the zero-vote
        // junk while letting thinly-voted industries actually fill their catalog.
        private const val REGIONAL_POPULAR_MIN_VOTE_COUNT = 3
        private const val REGIONAL_TOP_RATED_MIN_VOTE_COUNT = 50
        // TMDB keyword ids for adult/erotica that aren't caught by `adult=true` (softcore, erotic movie,
        // pornography, hardcore) — excluded from all discover queries via `without_keywords` (OR-joined).
        private const val EXCLUDED_ADULT_KEYWORDS = "190370|155477|445|260863"
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
    val genreIds: List<Int>? = null,
    val releaseDate: String? = null
) : android.os.Parcelable


