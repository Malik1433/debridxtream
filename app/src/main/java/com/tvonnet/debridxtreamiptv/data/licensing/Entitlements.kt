package com.tvonnet.debridxtreamiptv.data.licensing

import android.content.Context

/**
 * Thin entitlement gate for premium-only features, reading the cached license
 * state (set live by [LicenseManager]).
 *
 * Tier policy (owner-defined): PREMIUM = IPTV + Debrid; NORMAL = IPTV only —
 * every Debrid surface (sidebar entry, home rows, settings category, routes)
 * must be hidden for normal devices. A device inside its 7-day trial gets the
 * full premium experience. With global enforcement OFF the app behaves as
 * premium (fail-open, consistent with the launch gate).
 */
object Entitlements {

    fun isPremium(context: Context): Boolean =
        LicenseManager.getInstance(context).isPremiumCached()

    /** Whether Debrid surfaces should exist at all for this device right now. */
    fun isDebridAllowed(context: Context): Boolean {
        val lm = LicenseManager.getInstance(context)
        return !lm.isEnforced() || lm.isPremiumCached() || lm.isTrialActive()
    }
}
