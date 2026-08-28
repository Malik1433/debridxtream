package com.tvonnet.debridxtreamiptv.ui.debrid.discover

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.tvonnet.debridxtreamiptv.data.debrid.model.TmdbGenre
import com.tvonnet.debridxtreamiptv.data.debrid.repository.AddonCatalogRepository
import com.tvonnet.debridxtreamiptv.data.debrid.repository.CatalogItem
import com.tvonnet.debridxtreamiptv.data.onSuccess
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.util.Calendar
import javax.inject.Inject

/**
 * ViewModel for the Debrid Discover screen.
 *
 * Manages 4-way filter state (Type, Catalogue, Genre, Year) and exposes
 * a unified UI state for the Stremio home's Discover tab (StremioDiscoverSection).
 */
@HiltViewModel
class DebridDiscoverViewModel @Inject constructor(
    private val catalogRepo: AddonCatalogRepository
) : ViewModel() {

    // ── UI State ────────────────────────────────────────────────────────────────

    private val _uiState = MutableStateFlow<DiscoverUiState>(DiscoverUiState.Loading)
    val uiState: StateFlow<DiscoverUiState> = _uiState.asStateFlow()

    private val _genres = MutableStateFlow<List<TmdbGenre>>(emptyList())
    val genres: StateFlow<List<TmdbGenre>> = _genres.asStateFlow()

    // ── Filter State ────────────────────────────────────────────────────────────

    private val _filterState = MutableStateFlow(DiscoverFilterState())
    val filterState: StateFlow<DiscoverFilterState> = _filterState.asStateFlow()

    private var currentLanguage: String? = null
    private var currentRegion: String? = null
    private var currentReleaseDateGte: String? = null

    private var currentPage = 1
    private var items = mutableListOf<CatalogItem>()
    private var canLoadMore = true

    private var loadJob: Job? = null

    // ── Region options (Hollywood, Bollywood, etc.) ─────────────────────────────

    val regions = listOf(
        DiscoverRegion("All", null),
        DiscoverRegion("Hollywood", "en"),
        DiscoverRegion("Bollywood", "hi"),
        DiscoverRegion("Punjabi", "pa"),
        DiscoverRegion("Tamil", "ta"),
        DiscoverRegion("Telugu", "te"),
        DiscoverRegion("Korean", "ko"),
        DiscoverRegion("Spanish", "es"),
        DiscoverRegion("French", "fr"),
        DiscoverRegion("Turkish", "tr"),
        DiscoverRegion("Arabic", "ar"),
        DiscoverRegion("Japanese", "ja")
    )

    // ── Sort options ────────────────────────────────────────────────────────────

    val catalogueOptions = listOf(
        DiscoverCatalogue("Popular", AddonCatalogRepository.CATALOGUE_POPULAR),
        DiscoverCatalogue("Top Rated", AddonCatalogRepository.CATALOGUE_TOP_RATED),
        DiscoverCatalogue("Newest", AddonCatalogRepository.CATALOGUE_NEWEST)
    )

    val yearOptions: List<Int?> = buildList {
        add(null)
        val currentYear = Calendar.getInstance().get(Calendar.YEAR)
        repeat(YEAR_RANGE_COUNT) { offset ->
            add(currentYear - offset)
        }
    }

    init {
        loadGenres("movie")
        loadContent(reset = true)
    }

    // ── Public API ──────────────────────────────────────────────────────────────

    /**
     * Called when the user selects Movies or Series.
     * Reloads genres and resets content.
     *
     * @param type "movie" or "series"
     */
    fun setType(type: String) {
        val currentState = _filterState.value
        if (currentState.type == type) return
        _filterState.value = currentState.copy(type = type, genreId = null)
        loadGenres(type)
        loadContent(reset = true)
    }

    /**
     * Called when the user selects a genre chip.
     *
     * @param genreId The TMDB genre ID, or null for "All".
     */
    fun setGenre(genreId: Int?) {
        val currentState = _filterState.value
        if (currentState.genreId == genreId) return
        _filterState.value = currentState.copy(genreId = genreId)
        loadContent(reset = true)
    }

    fun setCatalogue(catalogue: String) {
        val currentState = _filterState.value
        if (currentState.catalogue == catalogue) return
        _filterState.value = currentState.copy(catalogue = catalogue)
        loadContent(reset = true)
    }

    fun setYear(year: Int?) {
        val currentState = _filterState.value
        if (currentState.year == year) return
        _filterState.value = currentState.copy(year = year)
        loadContent(reset = true)
    }

    /** Force a fresh reload with the current filters (e.g. after [setCustomFilters]). */
    fun reload() = loadContent(reset = true)

    /** Loads the next page of results. No-op if already loading or no more pages. */
    fun loadNextPage() {
        if (!canLoadMore || loadJob?.isActive == true) return
        loadContent(reset = false)
    }

    // ── Private ─────────────────────────────────────────────────────────────────

    fun setCustomFilters(language: String?, region: String?, releaseDateGte: String?) {
        currentLanguage = language
        currentRegion = region
        currentReleaseDateGte = releaseDateGte
    }

    private fun loadGenres(type: String) {
        viewModelScope.launch {
            val result = if (type == "movie") catalogRepo.getMovieGenres() else catalogRepo.getTvGenres()
            result.onSuccess { genreList ->
                _genres.value = genreList
            }
        }
    }

    private fun loadContent(reset: Boolean) {
        loadJob?.cancel()
        if (reset) {
            currentPage = 1
            items.clear()
            canLoadMore = true
            _uiState.value = DiscoverUiState.Loading
        }

        loadJob = viewModelScope.launch {
            try {
                val state = _filterState.value
                val result = catalogRepo.getDiscoveryContent(
                    originalLanguage = currentLanguage,
                    watchRegion = currentRegion,
                    releaseDateGte = currentReleaseDateGte,
                    type = state.type,
                    page = currentPage,
                    catalogue = state.catalogue,
                    year = state.year,
                    withGenres = state.genreId?.toString()
                )
                result.onSuccess { newItems ->
                    if (newItems.isEmpty()) {
                        canLoadMore = false
                    } else {
                        items.addAll(newItems)
                        currentPage++
                    }
                    _uiState.value = DiscoverUiState.Content(
                        items = items.toList(),
                        canLoadMore = canLoadMore
                    )
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                // A filter change cancels the in-flight page via loadJob.cancel(). Catching
                // that here used to run AFTER the new load's reset and stamp
                // canLoadMore=false onto it — freezing the grid at page 1 forever (the
                // "Discover shows barely any content" bug). Cancellation must propagate.
                throw e
            } catch (e: Exception) {
                if (items.isEmpty()) {
                    _uiState.value = DiscoverUiState.Error(e.message ?: "Failed to load content")
                }
                // If we have existing items, keep showing them; just stop pagination
                canLoadMore = false
            }
        }
    }
}

// ── UI State models ──────────────────────────────────────────────────────────────

data class DiscoverFilterState(
    val type: String = "movie",
    val catalogue: String = AddonCatalogRepository.CATALOGUE_POPULAR,
    val genreId: Int? = null,
    val year: Int? = null
)

sealed class DiscoverUiState {
    object Loading : DiscoverUiState()
    data class Content(
        val items: List<CatalogItem>,
        val canLoadMore: Boolean
    ) : DiscoverUiState()
    data class Error(val message: String) : DiscoverUiState()
}

data class DiscoverRegion(val label: String, val code: String?)
data class DiscoverCatalogue(val label: String, val value: String)

private const val YEAR_RANGE_COUNT = 40
