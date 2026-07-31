package com.tvonnet.debridxtreamiptv.data.licensing

import android.content.Context
import com.tvonnet.debridxtreamiptv.data.prefs.DebridPreferences

/**
 * What this device may use.
 *
 * **Tier policy changed 2026-08-01 (owner): there is only ONE tier.** Debrid is not sold separately
 * any more — a licensed device may use it, and whether it actually *does anything* depends on
 * something the customer controls: have they added their own debrid addons? They bring that service
 * themselves, exactly as they bring their IPTV subscription (§1).
 *
 * That splits one question into two that used to be conflated:
 *
 *  - [isDebridAllowed] — may Debrid surfaces exist? Now the same answer as "may this app run",
 *    because there is nothing extra to buy.
 *  - [isDebridConfigured] — has the customer supplied a debrid service? Drives labels and empty
 *    states. It must NOT hide anything: a customer who never sees Debrid never learns the app can
 *    do it.
 */
object Entitlements {

    fun isPremium(context: Context): Boolean =
        LicenseManager.getInstance(context).isPremiumCached()

    /**
     * Whether Debrid surfaces should exist at all.
     *
     * With one tier this follows the launch gate rather than a tier: a device entitled to run the
     * app is entitled to Debrid. Fail-open while enforcement is off, consistent with every other
     * gate here.
     */
    fun isDebridAllowed(context: Context): Boolean {
        val lm = LicenseManager.getInstance(context)
        return !lm.isEnforced() || lm.isEntitledCached()
    }

    /**
     * Whether the customer has actually supplied a debrid service — any Stremio addon, a
     * Real-Debrid token, or a MediaFusion URL.
     *
     * For labels and empty states, never for hiding. A surface that quietly vanishes teaches the
     * customer nothing; an empty one that says how to fill it teaches them everything.
     */
    fun isDebridConfigured(context: Context): Boolean {
        val prefs = DebridPreferences(context.applicationContext)
        return prefs.getStremioAddonUrls().isNotEmpty() ||
            !prefs.getRealDebridToken().isNullOrBlank() ||
            !prefs.getMediaFusionUrl().isNullOrBlank()
    }
}
