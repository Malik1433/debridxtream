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

        val iptvApplied = applyIptvConfig(context, data["iptv"] as? Map<*, *>)

        val debridPrefs = DebridPreferences(context.applicationContext)
        applyDebridConfig(debridPrefs, data["debridConfig"] as? Map<*, *>)
        applyLegacyDebridFields(debridPrefs, data)

        return iptvApplied
    }

    // Save IPTV config with loggedIn=false so LoginFragment triggers its own validation/login flow.
    private fun applyIptvConfig(context: Context, iptv: Map<*, *>?): Boolean {
        if (iptv == null) return false
        val server = iptv["url"] as? String
        val username = iptv["username"] as? String
        val password = iptv["password"] as? String

        val safeServer = server?.let { url -> CompanionUrlValidator.normalizeSafeHttpUrl(url) }
        if (safeServer == null || username == null || password == null) return false
        CredentialsPreferences(context.applicationContext)
            .saveSyncedCredentials(safeServer, username, password)
        return true
    }

    // New schema: debridConfig { token, mediaFusionUrl, stremioAddonUrls }
    private fun applyDebridConfig(debridPrefs: DebridPreferences, cfg: Map<*, *>?) {
        if (cfg == null) return
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
    private fun applyLegacyDebridFields(debridPrefs: DebridPreferences, data: Map<String, Any>) {
        val debridTokenLegacy = data["debrid"] as? String
        val mediafusionLegacy = data["mediafusion"] as? String
        val stremioUrlsLegacy = data["stremioAddonUrls"] as? List<*>

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
    }
}
