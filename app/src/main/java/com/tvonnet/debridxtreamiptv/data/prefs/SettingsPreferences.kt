package com.tvonnet.debridxtreamiptv.data.prefs

import android.content.Context
import android.content.SharedPreferences
import com.tvonnet.debridxtreamiptv.R

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
    /**
     * M7: only the DEFAULT is configuration-dependent — an explicit choice always wins.
     *
     * The EPG guide is a multi-day timeline GRID: it needs width, and on a 411dp phone there
     * is none, which is why Live read as "touch doesn't work" there. The classic screen is a
     * channel LIST with a preview above it — the shape a phone actually wants — so that is the
     * default in portrait. TV is untouched: `values` still says guide.
     */
    fun getLiveTvStyle(): String {
        val fallback = if (context.resources.getBoolean(R.bool.live_defaults_to_classic)) {
            STYLE_CLASSIC
        } else {
            STYLE_GUIDE
        }
        return prefs.getString(KEY_LIVE_TV_STYLE, fallback) ?: fallback
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
     * §7 U6 / U8: read IPTV credentials from the customer's ACCOUNT (playlists) instead of only from
     * a companion push.
     *
     * **Default flipped to ON (U8, 2026-08-01)** after the whole chain was exercised on real
     * hardware: claim from the phone, playlists read on the TV, auto-login, and the same again after
     * a sign-out.
     *
     * Turning it on is safe for a device that is NOT claimed to any account: the sync resolves no
     * owner, reads nothing and writes nothing. It only does something once a customer has actually
     * linked that TV — which is the point at which they want it to.
     */
    fun isAccountPlaylistSyncEnabled(): Boolean {
        return prefs.getBoolean(KEY_ACCOUNT_PLAYLIST_SYNC, true)
    }

    fun setAccountPlaylistSyncEnabled(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_ACCOUNT_PLAYLIST_SYNC, enabled).apply()
    }

    /**
     * S2: forget which channel to auto-tune to. Both values are ids issued by ONE provider, so
     * after a switch "resume last live" would open a channel the new provider does not have.
     *
     * Option A: only THIS provider's pair is cleared — the other servers keep theirs, which is the
     * whole point of switching being non-destructive now.
     */
    fun clearLastLiveResume() {
        prefs.edit()
            .remove(scoped(KEY_LAST_LIVE_STREAM_ID))
            .remove(scoped(KEY_LAST_LIVE_CATEGORY_ID))
            .apply()
    }

    fun getLastLiveStreamId(): String? {
        return prefs.getString(scoped(KEY_LAST_LIVE_STREAM_ID), null)
    }

    fun setLastLiveStreamId(streamId: String?) {
        prefs.edit().putString(scoped(KEY_LAST_LIVE_STREAM_ID), streamId).apply()
    }

    /** Category the last channel was watched in, so Resume can reopen that list. */
    fun getLastLiveCategoryId(): String? {
        return prefs.getString(scoped(KEY_LAST_LIVE_CATEGORY_ID), null)
    }

    fun setLastLiveCategoryId(categoryId: String?) {
        prefs.edit().putString(scoped(KEY_LAST_LIVE_CATEGORY_ID), categoryId).apply()
    }

    /**
     * Per-provider KEYS rather than a per-provider file, because this one file also holds the
     * genuinely global settings — the app language, the playback preferences, the Live layout. Those
     * must not fork per server, so only the two ids that belong to a provider are scoped.
     *
     * The unscoped key stays readable for the provider that was active before this shipped, which
     * is what stops an update from forgetting the channel somebody was watching.
     */
    private fun scoped(key: String): String {
        val fingerprint = ServerScopedPrefs.activeFingerprint(context)
        return if (fingerprint == ServerIdentity.NONE) key else "${key}_$fingerprint"
    }

    /**
     * M0: which UI layout to render — "auto" (detect the device), "tv" or "mobile".
     * Presentation only; it never gates data, playback or licensing.
     */
    fun getUiModeOverride(): String {
        return prefs.getString(KEY_UI_MODE_OVERRIDE, DEFAULT_UI_MODE_OVERRIDE) ?: DEFAULT_UI_MODE_OVERRIDE
    }

    fun setUiModeOverride(value: String) {
        prefs.edit().putString(KEY_UI_MODE_OVERRIDE, value).apply()
    }

    /** True until the one-time chooser has been answered on an ambiguous device. */
    fun isUiModeChooserPending(): Boolean {
        return getUiModeOverride() == DEFAULT_UI_MODE_OVERRIDE &&
            !prefs.getBoolean(KEY_UI_MODE_CHOOSER_SHOWN, false)
    }

    fun markUiModeChooserShown() {
        prefs.edit().putBoolean(KEY_UI_MODE_CHOOSER_SHOWN, true).apply()
    }

    companion object {
        private const val PREFS_NAME = "iptv_settings"
        private const val KEY_UI_MODE_OVERRIDE = "ui_mode_override"
        private const val KEY_UI_MODE_CHOOSER_SHOWN = "ui_mode_chooser_shown"
        private const val DEFAULT_UI_MODE_OVERRIDE = "auto"
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

