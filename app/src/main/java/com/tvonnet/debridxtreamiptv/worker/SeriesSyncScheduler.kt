package com.tvonnet.debridxtreamiptv.worker

import android.content.Context
import androidx.work.*
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

interface SeriesSyncScheduler {
    fun scheduleImmediateSync(seriesId: String)
}

@Singleton
class SeriesSyncSchedulerImpl @Inject constructor(
    @ApplicationContext private val context: Context
) : SeriesSyncScheduler {

    companion object {
        private const val TAG = "SeriesSyncScheduler"
    }

    override fun scheduleImmediateSync(seriesId: String) {
        val constraints = Constraints.Builder()
            .setRequiredNetworkType(NetworkType.CONNECTED)
            .build()

        val inputData = Data.Builder()
            .putString("series_id", seriesId)
            .build()

        val syncRequest = OneTimeWorkRequestBuilder<SeriesSyncWorker>()
            .setConstraints(constraints)
            .setInputData(inputData)
            .addTag("series_sync_$seriesId")
            .build()

        WorkManager.getInstance(context).enqueueUniqueWork(
            "series_sync_$seriesId",
            ExistingWorkPolicy.KEEP, // If already syncing, don't interrupt
            syncRequest
        )
    }
}
