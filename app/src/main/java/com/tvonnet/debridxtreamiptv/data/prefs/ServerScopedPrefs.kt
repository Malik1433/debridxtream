package com.tvonnet.debridxtreamiptv.data.prefs

import android.content.Context
import android.content.SharedPreferences
import android.util.Log

/**
 * A preference file that belongs to ONE provider (Option A).
 *
 * The catalogue moved to a per-provider database; these are the stores that are not in it and are
 * just as provider-specific — Continue Watching, the Recent Live list, the Home rows the customer
 * chose, and how fresh the last sync was. Keeping one copy of those across three servers is what
 * made a switch destructive in the first place.
 *
 * Scoped by FILE NAME rather than by prefixing every key, so the classes that own these stores keep
 * their key constants and their call sites exactly as they are. Fourteen places construct
 * `WatchHistoryPreferences` directly; none of them had to learn about providers.
 */
object ServerScopedPrefs {

    private const val TAG = "ServerScopedPrefs"

    /** @return the file for this provider, or the plain name when there is no provider yet. */
    fun name(base: String, fingerprint: String): String =
        if (fingerprint == ServerIdentity.NONE) base else "${base}_$fingerprint"

    /**
     * Opens this provider's file, handing it the device's existing values the first time.
     *
     * Without the hand-over an app UPDATE would look exactly like data loss: Continue Watching
     * empty, Home rows unchosen, on a device where nothing actually changed.
     *
     * COPIED rather than renamed, unlike the database. These files are a few kilobytes, and the
     * framework caches SharedPreferences instances by name — renaming one on disk behind its back
     * is a good way to have two objects disagree about what is stored.
     */
    fun open(context: Context, base: String, fingerprint: String): SharedPreferences {
        val scopedName = name(base, fingerprint)
        val scoped = context.getSharedPreferences(scopedName, Context.MODE_PRIVATE)
        if (scopedName == base || scoped.all.isNotEmpty()) return scoped

        val legacy = context.getSharedPreferences(base, Context.MODE_PRIVATE)
        if (legacy.all.isEmpty()) return scoped
        runCatching { copy(legacy, scoped) }
            .onSuccess { Log.i(TAG, "Handed the existing $base to the active provider") }
            .onFailure { Log.w(TAG, "Could not hand over $base", it) }
        return scoped
    }

    /**
     * Type dispatch, because SharedPreferences has no generic put. A value of a type not listed is
     * skipped rather than guessed at — losing one unknown preference is better than writing a
     * wrong one.
     */
    private fun copy(from: SharedPreferences, to: SharedPreferences) {
        val editor = to.edit()
        from.all.forEach { (key, value) ->
            when (value) {
                is String -> editor.putString(key, value)
                is Int -> editor.putInt(key, value)
                is Long -> editor.putLong(key, value)
                is Float -> editor.putFloat(key, value)
                is Boolean -> editor.putBoolean(key, value)
                is Set<*> -> editor.putStringSet(key, value.filterIsInstance<String>().toSet())
                else -> Log.w(TAG, "Skipped $key: unsupported type")
            }
        }
        editor.apply()
    }

    /** The active provider, read straight from the credentials — the one source for this answer. */
    fun activeFingerprint(context: Context): String =
        CredentialsPreferences(context.applicationContext).activeServerFingerprint
}
