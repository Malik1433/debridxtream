package com.tvonnet.debridxtreamiptv.ui.vod

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.paging.Pager
import androidx.paging.PagingConfig
import androidx.paging.PagingData
import androidx.paging.cachedIn
import com.tvonnet.debridxtreamiptv.data.Result
import com.tvonnet.debridxtreamiptv.data.onFailure
import com.tvonnet.debridxtreamiptv.data.onSuccess
import com.tvonnet.debridxtreamiptv.data.local.entity.FavoriteEntity
import com.tvonnet.debridxtreamiptv.data.model.XtreamCategory
import com.tvonnet.debridxtreamiptv.data.model.XtreamVodInfo
import com.tvonnet.debridxtreamiptv.data.repository.XtreamRepository
import com.tvonnet.debridxtreamiptv.data.local.entity.VodEntity
import com.tvonnet.debridxtreamiptv.data.local.entity.toXtreamVodInfo
import androidx.paging.map
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import javax.inject.Inject
import com.tvonnet.debridxtreamiptv.util.FAVORITES_CATEGORY_ID

data class VodUiState(
    val categories: List<XtreamCategory> = emptyList(),
    val selectedCategoryId: String? = null,
    val movies: List<XtreamVodInfo> = emptyList(),
    val isLoadingCategories: Boolean = false,
    val isLoadingMovies: Boolean = false,
    val error: String? = null,
    val searchQuery: String = "",
    val isSearching: Boolean = false
)

