package com.tvonnet.debridxtreamiptv.data.repository

import com.tvonnet.debridxtreamiptv.data.local.dao.WatchedStateDao
import com.tvonnet.debridxtreamiptv.data.local.entity.WatchedStateEntity
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class WatchedStateRepository @Inject constructor(
    private val watchedStateDao: WatchedStateDao
) {
    suspend fun upsert(state: WatchedStateEntity) {
        watchedStateDao.upsert(state)
    }

    suspend fun getState(identityKey: String): WatchedStateEntity? {
        return watchedStateDao.getState(identityKey)
    }

    fun observeState(identityKey: String) = watchedStateDao.observeState(identityKey)

    fun observeStates(identityKeys: List<String>) = watchedStateDao.observeStates(identityKeys)

    fun observeAllWatchedMovieKeys() = watchedStateDao.observeAllWatchedMovieKeys()

    fun observeAllWatchedEpisodeKeys() = watchedStateDao.observeAllWatchedEpisodeKeys()

    /** streamId -> (progressMs, durationMs) for in-progress IPTV movies (browse card strip). */
    fun observeInProgressMovieProgress() =
        watchedStateDao.observeInProgressMovies()

    suspend fun upsertAutomaticProgress(state: WatchedStateEntity, timestamp: Long) {
        watchedStateDao.upsert(
            state.copy(
                updatedAt = timestamp
            )
        )
    }

    suspend fun setManualWatched(
        state: WatchedStateEntity,
        watched: Boolean,
        timestamp: Long
    ) {
        watchedStateDao.upsert(
            state.copy(
                isWatched = watched,
                watchedAt = if (watched) timestamp else null,
                updatedAt = timestamp,
                manualState = if (watched) {
                    WatchedStateEntity.MANUAL_WATCHED
                } else {
                    WatchedStateEntity.MANUAL_UNWATCHED
                },
                manualUpdatedAt = timestamp
            )
        )
    }

    suspend fun setManualWatched(
        identityKey: String,
        watched: Boolean,
        timestamp: Long
    ): Int {
        return watchedStateDao.setManualWatched(identityKey, watched, timestamp)
    }

    suspend fun getEpisodeStatesForSeason(
        seriesIdentity: String,
        source: String,
        seasonNumber: Int
    ): List<WatchedStateEntity> {
        return watchedStateDao.getEpisodeStatesForSeason(seriesIdentity, source, seasonNumber)
    }

    suspend fun getEpisodeStatesForSeries(
        seriesIdentity: String,
        source: String
    ): List<WatchedStateEntity> {
        return watchedStateDao.getEpisodeStatesForSeries(seriesIdentity, source)
    }
}
