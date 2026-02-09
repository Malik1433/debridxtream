package com.tvonnet.debridxtreamiptv.features.seriesv2.ui

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.paging.PagingData
import androidx.paging.cachedIn
import com.tvonnet.debridxtreamiptv.core.network.NetworkResult
import com.tvonnet.debridxtreamiptv.data.Result
import com.tvonnet.debridxtreamiptv.features.seriesv2.data.model.EpisodeEntityV2
import com.tvonnet.debridxtreamiptv.features.seriesv2.data.repository.XtreamSeriesRepositoryV2
import com.tvonnet.debridxtreamiptv.features.seriesv2.ui.model.SeriesDetailUiState
import com.tvonnet.debridxtreamiptv.features.seriesv2.ui.model.SeriesNavigationEvent
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class SeriesDetailViewModelV2 @Inject constructor(
    private val repository: XtreamSeriesRepositoryV2,
    savedStateHandle: SavedStateHandle
) : ViewModel() {

    private val seriesId: String = checkNotNull(savedStateHandle["series_id"]) { "series_id is required" }
    // These args might be null if coming from a deep link, but usually required for Context
    private val username: String? = savedStateHandle["username"]
    private val password: String? = savedStateHandle["password"]

    // Navigation Events
    private val _navigationEvents = Channel<SeriesNavigationEvent>()
    val navigationEvents = _navigationEvents.receiveAsFlow()

    // Season State
    private val _selectedSeason = MutableStateFlow<Int?>(null)
    val selectedSeason: StateFlow<Int?> = _selectedSeason

    val seasons: StateFlow<List<Int>> = repository.getSeasons(seriesId)
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = emptyList()
        )

    // Episodes Stream (Paging Data) - Filtered by Season Logic
    // If _selectedSeason is null, we might show "All" or just "Season 1" default.
    // For now, let's assume if null, show all (or handle per requirements). 
    // BUT user asked for "Separated Seasons". 
    // Best practice: Default to first season once seasons are loaded.
    
    // We need a way to filter paging source by season.
    // The current DAO paging source gets ALL episodes.
    // We need a filtered query in DAO or a filter step here.
    // Paging 3 filtering is best done at Query level.
    
    // Changing approach: Pass season filter to repository.
    val episodes: Flow<PagingData<EpisodeEntityV2>> = _selectedSeason.flatMapLatest { seasonNum ->
        // #region agent log
        android.util.Log.d("SeriesDetailViewModelV2", "Episodes flow triggered: seriesId=$seriesId, seasonNum=$seasonNum, hasSeason=${seasonNum != null}")
        // #endregion
        if (seasonNum != null) {
            repository.getPagedEpisodesForSeason(seriesId, seasonNum)
        } else {
             // If no season selected yet (initial load), maybe show all or auto-select first?
             // Users usually want to see "Season 1" first.
             // We can use a special flow that waits for 'seasons' to emit, then selects first.
             // For simplicity/robustness: Show ALL until a season is picked.
             repository.getPagedEpisodes(seriesId)
        }
    }.cachedIn(viewModelScope)
    
    init {
        // Auto-select first season when seasons list loads (if nothing selected)
        viewModelScope.launch {
            seasons.collect { list ->
                // #region agent log
                android.util.Log.d("SeriesDetailViewModelV2", "Seasons collected: seriesId=$seriesId, count=${list.size}, seasons=$list, currentSelected=${_selectedSeason.value}")
                // #endregion
                if (list.isNotEmpty() && _selectedSeason.value == null) {
                    val preferredSeason = list.firstOrNull { it > 0 } ?: list.first()
                    // #region agent log
                    android.util.Log.d("SeriesDetailViewModelV2", "Auto-selecting season: seriesId=$seriesId, preferredSeason=$preferredSeason")
                    // #endregion
                    _selectedSeason.value = preferredSeason
                }
            }
        }
    }

    fun onSeasonSelected(seasonNum: Int) {
        _selectedSeason.value = seasonNum
    }

    // Internal trigger for refreshing metadata
    private val refreshTrigger = MutableStateFlow(0)
    
    // UI State
    // UI State
    val uiState: StateFlow<SeriesDetailUiState> = refreshTrigger.flatMapLatest { 
        repository.getSeriesById(seriesId)
    }.map { result ->
        when (result) {
            is Result.Success -> SeriesDetailUiState.Success(result.data)
            is Result.Error -> SeriesDetailUiState.Error(result.exception.message ?: "Unknown Error")
            is Result.Loading -> SeriesDetailUiState.Loading 
        }
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5000),
        initialValue = SeriesDetailUiState.Loading
    )

    fun onEpisodeClicked(episode: EpisodeEntityV2) {
        viewModelScope.launch {
            val streamUrl = repository.getStreamUrl(episode.episodeId, episode.containerExtension)
            _navigationEvents.send(
                SeriesNavigationEvent.NavigateToPlayer(
                    streamUrl = streamUrl,
                    episodeId = episode.episodeId,
                    seriesId = seriesId,
                    title = episode.title ?: "Episode ${episode.episodeNumber}",
                    posterUrl = episode.thumbnail,
                    containerExtension = episode.containerExtension,
                    seasonNumber = episode.seasonNumber,
                    episodeNumber = episode.episodeNumber
                )
            )
        }
    }

    fun onRetry() {
        refreshTrigger.value += 1
    }
}
