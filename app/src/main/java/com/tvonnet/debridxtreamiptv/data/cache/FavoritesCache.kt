package com.tvonnet.debridxtreamiptv.data.cache

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import com.tvonnet.debridxtreamiptv.data.local.entity.FavoriteEntity
import javax.inject.Inject
import javax.inject.Singleton

/**
 * FavoritesCache - In-Memory Cache for Favorites
 * Week 12: QA Recommendation #2 - Performance optimization
 * 
 * Purpose:
 * - Reduces database queries for isFavorite() checks
 * - Provides fast O(1) lookup for favorite status
 * - Updates reactively via StateFlow
 * 
 * Benefits:
 * - Faster UI updates (no database round-trip)
 * - Reduced battery usage
 * - Smoother scrolling in channel lists
 */
@Singleton
class FavoritesCache @Inject constructor() {
    
    // In-memory cache of favorited stream IDs
    private val _cachedFavoriteIds = MutableStateFlow<Set<String>>(emptySet())
    val cachedFavoriteIds: StateFlow<Set<String>> = _cachedFavoriteIds.asStateFlow()
    
    // Full favorites list for additional use cases
    private val _cachedFavorites = MutableStateFlow<List<FavoriteEntity>>(emptyList())
    val cachedFavorites: StateFlow<List<FavoriteEntity>> = _cachedFavorites.asStateFlow()
    
    /**
     * Update cache with new favorites list
     * Called when favorites Flow emits new data
     */
    fun updateCache(favorites: List<FavoriteEntity>) {
        _cachedFavorites.value = favorites
        _cachedFavoriteIds.value = favorites.map { it.streamId }.toSet()
        android.util.Log.d("FavoritesCache", "Cache updated: ${favorites.size} favorites")
    }
    
    /**
     * Fast O(1) check if stream is favorited
     * Uses in-memory Set for instant lookup
     */
    fun isFavorite(streamId: String): Boolean {
        return _cachedFavoriteIds.value.contains(streamId)
    }
    
    /**
     * Add stream ID to cache (optimistic update)
     * Should be called after adding to database
     */
    fun addToCache(streamId: String) {
        _cachedFavoriteIds.value = _cachedFavoriteIds.value + streamId
        android.util.Log.d("FavoritesCache", "Added to cache: $streamId")
    }
    
    /**
     * Remove stream ID from cache (optimistic update)
     * Should be called after removing from database
     */
    fun removeFromCache(streamId: String) {
        _cachedFavoriteIds.value = _cachedFavoriteIds.value - streamId
        android.util.Log.d("FavoritesCache", "Removed from cache: $streamId")
    }
    
    /**
     * Clear cache (e.g., on logout)
     */
    fun clearCache() {
        _cachedFavorites.value = emptyList()
        _cachedFavoriteIds.value = emptySet()
        android.util.Log.d("FavoritesCache", "Cache cleared")
    }
    
    /**
     * Get current cache size
     */
    fun getCacheSize(): Int = _cachedFavoriteIds.value.size
    
    /**
     * Check if cache is initialized
     */
    fun isInitialized(): Boolean = _cachedFavoriteIds.value.isNotEmpty()
}

