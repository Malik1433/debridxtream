package com.tvonnet.debridxtreamiptv.features.seriesv2.ui.model

import com.tvonnet.debridxtreamiptv.features.seriesv2.data.model.SeriesEntityV2

/**
 * UI State for Series Detail V2 Screen.
 */
sealed interface SeriesDetailUiState {
    data object Loading : SeriesDetailUiState
    
    data class Success(
        val series: SeriesEntityV2,
        val isOffline: Boolean = false
        // Episodes are exposed via a separate Flow<PagingData> in the ViewModel 
        // because PagingData cannot be easily held in a generic StateFlow.
    ) : SeriesDetailUiState

    data class Error(val message: String) : SeriesDetailUiState
}

/**
 * One-off navigation events
 */
sealed interface SeriesNavigationEvent {
    data class NavigateToPlayer(
        val streamUrl: String,
        val episodeId: String,
        val seriesId: String,
        val title: String,
        val posterUrl: String?,
        val containerExtension: String?,
        val seasonNumber: Int,
        val episodeNumber: Int?
    ) : SeriesNavigationEvent
    data object NavigateBack : SeriesNavigationEvent
    data class ShowMessage(val msg: String) : SeriesNavigationEvent
}
