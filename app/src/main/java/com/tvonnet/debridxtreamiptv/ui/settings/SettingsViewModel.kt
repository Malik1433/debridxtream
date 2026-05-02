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
import android.content.Context
import androidx.preference.PreferenceManager
import dagger.hilt.android.qualifiers.ApplicationContext

data class SettingsUiState(
    val playerEngine: String = "exo",
    val isTunnelingEnabled: Boolean = false,
    val isAutoPlayEnabled: Boolean = true,
    val isUiScaleEnabled: Boolean = true,
    val selectedCategory: SettingCategory = SettingCategory.GENERAL,
    val isDebridAuthenticated: Boolean = false,
    val addonRegistryUrl: String = "",
    val addonRegistryUrls: Set<String> = emptySet(),
    val isEpgAutoSyncEnabled: Boolean = true,
    val epgSyncIntervalHours: String = "6",
    val isSoftwareAudioEnabled: Boolean = true
)

enum class SettingCategory {
    GENERAL, IPTV_EPG, HOME, PLAYER, VISUALS, DEBRID, ABOUT, LOGOUT
}

@HiltViewModel
class SettingsViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
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
        val defaultPrefs = PreferenceManager.getDefaultSharedPreferences(context)
        val audioPrefs = com.tvonnet.debridxtreamiptv.data.prefs.SettingsPreferences(context)
        _uiState.update {
            it.copy(
                playerEngine = prefs.getPlayerEngine(),
                isTunnelingEnabled = prefs.isTunnelingModeEnabled(),
                isAutoPlayEnabled = prefs.isAutoPlayNextEnabled(),
                isUiScaleEnabled = prefs.isUiScaleAnimationEnabled(),
                isDebridAuthenticated = prefs.getRealDebridToken() != null,
                addonRegistryUrl = prefs.getAddonRegistryUrl(),
                addonRegistryUrls = prefs.getAddonRegistryUrls(),
                isEpgAutoSyncEnabled = defaultPrefs.getBoolean("epg_auto_sync", true),
                epgSyncIntervalHours = defaultPrefs.getString("epg_sync_interval", "6") ?: "6",
                isSoftwareAudioEnabled = audioPrefs.isSoftwareAudioEnabled()
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

    fun toggleSoftwareAudio(enabled: Boolean) {
        val audioPrefs = com.tvonnet.debridxtreamiptv.data.prefs.SettingsPreferences(context)
        audioPrefs.saveSoftwareAudioEnabled(enabled)
        _uiState.update { it.copy(isSoftwareAudioEnabled = enabled) }
    }

    fun toggleUiScale(enabled: Boolean) {
        prefs.setUiScaleAnimation(enabled)
        _uiState.update { it.copy(isUiScaleEnabled = enabled) }
    }

    fun logoutDebrid() {
        prefs.clearRealDebridToken()
        _uiState.update { it.copy(isDebridAuthenticated = false) }
    }

    fun setAddonRegistryUrl(url: String) {
        prefs.saveAddonRegistryUrl(url)
        _uiState.update { it.copy(addonRegistryUrl = url) }
    }

    /**
     * Adds a new scraper registry URL to the active set.
     *
     * @param url Fully-qualified HTTPS URL pointing to a JSON addon registry.
     */
    fun addAddonRegistryUrl(url: String) {
        prefs.addAddonRegistryUrl(url)
        _uiState.update { it.copy(addonRegistryUrls = prefs.getAddonRegistryUrls()) }
    }

    /**
     * Removes a scraper registry URL from the active set.
     *
     * @param url The URL to remove.
     */
    fun removeAddonRegistryUrl(url: String) {
        prefs.removeAddonRegistryUrl(url)
        _uiState.update { it.copy(addonRegistryUrls = prefs.getAddonRegistryUrls()) }
    }

    fun toggleEpgAutoSync(enabled: Boolean) {
        val defaultPrefs = PreferenceManager.getDefaultSharedPreferences(context)
        defaultPrefs.edit().putBoolean("epg_auto_sync", enabled).apply()
        _uiState.update { it.copy(isEpgAutoSyncEnabled = enabled) }
        com.tvonnet.debridxtreamiptv.worker.EpgSyncController().syncFromPreferences(context, defaultPrefs)
    }

    fun setEpgSyncInterval(hours: String) {
        val defaultPrefs = PreferenceManager.getDefaultSharedPreferences(context)
        defaultPrefs.edit().putString("epg_sync_interval", hours).apply()
        _uiState.update { it.copy(epgSyncIntervalHours = hours) }
        com.tvonnet.debridxtreamiptv.worker.EpgSyncController().syncFromPreferences(context, defaultPrefs)
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
