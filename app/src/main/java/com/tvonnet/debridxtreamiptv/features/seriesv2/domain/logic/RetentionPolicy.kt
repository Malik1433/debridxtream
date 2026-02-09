package com.tvonnet.debridxtreamiptv.features.seriesv2.domain.logic

import com.tvonnet.debridxtreamiptv.features.seriesv2.data.model.SeriesMeta
import javax.inject.Inject

/**
 * Pure Domain Logic for determining which Series should be purged from cache.
 * Rules:
 * 1. If 'isFavorite', NEVER purge.
 * 2. If NOT 'isFavorite' AND older than threshold, PURGE.
 */
class RetentionPolicy @Inject constructor() {

    fun getPurgableSeriesIds(
        allSeries: List<SeriesMeta>,
        favoriteIds: Set<String>,
        thresholdDetails: Long = 7 * 24 * 60 * 60 * 1000L, // Default 7 days
        currentTime: Long = System.currentTimeMillis()
    ): List<String> {
        val cutoffTime = currentTime - thresholdDetails
        
        return allSeries.filter { series ->
            val isOld = series.cachedAt < cutoffTime
            val isFavorite = favoriteIds.contains(series.seriesId)
            
            // Purge only if OLD and NOT FAVORITE
            isOld && !isFavorite
        }.map { it.seriesId }
    }
}
