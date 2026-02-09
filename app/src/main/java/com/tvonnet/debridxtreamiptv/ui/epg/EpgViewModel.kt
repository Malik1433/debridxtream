package com.tvonnet.debridxtreamiptv.ui.epg

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.tvonnet.debridxtreamiptv.data.Result
import com.tvonnet.debridxtreamiptv.data.local.entity.EpgEntity
import com.tvonnet.debridxtreamiptv.data.repository.XtreamRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Week 11: EPG (Electronic Program Guide) ViewModel
 * 
 * Manages EPG data and UI state for program guide display
 */
@HiltViewModel
class EpgViewModel @Inject constructor(
    private val repository: XtreamRepository
) : ViewModel() {
    
    private val _uiState = MutableStateFlow<EpgUiState>(EpgUiState.Loading)
    val uiState: StateFlow<EpgUiState> = _uiState.asStateFlow()
    
    private val _selectedChannelId = MutableStateFlow<String?>(null)
    val selectedChannelId: StateFlow<String?> = _selectedChannelId.asStateFlow()
    
    /**
     * Fetch EPG data from API and save to database
     */
    fun fetchEpg() {
        viewModelScope.launch {
            _uiState.value = EpgUiState.Loading
            
            val result = repository.fetchAndSaveEpg()
            
            when (result) {
                is Result.Success -> {
                    val programCount = result.data
                    _uiState.value = EpgUiState.Success(
                        message = "EPG loaded: $programCount programs"
                    )
                }
                is Result.Error -> {
                    _uiState.value = EpgUiState.Error(
                        message = "Failed to load EPG: ${result.exception.message}"
                    )
                }
                else -> {
                    // Handle any other cases
                }
            }
        }
    }
    
    /**
     * Load EPG programs for a specific channel
     */
    fun loadChannelPrograms(channelId: String) {
        _selectedChannelId.value = channelId
        
        viewModelScope.launch {
            repository.getEpgByChannel(channelId)
                ?.collect { programs ->
                    if (programs.isEmpty()) {
                        _uiState.value = EpgUiState.Empty("No programs available for this channel")
                    } else {
                        _uiState.value = EpgUiState.ProgramsLoaded(programs)
                    }
                }
        }
    }
    
    /**
     * Get current program for a channel (one-shot)
     */
    fun getCurrentProgram(channelId: String, callback: (EpgEntity?) -> Unit) {
        viewModelScope.launch {
            val program = repository.getCurrentProgram(channelId)
            callback(program)
        }
    }
    
    /**
     * Get next program for a channel (one-shot)
     */
    fun getNextProgram(channelId: String, callback: (EpgEntity?) -> Unit) {
        viewModelScope.launch {
            val program = repository.getNextProgram(channelId)
            callback(program)
        }
    }
    
    /**
     * Clear old EPG data
     */
    fun clearOldEpg() {
        viewModelScope.launch {
            repository.clearOldEpg()
        }
    }
}

/**
 * EPG UI State sealed class
 */
sealed class EpgUiState {
    object Loading : EpgUiState()
    data class Success(val message: String) : EpgUiState()
    data class ProgramsLoaded(val programs: List<EpgEntity>) : EpgUiState()
    data class Empty(val message: String) : EpgUiState()
    data class Error(val message: String) : EpgUiState()
}

