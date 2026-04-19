package com.tvonnet.debridxtreamiptv.features.seriesv2.worker

import android.content.Context
import android.util.Log
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.tvonnet.debridxtreamiptv.data.local.dao.FavoriteDao
import com.tvonnet.debridxtreamiptv.features.seriesv2.data.dao.SeriesDaoV2
import com.tvonnet.debridxtreamiptv.features.seriesv2.domain.logic.RetentionPolicy
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Background worker to prune stale Series data.
 * Uses [RetentionPolicy] to determine which items to delete.
 */
@HiltWorker
class SeriesCachePruningWorker @AssistedInject constructor(
    @Assisted appContext: Context,
    @Assisted workerParams: WorkerParameters,
    private val seriesDao: SeriesDaoV2,
    private val favoriteDao: FavoriteDao,
    private val retentionPolicy: RetentionPolicy
) : CoroutineWorker(appContext, workerParams) {

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        try {
            Log.d("SeriesJanitor", "Starting cache pruning...")

            // 1. Fetch Metadata
            val allMeta = seriesDao.getAllSeriesMeta()
            if (allMeta.isEmpty()) {
                Log.d("SeriesJanitor", "Cache empty. Nothing to prune.")
                return@withContext Result.success()
            }

            // 2. Fetch Favorites (Protected from pruning)
            val favorites = favoriteDao.getFavoritesSync()
            val favoriteIds = favorites.map { it.streamId }.toSet()

            // 3. Determine Purgable Items
            // Default threshold: 7 days (defined in Policy default, or we can override here)
            val toDelete = retentionPolicy.getPurgableSeriesIds(allMeta, favoriteIds)

            // 4. Delete
            if (toDelete.isNotEmpty()) {
                Log.i("SeriesJanitor", "Pruning ${toDelete.size} stale series...")
                seriesDao.deleteSeriesByIds(toDelete)
            } else {
                Log.d("SeriesJanitor", "No stale items found.")
            }

            Result.success()
        } catch (e: Exception) {
            Log.e("SeriesJanitor", "Pruning failed", e)
            Result.failure()
        }
    }
    companion object {
        fun schedule(context: Context) {
            val constraints = androidx.work.Constraints.Builder()
                .setRequiresCharging(true)
                .setRequiresDeviceIdle(true)
                .build()

            val request = androidx.work.PeriodicWorkRequestBuilder<SeriesCachePruningWorker>(
                24, java.util.concurrent.TimeUnit.HOURS
            )
                .setConstraints(constraints)
                .build()

            androidx.work.WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                "SeriesCachePruning",
                androidx.work.ExistingPeriodicWorkPolicy.KEEP,
                request
            )
        }
    }
}
