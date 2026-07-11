package com.tvonnet.debridxtreamiptv.data.local.dao

import androidx.room.*
import com.tvonnet.debridxtreamiptv.data.local.entity.ChannelEntity
import kotlinx.coroutines.flow.Flow

/**
 * Data Access Object for Channel operations
 * Week 6: Room Database Integration
 */
@Dao
interface ChannelDao {
    
    @Query("SELECT * FROM channels")
    fun getAllChannels(): Flow<List<ChannelEntity>>
    
    @Query("SELECT * FROM channels WHERE categoryId = :categoryId")
    suspend fun getChannelsByCategory(categoryId: String): List<ChannelEntity>
    
    /**
     * Week 8 (QA Rec #2): Get channels by category that are not expired
     * @param categoryId Category ID
     * @param expiryTimestamp Timestamp before which data is considered expired
     */
    @Query("SELECT * FROM channels WHERE categoryId = :categoryId AND cachedAt > :expiryTimestamp")
    suspend fun getChannelsByCategoryNotExpired(categoryId: String, expiryTimestamp: Long): List<ChannelEntity>

    @Query("SELECT COUNT(*) FROM channels WHERE categoryId = :categoryId AND streamType = :type")
    suspend fun countChannelsByCategory(categoryId: String, type: String): Int

    @Query("SELECT COUNT(*) FROM channels WHERE categoryId = :categoryId AND streamType = :type AND cachedAt > :expiryTimestamp")
    suspend fun countChannelsByCategoryNotExpired(categoryId: String, type: String, expiryTimestamp: Long): Int
    
    @Query("SELECT * FROM channels WHERE streamType = :type")
    fun getChannelsByType(type: String): Flow<List<ChannelEntity>>
    
    @Query("SELECT * FROM channels WHERE streamId = :streamId")
    suspend fun getChannelById(streamId: String): ChannelEntity?
    
    @Query("SELECT * FROM channels WHERE isFavorite = 1")
    fun getFavoriteChannels(): Flow<List<ChannelEntity>>
    
    @Query("UPDATE channels SET isFavorite = :isFavorite WHERE streamId = :streamId")
    suspend fun updateFavoriteStatus(streamId: String, isFavorite: Boolean)
    
    @Query("UPDATE channels SET lastWatched = :timestamp WHERE streamId = :streamId")
    suspend fun updateLastWatched(streamId: String, timestamp: Long)
    
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertChannel(channel: ChannelEntity)
    
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertChannels(channels: List<ChannelEntity>)
    
    @Delete
    suspend fun deleteChannel(channel: ChannelEntity)
    
    @Query("DELETE FROM channels WHERE categoryId = :categoryId")
    suspend fun deleteChannelsByCategory(categoryId: String)
    
    @Query("DELETE FROM channels")
    suspend fun deleteAllChannels()

    /**
     * Search channels globally
     */
    @Query("""
        SELECT * FROM channels
        WHERE name LIKE '%' || :query || '%'
        LIMIT 50
    """)
    suspend fun searchChannels(query: String): List<ChannelEntity>

    @Query("SELECT streamId FROM channels")
    suspend fun getAllChannelIds(): List<String>

    /** All live channels across the whole index (search-index + lazily-browsed rows). */
    @Query("SELECT * FROM channels WHERE streamType = 'live'")
    suspend fun getAllLiveChannels(): List<ChannelEntity>

    /** Deduped count of live channels — cheap badge for the "All" tab (no row materialize). */
    @Query("SELECT COUNT(DISTINCT streamId) FROM channels WHERE streamType = 'live'")
    suspend fun countAllLiveChannels(): Int
}
