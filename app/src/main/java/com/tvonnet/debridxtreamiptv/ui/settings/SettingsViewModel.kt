package com.tvonnet.debridxtreamiptv.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.tvonnet.debridxtreamiptv.data.prefs.DebridPreferences
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

data class SettingsUiState(
    val playerEngine: String = "exo",
    val isTunnelingEnabled: Boolean = false,
    val isAutoPlayEnabled: Boolean = true,
    val isUiScaleEnabled: Boolean = true,
    val selectedCategory: SettingCategory = SettingCategory.GENERAL,
    val isDebridAuthenticated: Boolean = false
)

enum class SettingCategory {
    GENERAL, HOME, PLAYER, VISUALS, DEBRID, ABOUT, LOGOUT
}

@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val prefs: DebridPreferences,
    private val homePrefs: com.tvonnet.debridxtreamiptv.data.prefs.HomePreferences,
    private val repository: com.tvonnet.debridxtreamiptv.data.repository.XtreamRepository
) : ViewModel() {

    private val _uiState = MutableStateFlow(SettingsUiState())
    val uiState: StateFlow<SettingsUiState> = _uiState.asStateFlow()

    init {
        loadSettings()
    }

    private fun loadSettings() {
        _uiState.update {
            it.copy(
                playerEngine = prefs.getPlayerEngine(),
                isTunnelingEnabled = prefs.isTunnelingModeEnabled(),
                isAutoPlayEnabled = prefs.isAutoPlayNextEnabled(),
                isUiScaleEnabled = prefs.isUiScaleAnimationEnabled(),
                isDebridAuthenticated = prefs.getRealDebridToken() != null
            )
        }
    }

    fun selectCategory(category: SettingCategory) {
        _uiState.update { it.copy(selectedCategory = category) }
    }

    fun setPlayerEngine(engine: String) {
        prefs.savePlayerEngine(engine)
        _uiState.update { it.copy(playerEngine = engine) }
    }

    fun toggleTunneling(enabled: Boolean) {
        prefs.setTunnelingMode(enabled)
        _uiState.update { it.copy(isTunnelingEnabled = enabled) }
    }

    fun toggleAutoPlay(enabled: Boolean) {
        prefs.setAutoPlayNext(enabled)
        _uiState.update { it.copy(isAutoPlayEnabled = enabled) }
    }

    fun toggleUiScale(enabled: Boolean) {
        prefs.setUiScaleAnimation(enabled)
        _uiState.update { it.copy(isUiScaleEnabled = enabled) }
    }

    fun logoutDebrid() {
        prefs.clearRealDebridToken()
        _uiState.update { it.copy(isDebridAuthenticated = false) }
    }

    suspend fun getCategories(type: String): List<com.tvonnet.debridxtreamiptv.data.model.XtreamCategory> {
        return kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            val cache = repository.readCache()
            
            when (type) {
                "movie" -> cache?.vod?.categories ?: emptyList()
                "series" -> cache?.series?.categories ?: emptyList()
                "live" -> cache?.live?.categories ?: emptyList()
                else -> emptyList()
            }
        }
    }
    
    fun saveCategories(type: String, ids: Set<String>) {
        when (type) {
            "movie" -> homePrefs.setSelectedMovieCategories(ids)
            "series" -> homePrefs.setSelectedSeriesCategories(ids)
            "live" -> homePrefs.setSelectedLiveCategories(ids)
        }
    }
}
