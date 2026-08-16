package com.tvonnet.debridxtreamiptv.data.repository

import android.util.Log
import com.tvonnet.debridxtreamiptv.data.cache.FavoritesCache
import com.tvonnet.debridxtreamiptv.data.local.dao.FavoriteDao
import com.tvonnet.debridxtreamiptv.data.local.entity.FavoriteEntity
import com.tvonnet.debridxtreamiptv.data.local.entity.WatchedStateEntity
import kotlinx.coroutines.flow.Flow

/**
 * Phase 7: the self-contained Favorites sub-domain lifted out of the XtreamRepository god-class.
 * It owns only the FavoriteDao + FavoritesCache — no session/credential/catalog-cache coupling — so
 * the bodies moved here verbatim and XtreamRepository just delegates its favorites API to an instance
 * of this class. Behaviour is byte-for-byte identical (including the O(1) cache fast-path and the
 * optimistic cache updates).
 */
internal class FavoritesRepository(
    private val favoriteDao: FavoriteDao,
    private val favoritesCache: FavoritesCache
) {
    private val tag = "XtreamRepository"

    /** Get all favorites as Flow (reactive) */
    fun getAllFavorites(): Flow<List<FavoriteEntity>> {
        return favoriteDao?.getAllFavorites()
            ?: throw IllegalStateException("FavoriteDao not initialized")
    }

    /** Get favorites filtered by type (live, vod, series) */
    fun getFavoritesByType(type: String): Flow<List<FavoriteEntity>> {
        return favoriteDao?.getFavoritesByType(type)
            ?: throw IllegalStateException("FavoriteDao not initialized")
    }

    /** Check if a stream is favorited */
    suspend fun isFavorite(streamId: String): Boolean {
        // Week 12: Use performance cache if available (O(1) lookup)
        if (favoritesCache != null && favoritesCache.isInitialized()) {
            return favoritesCache.isFavorite(streamId)
        }
        // Fallback to database query
        return favoriteDao?.isFavorite(streamId) ?: false
    }

    /**
     * Add a stream to favorites.
     *
     * [source] says whether [streamId] is an IPTV stream id (only meaningful on the provider that
     * issued it) or a debrid id (meaningful everywhere). It defaults to xtream because all but two
     * call sites are IPTV screens; the debrid detail screens pass their own.
     */
    suspend fun addFavorite(
        streamId: String,
        type: String,
        name: String,
        iconUrl: String? = null,
        source: String = WatchedStateEntity.SOURCE_XTREAM,
    ) {
        val favorite = FavoriteEntity(
            streamId = streamId,
            type = type,
            name = name,
            iconUrl = iconUrl,
            addedAt = System.currentTimeMillis(),
            source = source
        )
        favoriteDao?.insertFavorite(favorite)
        // Week 12: Update performance cache (optimistic)
        favoritesCache?.addToCache(streamId)
        Log.d(tag, "Added favorite: $streamId (name: $name, type: $type)")
    }

    /** Remove a stream from favorites */
    suspend fun removeFavorite(streamId: String) {
        favoriteDao?.deleteFavoriteByStreamId(streamId)
        // Week 12: Update performance cache (optimistic)
        favoritesCache?.removeFromCache(streamId)
        Log.d(tag, "Removed favorite: $streamId")
    }

    /** Remove all favorites */
    suspend fun clearAllFavorites() {
        favoriteDao?.deleteAllFavorites()
        Log.d(tag, "Cleared all favorites")
    }

    /** S2: remove only the favourites belonging to one source (see [addFavorite]). */
    suspend fun clearFavoritesForSource(source: String) {
        favoriteDao?.deleteFavoritesBySource(source)
        Log.d(tag, "Cleared favorites for source: $source")
    }
}
