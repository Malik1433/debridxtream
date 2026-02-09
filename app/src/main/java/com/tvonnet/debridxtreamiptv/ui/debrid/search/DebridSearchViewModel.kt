package com.tvonnet.debridxtreamiptv.ui.debrid.search

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.tvonnet.debridxtreamiptv.data.Result
import com.tvonnet.debridxtreamiptv.data.debrid.repository.AddonCatalogRepository
import com.tvonnet.debridxtreamiptv.data.debrid.repository.CatalogItem
import com.tvonnet.debridxtreamiptv.ui.debrid.DebridContentItem
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class DebridSearchViewModel @Inject constructor(
    private val repository: AddonCatalogRepository
) : ViewModel() {

    private val _uiState = MutableStateFlow<SearchUiState>(SearchUiState.Idle)
    val uiState: StateFlow<SearchUiState> = _uiState.asStateFlow()

    private var searchJob: Job? = null
    private var lastQuery: String = ""

    fun onSearchQuery(query: String) {
        val trimmedQuery = query.trim()
        if (trimmedQuery == lastQuery) return
        lastQuery = trimmedQuery

        searchJob?.cancel()

        if (trimmedQuery.length < 2) {
            _uiState.value = SearchUiState.Idle
            return
        }

        searchJob = viewModelScope.launch {
            // Debounce
            delay(500)
            
            _uiState.value = SearchUiState.Loading
            
            val result = repository.searchContent(trimmedQuery)
            
            when (result) {
                is Result.Success -> {
                    val items = result.data.map { it.toDebridContentItem() }
                    if (items.isEmpty()) {
                        _uiState.value = SearchUiState.Empty("No results found for \"$trimmedQuery\"")
                    } else {
                        _uiState.value = SearchUiState.Success(items)
                    }
                }
                is Result.Error -> {
                    _uiState.value = SearchUiState.Error(
                        result.exception.message ?: "Available content search failed"
                    )
                }
                is Result.Loading -> { 
                     // repository doesn't return loading usually
                }
            }
        }
    }

    private fun CatalogItem.toDebridContentItem(): DebridContentItem {
        return DebridContentItem(
            id = this.id,
            title = this.title,
            posterUrl = this.posterUrl,
            backdropUrl = this.backdropUrl,
            type = this.type,
            year = this.year,
            rating = this.rating
        )
    }
}

sealed class SearchUiState {
    object Idle : SearchUiState()
    object Loading : SearchUiState()
    data class Success(val items: List<DebridContentItem>) : SearchUiState()
    data class Empty(val message: String) : SearchUiState()
    data class Error(val message: String) : SearchUiState()
}
