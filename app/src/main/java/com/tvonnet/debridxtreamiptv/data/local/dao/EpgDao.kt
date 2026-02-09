package com.tvonnet.debridxtreamiptv.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.tvonnet.debridxtreamiptv.data.local.entity.EpgEntity
import kotlinx.coroutines.flow.Flow

/**
 * Week 11: EPG Data Access Object
 * 
 * Provides reactive queries for EPG data with efficient indexing
 */
@Dao
interface EpgDao {
    
    /**
     * Insert EPG programs (replace on conflict)
     */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(programs: List<EpgEntity>)
    
    /**
     * Insert single EPG program
     */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(program: EpgEntity)
    
    /**
     * Get all EPG programs for a specific channel (reactive)
     * Sorted by start time (earliest first)
     */
    @Query("SELECT * FROM epg WHERE channelId = :channelId ORDER BY start ASC")
    fun getProgramsByChannel(channelId: String): Flow<List<EpgEntity>>
    
    /**
     * Get current program for a specific channel (non-reactive, one-shot)
     * Returns the program that is currently playing (now >= start AND now < stop)
     */
    @Query("""
        SELECT * FROM epg 
        WHERE channelId = :channelId 
        AND start <= :currentTime 
        AND stop > :currentTime 
        ORDER BY start DESC 
        LIMIT 1
    """)
    suspend fun getCurrentProgram(channelId: String, currentTime: Long): EpgEntity?
    
    /**
     * Get upcoming programs for a specific channel
     * Returns programs that start after current time, limited to next 12 hours
     */
    @Query("""
        SELECT * FROM epg 
        WHERE channelId = :channelId 
        AND start > :currentTime 
        AND start < :endTime
        ORDER BY start ASC
    """)
    fun getUpcomingPrograms(
        channelId: String, 
        currentTime: Long,
        endTime: Long
    ): Flow<List<EpgEntity>>
    
    /**
     * Get next program for a specific channel (non-reactive)
     * Returns the program that starts immediately after current time
     */
    @Query("""
        SELECT * FROM epg 
        WHERE channelId = :channelId 
        AND start > :currentTime 
        ORDER BY start ASC 
        LIMIT 1
    """)
    suspend fun getNextProgram(channelId: String, currentTime: Long): EpgEntity?
    
    /**
     * Get all programs within a time range (for timeline view)
     */
    @Query("""
        SELECT * FROM epg 
        WHERE start >= :startTime 
        AND start < :endTime 
        ORDER BY channelId ASC, start ASC
    """)
    fun getProgramsInTimeRange(startTime: Long, endTime: Long): Flow<List<EpgEntity>>
    
    /**
     * Get all programs for multiple channels (batch query)
     */
    @Query("""
        SELECT * FROM epg 
        WHERE channelId IN (:channelIds) 
        ORDER BY channelId ASC, start ASC
    """)
    fun getProgramsByChannels(channelIds: List<String>): Flow<List<EpgEntity>>
    
    /**
     * Clear old EPG data (expired programs)
     * Delete programs that ended more than 24 hours ago
     */
    @Query("DELETE FROM epg WHERE stop < :cutoffTime")
    suspend fun clearOldPrograms(cutoffTime: Long)
    
    /**
     * Delete old programs and return count (Week 13: For WorkManager logging)
     */
    @Query("DELETE FROM epg WHERE stop < :cutoffTime")
    suspend fun deleteOldPrograms(cutoffTime: Long): Int
    
    /**
     * Clear all EPG data for a specific channel
     */
    @Query("DELETE FROM epg WHERE channelId = :channelId")
    suspend fun clearChannelPrograms(channelId: String)
    
    /**
     * Clear all EPG data
     */
    @Query("DELETE FROM epg")
    suspend fun clearAll()
    
    /**
     * Get count of EPG programs for a channel
     */
    @Query("SELECT COUNT(*) FROM epg WHERE channelId = :channelId")
    suspend fun getProgramCount(channelId: String): Int
    
    /**
     * Get total count of all EPG programs
     */
    @Query("SELECT COUNT(*) FROM epg")
    suspend fun getTotalProgramCount(): Int
    
    /**
     * Check if EPG data exists for a channel
     */
    @Query("SELECT EXISTS(SELECT 1 FROM epg WHERE channelId = :channelId LIMIT 1)")
    suspend fun hasEpgData(channelId: String): Boolean

    /**
     * Get current programs for multiple channels (batch)
     */
    @Query("""
        SELECT * FROM epg 
        WHERE channelId IN (:channelIds) 
        AND start <= :currentTime 
        AND stop > :currentTime
    """)
    suspend fun getCurrentProgramsForChannels(channelIds: List<String>, currentTime: Long): List<EpgEntity>

    /**
     * Search programs by title or description
     * Returns matching programs that have NOT ended yet (current or future)
     */
    @Query("""
        SELECT * FROM epg 
        WHERE (title LIKE '%' || :query || '%' OR description LIKE '%' || :query || '%')
        AND stop > :currentTime
        ORDER BY start ASC
        LIMIT 50
    """)
    suspend fun searchPrograms(query: String, currentTime: Long): List<EpgEntity>
}

