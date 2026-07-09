package com.tvonnet.debridxtreamiptv.ui.companion

import android.content.Context
import com.tvonnet.debridxtreamiptv.data.prefs.CredentialsPreferences
import com.tvonnet.debridxtreamiptv.data.prefs.DebridPreferences
import com.tvonnet.debridxtreamiptv.network.CompanionUrlValidator

/**
 * Applies a companion-sync payload (the Firestore `device_codes/<code>` document data)
 * to local preferences. Shared by [CompanionSetupActivity] and the login screen's
 * inline QR pairing overlay.
 */
object CompanionConfigApplier {

    /** @return true if an IPTV credential set was stored, i.e. auto-login can proceed. */
    fun apply(context: Context, data: Map<String, Any>?): Boolean {
        if (data == null) return false

        val iptv = data["iptv"] as? Map<*, *>
        val debridTokenLegacy = data["debrid"] as? String
        val mediafusionLegacy = data["mediafusion"] as? String
        val debridConfig = data["debridConfig"] as? Map<*, *>
        val stremioUrlsLegacy = data["stremioAddonUrls"] as? List<*>

        // Save IPTV config with loggedIn=false so LoginFragment triggers its own validation/login flow.
        var iptvApplied = false
        iptv?.let {
            val server = it["url"] as? String
            val username = it["username"] as? String
            val password = it["password"] as? String

            val safeServer = server?.let { url -> CompanionUrlValidator.normalizeSafeHttpUrl(url) }
            if (safeServer != null && username != null && password != null) {
                CredentialsPreferences(context.applicationContext)
                    .saveSyncedCredentials(safeServer, username, password)
                iptvApplied = true
            }
        }

        val debridPrefs = DebridPreferences(context.applicationContext)

        // New schema: debridConfig { token, mediaFusionUrl, stremioAddonUrls }
        debridConfig?.let { cfg ->
            val token = cfg["token"] as? String
            val mediaFusionUrl = cfg["mediaFusionUrl"] as? String
            val stremioAddonUrls = CompanionUrlValidator.normalizeSafeAddonUrls(
                (cfg["stremioAddonUrls"] as? List<*>)
                    ?.mapNotNull { it as? String }
            )

            if (!token.isNullOrBlank()) {
                debridPrefs.saveRealDebridToken(token)
            }
            val safeMediaFusionUrl = mediaFusionUrl?.let { CompanionUrlValidator.normalizeSafeHttpUrl(it) }
            if (safeMediaFusionUrl != null) {
                debridPrefs.saveMediaFusionUrl(safeMediaFusionUrl)
            }
            if (stremioAddonUrls.isNotEmpty()) {
                debridPrefs.setStremioAddonUrls(stremioAddonUrls)
            }
        }

        // Legacy fields (backward compatible)
        debridTokenLegacy?.takeIf { it.isNotBlank() }?.let { debridPrefs.saveRealDebridToken(it) }
        mediafusionLegacy
            ?.let { CompanionUrlValidator.normalizeSafeHttpUrl(it) }
            ?.let { debridPrefs.saveMediaFusionUrl(it) }
        val stremioAddonUrls = CompanionUrlValidator.normalizeSafeAddonUrls(
            stremioUrlsLegacy
                ?.mapNotNull { it as? String }
        )
        if (stremioAddonUrls.isNotEmpty()) {
            debridPrefs.setStremioAddonUrls(stremioAddonUrls)
        }

        return iptvApplied
    }
}
