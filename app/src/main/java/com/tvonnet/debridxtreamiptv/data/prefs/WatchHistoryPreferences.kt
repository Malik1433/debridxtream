package com.tvonnet.debridxtreamiptv.data.prefs

import android.content.Context
import android.content.SharedPreferences
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import com.tvonnet.debridxtreamiptv.data.model.ContinueWatchingItem
import com.tvonnet.debridxtreamiptv.data.model.FavoriteItem
import com.tvonnet.debridxtreamiptv.data.model.RecentlyWatchedItem
import com.tvonnet.debridxtreamiptv.data.model.RecentLiveChannelItem

class WatchHistoryPreferences(private val context: Context) {
    private val prefs: SharedPreferences = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    private val gson = Gson()
    
    // Continue Watching operations
    fun saveContinueWatchingItem(item: ContinueWatchingItem) {
        val currentList = readContinueWatchingList().toMutableList()
        val itemKey = continueWatchingKey(item)
        
        // Remove existing item with same contentId if present
        currentList.removeAll { it.contentId == item.contentId }
        // Remove existing items that map to the same logical content
        currentList.removeAll { continueWatchingKey(it) == itemKey }
        
        // Add new item at the beginning
        currentList.add(0, item)
        
        // Keep only last 20 items
        if (currentList.size > MAX_CONTINUE_WATCHING) {
            currentList.subList(MAX_CONTINUE_WATCHING, currentList.size).clear()
        }
        
        val json = gson.toJson(currentList)
        prefs.edit().putString(KEY_CONTINUE_WATCHING, json).apply()
    }
    
    fun getContinueWatchingList(): List<ContinueWatchingItem> {
        val currentList = readContinueWatchingList()
        val deduped = dedupeContinueWatching(currentList)
        if (deduped.size != currentList.size) {
            val json = gson.toJson(deduped)
            prefs.edit().putString(KEY_CONTINUE_WATCHING, json).apply()
        }
        return deduped
    }
    
    fun removeContinueWatchingItem(contentId: String) {
        val currentList = getContinueWatchingList().toMutableList()
        currentList.removeAll { it.contentId == contentId }
        val json = gson.toJson(currentList)
        prefs.edit().putString(KEY_CONTINUE_WATCHING, json).apply()
    }
    
    fun clearContinueWatching() {
        prefs.edit().remove(KEY_CONTINUE_WATCHING).apply()
    }

    private fun readContinueWatchingList(): List<ContinueWatchingItem> {
        val json = prefs.getString(KEY_CONTINUE_WATCHING, null) ?: return emptyList()
        return try {
            val type = object : TypeToken<List<ContinueWatchingItem>>() {}.type
            gson.fromJson<List<ContinueWatchingItem>>(json, type) ?: emptyList()
        } catch (e: Exception) {
            emptyList()
        }
    }

    private fun dedupeContinueWatching(items: List<ContinueWatchingItem>): List<ContinueWatchingItem> {
        if (items.isEmpty()) return items
        val seenKeys = mutableSetOf<String>()
        val result = mutableListOf<ContinueWatchingItem>()
        items.forEach { item ->
            val key = continueWatchingKey(item)
            if (seenKeys.add(key)) {
                result.add(item)
            }
        }
        return result
    }

    private fun continueWatchingKey(item: ContinueWatchingItem): String {
        val isSeries = item.contentType == com.tvonnet.debridxtreamiptv.data.model.ContentType.EPISODE ||
            item.contentType == com.tvonnet.debridxtreamiptv.data.model.ContentType.SERIES
        val stableId = when {
            !item.tmdbId.isNullOrBlank() -> "tmdb:${item.tmdbId}"
            !item.imdbId.isNullOrBlank() -> "imdb:${item.imdbId}"
            isSeries && !item.seriesTitle.isNullOrBlank() ->
                "title:${normalizeKey(item.seriesTitle)}"
            !item.title.isNullOrBlank() -> "title:${normalizeKey(item.title)}"
            else -> "content:${item.contentId}"
        }
        return if (isSeries) "series:$stableId" else "movie:$stableId"
    }

    private fun normalizeKey(value: String?): String {
        return value?.trim()?.lowercase().orEmpty()
    }
    
