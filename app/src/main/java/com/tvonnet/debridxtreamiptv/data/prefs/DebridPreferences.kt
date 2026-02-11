package com.tvonnet.debridxtreamiptv.data.prefs

import android.content.Context
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import com.tvonnet.debridxtreamiptv.data.debrid.api.RealDebridAuthInterceptor
import com.tvonnet.debridxtreamiptv.data.debrid.model.DebridRowConfig
import com.tvonnet.debridxtreamiptv.data.debrid.model.RealDebridAuthState
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Secure storage for Debrid-related configuration, including Real-Debrid OAuth tokens
 * and user-customizable catalog/add-on preferences.
 */
@Singleton
class DebridPreferences @Inject constructor(
    @ApplicationContext context: Context
) : RealDebridAuthInterceptor.AccessTokenProvider {

    private val gson = Gson()
    private val authStateType = object : TypeToken<RealDebridAuthState>() {}.type
    private val rowConfigListType = object : TypeToken<List<DebridRowConfig>>() {}.type

    private val sharedPreferences by lazy {
        val masterKey = MasterKey.Builder(context)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()

        EncryptedSharedPreferences.create(
            context,
            PREFS_NAME,
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
        )
    }

    override fun getAccessToken(): String? {
        return getAuthState()?.accessToken
    }

    fun saveAuthState(state: RealDebridAuthState) {
        sharedPreferences.edit()
            .putString(KEY_REAL_DEBRID_AUTH_STATE, gson.toJson(state))
            .apply()
    }

    fun getAuthState(): RealDebridAuthState? {
        val stored = sharedPreferences.getString(KEY_REAL_DEBRID_AUTH_STATE, null) ?: return null
        return runCatching {
            gson.fromJson<RealDebridAuthState>(stored, authStateType)
        }.getOrNull()
    }

    fun clearAuthState() {
        sharedPreferences.edit()
            .remove(KEY_REAL_DEBRID_AUTH_STATE)
            .apply()
    }

    fun saveSelectedAddonIds(ids: Set<String>) {
        sharedPreferences.edit()
            .putStringSet(KEY_SELECTED_ADDON_IDS, ids)
            .apply()
    }

    fun getSelectedAddonIds(): Set<String> {
        return sharedPreferences.getStringSet(KEY_SELECTED_ADDON_IDS, emptySet()) ?: emptySet()
    }

    fun saveRowConfigurations(configs: List<DebridRowConfig>) {
        sharedPreferences.edit()
            .putString(KEY_ROW_CONFIGS, gson.toJson(configs))
            .apply()
    }

    fun getRowConfigurations(): List<DebridRowConfig> {
        val stored = sharedPreferences.getString(KEY_ROW_CONFIGS, null) ?: return emptyList()
        return runCatching {
            gson.fromJson<List<DebridRowConfig>>(stored, rowConfigListType)
        }.getOrDefault(emptyList())
    }

    fun clearAll() {
        sharedPreferences.edit().clear().apply()
    }

    /**
     * Convenience methods for ViewModel access
     */
    fun getRealDebridToken(): String? {
        return getAuthState()?.accessToken
    }

    fun saveRealDebridToken(token: String) {
        // For simple token storage, create minimal auth state
        val now = System.currentTimeMillis()
        val state = RealDebridAuthState(
            accessToken = token,
            refreshToken = "",
            clientId = getRealDebridClientId(),
            clientSecret = "",
            tokenType = "Bearer",
            scope = null,
            issuedAt = now,
            expiresAt = now + (3600 * 1000L) // 1 hour default
        )
        saveAuthState(state)
    }

    fun clearRealDebridToken() {
        clearAuthState()
    }

    fun getRealDebridClientId(): String {
        return sharedPreferences.getString(KEY_CLIENT_ID, DEFAULT_CLIENT_ID) ?: DEFAULT_CLIENT_ID
    }

    companion object {
        private const val PREFS_NAME = "debrid_secure_prefs"
        private const val KEY_REAL_DEBRID_AUTH_STATE = "real_debrid_auth_state"
        private const val KEY_SELECTED_ADDON_IDS = "selected_addon_ids"
        private const val KEY_ROW_CONFIGS = "row_configs"
        private const val KEY_CLIENT_ID = "client_id"
        
        // Real-Debrid OAuth client ID (public, from RealDebrid API)
        private const val DEFAULT_CLIENT_ID = "X245A4XAIBGVM"
        private const val KEY_MEDIA_FUSION_URL = "media_fusion_url"

        // Settings Overhaul Keys
        private const val KEY_PLAYER_ENGINE = "pref_player_engine"
        private const val KEY_TUNNELING_MODE = "pref_tunneling_mode"
        private const val KEY_AUTO_PLAY_NEXT = "pref_auto_play_next"
        private const val KEY_UI_SCALE_ANIMATION = "pref_ui_scale_animation"
        private const val KEY_ADDON_REGISTRY_URL = "pref_addon_registry_url"
        
        const val DEFAULT_REGISTRY_URL = "https://raw.githubusercontent.com/DebridXtream/registry/main/addons.json"
        const val PUREFIRE_REGISTRY_URL = "https://raw.githubusercontent.com/PureFireHindi/registry/main/addons.json"
    }

    fun getAddonRegistryUrl(): String {
        return sharedPreferences.getString(KEY_ADDON_REGISTRY_URL, DEFAULT_REGISTRY_URL) ?: DEFAULT_REGISTRY_URL
    }

    fun setAddonRegistryUrl(url: String) {
        sharedPreferences.edit().putString(KEY_ADDON_REGISTRY_URL, url).apply()
    }

    // Aliases to match legacy usage in some ViewModels
    fun saveAddonRegistryUrl(url: String) = setAddonRegistryUrl(url)
    fun getAddonRegistryUrlLegacy(): String = getAddonRegistryUrl()

    fun getMediaFusionUrl(): String? {
        return sharedPreferences.getString(KEY_MEDIA_FUSION_URL, null)
    }

    fun saveMediaFusionUrl(url: String) {
        sharedPreferences.edit()
            .putString(KEY_MEDIA_FUSION_URL, url)
            .apply()
    }

    // Settings Overhaul: Player Engine
    fun getPlayerEngine(): String {
        return sharedPreferences.getString(KEY_PLAYER_ENGINE, "exo") ?: "exo"
    }

    fun savePlayerEngine(engine: String) {
        sharedPreferences.edit().putString(KEY_PLAYER_ENGINE, engine).apply()
    }

    // Settings Overhaul: Tunneling Mode
    fun isTunnelingModeEnabled(): Boolean {
        return sharedPreferences.getBoolean(KEY_TUNNELING_MODE, false)
    }

    fun setTunnelingMode(enabled: Boolean) {
        sharedPreferences.edit().putBoolean(KEY_TUNNELING_MODE, enabled).apply()
    }

    // Settings Overhaul: Auto Play Next
    fun isAutoPlayNextEnabled(): Boolean {
        return sharedPreferences.getBoolean(KEY_AUTO_PLAY_NEXT, true)
    }

    fun setAutoPlayNext(enabled: Boolean) {
        sharedPreferences.edit().putBoolean(KEY_AUTO_PLAY_NEXT, enabled).apply()
    }

    // Settings Overhaul: UI Scale Animation
    fun isUiScaleAnimationEnabled(): Boolean {
        return sharedPreferences.getBoolean(KEY_UI_SCALE_ANIMATION, true)
    }

    fun setUiScaleAnimation(enabled: Boolean) {
        sharedPreferences.edit().putBoolean(KEY_UI_SCALE_ANIMATION, enabled).apply()
    }
}


