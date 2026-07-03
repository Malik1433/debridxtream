package com.tvonnet.debridxtreamiptv.ui.debrid.seeall

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

    private suspend fun getDiscoveryContent(rowId: String, page: Int): Result<List<CatalogItem>> {
        return when (rowId) {
            "trending_movies" -> catalogRepo.getDiscoveryContent(type = "movie", sortBy = "popularity.desc", page = page)
            "trending_series" -> catalogRepo.getDiscoveryContent(type = "series", sortBy = "popularity.desc", page = page)
            "bollywood_movies" -> catalogRepo.getDiscoveryContent(type = "movie", originalLanguage = "hi", watchRegion = "IN", releaseDateGte = "2024-01-01", page = page)
            "bollywood_series" -> catalogRepo.getDiscoveryContent(type = "series", originalLanguage = "hi", watchRegion = "IN", releaseDateGte = "2024-01-01", page = page)
            "punjabi_movies" -> catalogRepo.getDiscoveryContent(type = "movie", originalLanguage = "pa", watchRegion = "IN", releaseDateGte = "2024-01-01", page = page)
            "tamil_movies" -> catalogRepo.getDiscoveryContent(type = "movie", originalLanguage = "ta", watchRegion = "IN", releaseDateGte = "2024-01-01", page = page)
            "tamil_series" -> catalogRepo.getDiscoveryContent(type = "series", originalLanguage = "ta", watchRegion = "IN", releaseDateGte = "2024-01-01", page = page)
            "netflix_movies" -> catalogRepo.getDiscoveryContent(type = "movie", watchProviders = "8", watchRegion = "US", releaseDateGte = "2024-01-01", page = page)
            "netflix_series" -> catalogRepo.getDiscoveryContent(type = "series", watchProviders = "8", watchRegion = "US", releaseDateGte = "2024-01-01", page = page)
            "prime_movies" -> catalogRepo.getDiscoveryContent(type = "movie", watchProviders = "9|119", watchRegion = "US", releaseDateGte = "2024-01-01", page = page)
            "prime_series" -> catalogRepo.getDiscoveryContent(type = "series", watchProviders = "9|119", watchRegion = "US", releaseDateGte = "2024-01-01", page = page)
            "disney_movies" -> catalogRepo.getDiscoveryContent(type = "movie", watchProviders = "337", watchRegion = "US", releaseDateGte = "2024-01-01", page = page)
            "disney_series" -> catalogRepo.getDiscoveryContent(type = "series", watchProviders = "337", watchRegion = "US", releaseDateGte = "2024-01-01", page = page)
            "apple_movies" -> catalogRepo.getDiscoveryContent(type = "movie", watchProviders = "2", watchRegion = "US", releaseDateGte = "2024-01-01", page = page)
            "apple_series" -> catalogRepo.getDiscoveryContent(type = "series", watchProviders = "2", watchRegion = "US", releaseDateGte = "2024-01-01", page = page)
            else -> Result.Success(emptyList())
        }
    }
}
