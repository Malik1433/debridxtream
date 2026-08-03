package com.tvonnet.debridxtreamiptv.worker

import android.content.Context
import android.util.Log
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.tvonnet.debridxtreamiptv.data.Result
import com.tvonnet.debridxtreamiptv.data.local.dao.EpgDao
import com.tvonnet.debridxtreamiptv.data.prefs.CredentialsPreferences
import com.tvonnet.debridxtreamiptv.data.repository.XtreamRepository
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * EPG Sync Worker - Week 13: Background EPG Refresh
 * 
 * Purpose:
 * - Periodically fetch EPG data in background
 * - Auto-cleanup old EPG entries (>24h)
 * - Battery-friendly with constraints
 * - Runs even when app is closed
 * 
 * Features:
 * - Periodic sync (configurable: 6/12/24 hours)
 * - Network-only execution
 * - Battery optimization support
 * - Logging for monitoring
 */
@HiltWorker
class EpgSyncWorker @AssistedInject constructor(
    @Assisted appContext: Context,
    @Assisted workerParams: WorkerParameters,
    private val repository: XtreamRepository,
    private val epgDao: EpgDao
) : CoroutineWorker(appContext, workerParams) {
    
    companion object {
        const val TAG = "EpgSyncWorker"
        const val WORK_NAME = "epg_sync_work"
        
        // EPG data older than 24 hours is considered stale
        const val EPG_STALE_THRESHOLD_MS = 24 * 60 * 60 * 1000L
    }
    
    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        try {
            Log.d(TAG, "EPG Sync started...")

            // Check if work is cancelled before starting expensive operations
            if (isStopped) {
                Log.w(TAG, "EPG Sync cancelled before start")
                return@withContext Result.success()
            }

            if (!initializeFromCredentials()) {
                return@withContext Result.success()
            }

            // Check for cancellation before network operation
            if (isStopped) {
                Log.w(TAG, "EPG Sync cancelled before network operation")
                return@withContext Result.success()
            }

            // Fetch and save EPG with cancellation support
            val result = repository.fetchAndSaveEpg()

            // Check for cancellation after network operation
            if (isStopped) {
                Log.w(TAG, "EPG Sync cancelled after network operation")
                return@withContext Result.success()
            }

            handleSyncResult(result)
        } catch (e: kotlinx.coroutines.CancellationException) {
            // WorkManager stopped us (or the heap-pressure abort fired) — report success so the job is
            // not retried in a loop; the next scheduled run picks it up.
            Log.w(TAG, "EPG Sync cancelled", e)
            Result.success()
        } catch (e: Exception) {
            Log.e(TAG, "EPG Sync exception", e)
            // Check if error is due to cancellation
            if (isStopped) {
                Log.w(TAG, "EPG Sync cancelled by system")
            }
            // Return success to avoid retry spam
            Result.success()
        }
    }
    
    // False when the sync must be skipped (no credentials, or cancellation mid-check);
    // the caller then reports success so the job is not retried.
    private fun initializeFromCredentials(): Boolean {
        // Get credentials
        val credentialsPrefs = CredentialsPreferences(applicationContext)
        val serverUrl = credentialsPrefs.getServerUrl()
        val username = credentialsPrefs.getUsername()
        val password = credentialsPrefs.getPassword()

        if (serverUrl == null || username == null || password == null) {
            Log.w(TAG, "EPG Sync skipped: No credentials found")
            return false
        }

        // Check for cancellation before repository initialization
        if (isStopped) {
            Log.w(TAG, "EPG Sync cancelled during credential check")
            return false
        }

        // Initialize repository
        repository.initialize(serverUrl, username, password)
        return true
    }

    // Every arm returns success — EPG failure is non-critical and must not retry-spam.
    private suspend fun handleSyncResult(result: com.tvonnet.debridxtreamiptv.data.Result<Int>): Result {
        when (result) {
            is com.tvonnet.debridxtreamiptv.data.Result.Success -> {
                val programCount = result.data
                Log.d(TAG, "EPG Sync successful: $programCount programs updated")

                // Check for cancellation before cleanup
                if (!isStopped) {
                    // Cleanup old EPG data
                    cleanupOldEpgData()

                    // Week 14: Save last sync time for settings display
                    saveLastSyncTime()
                }
            }
            is com.tvonnet.debridxtreamiptv.data.Result.Error -> {
                Log.e(TAG, "EPG Sync failed: ${result.exception.message}")
                // Check if error is due to cancellation
                if (isStopped || result.exception is kotlinx.coroutines.CancellationException) {
                    Log.w(TAG, "EPG Sync cancelled by system")
                }
            }
            is com.tvonnet.debridxtreamiptv.data.Result.Loading -> {
                // Should not happen in repository call
                Log.w(TAG, "EPG Sync loading state unexpected")
            }
        }
        return Result.success()
    }

    /**
     * Cleanup EPG entries older than 24 hours
     * Keeps database size manageable
     */
    private suspend fun cleanupOldEpgData() {
        try {
            val cutoffTime = System.currentTimeMillis() - EPG_STALE_THRESHOLD_MS
            epgDao.clearOldPrograms(cutoffTime)
            val remainingCount = epgDao.getTotalProgramCount()
            Log.d(TAG, "EPG Cleanup: Remaining $remainingCount programs")
        } catch (e: Exception) {
            Log.e(TAG, "EPG Cleanup failed", e)
        }
    }
    
    /**
     * Week 14: Save last sync time to SharedPreferences
     * Used by SettingsFragmentNew to display last sync time
     */
    private fun saveLastSyncTime() {
        try {
            val prefs = applicationContext.getSharedPreferences("epg_prefs", Context.MODE_PRIVATE)
            prefs.edit().putLong("last_sync_time", System.currentTimeMillis()).apply()
            Log.d(TAG, "Last sync time saved")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to save last sync time", e)
        }
    }
}

