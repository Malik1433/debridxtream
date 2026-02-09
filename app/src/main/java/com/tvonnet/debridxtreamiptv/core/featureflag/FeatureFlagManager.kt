package com.tvonnet.debridxtreamiptv.core.featureflag

import android.content.Context
import android.content.SharedPreferences

/**
 * Centralized feature flag manager.
 * Uses a memory cache for zero-latency access and SharedPreferences for persistence.
 */
object FeatureFlagManager {
    private const val PREF_NAME = "feature_flags"
    private const val KEY_SERIES_V2 = "enable_series_v2_engine"
    
    // Memory cache - Default to false (Safe Mode)
    // Publicly readable, privately writable to ensure consistency via setSeriesV2Enabled
    var isSeriesV2Enabled: Boolean = false
        private set

    private var preferences: SharedPreferences? = null

    /**
     * Initialize the FeatureFlagManager.
     * Should be called in Application.onCreate()
     */
    fun init(context: Context) {
        preferences = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
        // Load initial value from prefs, default false
        isSeriesV2Enabled = preferences?.getBoolean(KEY_SERIES_V2, false) ?: false
    }

    /**
     * Update the V2 Engine flag.
     * Updates both memory cache and SharedPreferences.
     */
    fun setSeriesV2Enabled(enabled: Boolean) {
        isSeriesV2Enabled = enabled
        preferences?.edit()?.putBoolean(KEY_SERIES_V2, enabled)?.apply()
    }
}
