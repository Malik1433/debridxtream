package com.tvonnet.debridxtreamiptv.data.prefs

import android.content.Context
import android.content.SharedPreferences

/**
 * Local cache of the device's licensing state so the activation gate can decide
 * instantly on launch (before the Firestore listener returns) and so a temporary
 * network outage never locks out an already-activated device.
 *
 * Source of truth is Firestore `licenses/{installId}` (see LicenseManager); this is
 * only a mirror of the last value we observed.
 */
class LicensePreferences(context: Context) {

    private val prefs: SharedPreferences =
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    /** Last observed status: "pending" | "active" | "inactive". Unknown before first sync. */
    var status: String
        get() = prefs.getString(KEY_STATUS, STATUS_UNKNOWN) ?: STATUS_UNKNOWN
        set(value) = prefs.edit().putString(KEY_STATUS, value).apply()

    /** "normal" | "premium". */
    var tier: String
        get() = prefs.getString(KEY_TIER, TIER_NORMAL) ?: TIER_NORMAL
        set(value) = prefs.edit().putString(KEY_TIER, value).apply()

    /** Epoch millis; 0 means no expiry. */
    var expiresAt: Long
        get() = prefs.getLong(KEY_EXPIRES_AT, 0L)
        set(value) = prefs.edit().putLong(KEY_EXPIRES_AT, value).apply()

    /** Server-side registration time of the license doc (anchors the 7-day trial). */
    var createdAt: Long
        get() = prefs.getLong(KEY_CREATED_AT, 0L)
        set(value) = prefs.edit().putLong(KEY_CREATED_AT, value).apply()

    /** When the doc was last confirmed active (for future offline-grace tightening). */
    var lastActiveAt: Long
        get() = prefs.getLong(KEY_LAST_ACTIVE_AT, 0L)
        set(value) = prefs.edit().putLong(KEY_LAST_ACTIVE_AT, value).apply()

    /** Whether the license doc has ever been created for this install. */
    var docCreated: Boolean
        get() = prefs.getBoolean(KEY_DOC_CREATED, false)
        set(value) = prefs.edit().putBoolean(KEY_DOC_CREATED, value).apply()

    /**
     * Global enforcement switch, mirrored from `app_config/licensing.enforce`.
     * Defaults to FALSE (fail-open) so shipping the gate never locks anyone out until
     * the owner explicitly turns enforcement on — and a Firestore outage can never brick
     * the app.
     */
    var enforce: Boolean
        get() = prefs.getBoolean(KEY_ENFORCE, false)
        set(value) = prefs.edit().putBoolean(KEY_ENFORCE, value).apply()

    /** True when the last known state grants access (active + not expired). */
    fun isCurrentlyEntitled(now: Long = System.currentTimeMillis()): Boolean {
        if (status != STATUS_ACTIVE) return false
        val exp = expiresAt
        return exp <= 0L || exp > now
    }

    fun isPremium(): Boolean = isCurrentlyEntitled() && tier == TIER_PREMIUM

    companion object {
        const val PREFS_NAME = "license_prefs"
        private const val KEY_STATUS = "status"
        private const val KEY_TIER = "tier"
        private const val KEY_EXPIRES_AT = "expires_at"
        private const val KEY_LAST_ACTIVE_AT = "last_active_at"
        private const val KEY_DOC_CREATED = "doc_created"
        private const val KEY_ENFORCE = "enforce"
        private const val KEY_CREATED_AT = "created_at"

        const val STATUS_UNKNOWN = "unknown"
        const val STATUS_PENDING = "pending"
        const val STATUS_ACTIVE = "active"
        const val STATUS_INACTIVE = "inactive"
        const val TIER_NORMAL = "normal"
        const val TIER_PREMIUM = "premium"
    }
}
