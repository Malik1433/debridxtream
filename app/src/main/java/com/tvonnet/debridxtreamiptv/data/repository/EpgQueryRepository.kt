package com.tvonnet.debridxtreamiptv.data.repository

import com.tvonnet.debridxtreamiptv.data.local.dao.EpgDao
import com.tvonnet.debridxtreamiptv.data.local.entity.EpgEntity
import kotlinx.coroutines.flow.Flow

/**
 * Phase 7: the read-only EPG query sub-domain lifted out of the XtreamRepository god-class. These
 * methods only READ the EpgDao (per-channel programmes, now/next, upcoming, existence, search, and the
 * 24h cleanup) — no session/credential/network coupling — so they move here verbatim and the
 * repository delegates. The network EPG paths (fetchAndSaveEpg / ensureEpgData / fetchShortEpg*) stay
 * on the repository because they need the live apiService + credentials.
 */
internal class EpgQueryRepository(
    private val epgDao: EpgDao
) {
    /** Get all EPG programs for a specific channel (reactive) */
    fun getEpgByChannel(channelId: String): Flow<List<EpgEntity>>? {
        return epgDao?.getProgramsByChannel(channelId)
    }

    /** Get current program for a channel (one-shot) */
    suspend fun getCurrentProgram(channelId: String): EpgEntity? {
        return epgDao?.getCurrentProgram(channelId, System.currentTimeMillis())
    }

    /** Get next program for a channel (one-shot) */
    suspend fun getNextProgram(channelId: String): EpgEntity? {
        return epgDao?.getNextProgram(channelId, System.currentTimeMillis())
    }

    /** Get upcoming programs for a channel (next 12 hours) */
    fun getUpcomingPrograms(channelId: String): Flow<List<EpgEntity>>? {
        val now = System.currentTimeMillis()
        val twelveHoursLater = now + (12 * 60 * 60 * 1000)
        return epgDao?.getUpcomingPrograms(channelId, now, twelveHoursLater)
    }

    /** Check if EPG data exists for a channel */
    suspend fun hasEpgData(channelId: String): Boolean {
        return epgDao?.hasEpgData(channelId) ?: false
    }

    /** Clear old EPG programs (older than 24 hours) */
    suspend fun clearOldEpg() {
        val cutoffTime = System.currentTimeMillis() - (24 * 60 * 60 * 1000)
        epgDao?.clearOldPrograms(cutoffTime)
    }

    suspend fun searchEpg(query: String): List<EpgEntity> {
        return epgDao?.searchPrograms(query, System.currentTimeMillis()) ?: emptyList()
    }
}
