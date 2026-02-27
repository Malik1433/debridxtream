package com.tvonnet.debridxtreamiptv.ui.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.tvonnet.debridxtreamiptv.data.repository.XtreamRepository
import com.tvonnet.debridxtreamiptv.data.prefs.HomePreferences
import com.tvonnet.debridxtreamiptv.data.prefs.DebridPreferences
import com.tvonnet.debridxtreamiptv.data.prefs.CredentialsPreferences
import com.tvonnet.debridxtreamiptv.data.debrid.source.TmdbRemoteDataSource
import com.tvonnet.debridxtreamiptv.data.model.*
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

data class HomeUiState(
    val isLoading: Boolean = true,
    val top10Movies: List<FeaturedItem> = emptyList(),
    val top10Series: List<FeaturedItem> = emptyList(),
    val heroItem: HeroContent? = null,
    val sections: List<HomeSection> = emptyList()
)


sealed class HomeSection {
    data class MovieRow(val title: String, val items: List<Any>) : HomeSection() // Replace Any with actual model
    data class SeriesRow(val title: String, val items: List<Any>) : HomeSection()
    data class ChannelRow(val title: String, val items: List<Any>) : HomeSection()
}

@HiltViewModel
class HomeViewModel @Inject constructor(
    private val repository: XtreamRepository,
    private val tmdbRemoteDataSource: TmdbRemoteDataSource,
    private val debridPrefs: DebridPreferences,
    private val homePrefs: HomePreferences,
    private val credentialsPrefs: CredentialsPreferences
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

            val serverUrl = credentialsPrefs.getServerUrl() ?: ""
            val username = credentialsPrefs.getUsername() ?: ""
            val password = credentialsPrefs.getPassword() ?: ""

            // 1. Fetch TMDB Trending Movies
            val trendingMoviesResult = tmdbRemoteDataSource.getTrendingMovies()
            val top10MoviesList = if (trendingMoviesResult.isSuccess) {
                trendingMoviesResult.getOrNull()?.results?.take(10)?.map { it.toFeaturedItem() } ?: emptyList()
            } else {
                // Fallback to IPTV VOD sorted by added date
                val vods = cache.vod?.streams ?: emptyList()
                vods.sortedByDescending { it.added }.take(10).map { 
                    it.toFeaturedItem(serverUrl, username, password)
                }
            }
            
            // 2. Fetch TMDB Trending Series
            val trendingSeriesResult = tmdbRemoteDataSource.getTrendingTvShows()
            val top10SeriesList = if (trendingSeriesResult.isSuccess) {
                trendingSeriesResult.getOrNull()?.results?.take(10)?.map { it.toFeaturedItem() } ?: emptyList()
            } else {
                // Fallback to IPTV Series
                val series = cache.series?.streams ?: emptyList()
                series.take(10).map { it.toFeaturedItem(serverUrl) }
            }
            
            val heroFeatured = top10MoviesList.firstOrNull() ?: top10SeriesList.firstOrNull()
            val heroContent = heroFeatured?.let { f ->
                HeroContent(
                    title = f.title,
                    description = f.description ?: "Experience high-quality streaming on DebridXtream.",
                    imageUrl = f.backdropUrl ?: f.posterUrl,
                    rating = f.rating ?: "N/A",
                    type = if (f.contentType == ContentType.MOVIE) "MOVIE" else "SERIES",
                    streamId = f.contentId
                )
            }

            // 3. Build Functional Rows (Recent Live & Continue Watching)
            val newSections = mutableListOf<HomeSection>()
            // Legacy rows removed as requested to focus on Cinematic Top 10s

            _uiState.update { 
                it.copy(
                    isLoading = false,
                    top10Movies = top10MoviesList,
                    top10Series = top10SeriesList,
                    heroItem = heroContent,
                    sections = newSections
                ) 
            }
        }
    }
}
