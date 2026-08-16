package com.tvonnet.debridxtreamiptv.data.prefs

import android.content.Context
import android.content.SharedPreferences
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.channels.awaitClose
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class HomePreferences @Inject constructor(
    @ApplicationContext context: Context
) {
    // Option A: these are category ids, and a category id belongs to the provider that issued it —
    // kept across a switch the customer's chosen rows come back empty. Scoped per provider so each
    // server remembers the rows chosen for it.
    private val prefs: SharedPreferences =
        ServerScopedPrefs.open(context, "home_config", ServerScopedPrefs.activeFingerprint(context))

    companion object {
        private const val KEY_SELECTED_MOVIE_CATS = "home_movie_categories"
        private const val KEY_SELECTED_SERIES_CATS = "home_series_categories"
        private const val KEY_SELECTED_LIVE_CATS = "home_live_categories"
    }

    fun getSelectedMovieCategories(): Set<String> {
        return prefs.getStringSet(KEY_SELECTED_MOVIE_CATS, emptySet()) ?: emptySet()
    }

    fun setSelectedMovieCategories(ids: Set<String>) {
        prefs.edit().putStringSet(KEY_SELECTED_MOVIE_CATS, ids).apply()
    }

    fun getSelectedSeriesCategories(): Set<String> {
        return prefs.getStringSet(KEY_SELECTED_SERIES_CATS, emptySet()) ?: emptySet()
    }

    fun setSelectedSeriesCategories(ids: Set<String>) {
        prefs.edit().putStringSet(KEY_SELECTED_SERIES_CATS, ids).apply()
    }

    fun getSelectedLiveCategories(): Set<String> {
        return prefs.getStringSet(KEY_SELECTED_LIVE_CATS, emptySet()) ?: emptySet()
    }

    fun setSelectedLiveCategories(ids: Set<String>) {
        prefs.edit().putStringSet(KEY_SELECTED_LIVE_CATS, ids).apply()
    }

    /**
     * S2: forget which categories the Home rows are built from. They are category ids issued by
     * ONE provider — kept across a switch, the customer's chosen rows come back empty.
     */
    fun clearSelectedCategories() {
        prefs.edit().clear().apply()
    }

    // Expose flow to notify ViewModel of changes
    fun getCategoriesChangedFlow(): kotlinx.coroutines.flow.Flow<Unit> = kotlinx.coroutines.flow.callbackFlow {
        val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
            if (key == KEY_SELECTED_MOVIE_CATS || key == KEY_SELECTED_SERIES_CATS || key == KEY_SELECTED_LIVE_CATS) {
                trySend(Unit)
            }
        }
        prefs.registerOnSharedPreferenceChangeListener(listener)
        awaitClose { prefs.unregisterOnSharedPreferenceChangeListener(listener) }
    }
}
