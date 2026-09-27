package com.tvonnet.debridxtreamiptv.data.prefs

import android.content.Context
import java.util.TimeZone

/**
 * The provider's own time zone, for catch-up: a timeshift request names its start in the SERVER's
 * local time ("2026-09-27:13-10"), not the device's.
 *
 * Kept in the provider's `sync_prefs` file (ServerScopedPrefs), which ServerDataReset already
 * clears on a server switch - so a switch can never pair the old provider's zone with the new
 * provider's streams (CLAUDE.md, "One device, one provider").
 */
object ServerClock {

    private const val FILE = "sync_prefs"
    private const val KEY_TIMEZONE = "server_timezone"

    /** Called with `server_info.timezone` on every successful login. Blank/unknown ids are ignored. */
    fun remember(context: Context, timezoneId: String?) {
        val id = timezoneId?.trim().orEmpty()
        if (parse(id) == null) return
        prefs(context).edit().putString(KEY_TIMEZONE, id).apply()
    }

    /** The provider's zone, or null when it never said (then catch-up is not offered). Disk read: call off the main thread. */
    fun zone(context: Context): TimeZone? = parse(prefs(context).getString(KEY_TIMEZONE, null))

    /** Pure: a real zone for [id], or null. TimeZone answers GMT for ids it does not know. */
    fun parse(id: String?): TimeZone? {
        if (id.isNullOrBlank()) return null
        val zone = TimeZone.getTimeZone(id)
        return if (zone.id == "GMT" && !id.equals("GMT", ignoreCase = true)) null else zone
    }

    private fun prefs(context: Context) = ServerScopedPrefs.open(
        context.applicationContext, FILE, ServerScopedPrefs.activeFingerprint(context)
    )
}
