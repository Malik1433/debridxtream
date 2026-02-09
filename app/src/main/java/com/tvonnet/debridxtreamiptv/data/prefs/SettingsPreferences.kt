package com.tvonnet.debridxtreamiptv.data.prefs

import android.content.Context
import android.content.SharedPreferences

class SettingsPreferences(private val context: Context) {
    private val prefs: SharedPreferences = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    
    fun saveRefreshInterval(hours: Int) {
        prefs.edit().putInt(KEY_REFRESH_INTERVAL, hours).apply()
    }
    
    fun getRefreshInterval(): Int {
        return prefs.getInt(KEY_REFRESH_INTERVAL, DEFAULT_REFRESH_INTERVAL)
    }
    
    fun shouldRefresh(lastRefreshTimestamp: Long): Boolean {
        val intervalHours = getRefreshInterval()
        val intervalMillis = intervalHours * 60 * 60 * 1000L
        return (System.currentTimeMillis() - lastRefreshTimestamp) >= intervalMillis
    }
    
    fun saveNetworkQuality(quality: String) {
        prefs.edit().putString(KEY_NETWORK_QUALITY, quality).apply()
    }
    
    fun getNetworkQuality(): String {
        return prefs.getString(KEY_NETWORK_QUALITY, "MODERATE") ?: "MODERATE"
    }
    
    fun saveAutoReconnect(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_AUTO_RECONNECT, enabled).apply()
    }
    
    fun isAutoReconnectEnabled(): Boolean {
        return prefs.getBoolean(KEY_AUTO_RECONNECT, true)
    }
    
    fun saveSupportUrl(url: String) {
        prefs.edit().putString(KEY_SUPPORT_URL, url).apply()
    }
    
    fun getSupportUrl(): String {
        return prefs.getString(KEY_SUPPORT_URL, "https://example.com/support") ?: "https://example.com/support"
    }
    
    companion object {
        private const val PREFS_NAME = "iptv_settings"
        private const val KEY_REFRESH_INTERVAL = "refresh_interval_hours"
        private const val DEFAULT_REFRESH_INTERVAL = 24
        private const val KEY_NETWORK_QUALITY = "network_quality"
        private const val KEY_AUTO_RECONNECT = "auto_reconnect_enabled"
        private const val KEY_SUPPORT_URL = "support_url"
    }
}

