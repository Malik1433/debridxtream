package com.tvonnet.debridxtreamiptv.ui.series

import android.content.Context
import android.util.Log
import androidx.annotation.VisibleForTesting
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.paging.Pager
import androidx.paging.PagingConfig
import androidx.paging.PagingData
import androidx.paging.cachedIn
import androidx.paging.map
import com.tvonnet.debridxtreamiptv.data.Result
import com.tvonnet.debridxtreamiptv.data.onFailure
import com.tvonnet.debridxtreamiptv.data.onSuccess
import com.tvonnet.debridxtreamiptv.data.local.entity.FavoriteEntity
import com.tvonnet.debridxtreamiptv.data.model.SeriesCategoryStatus
import com.tvonnet.debridxtreamiptv.data.model.XtreamCategory
import com.tvonnet.debridxtreamiptv.data.model.XtreamSeriesInfo
import com.tvonnet.debridxtreamiptv.data.local.entity.SeriesEntity
import com.tvonnet.debridxtreamiptv.data.local.entity.toXtreamSeriesInfo
import com.tvonnet.debridxtreamiptv.data.prefs.CredentialsPreferences
import com.tvonnet.debridxtreamiptv.data.repository.XtreamRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import com.tvonnet.debridxtreamiptv.util.FAVORITES_CATEGORY_ID

data class SeriesUiState(
    val categories: List<XtreamCategory> = emptyList(),
    val selectedCategoryId: String? = null,
    val series: List<XtreamSeriesInfo> = emptyList(),
    val isLoadingCategories: Boolean = false,
    val isLoadingSeries: Boolean = false,
    val isSwitchingCategory: Boolean = false, // New: track transition period
    val error: String? = null,
    val categoryStatus: SeriesCategoryStatus? = null,
    val searchQuery: String = "",
    val isSearching: Boolean = false
)

