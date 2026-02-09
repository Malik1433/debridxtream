package com.tvonnet.debridxtreamiptv.ui.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.tvonnet.debridxtreamiptv.data.repository.XtreamRepository
import com.tvonnet.debridxtreamiptv.data.prefs.DebridPreferences
import com.tvonnet.debridxtreamiptv.data.prefs.HomePreferences
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

data class HomeUiState(
    val isLoading: Boolean = true,
    val heroItem: HeroContent? = null,
    val sections: List<HomeSection> = emptyList()
)

data class HeroContent(
    val title: String,
    val description: String,
    val imageUrl: String?,
    val rating: String,
    val type: String, // "MOVIE" or "SERIES"
    val streamId: String
)

sealed class HomeSection {
    data class MovieRow(val title: String, val items: List<Any>) : HomeSection() // Replace Any with actual model
    data class SeriesRow(val title: String, val items: List<Any>) : HomeSection()
    data class ChannelRow(val title: String, val items: List<Any>) : HomeSection()
}

@HiltViewModel
class HomeViewModel @Inject constructor(
    private val repository: XtreamRepository,
    private val debridPrefs: DebridPreferences,
    private val homePrefs: HomePreferences
) : ViewModel() {

    private val _uiState = MutableStateFlow(HomeUiState())
    val uiState: StateFlow<HomeUiState> = _uiState.asStateFlow()

    init {
        observeSyncAndLoad()
    }

    private fun observeSyncAndLoad() {
        viewModelScope.launch {
            repository.syncProgress.collect { progress ->
                // Refresh UI when sync completes or if we have cache
                if (progress.state == com.tvonnet.debridxtreamiptv.data.model.SyncState.SUCCESS || 
                    repository.readCache() != null) {
                    loadHomeData()
                }
            }
        }
        
        // Listen for preference changes from Settings
        viewModelScope.launch {
            homePrefs.getCategoriesChangedFlow().collect {
                loadHomeData()
            }
        }

        // Initial load attempt
        loadHomeData()
    }

    private fun loadHomeData() {
        viewModelScope.launch {
            val cache = repository.readCache()
            if (cache == null) {
                // Trigger sync if needed (usually handled by MainActivity or HomeFragment)
                return@launch
            }

            _uiState.update { it.copy(isLoading = true) }

            // 1. Get Random Hero Item (Priority: Latest Movies -> Popular Series)
            val vods = cache.vod?.streams ?: emptyList()
            val series = cache.series?.streams ?: emptyList()
            val live = cache.live?.streams ?: emptyList()
            
            val selectedMovieCats = homePrefs.getSelectedMovieCategories()

            // Pick a random recent movie for Hero
            val randomHeroVod = vods.sortedByDescending { it.added }.take(50).shuffled().firstOrNull()
            
            val heroContent = randomHeroVod?.let { vod ->
                HeroContent(
                    title = vod.name ?: "Unknown Title",
                    description = vod.plot ?: vod.genre ?: "No description available.",
                    imageUrl = vod.stream_icon, // Using stream_icon as placeholder backdrop if cover missing
                    rating = if (vod.rating_5based != null && vod.rating_5based > 0) String.format("%.1f", vod.rating_5based) else "N/A",
                    type = "MOVIE",
                    streamId = vod.stream_id ?: ""
                )
            }

            // 2. Build Rows
            val newSections = mutableListOf<HomeSection>()

            // Latest Movies (Always show)
            if (vods.isNotEmpty()) {
                val latestMovies = vods.sortedByDescending { it.added }.take(15)
                newSections.add(HomeSection.MovieRow("Recently Added Movies", latestMovies))
            }

            // Custom Movie Categories
            if (selectedMovieCats.isNotEmpty()) {
                val vodCategories = cache.vod?.categories ?: repository.ensureVodCategories()
                
                selectedMovieCats.forEach { catId ->
                    val catName = vodCategories.find { it.category_id == catId }?.category_name ?: "Movies"
                    
                    // Try to get from cache first (including lazy-loaded ones we might already have)
                    val cachedLazy = repository.fetchVodStreamsForCategory(catId)
                    var catItems = if (cachedLazy is com.tvonnet.debridxtreamiptv.data.Result.Success) {
                        cachedLazy.data
                    } else {
                         vods.filter { vod -> 
                            vod.category_id == catId || 
                            (catId.toIntOrNull() != null && vod.category_ids?.contains(catId.toInt()) == true)
                        }
                    }
                    
                    if (catItems.isEmpty()) {
                        try {
                             // Force network fetch if ensuring categories didn't populate cache
                            val result = repository.fetchVodStreamsForCategory(catId)
                            if (result is com.tvonnet.debridxtreamiptv.data.Result.Success) {
                                catItems = result.data
                            }
                        } catch (e: Exception) {
                            android.util.Log.e("HomeVM", "Failed to fetch category $catId", e)
                        }
                    } 
                    
                    val finalItems = catItems.take(15)
                    android.util.Log.d("HomeVM", "Category $catId ($catName): found ${finalItems.size} items")

                    if (finalItems.isNotEmpty()) {
                        newSections.add(HomeSection.MovieRow(catName, finalItems))
                    }
                }
            }

            // Popular Series (Default or Custom)
            val selectedSeriesCats = homePrefs.getSelectedSeriesCategories()
            if (selectedSeriesCats.isNotEmpty()) {
                val seriesCategories = cache.series?.categories ?: repository.refreshSeriesCategories().getOrNull() ?: emptyList()
                
                selectedSeriesCats.forEach { catId ->
                    val catName = seriesCategories.find { it.category_id == catId }?.category_name ?: "Series"
                    
                    // Try to get from cache first
                    val cachedLazy = repository.fetchSeriesForCategory(catId)
                     var catItems = if (cachedLazy is com.tvonnet.debridxtreamiptv.data.Result.Success) {
                        cachedLazy.data
                    } else {
                         series.filter { item -> 
                            item.category_id == catId || 
                            (catId.toIntOrNull() != null && item.category_ids?.contains(catId.toInt()) == true)
                        }
                    }

                    if (catItems.isEmpty()) {
                        try {
                            val result = repository.fetchSeriesForCategory(catId)
                            if (result is com.tvonnet.debridxtreamiptv.data.Result.Success) {
                                catItems = result.data
                            }
                        } catch (e: Exception) {
                            android.util.Log.e("HomeVM", "Failed to fetch series category $catId", e)
                        }
                    }
                    
                    val finalItems = catItems.take(15)

                    if (finalItems.isNotEmpty()) {
                        newSections.add(HomeSection.SeriesRow(catName, finalItems))
                    }
                }
            } else if (series.isNotEmpty()) {
                val randomSeries = series.shuffled().take(15)
                newSections.add(HomeSection.SeriesRow("Popular Series", randomSeries))
            }

            // Live Channels (Default or Custom)
            val selectedLiveCats = homePrefs.getSelectedLiveCategories()
            if (selectedLiveCats.isNotEmpty()) {
                val liveCategories = cache.live?.categories ?: repository.ensureLiveCategories()
                
                selectedLiveCats.forEach { catId ->
                    val catName = liveCategories.find { it.category_id == catId }?.category_name ?: "Live TV"
                    
                    // Try to get from cache first
                    val cachedLazy = repository.fetchLiveStreamsForCategory(catId)
                    var catItems = if (cachedLazy is com.tvonnet.debridxtreamiptv.data.Result.Success) {
                        cachedLazy.data
                    } else {
                        live.filter { it.category_id == catId }
                    }
                    
                    if (catItems.isEmpty()) {
                        try {
                            val result = repository.fetchLiveStreamsForCategory(catId)
                            if (result is com.tvonnet.debridxtreamiptv.data.Result.Success) {
                                catItems = result.data
                            }
                        } catch (e: Exception) {
                            android.util.Log.e("HomeVM", "Failed to fetch live category $catId", e)
                        }
                    }

                    val finalItems = catItems.take(15)
                    if (finalItems.isNotEmpty()) {
                        newSections.add(HomeSection.ChannelRow(catName, finalItems))
                    }
                }
            } else if (live.isNotEmpty()) {
                val channels = live.take(15)
                newSections.add(HomeSection.ChannelRow("Live Channels", channels))
            }

            _uiState.update { 
                it.copy(
                    isLoading = false,
                    heroItem = heroContent,
                    sections = newSections
                ) 
            }
        }
    }
}
