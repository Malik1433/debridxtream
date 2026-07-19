package com.tvonnet.debridxtreamiptv.data.prefs

import android.content.Context
import android.content.SharedPreferences

class CredentialsPreferences(private val context: Context) {
    private val prefs: SharedPreferences = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    private val identityPrefs = IdentityPreferences(context)

    init {
        identityPrefs.migrateFromLegacy(
            deviceId = prefs.getString(KEY_DEVICE_ID, null),
            syncCode = prefs.getString(KEY_SYNC_CODE, null)
        )
    }
    
    fun saveCredentials(serverUrl: String, username: String, password: String) {
        prefs.edit().apply {
            putString(KEY_SERVER_URL, serverUrl)
            putString(KEY_USERNAME, username)
            putString(KEY_PASSWORD, password)
            putBoolean(KEY_LOGGED_IN, true)
            apply()
        }
    }

    /**
     * Saves credentials received from Companion Sync without marking as logged in.
     * This allows the LoginFragment to perform a fresh validation before proceeding.
     */
    fun saveSyncedCredentials(serverUrl: String, username: String, password: String) {
        prefs.edit().apply {
            putString(KEY_SERVER_URL, serverUrl)
            putString(KEY_USERNAME, username)
            putString(KEY_PASSWORD, password)
            putBoolean(KEY_LOGGED_IN, false) // Will be set to true after repository.login()
            apply()
        }
    }
    
    fun getServerUrl(): String? {
        return prefs.getString(KEY_SERVER_URL, null)
    }
    
    fun getUsername(): String? {
        return prefs.getString(KEY_USERNAME, null)
    }
    
    fun getPassword(): String? {
        return prefs.getString(KEY_PASSWORD, null)
    }
    
    fun isLoggedIn(): Boolean {
        return prefs.getBoolean(KEY_LOGGED_IN, false)
    }
    
    fun clearCredentials() {
        prefs.edit().apply {
            remove(KEY_SERVER_URL)
            remove(KEY_USERNAME)
            remove(KEY_PASSWORD)
            remove(KEY_LOGGED_IN)
            apply()
        }
    }

    /**
     * Preferred audio language used to bias source ordering on the Movie/Series
     * detail source panels. One of EN / HI / FR / ES / MULTI / ALL. "ALL" means
     * no preference (chip hidden, no language boost). Defaults to EN.
     */
    var preferredAudioLang: String
        get() = prefs.getString(KEY_PREFERRED_AUDIO_LANG, DEFAULT_PREFERRED_AUDIO_LANG)
            ?: DEFAULT_PREFERRED_AUDIO_LANG
        set(value) {
            prefs.edit().putString(KEY_PREFERRED_AUDIO_LANG, value).apply()
        }

    fun getSyncCode(): String? {
        return prefs.getString(KEY_SYNC_CODE, null) ?: identityPrefs.getLegacySyncCode()
    }

    fun saveSyncCode(code: String) {
        prefs.edit().putString(KEY_SYNC_CODE, code).apply()
        identityPrefs.saveLegacySyncCode(code)
    }


    // Expose for specialized cases
    fun getPrefs(): SharedPreferences = prefs

    /**
     * Returns a persistent unique device ID for pairing purposes.
     * Generates and stores a UUID on first access.
     *
     * @return A stable device identifier string.
     */
    fun getDeviceId(): String {
        val existing = prefs.getString(KEY_DEVICE_ID, null)
        if (existing != null) return existing
        val newId = identityPrefs.getOrCreateInstallInstanceId()
        prefs.edit().putString(KEY_DEVICE_ID, newId).apply()
        return newId
    }
    
    companion object {
        const val PREFS_NAME = "iptv_credentials"
        const val KEY_SERVER_URL = "server_url"
        const val KEY_USERNAME = "username"
        const val KEY_PASSWORD = "password"
        const val KEY_LOGGED_IN = "logged_in"
        const val KEY_SYNC_CODE = "sync_code"
        const val KEY_DEVICE_ID = "device_id"
        const val KEY_PREFERRED_AUDIO_LANG = "preferred_audio_lang"
        const val DEFAULT_PREFERRED_AUDIO_LANG = "EN"
        private const val KEY_IPTV_EXP_DATE = "iptv_exp_date"
        private const val KEY_IPTV_EXP_CACHED_AT = "iptv_exp_cached_at"
        private const val IPTV_EXP_TTL_MS = 12L * 60 * 60 * 1000 // 12h
    }

    /**
     * ST-4: cache the Xtream account expiry (epoch seconds) so Home doesn't fire a
     * `login` network call on every view creation just to render the expiry chip.
     * Returns the cached value only while younger than [IPTV_EXP_TTL_MS].
     */
    fun getCachedIptvExpiry(): Long? {
        val cachedAt = prefs.getLong(KEY_IPTV_EXP_CACHED_AT, 0L)
        if (cachedAt == 0L || System.currentTimeMillis() - cachedAt > IPTV_EXP_TTL_MS) return null
        return prefs.getLong(KEY_IPTV_EXP_DATE, 0L).takeIf { it > 0L }
    }

    fun saveIptvExpiry(expEpochSeconds: Long) {
        prefs.edit()
            .putLong(KEY_IPTV_EXP_DATE, expEpochSeconds)
            .putLong(KEY_IPTV_EXP_CACHED_AT, System.currentTimeMillis())
            .apply()
    }
}

