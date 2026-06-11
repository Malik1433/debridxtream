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
 * a unified UI state for consumption by [DebridDiscoverActivity].
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

    private var currentType = "movie"
    private var currentGenreId: String? = null
    private var currentCatalogue = AddonCatalogRepository.CATALOGUE_POPULAR
    private var currentYear: Int? = null
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
        if (currentType == type) return
        currentType = type
        currentGenreId = null // reset genre when type changes
        loadGenres(type)
        loadContent(reset = true)
    }

    /**
     * Called when the user selects a genre chip.
     *
     * @param genreId The TMDB genre ID, or null for "All".
     */
    fun setGenre(genreId: Int?) {
        val newId = genreId?.toString()
        if (currentGenreId == newId) return
        currentGenreId = newId
        loadContent(reset = true)
    }

    fun setCatalogue(catalogue: String) {
        if (currentCatalogue == catalogue) return
        currentCatalogue = catalogue
        loadContent(reset = true)
    }

    fun setYear(year: Int?) {
        if (currentYear == year) return
        currentYear = year
        loadContent(reset = true)
    }

    /** Loads the next page of results. No-op if already loading or no more pages. */
    fun loadNextPage() {
        if (!canLoadMore || loadJob?.isActive == true) return
        loadContent(reset = false)
    }

    // ── Private ─────────────────────────────────────────────────────────────────

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
                val result = catalogRepo.getDiscoveryContent(
                    type = currentType,
                    page = currentPage,
                    catalogue = currentCatalogue,
                    year = currentYear,
                    withGenres = currentGenreId
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
