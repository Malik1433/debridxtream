package com.tvonnet.debridxtreamiptv.data.local.dao

import androidx.room.*
import com.tvonnet.debridxtreamiptv.data.local.entity.CategoryEntity
import kotlinx.coroutines.flow.Flow

/**
 * Data Access Object for Category operations
 * Week 6: Room Database Integration
 */
@Dao
interface CategoryDao {
    
    @Query("SELECT * FROM categories")
    fun getAllCategories(): Flow<List<CategoryEntity>>
    
    @Query("SELECT * FROM categories WHERE type = :type")
    fun getCategoriesByType(type: String): Flow<List<CategoryEntity>>
    
    @Query("SELECT * FROM categories WHERE type = :type")
    suspend fun getCategoriesByTypeSync(type: String): List<CategoryEntity>
    
    /**
     * Week 8 (QA Rec #2): Get categories by type that are not expired
     * @param type Category type ("live", "vod", "series")
     * @param expiryTimestamp Timestamp before which data is considered expired
     */
    @Query("SELECT * FROM categories WHERE type = :type AND cachedAt > :expiryTimestamp")
    suspend fun getCategoriesByTypeNotExpired(type: String, expiryTimestamp: Long): List<CategoryEntity>
    
    @Query("SELECT * FROM categories WHERE categoryId = :categoryId")
    suspend fun getCategoryById(categoryId: String): CategoryEntity?
    
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertCategory(category: CategoryEntity)
    
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertCategories(categories: List<CategoryEntity>)
    
    @Delete
    suspend fun deleteCategory(category: CategoryEntity)
    
    @Query("DELETE FROM categories WHERE type = :type")
    suspend fun deleteCategoriesByType(type: String)
    
    @Query("DELETE FROM categories")
    suspend fun deleteAllCategories()
}

