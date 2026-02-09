package com.tvonnet.debridxtreamiptv.ui.series

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.tvonnet.debridxtreamiptv.data.local.relation.toXtreamSeriesDetailResponse
import com.tvonnet.debridxtreamiptv.data.model.XtreamSeriesDetailResponse
import com.tvonnet.debridxtreamiptv.data.repository.XtreamRepository
import com.tvonnet.debridxtreamiptv.worker.SeriesSyncScheduler
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.Dispatchers
import javax.inject.Inject

sealed class SeriesDetailUiState {
    object Idle : SeriesDetailUiState()
    object Loading : SeriesDetailUiState()
    data class Success(val detail: XtreamSeriesDetailResponse) : SeriesDetailUiState()
    data class Error(val message: String) : SeriesDetailUiState()
}

@HiltViewModel
class SeriesDetailViewModel @Inject constructor(
    private val repository: XtreamRepository,
    private val seriesSyncScheduler: SeriesSyncScheduler
) : ViewModel() {

    private val _uiState = MutableStateFlow<SeriesDetailUiState>(SeriesDetailUiState.Idle)
    val uiState: StateFlow<SeriesDetailUiState> = _uiState.asStateFlow()

    private var currentSeriesId: String? = null

    fun loadSeries(seriesId: String) {
        android.util.Log.d("SeriesDebug", "loadSeries called for ID: $seriesId")
        // Always reset state or check if we need to reload
        currentSeriesId = seriesId
        
        _uiState.value = SeriesDetailUiState.Loading
        
        viewModelScope.launch {
            android.util.Log.d("SeriesDebug", "Starting Flow collection for $seriesId")
            repository.getSeriesDetailFlow(seriesId).collect { seriesWithDetails ->
                if (seriesWithDetails != null) {
                    android.util.Log.d("SeriesDebug", "Flow Emitted Data for $seriesId: ${seriesWithDetails.series.name}")
                    val detail = seriesWithDetails.toXtreamSeriesDetailResponse()
                     _uiState.value = SeriesDetailUiState.Success(detail)
                } else {
                    android.util.Log.d("SeriesDebug", "Flow Emitted NULL for $seriesId")
                }
            }
        }
        
        refreshSeries(seriesId)
    }

    fun refreshSeries(seriesId: String) {
        android.util.Log.d("SeriesDebug", "refreshSeries called for $seriesId")
        viewModelScope.launch(Dispatchers.IO) {
            val startTime = System.currentTimeMillis()
            val result = repository.syncSeriesDetail(seriesId)
            val duration = System.currentTimeMillis() - startTime
            android.util.Log.d("SeriesDebug", "syncSeriesDetail finished in ${duration}ms. Result: $result")

            if (result is com.tvonnet.debridxtreamiptv.data.Result.Success) {
                // Fix for "Stuck on Loading": ALWAYS update UI with fresh network data
                // This acts as a robust fallback if the DB Flow is slow or fails to emit
                android.util.Log.d("SeriesDebug", "✅ Network Sync Success. Broadcasting data to UI immediately.")
                _uiState.value = SeriesDetailUiState.Success(result.data)
            } else if (result is com.tvonnet.debridxtreamiptv.data.Result.Error) {
                android.util.Log.e("SeriesDebug", "Sync Error: ${result.exception.message}")
                // Only show error if we don't have data already
                if (_uiState.value !is SeriesDetailUiState.Success) {
                    _uiState.value = SeriesDetailUiState.Error(result.exception.message ?: "Failed to load series")
                }
            }
        }
    }
}
