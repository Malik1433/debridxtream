package com.tvonnet.debridxtreamiptv.data.prefs

import android.content.Context
import android.content.SharedPreferences

class CredentialsPreferences(private val context: Context) {
    private val prefs: SharedPreferences = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    
    fun saveCredentials(serverUrl: String, username: String, password: String) {
        prefs.edit().apply {
            putString(KEY_SERVER_URL, serverUrl)
            putString(KEY_USERNAME, username)
            putString(KEY_PASSWORD, password)
            putBoolean(KEY_LOGGED_IN, true)
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
        prefs.edit().clear().apply()
    }

    /**
     * Gets or generates a stable unique identifier for this device.
     */
    fun getDeviceId(): String {
        var deviceId = prefs.getString(KEY_DEVICE_ID, null)
        if (deviceId == null) {
            deviceId = java.util.UUID.randomUUID().toString().take(12) // Shorter for pairing convenience
            prefs.edit().putString(KEY_DEVICE_ID, deviceId).apply()
        }
        return deviceId
    }

    companion object {
        private const val PREFS_NAME = "iptv_credentials"
        private const val KEY_SERVER_URL = "server_url"
        private const val KEY_USERNAME = "username"
        private const val KEY_PASSWORD = "password"
        private const val KEY_LOGGED_IN = "logged_in"
        private const val KEY_DEVICE_ID = "device_id"
    }
}

