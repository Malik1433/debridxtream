// androidx.security-crypto reached 1.1.0 STABLE on 2026-09-05 (it had been shipping as
// 1.1.0-alpha06 - an alpha crypto library holding real credentials) and the SAME release deprecated
// EncryptedSharedPreferences / MasterKey. It still works, AndroidX ships no replacement, and all
// four stores in this app depend on the identical scheme, so the suppression is deliberate:
// replacing the storage layer is its own planned change with its own migration, not a drive-by.
// The file-level form is needed because the deprecation also fires on the imports.
@file:Suppress("DEPRECATION")

package com.tvonnet.debridxtreamiptv.data.parental

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import com.tvonnet.debridxtreamiptv.data.model.IptvCache
import com.tvonnet.debridxtreamiptv.data.model.XtreamCategory
import com.tvonnet.debridxtreamiptv.data.model.XtreamSeriesInfo
import com.tvonnet.debridxtreamiptv.data.model.XtreamStream
import com.tvonnet.debridxtreamiptv.data.model.XtreamVodInfo
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Parental controls: the stored half ([ParentalPolicy] is the pure half) — 2026-09-03.
 *
 * Storage is the app's encrypted preferences (the same MasterKey scheme the credential stores
 * use); what is stored is a salt and a hash, never the PIN. Adult category ids are remembered
 * as categories pass through [categories], so the stream-level filters ([liveStreams] …) can
 * hide a channel whose category is hidden without a second lookup. The set is per provider by
 * construction: it is rebuilt from whatever category list the current server returns.
 *
 * Wired at the data facade (`XtreamRepository`) so every surface agrees — see [ParentalPolicy].
 */
@Singleton
class ParentalControls @Inject constructor(@ApplicationContext private val context: Context) {

    private val policy = ParentalPolicy()

    /**
     * Encrypted, with a plain fallback (2026-09-05).
     *
     * This used to call `EncryptedSharedPreferences.create` unguarded. `ParentalControls` is
     * injected into `XtreamRepository`, so on a device where the AndroidKeyStore path fails — which
     * the credential store has a comment about, having seen it on Fire OS — the throw came out of
     * the DATA FACADE and took the app with it. A crash is strictly worse than either alternative,
     * and the credential store already answers this the same way.
     *
     * The trade-off, stated rather than hidden: on the fallback the PIN lands in plain prefs. What
     * is stored is a SALTED SHA-256 of the PIN, never the PIN, so the file is not a password
     * either way — see [ParentalPolicy]. A device that once had encryption and later cannot get it
     * reads the plain file instead and looks unconfigured; it does not lock anybody out, and it is
     * recoverable by setting the PIN again.
     *
     * DEPRECATION: androidx.security-crypto reached 1.1.0 stable and deprecated this API in the
     * same release. It still works, there is no AndroidX replacement, and this app's credential
     * store depends on the identical scheme — so both call sites are suppressed HERE with the
     * reason, and replacing the storage layer is its own planned change, not a drive-by.
     */
    private val prefs: SharedPreferences by lazy {
        try {
            val masterKey = MasterKey.Builder(context).setKeyScheme(MasterKey.KeyScheme.AES256_GCM).build()
            EncryptedSharedPreferences.create(
                context,
                PREFS_NAME,
                masterKey,
                EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
            )
        } catch (e: Exception) {
            android.util.Log.w(TAG, "EncryptedSharedPreferences unavailable; using plain fallback", e)
            context.getSharedPreferences(PREFS_NAME + "_plain", Context.MODE_PRIVATE)
        }
    }

    @Volatile private var adultCategoryIds: Set<String> = emptySet()

    // ── state ────────────────────────────────────────────────────────────────

    val isEnabled: Boolean get() = prefs.getBoolean(KEY_ENABLED, false) && hasPin

    val hasPin: Boolean get() = !prefs.getString(KEY_HASH, null).isNullOrEmpty()

    val isUnlocked: Boolean get() = policy.isUnlocked

    /** True when adult categories are currently hidden from every list. */
    val hidesAdult: Boolean get() = policy.hidesAdult(isEnabled)

    fun setEnabled(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_ENABLED, enabled).apply()
        if (!enabled) policy.lockNow()
    }

    /** Stores a new PIN (salt + hash) and turns the controls on. */
    fun setPin(pin: String): Boolean {
        if (!ParentalPolicy.isValidPin(pin)) return false
        val salt = ParentalPolicy.newSalt()
        prefs.edit()
            .putString(KEY_SALT, salt)
            .putString(KEY_HASH, ParentalPolicy.hashPin(salt, pin))
            .putBoolean(KEY_ENABLED, true)
            .apply()
        policy.lockNow()
        return true
    }

    fun verifyPin(pin: String): Boolean =
        ParentalPolicy.matches(prefs.getString(KEY_HASH, null), prefs.getString(KEY_SALT, null), pin)

    /** A correct PIN opens the adult categories for this session; a wrong one changes nothing. */
    fun unlockWithPin(pin: String): Boolean {
        if (!verifyPin(pin)) return false
        policy.unlockForSession()
        return true
    }

    fun lockNow() = policy.lockNow()

    /** Removes the PIN and turns the controls off. */
    fun clear() {
        prefs.edit().remove(KEY_SALT).remove(KEY_HASH).putBoolean(KEY_ENABLED, false).apply()
        policy.lockNow()
    }

    // ── filters used by the data facade ─────────────────────────────────────

    fun categories(list: List<XtreamCategory>): List<XtreamCategory> {
        adultCategoryIds = adultCategoryIds + AdultContentDetector.adultIds(list, { it.category_id }, { it.category_name })
        val visible = policy.filterCategories(isEnabled, list) { it.category_name }
        // Counts only, never names: the release-build proof that the gate is doing its job.
        if (visible.size != list.size) Log.i(TAG, "hiding ${list.size - visible.size} of ${list.size} categories")
        return visible
    }

    fun liveStreams(list: List<XtreamStream>): List<XtreamStream> =
        policy.filterByCategory(isEnabled, list, adultCategoryIds) { it.category_id }

    fun vod(list: List<XtreamVodInfo>): List<XtreamVodInfo> =
        policy.filterByCategory(isEnabled, list, adultCategoryIds) { it.category_id }

    fun series(list: List<XtreamSeriesInfo>): List<XtreamSeriesInfo> =
        policy.filterByCategory(isEnabled, list, adultCategoryIds) { it.category_id }

    /** The raw cache, with the hidden categories and their streams removed. */
    fun cache(cache: IptvCache?): IptvCache? {
        if (cache == null || !hidesAdult) return cache
        return cache.copy(
            live = cache.live?.let { it.copy(categories = categories(it.categories), streams = liveStreams(it.streams)) },
            vod = cache.vod?.let { it.copy(categories = categories(it.categories), streams = vod(it.streams)) },
            series = cache.series?.let { it.copy(categories = categories(it.categories), streams = series(it.streams)) },
        )
    }

    private companion object {
        const val TAG = "ParentalControls"
        const val PREFS_NAME = "parental_controls"
        const val KEY_ENABLED = "enabled"
        const val KEY_SALT = "salt"
        const val KEY_HASH = "hash"
    }
}
