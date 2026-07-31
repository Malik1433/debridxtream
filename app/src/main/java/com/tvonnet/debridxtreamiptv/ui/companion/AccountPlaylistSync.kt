package com.tvonnet.debridxtreamiptv.ui.companion

import android.content.Context
import android.util.Log
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.ListenerRegistration
import com.tvonnet.debridxtreamiptv.data.prefs.CredentialsPreferences
import com.tvonnet.debridxtreamiptv.data.prefs.SettingsPreferences
import com.tvonnet.debridxtreamiptv.network.CompanionUrlValidator

/**
 * Reads the customer's playlists from their ACCOUNT (§7 U6).
 *
 * This is the half of §7 that replaces the push channel. [CompanionConfigSync] waits for the phone
 * to write config into `device_codes` — a document any stranger who learns the device key can read.
 * Here the TV *reads* what its owner already has, and the read is allowed only because the account
 * claimed this device: rules resolve `device_auth/{ourAnonUid}` and let us see that owner's
 * playlists and nobody else's.
 *
 * Because the TV reads rather than being pushed to, an edit on the phone lands here on its own — no
 * second mechanism, no "push again" button.
 *
 * **Behind a flag, defaulting OFF.** It rewrites the credentials the app logs in with, which is the
 * single most disruptive thing that can be done to a working install, so it ships dark. The old
 * companion path is untouched and keeps working either way.
 */
object AccountPlaylistSync {

    private const val TAG = "AccountPlaylistSync"

    private var bindingListener: ListenerRegistration? = null
    private var playlistListener: ListenerRegistration? = null
    private var authListener: FirebaseAuth.AuthStateListener? = null
    private var started = false

    /**
     * Fired after credentials from the account are written, so a screen that is waiting on them can
     * act.
     *
     * Without this the login screen is where the whole feature dies quietly: the credentials land in
     * prefs, nothing tells the fragment, and the customer — who just added a playlist on their phone
     * — sits looking at a login form. The QR pairing overlay already solves this for the old path
     * with its `onPaired` callback; this is the same signal for the new one.
     */
    @Volatile
    var onCredentialsApplied: (() -> Unit)? = null

    /** Idempotent. Safe to call from `MainActivity.onCreate`; returns immediately. */
    fun start(context: Context) {
        if (started) return
        val appContext = context.applicationContext
        if (!SettingsPreferences(appContext).isAccountPlaylistSyncEnabled()) return
        started = true

        val auth = try {
            FirebaseAuth.getInstance()
        } catch (e: Exception) {
            Log.w(TAG, "auth unavailable", e); started = false; return
        }

        val uid = auth.currentUser?.uid
        if (uid != null) { bind(appContext, uid); return }

        // On the very first launch DeviceIdentity is still signing in, so waiting is the difference
        // between "works" and "works on the second launch". The listener removes itself as soon as
        // an identity appears — leaving it attached would be exactly the kind of stray callback that
        // has leaked fragments in this app before.
        val listener = FirebaseAuth.AuthStateListener { a ->
            val newUid = a.currentUser?.uid ?: return@AuthStateListener
            authListener?.let { a.removeAuthStateListener(it) }
            authListener = null
            bind(appContext, newUid)
        }
        authListener = listener
        auth.addAuthStateListener(listener)
    }

    private fun bind(appContext: Context, uid: String) {
        if (bindingListener != null) return
        bindingListener = FirebaseFirestore.getInstance()
            .collection("device_auth").document(uid)
            .addSnapshotListener { snap, e ->
                if (e != null) { Log.w(TAG, "binding listen failed", e); return@addSnapshotListener }
                val ownerUid = snap?.getString("ownerUid")
                if (ownerUid.isNullOrBlank()) {
                    // Not claimed (or released). Stop following an owner we no longer belong to.
                    playlistListener?.remove()
                    playlistListener = null
                    return@addSnapshotListener
                }
                watchPlaylists(appContext, ownerUid)
            }
    }

    private fun watchPlaylists(appContext: Context, ownerUid: String) {
        playlistListener?.remove()
        playlistListener = FirebaseFirestore.getInstance()
            .collection("playlists")
            // The equality filter is what satisfies the read rule — without it the query is
            // rejected wholesale rather than filtered.
            .whereEqualTo("ownerUid", ownerUid)
            .addSnapshotListener { snap, e ->
                if (e != null) { Log.w(TAG, "playlist listen failed", e); return@addSnapshotListener }
                val docs = snap?.documents ?: return@addSnapshotListener
                val chosen = docs.firstOrNull { doc ->
                    doc.getBoolean("enabled") != false && doc.getString("type") != "m3u"
                } ?: return@addSnapshotListener

                apply(
                    appContext,
                    url = chosen.getString("url"),
                    username = chosen.getString("username"),
                    password = chosen.getString("password"),
                )
            }
    }

    /**
     * Writes the credentials only when they actually differ.
     *
     * A snapshot listener re-fires for reasons that have nothing to do with the content — cache
     * replays, metadata changes, reconnects. Writing every time would reset `loggedIn` on each one
     * and bounce a perfectly happy install back through the login flow.
     */
    private fun apply(appContext: Context, url: String?, username: String?, password: String?) {
        val safeUrl = url?.let { CompanionUrlValidator.normalizeSafeHttpUrl(it) } ?: return
        if (username.isNullOrBlank() || password.isNullOrBlank()) return

        val prefs = CredentialsPreferences(appContext)
        val unchanged = prefs.getServerUrl() == safeUrl &&
            prefs.getUsername() == username &&
            prefs.getPassword() == password
        if (unchanged) return

        prefs.saveSyncedCredentials(safeUrl, username, password)
        Log.i(TAG, "applied playlist from account")
        runCatching { onCredentialsApplied?.invoke() }
            .onFailure { Log.w(TAG, "credentials-applied callback failed", it) }
    }

    fun stop() {
        playlistListener?.remove()
        playlistListener = null
        bindingListener?.remove()
        bindingListener = null
        authListener?.let { runCatching { FirebaseAuth.getInstance().removeAuthStateListener(it) } }
        authListener = null
        started = false
    }
}
