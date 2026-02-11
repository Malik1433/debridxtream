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
        prefs.edit().clear().apply()
    }

    fun getSyncCode(): String? {
        return prefs.getString(KEY_SYNC_CODE, null)
    }

    fun saveSyncCode(code: String) {
        prefs.edit().putString(KEY_SYNC_CODE, code).apply()
    }


    // Expose for specialized cases
    fun getPrefs(): SharedPreferences = prefs
    
    companion object {
        const val PREFS_NAME = "iptv_credentials"
        const val KEY_SERVER_URL = "server_url"
        const val KEY_USERNAME = "username"
        const val KEY_PASSWORD = "password"
        const val KEY_LOGGED_IN = "logged_in"
        const val KEY_SYNC_CODE = "sync_code"
    }


}

