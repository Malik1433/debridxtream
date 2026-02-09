package com.tvonnet.debridxtreamiptv.ui.favorites

import androidx.lifecycle.viewModelScope
import com.tvonnet.debridxtreamiptv.data.local.entity.FavoriteEntity
import com.tvonnet.debridxtreamiptv.data.repository.XtreamRepository
import com.tvonnet.debridxtreamiptv.ui.base.BaseViewModel
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * FavoritesViewModel - Week 10: Favorites System
 * 
 * Features:
 * - Display all favorites (Live TV, VOD, Series)
 * - Filter favorites by type
 * - Remove favorites
 * - Navigate to player/details
 */
@HiltViewModel
class FavoritesViewModel @Inject constructor(
    private val repository: XtreamRepository
) : BaseViewModel<FavoritesUiState, FavoritesEvent>() {
    
    private val _selectedFilter = MutableStateFlow<FavoriteFilter>(FavoriteFilter.ALL)
    val selectedFilter: StateFlow<FavoriteFilter> = _selectedFilter.asStateFlow()
    
    override fun getInitialState(): FavoritesUiState = FavoritesUiState()
    
    init {
        loadFavorites()
    }
    
    override fun onEvent(event: FavoritesEvent) {
        when (event) {
            is FavoritesEvent.LoadFavorites -> loadFavorites()
            is FavoritesEvent.FilterByType -> filterByType(event.filter)
            is FavoritesEvent.RemoveFavorite -> removeFavorite(event.streamId)
            is FavoritesEvent.PlayStream -> playStream(event.stream)
            is FavoritesEvent.ClearAll -> clearAllFavorites()
        }
    }
    
    private fun loadFavorites() {
        viewModelScope.launch {
            try {
                updateState { copy(isLoading = true, error = null) }
                
                // Observe favorites from Room database
                repository.getAllFavorites()
                    .combine(selectedFilter) { favorites, filter ->
                        when (filter) {
                            FavoriteFilter.ALL -> favorites
                            FavoriteFilter.LIVE -> favorites.filter { it.type == "live" }
                            FavoriteFilter.VOD -> favorites.filter { it.type == "vod" }
                            FavoriteFilter.SERIES -> favorites.filter { it.type == "series" }
                        }
                    }
                    .collect { filteredFavorites ->
                        updateState {
                            copy(
                                favorites = filteredFavorites,
                                isLoading = false,
                                error = null
                            )
                        }
                    }
            } catch (e: Exception) {
                updateState {
                    copy(
                        isLoading = false,
                        error = "Failed to load favorites: ${e.message}"
                    )
                }
            }
        }
    }
    
    private fun filterByType(filter: FavoriteFilter) {
        _selectedFilter.value = filter
    }
    
    private fun removeFavorite(streamId: String) {
        viewModelScope.launch {
            try {
                repository.removeFavorite(streamId)
                // Favorites will auto-update via Flow
            } catch (e: Exception) {
                updateState {
                    copy(error = "Failed to remove favorite: ${e.message}")
                }
            }
        }
    }
    
    private fun playStream(@Suppress("UNUSED_PARAMETER") stream: Any) {
        // Navigate to player - This will be handled by the Fragment
        // Parameter kept for event signature compatibility
    }
    
    private fun clearAllFavorites() {
        viewModelScope.launch {
            try {
                repository.clearAllFavorites()
                // Favorites will auto-update via Flow
            } catch (e: Exception) {
                updateState {
                    copy(error = "Failed to clear favorites: ${e.message}")
                }
            }
        }
    }
    
    /**
     * Week 12: Expose repository for playback functionality
     */
    fun getRepository(): XtreamRepository = repository
}

/**
 * UI State for Favorites screen
 */
data class FavoritesUiState(
    val favorites: List<FavoriteEntity> = emptyList(),
    val isLoading: Boolean = false,
    val error: String? = null
)

/**
 * Events for Favorites screen
 */
sealed class FavoritesEvent {
    object LoadFavorites : FavoritesEvent()
    data class FilterByType(val filter: FavoriteFilter) : FavoritesEvent()
    data class RemoveFavorite(val streamId: String) : FavoritesEvent()
    data class PlayStream(val stream: Any) : FavoritesEvent()
    object ClearAll : FavoritesEvent()
}

/**
 * Filter options for favorites
 */
enum class FavoriteFilter(val displayName: String) {
    ALL("All"),
    LIVE("Live TV"),
    VOD("Movies"),
    SERIES("Series")
}