    // Favorites operations
    fun addFavorite(item: FavoriteItem) {
        val currentList = getFavoritesList().toMutableList()
        
        // Don't add if already exists
        if (currentList.any { it.contentId == item.contentId }) {
            return
        }
        
        // Add new item at the beginning
        currentList.add(0, item)
        
        val json = gson.toJson(currentList)
        prefs.edit().putString(KEY_FAVORITES, json).apply()
    }
    
    fun removeFavorite(contentId: String) {
        val currentList = getFavoritesList().toMutableList()
        currentList.removeAll { it.contentId == contentId }
        val json = gson.toJson(currentList)
        prefs.edit().putString(KEY_FAVORITES, json).apply()
    }
    
    fun getFavoritesList(): List<FavoriteItem> {
        val json = prefs.getString(KEY_FAVORITES, null) ?: return emptyList()
        return try {
            val type = object : TypeToken<List<FavoriteItem>>() {}.type
            gson.fromJson<List<FavoriteItem>>(json, type) ?: emptyList()
        } catch (e: Exception) {
            emptyList()
        }
    }
    
    fun isFavorite(contentId: String): Boolean {
        return getFavoritesList().any { it.contentId == contentId }
    }
    
    fun clearFavorites() {
        prefs.edit().remove(KEY_FAVORITES).apply()
    }
    
    // Recently Watched operations
    fun addRecentlyWatched(item: RecentlyWatchedItem) {
        val currentList = getRecentlyWatchedList().toMutableList()
        
        // Remove existing item with same contentId if present
        currentList.removeAll { it.contentId == item.contentId }
        
        // Add new item at the beginning
        currentList.add(0, item)
        
        // Keep only last 30 items
        if (currentList.size > MAX_RECENTLY_WATCHED) {
            currentList.subList(MAX_RECENTLY_WATCHED, currentList.size).clear()
        }
        
        val json = gson.toJson(currentList)
        prefs.edit().putString(KEY_RECENTLY_WATCHED, json).apply()
    }
    
    fun getRecentlyWatchedList(): List<RecentlyWatchedItem> {
        val json = prefs.getString(KEY_RECENTLY_WATCHED, null) ?: return emptyList()
        return try {
            val type = object : TypeToken<List<RecentlyWatchedItem>>() {}.type
            gson.fromJson<List<RecentlyWatchedItem>>(json, type) ?: emptyList()
        } catch (e: Exception) {
            emptyList()
        }
    }
    
    fun clearRecentlyWatched() {
        prefs.edit().remove(KEY_RECENTLY_WATCHED).apply()
    }
    
    // Recent Live Channels operations (for live TV history)
    fun addRecentLiveChannel(item: RecentLiveChannelItem) {
        val currentList = getRecentLiveChannelsList().toMutableList()
        
        // Remove existing item with same channelId if present
        currentList.removeAll { it.channelId == item.channelId }
        
        // Add new item at the beginning
        currentList.add(0, item)
        
        // Keep only last 10 items
        if (currentList.size > MAX_RECENT_LIVE_CHANNELS) {
            currentList.subList(MAX_RECENT_LIVE_CHANNELS, currentList.size).clear()
        }
        
        val json = gson.toJson(currentList)
        prefs.edit().putString(KEY_RECENT_LIVE_CHANNELS, json).apply()
    }
    
    fun getRecentLiveChannelsList(): List<RecentLiveChannelItem> {
        val json = prefs.getString(KEY_RECENT_LIVE_CHANNELS, null) ?: return emptyList()
        return try {
            val type = object : TypeToken<List<RecentLiveChannelItem>>() {}.type
            gson.fromJson<List<RecentLiveChannelItem>>(json, type) ?: emptyList()
        } catch (e: Exception) {
            emptyList()
        }
    }
    
    fun clearRecentLiveChannels() {
        prefs.edit().remove(KEY_RECENT_LIVE_CHANNELS).apply()
    }
    
    fun clearAll() {
        prefs.edit().clear().apply()
    }
    
    companion object {
        private const val PREFS_NAME = "watch_history"
        private const val KEY_CONTINUE_WATCHING = "continue_watching"
        private const val KEY_FAVORITES = "favorites"
        private const val KEY_RECENTLY_WATCHED = "recently_watched"
        private const val KEY_RECENT_LIVE_CHANNELS = "recent_live_channels"
        private const val MAX_CONTINUE_WATCHING = 20
        private const val MAX_RECENTLY_WATCHED = 30
        private const val MAX_RECENT_LIVE_CHANNELS = 10
    }
}
