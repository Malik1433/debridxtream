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
    val selectedCategory: SettingCategory = SettingCategory.PLAYBACK,
    /** G4: bumped by [SettingsViewModel.refreshUi] to force a rebuild when nothing else changed. */
    val revision: Int = 0,
    val accountUsername: String? = null,
    val accountServer: String? = null,
    val isDebridAuthenticated: Boolean = false,
    val addonRegistryUrl: String = "",
    val addonRegistryUrls: Set<String> = emptySet(),
    val stremioAddonUrls: Set<String> = emptySet(),
    val isEpgAutoSyncEnabled: Boolean = true,
    val epgSyncIntervalHours: String = "6",
    val isSoftwareAudioEnabled: Boolean = true,
    val preferredAudioLang: String = "EN",
    val preferredAudioLang2: String = "NONE",
    /** M0: "auto" | "tv" | "mobile" — which layout the app renders. */
    val uiMode: String = "auto",
    val liveTvStyle: String = "guide",
    val epgTimelineZoom: String = "standard",
    val epgRowDensity: String = "standard",
    val epgGenreTint: Boolean = true,
    val resumeLastLive: Boolean = false
)

/**
 * The settings rail, in the order it is shown. Grouped the way a viewer looks for things — what
 * plays, what Live TV does, what Home shows, where sources come from, the data on the box, and the
 * account — rather than by which class happens to own the preference.
 */
enum class SettingCategory {
    PLAYBACK, LIVE_TV, HOME, ADDONS, DATA, ABOUT, ACCOUNT
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
        val credentialsPrefs = com.tvonnet.debridxtreamiptv.data.prefs.CredentialsPreferences(context)
        _uiState.update {
            it.copy(
                accountUsername = credentialsPrefs.getUsername(),
                accountServer = credentialsPrefs.getServerUrl(),
                isDebridAuthenticated = prefs.getRealDebridToken() != null,
                addonRegistryUrl = prefs.getAddonRegistryUrl(),
                addonRegistryUrls = prefs.getAddonRegistryUrls(),
                stremioAddonUrls = prefs.getStremioAddonUrls(),
                isEpgAutoSyncEnabled = defaultPrefs.getBoolean("epg_auto_sync", true),
                epgSyncIntervalHours = defaultPrefs.getString("epg_sync_interval", "6") ?: "6",
                isSoftwareAudioEnabled = audioPrefs.isSoftwareAudioEnabled(),
                preferredAudioLang = credentialsPrefs.preferredAudioLang,
                preferredAudioLang2 = credentialsPrefs.preferredAudioLang2,
                uiMode = audioPrefs.getUiModeOverride(),
                liveTvStyle = audioPrefs.getLiveTvStyle(),
                epgTimelineZoom = audioPrefs.getEpgTimelineZoom(),
                epgRowDensity = audioPrefs.getEpgRowDensity(),
                epgGenreTint = audioPrefs.isEpgGenreTintEnabled(),
                resumeLastLive = audioPrefs.isResumeLastLiveEnabled()
            )
        }
    }

    fun setPreferredAudioLang(lang: String) {
        com.tvonnet.debridxtreamiptv.data.prefs.CredentialsPreferences(context).preferredAudioLang = lang
        _uiState.update { it.copy(preferredAudioLang = lang) }
    }

    /**
     * M0: TV vs mobile layout. Recorded here; the routing that reads it happens at
     * activity start (M1+), so today this only persists the choice.
     */
    fun setUiMode(mode: String) {
        com.tvonnet.debridxtreamiptv.data.prefs.SettingsPreferences(context).setUiModeOverride(mode)
        _uiState.update { it.copy(uiMode = mode) }
    }

    /** H9: the secondary priority ("Hindi na mile to German"). "NONE" = off. */
    fun setPreferredAudioLang2(lang: String) {
        com.tvonnet.debridxtreamiptv.data.prefs.CredentialsPreferences(context).preferredAudioLang2 = lang
        _uiState.update { it.copy(preferredAudioLang2 = lang) }
    }

    fun selectCategory(category: SettingCategory) {
        _uiState.update { it.copy(selectedCategory = category) }
    }

    /**
     * G4: re-emit so the screen rebuilds its rows, without any setting having changed.
     *
     * The refresh progress lives on the row, and the row is rebuilt from this state — so something
     * has to say "ask again". A counter rather than a copy of the same values: `update` on an equal
     * object emits nothing, and the progress would freeze at whatever it read first.
     */
    fun refreshUi() {
        _uiState.update { it.copy(revision = it.revision + 1) }
    }

    fun toggleSoftwareAudio(enabled: Boolean) {
        val audioPrefs = com.tvonnet.debridxtreamiptv.data.prefs.SettingsPreferences(context)
        audioPrefs.saveSoftwareAudioEnabled(enabled)
        _uiState.update { it.copy(isSoftwareAudioEnabled = enabled) }
    }

    // ── Live TV EPG guide appearance ────────────────────────────────────
    fun setLiveTvStyle(style: String) {
        com.tvonnet.debridxtreamiptv.data.prefs.SettingsPreferences(context).saveLiveTvStyle(style)
        _uiState.update { it.copy(liveTvStyle = style) }
    }

    fun setEpgTimelineZoom(zoom: String) {
        com.tvonnet.debridxtreamiptv.data.prefs.SettingsPreferences(context).saveEpgTimelineZoom(zoom)
        _uiState.update { it.copy(epgTimelineZoom = zoom) }
    }

    fun setEpgRowDensity(density: String) {
        com.tvonnet.debridxtreamiptv.data.prefs.SettingsPreferences(context).saveEpgRowDensity(density)
        _uiState.update { it.copy(epgRowDensity = density) }
    }

    fun toggleEpgGenreTint(enabled: Boolean) {
        com.tvonnet.debridxtreamiptv.data.prefs.SettingsPreferences(context).saveEpgGenreTint(enabled)
        _uiState.update { it.copy(epgGenreTint = enabled) }
    }

    fun toggleResumeLastLive(enabled: Boolean) {
        com.tvonnet.debridxtreamiptv.data.prefs.SettingsPreferences(context).setResumeLastLiveEnabled(enabled)
        _uiState.update { it.copy(resumeLastLive = enabled) }
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

    fun addStremioAddonUrl(url: String) {
        prefs.addStremioAddonUrl(url)
        _uiState.update { it.copy(stremioAddonUrls = prefs.getStremioAddonUrls()) }
    }

    fun removeStremioAddonUrl(url: String) {
        prefs.removeStremioAddonUrl(url)
        _uiState.update { it.copy(stremioAddonUrls = prefs.getStremioAddonUrls()) }
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
    
    /** What the customer already chose, so the sheet opens with those ticked (G3/G4). */
    fun getSelectedCategories(type: String): Set<String> = when (type) {
        "movie" -> homePrefs.getSelectedMovieCategories()
        "series" -> homePrefs.getSelectedSeriesCategories()
        "live" -> homePrefs.getSelectedLiveCategories()
        else -> emptySet()
    }

    fun saveCategories(type: String, ids: Set<String>) {
        when (type) {
            "movie" -> homePrefs.setSelectedMovieCategories(ids)
            "series" -> homePrefs.setSelectedSeriesCategories(ids)
            "live" -> homePrefs.setSelectedLiveCategories(ids)
        }
    }
}
