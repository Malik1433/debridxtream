package com.tvonnet.debridxtreamiptv.data.local.dao

import androidx.room.*
import com.tvonnet.debridxtreamiptv.data.local.entity.FavoriteEntity
import kotlinx.coroutines.flow.Flow

/**
 * Data Access Object for Favorites operations
 * Week 6: Room Database Integration
 */
@Dao
interface FavoriteDao {
    
    @Query("SELECT * FROM favorites ORDER BY addedAt DESC")
    fun getAllFavorites(): Flow<List<FavoriteEntity>>

    @Query("SELECT * FROM favorites ORDER BY addedAt DESC")
    suspend fun getFavoritesSync(): List<FavoriteEntity>
    
    @Query("SELECT * FROM favorites WHERE type = :type ORDER BY addedAt DESC")
    fun getFavoritesByType(type: String): Flow<List<FavoriteEntity>>
    
    @Query("SELECT * FROM favorites WHERE streamId = :streamId")
    suspend fun getFavoriteByStreamId(streamId: String): FavoriteEntity?
    
    @Query("SELECT EXISTS(SELECT 1 FROM favorites WHERE streamId = :streamId)")
    suspend fun isFavorite(streamId: String): Boolean
    
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertFavorite(favorite: FavoriteEntity)
    
    @Delete
    suspend fun deleteFavorite(favorite: FavoriteEntity)
    
    @Query("DELETE FROM favorites WHERE streamId = :streamId")
    suspend fun deleteFavoriteByStreamId(streamId: String)
    
    @Query("DELETE FROM favorites")
    suspend fun deleteAllFavorites()
}

