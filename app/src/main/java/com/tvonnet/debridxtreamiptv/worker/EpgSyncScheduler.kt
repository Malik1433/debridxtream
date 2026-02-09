package com.tvonnet.debridxtreamiptv.worker

import android.content.Context
import androidx.work.*
import java.util.concurrent.TimeUnit

/**
 * EPG Sync Scheduler - Week 13: Background EPG Refresh
 * 
 * Purpose:
 * - Schedule periodic EPG syncs
 * - Battery-friendly constraints
 * - Network-only execution
 * - Configurable interval
 * 
 * Features:
 * - Periodic work (6/12/24 hours)
 * - Network constraints
 * - Battery optimization
 * - Unique work (no duplicates)
 */
object EpgSyncScheduler : EpgSyncSchedulerDelegate {
    
    private const val TAG = "EpgSyncScheduler"
    
    /**
     * Schedule periodic EPG sync
     * Default: Every 6 hours
     */
    override fun schedulePeriodicSync(
        context: Context,
        intervalHours: Long,
        wifiOnly: Boolean,
        batteryNotLow: Boolean
    ) {
        val constraintsBuilder = Constraints.Builder()
            .setRequiredNetworkType(
                if (wifiOnly) NetworkType.UNMETERED else NetworkType.CONNECTED
            )
        if (batteryNotLow) {
            constraintsBuilder.setRequiresBatteryNotLow(true)
        }
        val constraints = constraintsBuilder.build()
        
        val syncRequest = PeriodicWorkRequestBuilder<EpgSyncWorker>(
            intervalHours, TimeUnit.HOURS,
            15, TimeUnit.MINUTES // Flex interval: run within 15 min of schedule
        )
            .setConstraints(constraints)
            .setBackoffCriteria(
                BackoffPolicy.EXPONENTIAL,
                15, TimeUnit.MINUTES
            )
            .addTag("epg_sync")
            .build()
        
        WorkManager.getInstance(context).enqueueUniquePeriodicWork(
            EpgSyncWorker.WORK_NAME,
            ExistingPeriodicWorkPolicy.KEEP, // Keep existing if already scheduled
            syncRequest
        )
        
        android.util.Log.d(
            TAG,
            "EPG Sync scheduled: Every $intervalHours hours | wifiOnly=$wifiOnly | batteryOptimized=$batteryNotLow"
        )
    }

    fun schedulePeriodicSync(context: Context, intervalHours: Long = 6) {
        schedulePeriodicSync(context, intervalHours, wifiOnly = true, batteryNotLow = true)
    }
    
    /**
     * Schedule one-time immediate sync
     * Use case: Manual refresh
     */
    fun scheduleImmediateSync(context: Context) {
        val constraints = Constraints.Builder()
            .setRequiredNetworkType(NetworkType.CONNECTED)
            .build()
        
        val syncRequest = OneTimeWorkRequestBuilder<EpgSyncWorker>()
            .setConstraints(constraints)
            .addTag("epg_sync_immediate")
            .build()
        
        WorkManager.getInstance(context).enqueueUniqueWork(
            "${EpgSyncWorker.WORK_NAME}_immediate",
            ExistingWorkPolicy.REPLACE,
            syncRequest
        )
        
        android.util.Log.d(TAG, "EPG Immediate sync scheduled")
    }
    
    /**
     * Cancel all EPG sync work
     * Use case: User disables auto-sync
     */
    override fun cancelSync(context: Context) {
        WorkManager.getInstance(context).cancelUniqueWork(EpgSyncWorker.WORK_NAME)
        android.util.Log.d(TAG, "EPG Sync cancelled")
    }
    
    /**
     * Check if sync is scheduled
     */
    fun isSyncScheduled(context: Context): Boolean {
        val workInfo = WorkManager.getInstance(context)
            .getWorkInfosForUniqueWork(EpgSyncWorker.WORK_NAME)
            .get()
        
        return workInfo.any { it.state == WorkInfo.State.ENQUEUED || it.state == WorkInfo.State.RUNNING }
    }
    
    /**
     * Get last sync time
     */
    fun getLastSyncTime(context: Context): Long? {
        val workInfo = WorkManager.getInstance(context)
            .getWorkInfosForUniqueWork(EpgSyncWorker.WORK_NAME)
            .get()
            .firstOrNull()
        
        return workInfo?.outputData?.getLong("sync_time", 0)
    }
}