sealed class VodEvent {
    object LoadCategories : VodEvent()
    data class SelectCategory(val categoryId: String) : VodEvent()
    data class PlayMovie(val movie: XtreamVodInfo) : VodEvent()
    data class SearchMovies(val query: String) : VodEvent()
    object ClearSearch : VodEvent()
    object Retry : VodEvent()
}

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
@HiltViewModel
class VodViewModel @Inject constructor(
    private val repository: XtreamRepository,
    private val vodDao: com.tvonnet.debridxtreamiptv.data.local.dao.VodDao,
    private val favoritesCache: com.tvonnet.debridxtreamiptv.data.cache.FavoritesCache,
    val savedStateHandle: SavedStateHandle
) : ViewModel() {
    
    private val _uiState = MutableStateFlow(VodUiState())
    val uiState: StateFlow<VodUiState> = _uiState.asStateFlow()
    
    /**
     * Paged movies flow for current selected category
     * Using Paging3 for efficient memory usage and smooth scrolling
     */
    private val _selectedCategoryFlow = MutableStateFlow<String?>(null)
    private val _searchQueryFlow = MutableStateFlow("")
    
    val pagedMovies: Flow<PagingData<XtreamVodInfo>> = combine(
        _selectedCategoryFlow.filterNotNull(),
        _searchQueryFlow
    ) { categoryId, searchQuery ->
        Pair(categoryId, searchQuery)
    }.flatMapLatest { (categoryId, searchQuery) ->
        if (categoryId == FAVORITES_CATEGORY_ID) {
            repository.getFavoritesByType("vod")
                .map { favorites ->
                    val items = buildFavoriteMovies(favorites, searchQuery)
                    PagingData.from(items)
                }
        } else {
            Pager(
                config = PagingConfig(
                    pageSize = 20,
                    enablePlaceholders = true, // Placeholders work well with Room
                    prefetchDistance = 5
                ),
                pagingSourceFactory = { vodDao.getMoviesByCategory(categoryId, searchQuery) }
            ).flow
                .map { pagingData ->
                    pagingData.map { entity -> entity.toXtreamVodInfo() }
                }
        }
    }.cachedIn(viewModelScope)
    
    init {
        // Auto-load categories on initialization
        onEvent(VodEvent.LoadCategories)
    }
    
    fun onEvent(event: VodEvent) {
        when (event) {
            is VodEvent.LoadCategories -> loadCategories()
            is VodEvent.SelectCategory -> loadMoviesForCategory(event.categoryId)
            is VodEvent.PlayMovie -> handlePlayMovie(event.movie)
            is VodEvent.SearchMovies -> searchMovies(event.query)
            is VodEvent.ClearSearch -> clearSearch()
            is VodEvent.Retry -> retry()
        }
    }
    
    private fun loadCategories() {
        viewModelScope.launch {
            try {
                _uiState.update { it.copy(isLoadingCategories = true, error = null) }
                
                val categories = repository.ensureVodCategories()
                
                if (categories.isEmpty()) {
                    _uiState.update {
                        it.copy(
                            isLoadingCategories = false,
                            error = "No movie categories found. Please login and sync."
                        )
                    }
                } else {
                    _uiState.update {
                        it.copy(
                            categories = categories,
                            isLoadingCategories = false,
                            selectedCategoryId = categories.firstOrNull()?.category_id
                        )
                    }
                    
                    // Auto-load first category
                    categories.firstOrNull()?.category_id?.let { categoryId ->
                        loadMoviesForCategory(categoryId)
                    }
                }
            } catch (e: Exception) {
                _uiState.update {
                    it.copy(
                        isLoadingCategories = false,
                        error = e.message ?: "Unknown error occurred"
                    )
                }
            }
        }
    }
    
    private fun loadMoviesForCategory(categoryId: String) {
        viewModelScope.launch {
            try {
                _uiState.update {
                    it.copy(
                        selectedCategoryId = categoryId,
                        isLoadingMovies = true, // Show loading while we sync/check DB
                        error = null
                    )
                }
                
                // Trigger paging for this category (DB observation)
                _selectedCategoryFlow.value = categoryId
                
                if (categoryId == FAVORITES_CATEGORY_ID) {
                    _uiState.update { it.copy(isLoadingMovies = false) }
                } else {
                    // Trigger network sync (Hybrid Sync)
                    // The PagingSource will automatically emit updates when DB changes
                    val result = repository.fetchVodStreamsForCategory(categoryId)
                    
                    result.onSuccess { 
                        _uiState.update { it.copy(isLoadingMovies = false) }
                    }
                    
                    result.onFailure { error ->
                        // If DB has data, we don't show error, just stop loading
                        // But we can't easily check DB count here without another query
                        // For now, just stop loading. Paging adapter load state will handle empty/error states visually if needed
                        _uiState.update { 
                            it.copy(
                                isLoadingMovies = false,
                                // Only show error if we really want to interrupt user
                                // error = error.message 
                            ) 
                        }
                    }
                }
            } catch (e: Exception) {
                _uiState.update {
                    it.copy(
                        isLoadingMovies = false,
                        error = e.message ?: "Unknown error occurred"
                    )
                }
            }
        }
    }

    private fun searchMovies(query: String) {
        val trimmed = query.trim()
        _uiState.update { it.copy(searchQuery = trimmed, isSearching = trimmed.isNotEmpty()) }
        _searchQueryFlow.value = trimmed
    }

    private fun clearSearch() {
        _uiState.update { it.copy(searchQuery = "", isSearching = false) }
        _searchQueryFlow.value = ""
    }
    
    private fun handlePlayMovie(movie: XtreamVodInfo) {
        // Emit navigation event (handle in Fragment)
        savedStateHandle["play_movie"] = movie
    }
    
    private fun retry() {
        val currentState = _uiState.value
        if (currentState.categories.isEmpty()) {
            onEvent(VodEvent.LoadCategories)
        } else {
            currentState.selectedCategoryId?.let { categoryId ->
                onEvent(VodEvent.SelectCategory(categoryId))
            }
        }
    }
    
    /**
     * Week 13: Check if movie is favorited (O(1) cache lookup)
     */
    fun isFavorite(streamId: String): Boolean {
        return favoritesCache.isFavorite(streamId)
    }

    private fun buildFavoriteMovies(
        favorites: List<FavoriteEntity>,
        searchQuery: String
    ): List<XtreamVodInfo> {
        val items = favorites.mapNotNull { favorite ->
            repository.getVodById(favorite.streamId) ?: favoriteFallbackMovie(favorite)
        }
        if (searchQuery.isBlank()) return items
        val query = searchQuery.trim()
        return items.filter { movie ->
            movie.name?.contains(query, ignoreCase = true) == true
        }
    }

    private fun favoriteFallbackMovie(favorite: FavoriteEntity): XtreamVodInfo {
        return XtreamVodInfo(
            num = null,
            name = favorite.name,
            stream_type = "vod",
            stream_id = favorite.streamId,
            stream_icon = favorite.iconUrl,
            rating = null,
            rating_5based = null,
            added = null,
            category_id = FAVORITES_CATEGORY_ID,
            category_ids = null,
            container_extension = null,
            custom_sid = null,
            direct_source = null,
            duration = null,
            cover = favorite.iconUrl,
            plot = null,
            cast = null,
            director = null,
            genre = null,
            releaseDate = null,
            youtube_trailer = null,
            rating_imdb = null
        )
    }
    
    /**
     * Week 13: Get repository for favorites operations
     */
    fun getRepository(): XtreamRepository = repository
}
