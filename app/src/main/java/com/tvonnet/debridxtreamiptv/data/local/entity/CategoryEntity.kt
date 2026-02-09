package com.tvonnet.debridxtreamiptv.data.local.entity

import androidx.room.Entity
import androidx.room.PrimaryKey
import com.tvonnet.debridxtreamiptv.data.model.XtreamCategory

/**
 * Room Entity for caching category data locally
 * Week 6: Room Database Integration
 * Week 8: Added cachedAt timestamp for TTL support (QA Recommendation #2)
 */
@Entity(tableName = "categories")
data class CategoryEntity(
    @PrimaryKey val categoryId: String,
    val categoryName: String,
    val type: String, // "live", "vod", "series"
    val cachedAt: Long = System.currentTimeMillis() // Week 8: For TTL checking
)

/**
 * Extension function to convert XtreamCategory to CategoryEntity
 * Week 8: Added cachedAt timestamp
 */
fun XtreamCategory.toCategoryEntity(type: String): CategoryEntity {
    return CategoryEntity(
        categoryId = category_id ?: "",
        categoryName = category_name ?: "",
        type = type,
        cachedAt = System.currentTimeMillis() // Week 8: Cache timestamp
    )
}

/**
 * Extension function to convert CategoryEntity back to XtreamCategory
 */
fun CategoryEntity.toXtreamCategory(): XtreamCategory {
    return XtreamCategory(
        category_id = categoryId,
        category_name = categoryName,
        parent_id = null
    )
}

