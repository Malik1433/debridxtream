package com.tvonnet.debridxtreamiptv.data.licensing

import android.content.Context

/**
 * Thin entitlement gate for premium-only features. Reads the cached license tier
 * (set by [LicenseManager]). Wire specific premium surfaces behind [isPremium]
 * once the premium feature set is defined.
 */
object Entitlements {
    fun isPremium(context: Context): Boolean =
        LicenseManager.getInstance(context).isPremiumCached()
}
