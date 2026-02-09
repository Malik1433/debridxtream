package com.tvonnet.debridxtreamiptv.worker

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.tvonnet.debridxtreamiptv.data.repository.XtreamRepository
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@HiltWorker
class SeriesSyncWorker @AssistedInject constructor(
    @Assisted appContext: Context,
    @Assisted workerParams: WorkerParameters,
    private val repository: XtreamRepository
) : CoroutineWorker(appContext, workerParams) {

    override suspend fun doWork(): Result {
        val seriesId = inputData.getString("series_id") ?: return Result.failure()

        return withContext(Dispatchers.IO) {
            when (repository.syncSeriesDetail(seriesId)) {
                is com.tvonnet.debridxtreamiptv.data.Result.Success -> Result.success()
                is com.tvonnet.debridxtreamiptv.data.Result.Error -> Result.retry()
                else -> Result.failure()
            }
        }
    }
}
