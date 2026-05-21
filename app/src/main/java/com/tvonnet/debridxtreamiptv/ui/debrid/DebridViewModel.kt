package com.tvonnet.debridxtreamiptv.ui.debrid

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.tvonnet.debridxtreamiptv.data.debrid.repository.DebridAccountRepository
import com.tvonnet.debridxtreamiptv.data.debrid.repository.AddonCatalogRepository
import com.tvonnet.debridxtreamiptv.data.debrid.repository.CatalogItem
import com.tvonnet.debridxtreamiptv.data.Result
import com.tvonnet.debridxtreamiptv.data.prefs.DebridPreferences
import com.tvonnet.debridxtreamiptv.data.onSuccess
import com.tvonnet.debridxtreamiptv.data.onFailure
import com.tvonnet.debridxtreamiptv.data.model.ContentType
import com.tvonnet.debridxtreamiptv.data.model.ContinueWatchingItem
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import retrofit2.HttpException
import javax.inject.Inject

/**
 * ViewModel for Debrid section
 * Manages authentication state and content loading
 */
@HiltViewModel
class DebridViewModel @Inject constructor(
    private val debridAccountRepo: DebridAccountRepository,
    private val catalogRepo: AddonCatalogRepository,
    private val debridPrefs: DebridPreferences
) : ViewModel() {
    
    private val _uiState = MutableStateFlow<DebridUiState>(DebridUiState.Loading)
    val uiState: StateFlow<DebridUiState> = _uiState.asStateFlow()
    
    private val _genres = MutableStateFlow<List<com.tvonnet.debridxtreamiptv.data.debrid.model.TmdbGenre>>(emptyList())
    val genres: StateFlow<List<com.tvonnet.debridxtreamiptv.data.debrid.model.TmdbGenre>> = _genres.asStateFlow()
    
    private var loadJob: Job? = null
    private var cachedRows: List<DebridRow> = emptyList()
    private var catalogStateVersion: Long = 0L
    
    fun checkAuthStatus() {
        viewModelScope.launch {
            val authState = debridAccountRepo.getCachedAuthState()

            // Start catalog bootstrap immediately so startup is cache-first / non-blocking.
            loadCatalog()

            if (authState != null) {
                val refreshResult = debridAccountRepo.refreshAuthStateIfNeeded()
                if (refreshResult is Result.Error && shouldClearAuthState(refreshResult.exception)) {
                    debridPrefs.clearRealDebridToken()
                }

                // Verify token is still valid (force refresh once on auth error)
                debridAccountRepo.getUser().onSuccess {
                    // Auth valid
                }.onFailure { error ->
                    if (isAuthError(error)) {
                        val forcedRefresh = debridAccountRepo.refreshAuthState(force = true)
                        if (forcedRefresh is Result.Error && shouldClearAuthState(forcedRefresh.exception)) {
                            debridPrefs.clearRealDebridToken()
                        }
                    }
                }
            }
        }
    }
    
    fun loadCatalog(force: Boolean = false) {
        if (!force && loadJob?.isActive == true) return
        loadJob?.cancel()
        val cachedSnapshot = cachedRows
        val hasCache = cachedSnapshot.isNotEmpty()
        val requestVersion = catalogStateVersion

        _uiState.value = if (hasCache) {
            DebridUiState.Content(cachedSnapshot, DebridRefreshState.Refreshing)
        } else {
            DebridUiState.Loading
        }

        loadJob = viewModelScope.launch {
            try {
                val result = withTimeoutOrNull(15_000L) { fetchCatalogRows() }

                if (result == null) {
                    if (requestVersion != catalogStateVersion) return@launch
                    _uiState.value = if (hasCache) {
                        DebridUiState.Content(cachedSnapshot, DebridRefreshState.Failed)
                    } else {
                        DebridUiState.Error("Timed out loading Debrid catalog. Check your connection and try again.")
                    }
                    return@launch
                }

                if (result.rows.isNotEmpty()) {
                    if (requestVersion != catalogStateVersion) return@launch
                    cachedRows = result.rows
                    catalogStateVersion++
                    _uiState.value = DebridUiState.Content(result.rows, DebridRefreshState.Idle)
                } else if (hasCache) {
                    if (requestVersion != catalogStateVersion) return@launch
                    _uiState.value = DebridUiState.Content(cachedSnapshot, DebridRefreshState.Failed)
                } else {
                    if (requestVersion != catalogStateVersion) return@launch
                    _uiState.value = DebridUiState.Error(buildErrorMessage(result.errors))
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _uiState.value = DebridUiState.Error(e.message ?: "Failed to load content")
            }
        }
    }

    /**
     * Atomically refreshes only the "Continue Watching" row within the current UI state.
     *
     * This avoids a full catalog reload (which causes flicker and focus loss) by reading
     * fresh history from local storage and patching the existing cached row list in-place.
     * Mirrors Stremio's approach: metadata is the source of truth, UI updates are instantaneous.
     */
    fun refreshContinueWatching() {
        viewModelScope.launch {
            try {
                val freshHistory = catalogRepo.getContinueWatching()
                val freshItems = freshHistory.map { it.toDebridContentItem() }

                val currentState = _uiState.value
                if (currentState !is DebridUiState.Content) return@launch

                val existingRows = currentState.rows.toMutableList()
                val cwIndex = existingRows.indexOfFirst { it.id == "continue_watching" }

                if (freshItems.isNotEmpty()) {
                    val newCwRow = DebridRow(
                        id = "continue_watching",
                        title = "Continue Watching",
                        items = freshItems,
                        canLoadMore = false
                    )
                    if (cwIndex >= 0) {
                        existingRows[cwIndex] = newCwRow
                    } else {
                        existingRows.add(0, newCwRow)
                    }
                } else if (cwIndex >= 0) {
                    existingRows.removeAt(cwIndex)
                }

                cachedRows = existingRows
                catalogStateVersion++
                _uiState.value = DebridUiState.Content(existingRows, DebridRefreshState.Idle)
            } catch (e: Exception) {
                // Non-fatal: silently ignore refresh errors to avoid disrupting the UI
                android.util.Log.w("DebridViewModel", "Continue Watching refresh failed: ${e.message}")
            }
        }
    }

    fun fetchGenres() {
        viewModelScope.launch {
            try {
                val movieGenres = catalogRepo.getMovieGenres()
                val tvGenres = catalogRepo.getTvGenres()
                
                val allGenres = mutableSetOf<com.tvonnet.debridxtreamiptv.data.debrid.model.TmdbGenre>()
                movieGenres.onSuccess { allGenres.addAll(it) }
                tvGenres.onSuccess { allGenres.addAll(it) }
                
                _genres.value = allGenres.sortedBy { it.name }
            } catch (e: Exception) {
                // Ignore genre load errors
            }
        }
    }
    
    fun logout() {
        viewModelScope.launch {
            debridPrefs.clearRealDebridToken()
            _uiState.value = DebridUiState.NotAuthenticated
        }
    }

    private fun isAuthError(error: Throwable?): Boolean {
        if (error is HttpException) {
            if (error.code() == 401 || error.code() == 403) return true
        }
        val message = error?.message?.lowercase() ?: return false
        return message.contains("401") ||
            message.contains("unauthorized") ||
            message.contains("forbidden") ||
            message.contains("invalid") ||
            message.contains("expired")
    }

    private fun shouldClearAuthState(error: Throwable?): Boolean {
        if (error is HttpException) {
            return error.code() == 400 || error.code() == 401 || error.code() == 403
        }
        val message = error?.message?.lowercase() ?: return false
        return message.contains("invalid_grant") ||
            message.contains("invalid_token") ||
            message.contains("invalid token") ||
            message.contains("unauthorized") ||
            message.contains("forbidden")
    }

    private fun buildErrorMessage(errors: List<Exception>): String {
        if (errors.isEmpty()) {
            return "No content available"
        }
        val missingApiKey = errors.any { error ->
            error.message?.contains("tmdb api key", ignoreCase = true) == true
        }
        return if (missingApiKey) {
            "TMDB API key missing. Set TMDB_API_KEY in local.properties."
        } else {
            "Unable to load catalog. Check your connection and try again."
        }
    }

    private val rowParams = mutableMapOf<String, RowLoadParams>()
    private val rowPages = mutableMapOf<String, Int>()
    
    fun loadNextPage(rowId: String) {
        val params = rowParams[rowId] ?: return
        if (loadJob?.isActive == true) return // Prevent concurrent loads for now
        
        viewModelScope.launch {
            val currentPage = rowPages[rowId] ?: 1
            val nextPage = currentPage + 1
            
            // 1. Fetch Data directly (Sentinel is handled by Adapter)
            val result = loadRowContent(params, nextPage)
            
            result.onSuccess { newItems ->
                if (newItems.isNotEmpty()) {
                    rowPages[rowId] = nextPage
                    
                    val currentState = _uiState.value
                    if (currentState is DebridUiState.Content) {
                        val updatedRows = currentState.rows.map { row ->
                            if (row.id == rowId) {
                                val realNewItems = newItems.map { it.toDebridContentItem() }
                                row.copy(items = row.items + realNewItems)
                            } else {
                                row
                            }
                        }
                        _uiState.value = DebridUiState.Content(updatedRows, DebridRefreshState.Idle)
                        cachedRows = updatedRows
                        catalogStateVersion++
                    }
                } else {
                     // End of list reached
                    val currentState = _uiState.value
                    if (currentState is DebridUiState.Content) {
                        val updatedRows = currentState.rows.map { row ->
                            if (row.id == rowId) {
                                row.copy(canLoadMore = false)
                            } else {
                                row
                            }
                        }
                        _uiState.value = DebridUiState.Content(updatedRows, DebridRefreshState.Idle)
                    }
                }
            }.onFailure {
                 // Error handling: keep existing items
                 // For now, we just don't add new items.
            }
        }
    }

    private suspend fun fetchCatalogRows(): CatalogLoadResult {
        val rows = mutableListOf<DebridRow>()
        val errors = mutableListOf<Exception>()
        rowParams.clear()
        rowPages.clear()

        suspend fun appendRow(id: String, title: String, params: RowLoadParams? = null, preloadedItems: Result<List<CatalogItem>>? = null) {
            val result = preloadedItems ?: if (params != null) {
                loadRowContent(params, 1)
            } else {
                return // Should not happen
            }

            result.onSuccess { items ->
                if (items.isNotEmpty()) {
                    if (params != null) {
                        rowParams[id] = params
                        rowPages[id] = 1
                    }
                    rows.add(
                        DebridRow(
                            id = id,
                            title = title,
                            items = items.map { it.toDebridContentItem() },
                            canLoadMore = params != null // Only rows with params can load more
                        )
                    )
                }
            }.onFailure { error ->
                errors.add(error)
            }
        }

        // Load Continue Watching
        try {
            val continueWatching = catalogRepo.getContinueWatching()
            if (continueWatching.isNotEmpty()) {
                rows.add(
                    DebridRow(
                        id = "continue_watching",
                        title = "Continue Watching",
                        items = continueWatching.map { it.toDebridContentItem() },
                        canLoadMore = false
                    )
                )
            }
        } catch (e: Exception) {
            // Ignore history load errors
        }

        // 2. My Library (New optional row)
        try {
            val libraryItems = catalogRepo.getLibraryItems()
            if (libraryItems.isNotEmpty()) {
                rows.add(
                    DebridRow(
                        id = "my_library",
                        title = "My Library",
                        items = libraryItems.map { it.toDebridContentItem() },
                        canLoadMore = false
                    )
                )
            }
        } catch (e: Exception) {
            // Ignore library load errors
        }

        // 3. Trending Movies
        appendRow("trending_movies", "Trending Movies", preloadedItems = catalogRepo.getTrendingMovies())

        // 4. Trending Series
        appendRow("trending_series", "Trending Series", preloadedItems = catalogRepo.getTrendingSeries())

        // --- CUSTOM ROWS START ---

        // Bollywood Movies
        appendRow("bollywood_movies", "Bollywood New Popular Movies", 
            RowLoadParams("movie", originalLanguage = "hi", watchRegion = "IN", releaseDateGte = "2024-01-01"))

        // Bollywood Series
        appendRow("bollywood_series", "Bollywood New Popular Series",
            RowLoadParams("series", originalLanguage = "hi", watchRegion = "IN", releaseDateGte = "2024-01-01"))

        // Punjabi Movies
        appendRow("punjabi_movies", "Punjabi New Popular Movies",
            RowLoadParams("movie", originalLanguage = "pa", watchRegion = "IN", releaseDateGte = "2024-01-01"))

        // Tamil Movies
        appendRow("tamil_movies", "Tamil New Popular Movies",
            RowLoadParams("movie", originalLanguage = "ta", watchRegion = "IN", releaseDateGte = "2024-01-01"))

        // Tamil Series
        appendRow("tamil_series", "Tamil New Popular Series",
            RowLoadParams("series", originalLanguage = "ta", watchRegion = "IN", releaseDateGte = "2024-01-01"))

        // Netflix Movies
        appendRow("netflix_movies", "Netflix New Popular Movies",
            RowLoadParams("movie", watchProviders = "8", watchRegion = "US", releaseDateGte = "2024-01-01"))

        // Netflix Series
        appendRow("netflix_series", "Netflix New Popular Series",
            RowLoadParams("series", watchProviders = "8", watchRegion = "US", releaseDateGte = "2024-01-01"))

        // Amazon Prime Movies
        appendRow("prime_movies", "Prime Video New Popular Movies",
            RowLoadParams("movie", watchProviders = "9|119", watchRegion = "US", releaseDateGte = "2024-01-01"))

        // Amazon Prime Series
        appendRow("prime_series", "Prime Video New Popular Series",
            RowLoadParams("series", watchProviders = "9|119", watchRegion = "US", releaseDateGte = "2024-01-01"))

        // Disney+ Movies
        appendRow("disney_movies", "Disney+ New Popular Movies",
            RowLoadParams("movie", watchProviders = "337", watchRegion = "US", releaseDateGte = "2024-01-01"))

        // Disney+ Series
        appendRow("disney_series", "Disney+ New Popular Series",
            RowLoadParams("series", watchProviders = "337", watchRegion = "US", releaseDateGte = "2024-01-01"))

        // HBO Max Series
        appendRow("hbo_series", "HBO / Max New Popular Series",
            RowLoadParams("series", watchProviders = "384", watchRegion = "US", releaseDateGte = "2024-01-01"))

        // Hollywood Movies
        appendRow("hollywood_movies", "Hollywood New Popular Movies",
            RowLoadParams("movie", originalLanguage = "en", watchRegion = "US", releaseDateGte = "2024-01-01"))

        // Hollywood Series
        appendRow("hollywood_series", "Hollywood New Popular Series",
            RowLoadParams("series", originalLanguage = "en", watchRegion = "US", releaseDateGte = "2024-01-01"))

        // --- CUSTOM ROWS END ---

        return CatalogLoadResult(rows = rows, errors = errors)
    }

    private suspend fun loadRowContent(params: RowLoadParams, page: Int): Result<List<CatalogItem>> {
        return catalogRepo.getDiscoveryContent(
            type = params.type,
            page = page,
            sortBy = params.sortBy,
            originalLanguage = params.originalLanguage,
            watchProviders = params.watchProviders,
            watchRegion = params.watchRegion,
            releaseDateGte = params.releaseDateGte
        )
    }

    private data class CatalogLoadResult(
        val rows: List<DebridRow>,
        val errors: List<Exception>
    )
}

/**
 * UI state for Debrid section
 */
sealed class DebridUiState {
    object Loading : DebridUiState()
    object NotAuthenticated : DebridUiState()
    object Authenticated : DebridUiState()
    data class Content(
        val rows: List<DebridRow>,
        val refreshState: DebridRefreshState = DebridRefreshState.Idle
    ) : DebridUiState()
    data class Error(val message: String) : DebridUiState()
}

enum class DebridRefreshState {
    Idle,
    Refreshing,
    Failed
}

/**
 * Represents a horizontal content row
 */
data class DebridRow(
    val id: String,
    val title: String,
    val items: List<DebridContentItem>,
    val canLoadMore: Boolean = false
)

/**
 * Params to load more content for a row
 */
data class RowLoadParams(
    val type: String,
    val sortBy: String = "popularity.desc",
    val originalLanguage: String? = null,
    val watchProviders: String? = null,
    val watchRegion: String? = "US",
    val releaseDateGte: String? = null
)

/**
 * Individual content item within a row
 */
data class DebridContentItem(
    val id: String,
    val title: String,
    val posterUrl: String?,
    val backdropUrl: String?,
    val type: String, // "movie" or "series"
    val year: String?,
    val rating: String?,
    val contentType: ContentType? = null,
    val isContinueWatching: Boolean = false,
    val resumePositionMs: Long? = null,
    val durationMs: Long? = null,
    val seasonNumber: Int? = null,
    val episodeNumber: Int? = null,
    val episodeTitle: String? = null,
    val seriesTitle: String? = null,
    val tmdbId: String? = null,
    val imdbId: String? = null,
    val debridInfoHash: String? = null,
    val debridMagnet: String? = null,
    val streamUrl: String? = null,
    val source: String = "xtream",
    val expiresAt: Long? = null,
    val overview: String? = null,
    val genreIds: List<Int>? = null,
    val isSkeleton: Boolean = false
) {
    fun isExpired(): Boolean {
        return expiresAt == null || System.currentTimeMillis() > expiresAt
    }
}

/**
 * Extension to map catalog items to UI models
 */
private fun CatalogItem.toDebridContentItem(): DebridContentItem {
    return DebridContentItem(
        id = this.id,
        title = this.title,
        posterUrl = this.posterUrl,
        backdropUrl = this.backdropUrl,
        type = this.type,
        year = this.year,
        rating = this.rating,
        overview = this.overview,
        genreIds = this.genreIds
    )
}

private fun ContinueWatchingItem.toDebridContentItem(): DebridContentItem {
    val isEpisode = contentType == ContentType.EPISODE
    val isSeries = contentType == ContentType.SERIES || isEpisode
    val detailId = tmdbId ?: contentId
    val displayTitle = seriesTitle?.takeIf { it.isNotBlank() } ?: title
    return DebridContentItem(
        id = detailId,
        title = displayTitle,
        posterUrl = posterUrl,
        backdropUrl = backdropUrl,
        type = if (isSeries) "series" else "movie",
        year = null,
        rating = null,
        contentType = contentType,
        isContinueWatching = true,
        resumePositionMs = currentPosition,
        durationMs = totalDuration,
        seasonNumber = seasonNumber,
        episodeNumber = episodeNumber,
        episodeTitle = episodeTitle,
        seriesTitle = seriesTitle,
        tmdbId = tmdbId,
        imdbId = imdbId,
        debridInfoHash = debridInfoHash,
        debridMagnet = debridMagnet,
        streamUrl = streamUrl,
        source = source,
        expiresAt = expiresAt
    )
}
