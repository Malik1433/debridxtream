// Deprecated-API suppression: see data/parental/ParentalControls.kt for why security-crypto's
// EncryptedSharedPreferences is still used after 1.1.0 deprecated it.
@file:Suppress("DEPRECATION")

package com.tvonnet.debridxtreamiptv.data.prefs

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey

class CredentialsPreferences(private val context: Context) {
    private val prefs: SharedPreferences = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    private val identityPrefs = IdentityPreferences(context)

    // Phase 5: the sensitive credential values (server_url / username / password / logged_in) live in
    // an ENCRYPTED prefs file. If EncryptedSharedPreferences can't be created (its AndroidKeyStore
    // path is flaky on some Fire OS builds), fall back to the plain prefs so the user is never
    // crashed or locked out — no worse than before this change.
    //
    // 2026-09-05: security-crypto went 1.1.0-alpha06 -> 1.1.0 STABLE (an alpha crypto library was
    // holding these credentials), and the same release deprecated this API. It still works and
    // AndroidX offers no replacement, so the suppression is deliberate and the reason lives next to
    // the other call site in ParentalControls. Read-compatibility with prefs written by alpha06 was
    // verified on the logged-in Fire TV: the session survived the upgrade.
    private val credsPrefs: SharedPreferences = try {
        val masterKey = MasterKey.Builder(context)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()
        EncryptedSharedPreferences.create(
            context,
            SECURE_PREFS_NAME,
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
        )
    } catch (e: Exception) {
        android.util.Log.w("CredentialsPrefs", "EncryptedSharedPreferences unavailable; using plain fallback", e)
        prefs
    }

    init {
        identityPrefs.migrateFromLegacy(
            deviceId = prefs.getString(KEY_DEVICE_ID, null),
            syncCode = prefs.getString(KEY_SYNC_CODE, null)
        )
        // One-time migration: move any legacy PLAINTEXT credentials into the encrypted store, then
        // scrub them from the plain file. Only runs when encryption is actually active
        // (credsPrefs !== prefs) and the legacy plaintext password is still present.
        if (credsPrefs !== prefs && prefs.contains(KEY_PASSWORD)) {
            credsPrefs.edit()
                .putString(KEY_SERVER_URL, prefs.getString(KEY_SERVER_URL, null))
                .putString(KEY_USERNAME, prefs.getString(KEY_USERNAME, null))
                .putString(KEY_PASSWORD, prefs.getString(KEY_PASSWORD, null))
                .putBoolean(KEY_LOGGED_IN, prefs.getBoolean(KEY_LOGGED_IN, false))
                .apply()
            prefs.edit()
                .remove(KEY_SERVER_URL).remove(KEY_USERNAME).remove(KEY_PASSWORD).remove(KEY_LOGGED_IN)
                .apply()
        }
        adoptCurrentServerOnFirstRun()
    }

    // ==========================================================================================
    // Which provider the data on this device belongs to (S1).
    //
    // Five call sites change the server — the login form, the account playlist sync, the companion
    // HTTP server, remote pairing and the companion applier — and every one of them goes through
    // the two save methods below. So the answer is derived HERE, at the single place a credential
    // can change, rather than at each door: a sixth door added later inherits it for free.
    //
    // Two fingerprints, not one, and that is the whole design. [activeServerFingerprint] is what
    // the app is pointed at *now*; [getSyncedServerFingerprint] is what the catalogue, favourites
    // and history on disk actually belong to. A switch is simply the two disagreeing, which means
    // it survives being interrupted: kill the app halfway through and the disagreement is still
    // there on the next launch, so the cleanup runs again instead of leaving a half-swapped device.
    // ==========================================================================================

    /** The provider the stored credentials point at. Derived, never stored — it cannot go stale. */
    val activeServerFingerprint: String
        get() = ServerIdentity.fingerprint(getServerUrl(), getUsername())

    /**
     * What to CALL the active provider when telling the customer they have been moved to it.
     *
     * Only the account sync knows a name — the customer typed it on the portal. Everything else
     * (the login form, a QR pairing) has nothing but a URL, so this is blank there and the caller
     * falls back to the host. Stored in plain prefs: it is a label the customer chose, not a
     * credential.
     */
    var serverLabel: String
        get() = prefs.getString(KEY_SERVER_LABEL, "").orEmpty()
        set(value) {
            prefs.edit().putString(KEY_SERVER_LABEL, value).apply()
        }

