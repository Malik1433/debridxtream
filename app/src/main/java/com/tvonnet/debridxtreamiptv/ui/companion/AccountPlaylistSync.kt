package com.tvonnet.debridxtreamiptv.ui.companion

import android.content.Context
import android.util.Log
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.ListenerRegistration
import com.tvonnet.debridxtreamiptv.data.licensing.LicenseManager
import com.tvonnet.debridxtreamiptv.data.prefs.CredentialsPreferences
import com.tvonnet.debridxtreamiptv.data.prefs.SettingsPreferences
import com.tvonnet.debridxtreamiptv.network.CompanionUrlValidator

/** One IPTV source on the customer's account, as the TV sees it. */
data class AccountPlaylist(
    val id: String,
    val name: String,
    val url: String,
    val username: String,
    val password: String,
    val enabled: Boolean,
    val isXtream: Boolean,
    val createdAtMs: Long,
    /** installId this playlist is addressed to; empty means every device on the account. */
    val assignedTo: String,
)

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
 * **One server per device, and switching is supported.** The account may hold several playlists;
 * each is addressed to a device (or to all of them) and a device runs exactly the one that applies
 * to it. Re-addressing a playlist therefore MOVES that device to another provider.
 *
 * This comment used to claim the opposite — "switching servers on a device is deliberately
 * impossible" — and it was wrong in the way that matters: nothing prevented it, the credentials
 * were simply rewritten and the data left alone. The catalogue, favourites, watch history and
 * watched state are keyed by stream id with no record of the provider, so the device kept serving
 * the previous server's rows against the new server's ids. That is fixed by S1-S4, not by
 * pretending the door is locked:
 *
 *  - [CredentialsPreferences.isServerDataStale] answers "does the data on disk still belong to the
 *    server we point at" — from state, so an interrupted switch is retried,
 *  - `ServerDataReset` clears the previous provider's data, and
 *  - `InitialSyncFragment` runs it before the new sync writes anything, telling the customer which
 *    provider they were moved to.
 *
 * **The flag defaults ON.** The comment here used to say it "ships dark"; the default in
 * [SettingsPreferences.isAccountPlaylistSyncEnabled] has been `true`. The flag remains as an
 * off-switch, and the old companion path is untouched and keeps working either way.
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

    /** Latest snapshot of the account's playlists. Written from the Firestore listener (main thread). */
    @Volatile
    private var available: List<AccountPlaylist> = emptyList()

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

        // The listener STAYS attached for the app's lifetime rather than resolving once (§8.1).
        // Identity here is not fixed: the device starts anonymous, and signing in for premium
        // replaces that uid with the customer's own. Binding a single time would leave the sync
        // pointed at an identity that no longer exists — playlists would stop with no error, on a
        // device that was working a second earlier.
        val listener = FirebaseAuth.AuthStateListener { a -> resolveOwner(appContext, a.currentUser) }
        authListener = listener
        auth.addAuthStateListener(listener) // fires immediately with the current state
    }

    /**
     * Works out whose playlists this device should be reading, for either kind of identity.
     *
     * Signed in as a real user, the owner IS the caller — no indirection needed, and the rules let
     * them read their own playlists directly. Anonymous, we are a claimed TV and the owner is
     * whoever `device_auth` says claimed us.
     */
    private fun resolveOwner(appContext: Context, user: com.google.firebase.auth.FirebaseUser?) {
        bindingListener?.remove(); bindingListener = null
        playlistListener?.remove(); playlistListener = null

        if (user == null) return
        if (!user.isAnonymous) {
            Log.i(TAG, "signed-in account - reading its playlists directly")
            watchPlaylists(appContext, user.uid)
            return
        }
        bindingListener = FirebaseFirestore.getInstance()
            .collection("device_auth").document(user.uid)
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

                val installId = LicenseManager.getInstance(appContext).installId
                available = docs.mapNotNull { doc ->
                    val url = doc.getString("url") ?: return@mapNotNull null
                    // Blank deviceId means "every device" — that is what keeps playlists saved
                    // before assignment existed working, and what lets one provider serve a whole
                    // household without being assigned to each TV in turn.
                    val assignedTo = doc.getString("deviceId").orEmpty()
                    if (assignedTo.isNotEmpty() && assignedTo != installId) return@mapNotNull null
                    AccountPlaylist(
                        assignedTo = assignedTo,
                        id = doc.id,
                        name = doc.getString("name").orEmpty().ifBlank { hostOf(url) },
                        url = url,
                        username = doc.getString("username").orEmpty(),
                        password = doc.getString("password").orEmpty(),
                        enabled = doc.getBoolean("enabled") != false,
                        isXtream = doc.getString("type") != "m3u",
                        // Sorted by the same key the phone sorts by, so "the first one" means the
                        // same thing in both places. Firestore's default order is by document id,
                        // which would have made the TV's fallback pick something the customer never
                        // saw at the top of their list.
                        createdAtMs = doc.getTimestamp("createdAt")?.toDate()?.time ?: Long.MAX_VALUE,
                    )
                }.sortedBy { it.createdAtMs }
                applyActive(appContext)
            }
    }

    /**
     * Applies the one playlist this TV should run. **Most specific assignment wins**: a playlist
     * addressed to this device beats one marked "all devices".
     *
     * There is deliberately no way to switch servers on a device. Watch history, favourites and the
     * catalogue are all keyed by stream id with no record of which provider it came from — so
     * switching would leave Continue Watching full of entries that belong to the other server and
     * cannot play. One server per device is what this app's data layer actually supports; the
     * account holds several so that DIFFERENT TVs can run different ones, which is safe because each
     * device only ever sees one.
     */
    private fun applyActive(appContext: Context) {
        val usable = available.filter { it.enabled && it.isXtream }
        val installId = LicenseManager.getInstance(appContext).installId
        val chosen = usable.firstOrNull { it.assignedTo == installId }
            ?: usable.firstOrNull { it.assignedTo.isEmpty() }
            ?: return
        apply(appContext, chosen.url, chosen.username, chosen.password, chosen.name)
    }

    private fun hostOf(url: String): String =
        runCatching { android.net.Uri.parse(url).host }.getOrNull()?.takeIf { it.isNotBlank() } ?: url

    /**
     * Writes the credentials only when they actually differ.
     *
     * A snapshot listener re-fires for reasons that have nothing to do with the content — cache
     * replays, metadata changes, reconnects. Writing every time would reset `loggedIn` on each one
     * and bounce a perfectly happy install back through the login flow.
     */
    private fun apply(
        appContext: Context,
        url: String?,
        username: String?,
        password: String?,
        label: String,
    ) {
        val safeUrl = url?.let { CompanionUrlValidator.normalizeSafeHttpUrl(it) } ?: return
        if (username.isNullOrBlank() || password.isNullOrBlank()) return

        val prefs = CredentialsPreferences(appContext)
        val unchanged = prefs.getServerUrl() == safeUrl &&
            prefs.getUsername() == username &&
            prefs.getPassword() == password
        if (unchanged) return

        prefs.saveSyncedCredentials(safeUrl, username, password)
        // The name the customer gave this playlist on the portal, so the device can tell them which
        // one it just moved to rather than showing them a hostname they may not recognise.
        prefs.serverLabel = label
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
