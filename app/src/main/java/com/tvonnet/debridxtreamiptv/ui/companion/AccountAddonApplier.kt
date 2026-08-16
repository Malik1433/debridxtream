package com.tvonnet.debridxtreamiptv.ui.companion

import android.util.Log
import com.tvonnet.debridxtreamiptv.data.prefs.DebridPreferences
import com.tvonnet.debridxtreamiptv.network.CompanionUrlValidator

/** One debrid link on the customer's account, as the device sees it. */
data class AccountAddon(
    val id: String,
    val name: String,
    val url: String,
    /** "addon" — a single Stremio manifest — or "registry" — a list of them. */
    val kind: String,
    val enabled: Boolean,
    /** installId this link is addressed to; empty means every device on the account. */
    val assignedTo: String,
)

/**
 * Turns the account's debrid links into this device's add-on settings (D3).
 *
 * The links used to reach a television one way only: somebody typed a hundred-character
 * `manifest.json` URL into Settings with a remote control. The phone-pairing path could already
 * carry them, but only while both devices were on the same network and only at pairing time — so a
 * link added later never arrived. The account can carry them at any time, to every device, which is
 * exactly what it already does for playlists.
 *
 * **Replace, not merge**, matching the pairing path: the account is the source of truth, so a link
 * REMOVED there has to disappear from the device. Merging would make removal impossible and quietly
 * leave a dead add-on on every television forever.
 *
 * Every URL goes through [CompanionUrlValidator] — the same gate the pairing path uses — so a
 * malformed or credential-bearing URL cannot reach preferences from this direction either.
 */
object AccountAddonApplier {

    private const val TAG = "AccountAddonApplier"
    const val KIND_REGISTRY = "registry"

    /**
     * Applies the links addressed to this device.
     *
     * @param installId this device's id, used to drop links meant for a different one.
     * @return true when something was written.
     */
    fun apply(prefs: DebridPreferences, all: List<AccountAddon>, installId: String): Boolean {
        val mine = all.filter { it.enabled && appliesTo(it, installId) }
        val addons = CompanionUrlValidator.normalizeSafeAddonUrls(
            mine.filter { it.kind != KIND_REGISTRY }.map { it.url }
        )
        val registries = CompanionUrlValidator.normalizeSafeAddonUrls(
            mine.filter { it.kind == KIND_REGISTRY }.map { it.url }
        )

        // An account with no links yet must not wipe what the customer set up ON the device before
        // this feature existed. Only an account that has ever had a link takes ownership of the
        // list — after that, an empty list is a real instruction and clearing is correct.
        if (all.isEmpty()) return false

        var wrote = false
        if (prefs.getStremioAddonUrls().toSet() != addons.toSet()) {
            prefs.setStremioAddonUrls(addons)
            Log.i(TAG, "Applied ${addons.size} add-on link(s) from the account")
            wrote = true
        }
        if (prefs.getAddonRegistryUrls() != registries.toSet()) {
            // Replace semantics spelled out, because DebridPreferences only offers add/remove.
            prefs.getAddonRegistryUrls().forEach { prefs.removeAddonRegistryUrl(it) }
            registries.forEach { prefs.addAddonRegistryUrl(it) }
            Log.i(TAG, "Applied ${registries.size} add-on list(s) from the account")
            wrote = true
        }
        return wrote
    }

    /** Most specific wins, as it does for playlists: addressed to this device, or to all of them. */
    private fun appliesTo(addon: AccountAddon, installId: String): Boolean =
        addon.assignedTo.isEmpty() || addon.assignedTo == installId
}