    /**
     * The server the customer picked ON THIS DEVICE, by playlist id — or blank to follow the
     * account's own assignment.
     *
     * A5: without this the picker would not survive its own success. The account decides which
     * playlist a device runs (assigned to it, else the one marked for all devices), and that
     * decision is re-applied on every Firestore snapshot — so choosing another server in Settings
     * would hold until the next snapshot and then silently flip back.
     *
     * It is a preference of the DEVICE, not of the account: the customer is standing in front of
     * this television choosing what THIS one plays, and their phone should not be moved with it.
     */
    var chosenPlaylistId: String
        get() = prefs.getString(KEY_CHOSEN_PLAYLIST, "").orEmpty()
        set(value) {
            prefs.edit().putString(KEY_CHOSEN_PLAYLIST, value).apply()
        }

    /** The provider the on-device data belongs to. */
    fun getSyncedServerFingerprint(): String =
        prefs.getString(KEY_SYNCED_SERVER, ServerIdentity.NONE) ?: ServerIdentity.NONE

    /**
     * Does the data on disk belong to somebody else? True after a server change and until the
     * cleanup has run, and true after a logout (no server owns the data then either).
     */
    fun isServerDataStale(): Boolean = getSyncedServerFingerprint() != activeServerFingerprint

    /**
     * Declares that the on-device data now belongs to the active server. Called by the cleanup
     * once it has finished — never by a save, or the change it is meant to announce is erased by
     * the very write that caused it.
     */
    fun markServerDataSynced() {
        prefs.edit().putString(KEY_SYNCED_SERVER, activeServerFingerprint).apply()
    }

    /**
     * Forces BOTH preference files to disk synchronously, for the one caller that is about to
     * kill the process ([com.tvonnet.debridxtreamiptv.ui.ServerSwitch.restartInto]).
     *
     * Every setter here uses `apply()`, which persists on a background queue — and
     * `Runtime.getRuntime().exit(0)` does not wait for that queue. Without this flush the
     * credentials and playlist choice the customer just switched to could die with the process,
     * and the restart came back on the SAME server (owner hit it live, 2026-08-30). A `commit()`
     * writes the file's complete current in-memory state, so it also lands every earlier
     * apply()-ed change on that file.
     */
    fun flushBeforeRestart() {
        prefs.edit().putLong(KEY_FLUSHED_AT, System.currentTimeMillis()).commit()
        credsPrefs.edit().putLong(KEY_FLUSHED_AT, System.currentTimeMillis()).commit()
    }

    /**
     * Existing installs have no record of which server their data came from, and it is theirs —
     * they have only ever had one. Adopting it once, silently, is what stops an app UPDATE from
     * reading as a server switch and wiping every user's catalogue and Continue Watching.
     */
    private fun adoptCurrentServerOnFirstRun() {
        if (prefs.contains(KEY_SYNCED_SERVER)) return
        prefs.edit().putString(KEY_SYNCED_SERVER, activeServerFingerprint).apply()
    }


