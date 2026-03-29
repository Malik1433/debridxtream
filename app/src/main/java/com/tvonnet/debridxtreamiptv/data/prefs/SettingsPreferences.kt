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

    fun getPreferredAudioLanguage(): String? {
        return prefs.getString(KEY_PREFERRED_AUDIO, null)
    }

    fun savePreferredAudioLanguage(lang: String) {
        prefs.edit().putString(KEY_PREFERRED_AUDIO, lang).apply()
    }

    fun getPreferredSubtitleLanguage(): String? {
        return prefs.getString(KEY_PREFERRED_SUBTITLE, null)
    }

    fun savePreferredSubtitleLanguage(lang: String) {
        prefs.edit().putString(KEY_PREFERRED_SUBTITLE, lang).apply()
    }

    fun saveLastTrackSelection(hash: String?, audioIndex: Int, textIndex: Int) {
        if (hash == null) return
        prefs.edit()
            .putString(KEY_LAST_SELECTION_HASH, hash)
            .putInt(KEY_LAST_AUDIO_INDEX + hash, audioIndex)
            .putInt(KEY_LAST_TEXT_INDEX + hash, textIndex)
            .apply()
    }

    fun saveSeriesTrackPreference(seriesId: String?, audioIndex: Int, textIndex: Int) {
        if (seriesId == null) return
        prefs.edit()
            .putInt(KEY_SERIES_AUDIO_INDEX + seriesId, audioIndex)
            .putInt(KEY_SERIES_TEXT_INDEX + seriesId, textIndex)
            .apply()
    }

    fun getLastSelectionHash(): String? {
        return prefs.getString(KEY_LAST_SELECTION_HASH, null)
    }

    fun getLastAudioIndex(): Int {
        val hash = getLastSelectionHash() ?: return -1
        return prefs.getInt(KEY_LAST_AUDIO_INDEX + hash, -1)
    }

    fun getLastTextIndex(): Int {
        val hash = getLastSelectionHash() ?: return -1
        return prefs.getInt(KEY_LAST_TEXT_INDEX + hash, -1)
    }

    fun getSeriesAudioIndex(seriesId: String?): Int {
        if (seriesId == null) return -1
        return prefs.getInt(KEY_SERIES_AUDIO_INDEX + seriesId, -1)
    }

    fun getSeriesTextIndex(seriesId: String?): Int {
        if (seriesId == null) return -1
        return prefs.getInt(KEY_SERIES_TEXT_INDEX + seriesId, -1)
    }
    
    companion object {
        private const val PREFS_NAME = "iptv_settings"
        private const val KEY_REFRESH_INTERVAL = "refresh_interval_hours"
        private const val DEFAULT_REFRESH_INTERVAL = 24
        private const val KEY_NETWORK_QUALITY = "network_quality"
        private const val KEY_AUTO_RECONNECT = "auto_reconnect_enabled"
        private const val KEY_SUPPORT_URL = "support_url"
        private const val KEY_PREFERRED_AUDIO = "pref_audio_lang"
        private const val KEY_PREFERRED_SUBTITLE = "pref_subtitle_lang"
        private const val KEY_LAST_SELECTION_HASH = "last_selection_hash"
        private const val KEY_LAST_AUDIO_INDEX = "last_audio_idx_"
        private const val KEY_LAST_TEXT_INDEX = "last_text_idx_"
        private const val KEY_SERIES_AUDIO_INDEX = "series_audio_idx_"
        private const val KEY_SERIES_TEXT_INDEX = "series_text_idx_"
    }
}

