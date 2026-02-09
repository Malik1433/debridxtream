package com.tvonnet.debridxtreamiptv.ui.search

import androidx.lifecycle.viewModelScope
import com.tvonnet.debridxtreamiptv.data.model.XtreamSeriesInfo
import com.tvonnet.debridxtreamiptv.data.model.XtreamStream
import com.tvonnet.debridxtreamiptv.data.model.XtreamVodInfo
import com.tvonnet.debridxtreamiptv.data.repository.XtreamRepository
import com.tvonnet.debridxtreamiptv.data.local.dao.SearchHistoryDao
import com.tvonnet.debridxtreamiptv.data.local.entity.SearchHistoryEntity
import com.tvonnet.debridxtreamiptv.ui.base.BaseViewModel
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import kotlinx.coroutines.async
import javax.inject.Inject

/**
 * Week 9: Search Functionality ViewModel
 * Week 10: Added Search History Persistence with Room
 * 
 * Features:
 * - Debounced search (300ms delay after user stops typing)
 * - Global search across Live TV, VOD, and Series
 * - Search history persistence (Room database)
 * - Result categorization
 * 
 * State Flow Architecture:
 * - Reactive search with StateFlow
 * - Automatic debouncing
 * - Clean state updates
 */

data class SearchUiState(
    val query: String = "",
    val liveResults: List<XtreamStream> = emptyList(),
    val vodResults: List<XtreamVodInfo> = emptyList(),
    val seriesResults: List<XtreamSeriesInfo> = emptyList(),
    val epgResults: List<com.tvonnet.debridxtreamiptv.data.local.entity.EpgEntity> = emptyList(),
    val isSearching: Boolean = false,
    val recentSearches: List<String> = emptyList(),
    val error: String? = null
) {
    val totalResults: Int
        get() = liveResults.size + vodResults.size + seriesResults.size + epgResults.size
    
    val hasResults: Boolean
        get() = totalResults > 0
    
    val shouldShowEmptyState: Boolean
        get() = !isSearching && query.length >= 2 && !hasResults
}

sealed class SearchEvent {
    data class QueryChanged(val query: String) : SearchEvent()
    data class SelectLiveStream(val stream: XtreamStream) : SearchEvent()
    data class SelectVodStream(val vod: XtreamVodInfo) : SearchEvent()
    data class SelectSeries(val series: XtreamSeriesInfo) : SearchEvent()
    data class SelectEpgProgram(val program: com.tvonnet.debridxtreamiptv.data.local.entity.EpgEntity) : SearchEvent()
    data class SelectRecentSearch(val query: String) : SearchEvent()
    object ClearHistory : SearchEvent()
    object ClearQuery : SearchEvent()
}

@HiltViewModel
class SearchViewModel @Inject constructor(
    private val repository: XtreamRepository,
    private val searchHistoryDao: SearchHistoryDao
) : BaseViewModel<SearchUiState, SearchEvent>() {
    
    override fun getInitialState() = SearchUiState()
    
    // Search query flow for debouncing
    private val _searchQuery = MutableStateFlow("")
    
    init {
        setupSearch()
        loadRecentSearches()
    }
    
    @OptIn(FlowPreview::class)
    private fun setupSearch() {
        viewModelScope.launch {
            _searchQuery
                .debounce(300) // Wait 300ms after user stops typing
                .distinctUntilChanged() // Only trigger if query actually changed
                .collect { query ->
                    updateState { copy(query = query) }
                    
                    if (query.length >= 2) {
                        performSearch(query)
                    } else {
                        // Clear results if query too short
                        updateState {
                            copy(
                                liveResults = emptyList(),
                                vodResults = emptyList(),
                                seriesResults = emptyList(),
                                epgResults = emptyList(),
                                isSearching = false
                            )
                        }
                    }
                }
        }
    }
    
    override fun onEvent(event: SearchEvent) {
        when (event) {
            is SearchEvent.QueryChanged -> {
                _searchQuery.value = event.query
            }
            is SearchEvent.SelectLiveStream -> {
                addToRecentSearches(uiState.value.query)
                // Navigation handled by Fragment
            }
            is SearchEvent.SelectVodStream -> {
                addToRecentSearches(uiState.value.query)
                // Navigation handled by Fragment
            }
            is SearchEvent.SelectSeries -> {
                addToRecentSearches(uiState.value.query)
                // Navigation handled by Fragment
            }
            is SearchEvent.SelectEpgProgram -> {
                addToRecentSearches(uiState.value.query)
                // Navigation handled by Fragment
            }
            is SearchEvent.SelectRecentSearch -> {
                _searchQuery.value = event.query
            }
            SearchEvent.ClearHistory -> {
                clearRecentSearches()
            }
            SearchEvent.ClearQuery -> {
                _searchQuery.value = ""
            }
        }
    }
    
    private fun performSearch(query: String) {
        viewModelScope.launch(exceptionHandler) {
            updateState { copy(isSearching = true, error = null) }
            
            try {
                // Week 14: Use efficient Database Search (Parallel Execution)
                // This replaces the memory-intensive cache reading
                
                val liveResults = async { repository.searchLive(query) }
                val vodResults = async { repository.searchVod(query) }
                val seriesResults = async { repository.searchSeries(query) }
                val epgResults = async { repository.searchEpg(query) }

                val live = liveResults.await()
                val vod = vodResults.await()
                val series = seriesResults.await()
                val epg = epgResults.await()
                
                // Calculate total results
                val totalResults = live.size + vod.size + series.size + epg.size
                
                // Update state with results
                updateState {
                    copy(
                        liveResults = live,
                        vodResults = vod,
                        seriesResults = series,
                        epgResults = epg,
                        isSearching = false,
                        error = if (totalResults == 0) {
                            "No results found for '$query'. Please ensure content is loaded by browsing categories."
                        } else null
                    )
                }
                
            } catch (e: Exception) {
                updateState {
                    copy(
                        isSearching = false,
                        error = "Search failed: ${e.message}"
                    )
                }
            }
        }
    }
    
    private fun loadRecentSearches() {
        viewModelScope.launch {
            // Week 10: Load from Room database
            searchHistoryDao.getRecentSearches(MAX_RECENT_SEARCHES)
                .collect { searches ->
                    val queryList = searches.map { it.query }
                    updateState { copy(recentSearches = queryList) }
                }
        }
    }
    
    private fun addToRecentSearches(query: String) {
        if (query.isBlank()) return
        
        viewModelScope.launch {
            try {
                // Week 10: Persist to Room database
                val searchHistory = SearchHistoryEntity(query = query)
                searchHistoryDao.insertSearch(searchHistory)
                
                // Clean old searches (keep only last 50)
                searchHistoryDao.cleanOldSearches()
            } catch (e: Exception) {
                updateState { copy(error = e.message ?: "Failed to save search history") }
            }
        }
    }
    
    private fun clearRecentSearches() {
        viewModelScope.launch {
            try {
                // Week 10: Clear from Room database
                searchHistoryDao.deleteAllSearches()
            } catch (e: Exception) {
                updateState { copy(error = e.message ?: "Failed to clear search history") }
            }
        }
    }
    
    override fun handleException(exception: Throwable) {
        updateState {
            copy(
                isSearching = false,
                error = exception.message ?: "Unknown error occurred"
            )
        }
    }
    
    companion object {
        private const val MAX_RECENT_SEARCHES = 10
    }
}

