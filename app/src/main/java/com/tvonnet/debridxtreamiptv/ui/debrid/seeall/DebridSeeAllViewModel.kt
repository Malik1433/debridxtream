package com.tvonnet.debridxtreamiptv.ui.debrid.seeall

import com.tvonnet.debridxtreamiptv.ui.debrid.DebridCatalogRows

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.tvonnet.debridxtreamiptv.data.Result
import com.tvonnet.debridxtreamiptv.data.debrid.repository.AddonCatalogRepository
import com.tvonnet.debridxtreamiptv.data.debrid.repository.CatalogItem
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

sealed class DebridSeeAllUiState {
    object Loading : DebridSeeAllUiState()
    data class Content(val items: List<CatalogItem>, val canLoadMore: Boolean) : DebridSeeAllUiState()
    data class Error(val message: String) : DebridSeeAllUiState()
}

@HiltViewModel
class DebridSeeAllViewModel @Inject constructor(
    private val catalogRepo: AddonCatalogRepository
) : ViewModel() {

    private val _uiState = MutableStateFlow<DebridSeeAllUiState>(DebridSeeAllUiState.Loading)
    val uiState: StateFlow<DebridSeeAllUiState> = _uiState.asStateFlow()

    private var currentPage = 1
    private var currentRowId: String = ""
    private val currentItems = mutableListOf<CatalogItem>()
    private var isFetching = false

    fun initRow(rowId: String) {
        if (currentRowId == rowId) return
        currentRowId = rowId
        currentPage = 1
        currentItems.clear()
        _uiState.value = DebridSeeAllUiState.Loading
        fetchData()
    }

    fun loadNextPage() {
        val state = _uiState.value
        if (state is DebridSeeAllUiState.Content && !state.canLoadMore) return
        if (isFetching) return
        currentPage++
        fetchData()
    }

    private fun fetchData() {
        isFetching = true
        viewModelScope.launch {
            try {
                val response = getDiscoveryContent(currentRowId, currentPage)
                if (response is Result.Success) {
                    val resultItems = response.data
                    currentItems.addAll(resultItems)
                    val canLoadMore = resultItems.isNotEmpty()
                    _uiState.value = DebridSeeAllUiState.Content(currentItems.toList(), canLoadMore)
                } else if (response is Result.Error) {
                    val errorMsg = response.exception.message ?: "Unknown error"
                    if (currentItems.isEmpty()) {
                        _uiState.value = DebridSeeAllUiState.Error(errorMsg)
                    } else {
                        _uiState.value = DebridSeeAllUiState.Content(currentItems.toList(), false)
                    }
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e // never mistake teardown for "no more pages"
            } catch (e: Exception) {
                if (currentItems.isEmpty()) {
                    _uiState.value = DebridSeeAllUiState.Error(e.message ?: "Unknown error")
                } else {
                    _uiState.value = DebridSeeAllUiState.Content(currentItems.toList(), false)
                }
            } finally {
                isFetching = false
            }
        }
    }

    /**
     * One See-All row's discovery query — the old when-chain as data. Defaults mirror
     * AddonCatalogRepository.getDiscoveryContent (watchRegion defaults to "US" there too),
     * so a row that omitted a param behaves exactly as before. An unknown rowId returns
     * an empty page. Mirror the home-row params in DebridViewModel (appendRow) — three
     * rows once existed on Home with no "See All" case, and their screen came up blank.
     */
    private data class DiscoveryQuery(
        val type: String,
        val sortBy: String? = null,
        val originalLanguage: String? = null,
        val watchProviders: String? = null,
        val watchRegion: String? = "US",
        val releaseDateGte: String? = null,
    )

    // The two trending rows are preloaded on the home from the trending endpoints, so they page
    // here as plain popularity queries. Every other row is DebridCatalogRows, the one list the
    // home builds from - a row cannot exist on the home and be missing here any more.
    private val discoveryQueries: Map<String, DiscoveryQuery> = mapOf(
        "trending_movies" to DiscoveryQuery(type = "movie", sortBy = "popularity.desc"),
        "trending_series" to DiscoveryQuery(type = "series", sortBy = "popularity.desc"),
    ) + DebridCatalogRows.specs.associate { spec ->
        spec.id to DiscoveryQuery(
            type = spec.type,
            originalLanguage = spec.originalLanguage,
            watchProviders = spec.watchProviders,
            watchRegion = spec.watchRegion,
            releaseDateGte = spec.releaseDateGte,
        )
    }

    private suspend fun getDiscoveryContent(rowId: String, page: Int): Result<List<CatalogItem>> {
        val query = discoveryQueries[rowId] ?: return Result.Success(emptyList())
        return catalogRepo.getDiscoveryContent(
            type = query.type,
            page = page,
            sortBy = query.sortBy,
            originalLanguage = query.originalLanguage,
            watchProviders = query.watchProviders,
            watchRegion = query.watchRegion,
            releaseDateGte = query.releaseDateGte
        )
    }
}
