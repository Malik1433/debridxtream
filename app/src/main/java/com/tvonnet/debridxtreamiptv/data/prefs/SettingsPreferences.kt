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

    fun isSoftwareAudioEnabled(): Boolean {
        return prefs.getBoolean(KEY_SOFTWARE_AUDIO, true) // Enabled by default to fix missing audio
    }

    fun saveSoftwareAudioEnabled(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_SOFTWARE_AUDIO, enabled).apply()
    }

    // ── Live TV layout (Classic 3-column vs new EPG Guide) ───────────────
    fun getLiveTvStyle(): String {
        return prefs.getString(KEY_LIVE_TV_STYLE, STYLE_GUIDE) ?: STYLE_GUIDE
    }

    fun saveLiveTvStyle(style: String) {
        prefs.edit().putString(KEY_LIVE_TV_STYLE, style).apply()
    }

    // ── EPG Guide tweakables (Live TV EPG spec §7) ───────────────────────
    fun getEpgTimelineZoom(): String {
        return prefs.getString(KEY_EPG_TIMELINE_ZOOM, ZOOM_STANDARD) ?: ZOOM_STANDARD
    }

    fun saveEpgTimelineZoom(zoom: String) {
        prefs.edit().putString(KEY_EPG_TIMELINE_ZOOM, zoom).apply()
    }

    fun getEpgRowDensity(): String {
        return prefs.getString(KEY_EPG_ROW_DENSITY, DENSITY_STANDARD) ?: DENSITY_STANDARD
    }

    fun saveEpgRowDensity(density: String) {
        prefs.edit().putString(KEY_EPG_ROW_DENSITY, density).apply()
    }

    fun isEpgGenreTintEnabled(): Boolean {
        return prefs.getBoolean(KEY_EPG_GENRE_TINT, true)
    }

    fun saveEpgGenreTint(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_EPG_GENRE_TINT, enabled).apply()
    }

    /** When on, Live TV opens on the last-watched channel instead of the first. */
    fun isResumeLastLiveEnabled(): Boolean {
        return prefs.getBoolean(KEY_RESUME_LAST_LIVE, false)
    }

    fun setResumeLastLiveEnabled(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_RESUME_LAST_LIVE, enabled).apply()
    }

    /**
     * §7 U6: read IPTV credentials from the customer's ACCOUNT (playlists) instead of only from a
     * companion push.
     *
     * Defaults OFF. It rewrites the credentials the app logs in with, so it ships dark and is turned
     * on deliberately after the claim flow has been exercised on real hardware:
     *   adb shell am broadcast … or edit `iptv_settings` → account_playlist_sync = true
     */
    fun isAccountPlaylistSyncEnabled(): Boolean {
        return prefs.getBoolean(KEY_ACCOUNT_PLAYLIST_SYNC, false)
    }

    fun setAccountPlaylistSyncEnabled(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_ACCOUNT_PLAYLIST_SYNC, enabled).apply()
    }

    fun getLastLiveStreamId(): String? {
        return prefs.getString(KEY_LAST_LIVE_STREAM_ID, null)
    }

    fun setLastLiveStreamId(streamId: String?) {
        prefs.edit().putString(KEY_LAST_LIVE_STREAM_ID, streamId).apply()
    }

    /** Category the last channel was watched in, so Resume can reopen that list. */
    fun getLastLiveCategoryId(): String? {
        return prefs.getString(KEY_LAST_LIVE_CATEGORY_ID, null)
    }

    fun setLastLiveCategoryId(categoryId: String?) {
        prefs.edit().putString(KEY_LAST_LIVE_CATEGORY_ID, categoryId).apply()
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
        private const val KEY_SOFTWARE_AUDIO = "software_audio_enabled"
        private const val KEY_LAST_SELECTION_HASH = "last_selection_hash"
        private const val KEY_LAST_AUDIO_INDEX = "last_audio_idx_"
        private const val KEY_LAST_TEXT_INDEX = "last_text_idx_"
        private const val KEY_SERIES_AUDIO_INDEX = "series_audio_idx_"
        private const val KEY_SERIES_TEXT_INDEX = "series_text_idx_"

        // Live TV layout
        const val KEY_LIVE_TV_STYLE = "live_tv_style"
        const val STYLE_GUIDE = "guide"
        const val STYLE_CLASSIC = "classic"

        // EPG Guide tweakables
        const val KEY_EPG_TIMELINE_ZOOM = "epg_timeline_zoom"
        const val ZOOM_COMPACT = "compact"
        const val ZOOM_STANDARD = "standard"
        const val ZOOM_WIDE = "wide"

        const val KEY_EPG_ROW_DENSITY = "epg_row_density"
        const val DENSITY_COZY = "cozy"
        const val DENSITY_STANDARD = "standard"
        const val DENSITY_ROOMY = "roomy"

        const val KEY_EPG_GENRE_TINT = "epg_genre_tint"

        // Live TV resume
        const val KEY_RESUME_LAST_LIVE = "resume_last_live"
        const val KEY_ACCOUNT_PLAYLIST_SYNC = "account_playlist_sync"
        const val KEY_LAST_LIVE_STREAM_ID = "last_live_stream_id"
        const val KEY_LAST_LIVE_CATEGORY_ID = "last_live_category_id"
    }
}

