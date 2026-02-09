package com.tvonnet.debridxtreamiptv.worker

import android.content.Context
import android.content.SharedPreferences
import androidx.preference.PreferenceManager

/**
 * Coordinates WorkManager scheduling for EPG background refresh based on
 * user preferences collected from either the legacy or new settings screens.
 *
 * TDD note: behaviour validated by EpgSyncControllerTest.
 */
class EpgSyncController(
    private val scheduler: EpgSyncSchedulerDelegate = EpgSyncScheduler
) {

    fun applyOptions(context: Context, options: EpgSyncOptions) {
        if (options.enabled) {
            scheduler.schedulePeriodicSync(
                context = context,
                intervalHours = options.intervalHours,
                wifiOnly = options.wifiOnly,
                batteryNotLow = options.batteryNotLow
            )
        } else {
            scheduler.cancelSync(context)
        }
    }

    fun syncFromPreferences(
        context: Context,
        sharedPreferences: SharedPreferences = PreferenceManager.getDefaultSharedPreferences(context)
    ) {
        val options = EpgSyncOptions.fromPreferences(sharedPreferences)
        applyOptions(context, options)
    }
}

data class EpgSyncOptions(
    val enabled: Boolean,
    val intervalHours: Long,
    val wifiOnly: Boolean,
    val batteryNotLow: Boolean
) {
    companion object {
        private const val DEFAULT_INTERVAL = 6L

        fun fromPreferences(prefs: SharedPreferences): EpgSyncOptions {
            val enabled = prefs.getBoolean("epg_auto_sync",
                prefs.getBoolean("epg_sync_enabled", true))

            val interval = prefs.getString("epg_sync_interval", null)
                ?: prefs.getString("epg_sync_frequency", null)
                ?: DEFAULT_INTERVAL.toString()

            val wifiOnly = prefs.getBoolean("epg_network_only",
                prefs.getBoolean("epg_sync_network_only", true))

            val battery = prefs.getBoolean("epg_battery_saver",
                prefs.getBoolean("epg_sync_battery_saver", true))

            return EpgSyncOptions(
                enabled = enabled,
                intervalHours = interval.toLongOrNull() ?: DEFAULT_INTERVAL,
                wifiOnly = wifiOnly,
                batteryNotLow = battery
            )
        }
    }
}

interface EpgSyncSchedulerDelegate {
    fun schedulePeriodicSync(
        context: Context,
        intervalHours: Long = 6,
        wifiOnly: Boolean = true,
        batteryNotLow: Boolean = true
    )

    fun cancelSync(context: Context)
}