sealed class SeriesEvent {
    object LoadCategories : SeriesEvent()
    data class SelectCategory(val categoryId: String) : SeriesEvent()
    data class PlaySeries(val series: XtreamSeriesInfo) : SeriesEvent()
    data class SearchSeries(val query: String) : SeriesEvent()
    object ClearSearch : SeriesEvent()
    object Retry : SeriesEvent()
}

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
@HiltViewModel
class SeriesViewModel @Inject constructor(
    private val repository: XtreamRepository,
    private val seriesDao: com.tvonnet.debridxtreamiptv.data.local.dao.SeriesDao,
    private val favoritesCache: com.tvonnet.debridxtreamiptv.data.cache.FavoritesCache,
    @ApplicationContext private val context: Context,
    val savedStateHandle: SavedStateHandle
) : ViewModel() {
    
    companion object {
        private val CATEGORY_CACHE_TTL_MS = TimeUnit.MINUTES.toMillis(15)

        @VisibleForTesting
        var ioDispatcher: CoroutineDispatcher = Dispatchers.IO
    }
    
    private val _uiState = MutableStateFlow(SeriesUiState())
    val uiState: StateFlow<SeriesUiState> = _uiState.asStateFlow()
    
    /**
     * Paged series flow for current selected category
     * Using Paging3 for efficient memory usage and smooth scrolling
     */
    private val _selectedCategoryFlow = MutableStateFlow<String?>(null)
    private val _searchQueryFlow = MutableStateFlow("")
    
    val pagedSeries: Flow<PagingData<XtreamSeriesInfo>> = combine(
        _selectedCategoryFlow.filterNotNull(),
        _searchQueryFlow
    ) { categoryId, searchQuery ->
        Pair(categoryId, searchQuery)
    }.flatMapLatest { (categoryId, searchQuery) ->
        if (categoryId == FAVORITES_CATEGORY_ID) {
            repository.getFavoritesByType("series")
                .map { favorites ->
                    val items = buildFavoriteSeries(favorites, searchQuery)
                    PagingData.from(items)
                }
        } else {
            val pagerFlow: Flow<PagingData<SeriesEntity>> = Pager(
                config = PagingConfig(
                    pageSize = 60,
                    enablePlaceholders = false,
                    prefetchDistance = 20
                ),
                pagingSourceFactory = { seriesDao.getSeriesByCategory(categoryId, searchQuery) }
            ).flow

            pagerFlow.map { pagingData ->
                // Clear error when data successfully emits from the PagingSource
                _uiState.update { it.copy(error = null) }
                pagingData.map { entity -> entity.toXtreamSeriesInfo() }
            }
        }
    }.cachedIn(viewModelScope)
    
    init {
        // Initialize repository with credentials before loading data
        initializeRepository()
        // Auto-load categories on initialization
        onEvent(SeriesEvent.LoadCategories)
    }
    
    /**
     * Initialize repository with saved credentials
     * Required before loading data
     */
    private fun initializeRepository() {
        val credentialsPrefs = CredentialsPreferences(context)
        val serverUrl = credentialsPrefs.getServerUrl()
        val username = credentialsPrefs.getUsername()
        val password = credentialsPrefs.getPassword()
        
        if (serverUrl != null && username != null && password != null) {
            repository.initialize(serverUrl, username, password)
        }
    }
    
    fun onEvent(event: SeriesEvent) {
        when (event) {
            is SeriesEvent.LoadCategories -> loadCategories()
            is SeriesEvent.SelectCategory -> {
                // Clear error immediately on new selection to prevent stale messages
                _uiState.update { it.copy(error = null) }
                loadSeriesForCategory(event.categoryId)
            }
            is SeriesEvent.PlaySeries -> handlePlaySeries(event.series)
            is SeriesEvent.SearchSeries -> searchSeries(event.query)
            is SeriesEvent.ClearSearch -> clearSearch()
            is SeriesEvent.Retry -> retry()
        }
    }
    
    private fun loadCategories() {
        viewModelScope.launch {
            _uiState.update { it.copy(isLoadingCategories = true, error = null) }
            
            val categories = withContext(ioDispatcher) {
                repository.ensureSeriesCategories()
            }
            
            if (categories.isEmpty()) {
                handleEmptyCategories("No series categories found.")
            } else {
                updateCategories(categories, finalizeLoading = true)
            }
        }
    }
    
    private fun loadSeriesForCategory(categoryId: String) {
        viewModelScope.launch {
            try {
                _uiState.update {
                    it.copy(
                        selectedCategoryId = categoryId,
                        isLoadingSeries = true,
                        isSwitchingCategory = true, // Track transition start
                        error = null
                    )
                }
                
                // Trigger paging for this category
                _selectedCategoryFlow.value = categoryId
                
                if (categoryId == FAVORITES_CATEGORY_ID) {
                    _uiState.update { 
                        it.copy(
                            isLoadingSeries = false,
                            isSwitchingCategory = false 
                        ) 
                    }
                } else {
                    // Fetch series from repository (lazy loading / sync)
                    val result = withContext(ioDispatcher) {
                        repository.fetchSeriesForCategory(categoryId)
                    }
                    
                    result.onSuccess { 
                        // Week 14: Clear transition flag after sync + propagation delay
                        kotlinx.coroutines.delay(1000) // Increased delay for stability
                        _uiState.update { 
                            it.copy(
                                isLoadingSeries = false,
                                isSwitchingCategory = false 
                            ) 
                        }
                    }
                    
                    result.onFailure { error ->
                        _uiState.update {
                            it.copy(
                                isLoadingSeries = false,
                                isSwitchingCategory = false,
                                error = error.message ?: "Failed to load series"
                            )
                        }
                    }

                    publishCategoryStatus(categoryId)
                }
            } catch (e: Exception) {
                _uiState.update {
                    it.copy(
                        isLoadingSeries = false,
                        error = e.message ?: "Unknown error occurred"
                    )
                }
                publishCategoryStatus(categoryId)
            }
        }
    }
    
    private fun updateCategories(categories: List<XtreamCategory>, finalizeLoading: Boolean) {
        val previousState = _uiState.value
        val previousSelected = previousState.selectedCategoryId
        val newSelected = when {
            previousSelected != null && categories.any { it.category_id == previousSelected } -> previousSelected
            else -> categories.firstOrNull()?.category_id
        }
        val shouldLoadSeries = newSelected != null && (
            previousSelected == null ||
            previousSelected != newSelected ||
            finalizeLoading
        )
        
        _uiState.update {
            it.copy(
                categories = categories,
                selectedCategoryId = newSelected,
                isLoadingCategories = !finalizeLoading,
                error = null
            )
        }
        
        if (shouldLoadSeries && newSelected != null) {
            loadSeriesForCategory(newSelected)
        }
        
        if (categories.isEmpty()) {
            _uiState.update { it.copy(series = emptyList()) }
        }
    }
    
    private fun handleEmptyCategories(message: String) {
        _uiState.update {
            it.copy(
                categories = emptyList(),
                series = emptyList(),
                selectedCategoryId = null,
                isLoadingCategories = false,
                error = message
            )
        }
    }
    
    private fun handlePlaySeries(series: XtreamSeriesInfo) {
        // Emit navigation event (handle in Fragment)
        savedStateHandle["play_series"] = series
    }

    private fun searchSeries(query: String) {
        val trimmed = query.trim()
        _uiState.update { it.copy(searchQuery = trimmed, isSearching = trimmed.isNotEmpty()) }
        _searchQueryFlow.value = trimmed
    }

    private fun clearSearch() {
        _uiState.update { it.copy(searchQuery = "", isSearching = false) }
        _searchQueryFlow.value = ""
    }
    
    private fun retry() {
        val currentState = _uiState.value
        if (currentState.categories.isEmpty()) {
            onEvent(SeriesEvent.LoadCategories)
        } else {
            currentState.selectedCategoryId?.let { categoryId ->
                onEvent(SeriesEvent.SelectCategory(categoryId))
            }
        }
    }
    
    /**
     * Week 13: Check if series is favorited (O(1) cache lookup)
     */
    fun isFavorite(streamId: String): Boolean {
        return favoritesCache.isFavorite(streamId)
    }
    
    /**
     * Week 13: Get repository for favorites operations
     */
    fun getRepository(): XtreamRepository = repository

    private fun publishCategoryStatus(categoryId: String) {
        val status = repository.getSeriesCategoryStatus(categoryId)
        _uiState.update { current ->
            current.copy(categoryStatus = status?.takeIf { it.isActionable })
        }
    }

    private suspend fun buildFavoriteSeries(
        favorites: List<FavoriteEntity>,
        searchQuery: String
    ): List<XtreamSeriesInfo> {
        val items = favorites.mapNotNull { favorite ->
            repository.getSeriesById(favorite.streamId) ?: favoriteFallbackSeries(favorite)
        }
        if (searchQuery.isBlank()) return items
        val query = searchQuery.trim()
        return items.filter { series ->
            series.name?.contains(query, ignoreCase = true) == true
        }
    }

    private fun favoriteFallbackSeries(favorite: FavoriteEntity): XtreamSeriesInfo {
        return XtreamSeriesInfo(
            num = null,
            name = favorite.name,
            series_id = favorite.streamId,
            cover = favorite.iconUrl,
            plot = null,
            cast = null,
            director = null,
            genre = null,
            releaseDate = null,
            rating = null,
            rating_5based = null,
            category_id = FAVORITES_CATEGORY_ID,
            category_ids = null,
            backdrop_path = null,
            youtube_trailer = null,
            episodes = null
        )
    }
}