    fun saveCredentials(serverUrl: String, username: String, password: String) {
        credsPrefs.edit().apply {
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
        credsPrefs.edit().apply {
            putString(KEY_SERVER_URL, serverUrl)
            putString(KEY_USERNAME, username)
            putString(KEY_PASSWORD, password)
            putBoolean(KEY_LOGGED_IN, false) // Will be set to true after repository.login()
            apply()
        }
    }
    
    fun getServerUrl(): String? {
        return credsPrefs.getString(KEY_SERVER_URL, null)
    }
    
    fun getUsername(): String? {
        return credsPrefs.getString(KEY_USERNAME, null)
    }
    
    fun getPassword(): String? {
        return credsPrefs.getString(KEY_PASSWORD, null)
    }
    
    fun isLoggedIn(): Boolean {
        return credsPrefs.getBoolean(KEY_LOGGED_IN, false)
    }
    
    fun clearCredentials() {
        credsPrefs.edit().apply {
            remove(KEY_SERVER_URL)
            remove(KEY_USERNAME)
            remove(KEY_PASSWORD)
            remove(KEY_LOGGED_IN)
            apply()
        }
        prefs.edit().remove(KEY_SERVER_LABEL).apply()
    }

    /**
     * Preferred audio language used to bias source ordering on the Movie/Series
     * detail source panels. One of EN / HI / FR / ES / MULTI / ALL. "ALL" means
     * no preference (chip hidden, no language boost). Defaults to EN.
     */
    var preferredAudioLang: String
        get() = prefs.getString(KEY_PREFERRED_AUDIO_LANG, DEFAULT_PREFERRED_AUDIO_LANG)
            ?: DEFAULT_PREFERRED_AUDIO_LANG
        set(value) {
            prefs.edit().putString(KEY_PREFERRED_AUDIO_LANG, value).apply()
        }

    /**
     * H9: the SECONDARY priority — ranks a source when the primary language is not
     * available in it ("Hindi na mile to German"). "NONE" disables it.
     */
    var preferredAudioLang2: String
        get() = prefs.getString(KEY_PREFERRED_AUDIO_LANG_2, DEFAULT_PREFERRED_AUDIO_LANG_2)
            ?: DEFAULT_PREFERRED_AUDIO_LANG_2
        set(value) {
            prefs.edit().putString(KEY_PREFERRED_AUDIO_LANG_2, value).apply()
        }

    fun getSyncCode(): String? {
        return prefs.getString(KEY_SYNC_CODE, null) ?: identityPrefs.getLegacySyncCode()
    }

    fun saveSyncCode(code: String) {
        prefs.edit().putString(KEY_SYNC_CODE, code).apply()
        identityPrefs.saveLegacySyncCode(code)
    }


    // Expose for specialized cases
    fun getPrefs(): SharedPreferences = prefs

    /**
     * Returns a persistent unique device ID for pairing purposes.
     * Generates and stores a UUID on first access.
     *
     * @return A stable device identifier string.
     */
    fun getDeviceId(): String {
        val existing = prefs.getString(KEY_DEVICE_ID, null)
        if (existing != null) return existing
        val newId = identityPrefs.getOrCreateInstallInstanceId()
        prefs.edit().putString(KEY_DEVICE_ID, newId).apply()
        return newId
    }
    
    companion object {
        const val PREFS_NAME = "iptv_credentials"
        private const val SECURE_PREFS_NAME = "iptv_credentials_secure"
        const val KEY_SERVER_URL = "server_url"
        const val KEY_USERNAME = "username"
        const val KEY_PASSWORD = "password"
        const val KEY_LOGGED_IN = "logged_in"
        const val KEY_SYNC_CODE = "sync_code"
        /** Fingerprint of the provider the on-device data belongs to. Plain prefs: it is a digest. */
        const val KEY_SYNCED_SERVER = "synced_server_fingerprint"
        /** Written by [flushBeforeRestart] purely to force a synchronous full-file commit. */
        private const val KEY_FLUSHED_AT = "flushed_at"
        const val KEY_SERVER_LABEL = "server_label"
        const val KEY_CHOSEN_PLAYLIST = "chosen_playlist_id"
        const val KEY_DEVICE_ID = "device_id"
        const val KEY_PREFERRED_AUDIO_LANG = "preferred_audio_lang"
        const val DEFAULT_PREFERRED_AUDIO_LANG = "EN"
        const val KEY_PREFERRED_AUDIO_LANG_2 = "preferred_audio_lang_2"
        const val DEFAULT_PREFERRED_AUDIO_LANG_2 = "NONE"
        private const val KEY_IPTV_EXP_DATE = "iptv_exp_date"
        private const val KEY_IPTV_EXP_CACHED_AT = "iptv_exp_cached_at"
        private const val KEY_IPTV_EXP_FOR = "iptv_exp_fingerprint"
        // The floor between background revalidations, NOT a validity window. The chip paints from
        // cache instantly and refreshes behind it; this only stops a browse session that hops
        // Home ↔ sections every few seconds from firing a `login` call per hop — some Xtream
        // panels rate-limit exactly that call.
        private const val IPTV_EXP_REFRESH_FLOOR_MS = 15L * 60 * 1000
    }

    /**
     * The cached Xtream account expiry (epoch seconds) — ONLY if it belongs to the provider the
     * credentials currently point at. It used to be a bare 12h TTL, and a server switch inside
     * that window showed the OLD provider's date on the new provider's Home for up to 12 hours
     * (the owner caught it live). Keyed by [activeServerFingerprint] a switch misses the cache by
     * construction and the new server is asked immediately.
     */
    fun getCachedIptvExpiry(): Long? {
        if (prefs.getString(KEY_IPTV_EXP_FOR, null) != activeServerFingerprint) return null
        return prefs.getLong(KEY_IPTV_EXP_DATE, 0L).takeIf { it > 0L }
    }

    /**
     * True while the last refresh is younger than [IPTV_EXP_REFRESH_FLOOR_MS] — the caller may
     * skip the background revalidate, nothing more. An extension on the SAME server therefore
     * shows within one Home visit after at most 15 minutes, instead of the old 12 hours.
     */
    fun isIptvExpiryFresh(): Boolean =
        prefs.getString(KEY_IPTV_EXP_FOR, null) == activeServerFingerprint &&
            System.currentTimeMillis() - prefs.getLong(KEY_IPTV_EXP_CACHED_AT, 0L) <
            IPTV_EXP_REFRESH_FLOOR_MS

    fun saveIptvExpiry(expEpochSeconds: Long) {
        prefs.edit()
            .putLong(KEY_IPTV_EXP_DATE, expEpochSeconds)
            .putLong(KEY_IPTV_EXP_CACHED_AT, System.currentTimeMillis())
            .putString(KEY_IPTV_EXP_FOR, activeServerFingerprint)
            .apply()
    }
}

