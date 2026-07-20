
package com.tvonnet.debridxtreamiptv.data.local

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import com.tvonnet.debridxtreamiptv.data.local.converters.DatabaseConverters
import com.tvonnet.debridxtreamiptv.data.local.dao.CategoryDao
import com.tvonnet.debridxtreamiptv.data.local.dao.ChannelDao
import com.tvonnet.debridxtreamiptv.data.local.dao.EpgDao
import com.tvonnet.debridxtreamiptv.data.local.dao.FavoriteDao
import com.tvonnet.debridxtreamiptv.data.local.dao.SearchHistoryDao
import com.tvonnet.debridxtreamiptv.data.local.dao.SeriesDao
import com.tvonnet.debridxtreamiptv.data.local.dao.VodDao
import com.tvonnet.debridxtreamiptv.data.local.dao.WatchedStateDao
import com.tvonnet.debridxtreamiptv.data.local.entity.CategoryEntity
import com.tvonnet.debridxtreamiptv.data.local.entity.ChannelEntity
import com.tvonnet.debridxtreamiptv.data.local.entity.EpgEntity
import com.tvonnet.debridxtreamiptv.data.local.entity.FavoriteEntity
import com.tvonnet.debridxtreamiptv.data.local.entity.SearchHistoryEntity
import com.tvonnet.debridxtreamiptv.data.local.entity.SeriesEntity
import com.tvonnet.debridxtreamiptv.data.local.entity.VodEntity
import com.tvonnet.debridxtreamiptv.data.local.entity.SeasonEntity
import com.tvonnet.debridxtreamiptv.data.local.entity.EpisodeEntity
import com.tvonnet.debridxtreamiptv.data.local.entity.WatchedStateEntity
import com.tvonnet.debridxtreamiptv.features.seriesv2.data.model.SeriesEntityV2
import com.tvonnet.debridxtreamiptv.features.seriesv2.data.dao.SeriesDaoV2

/**
 * Main Room Database for the app
 * Week 6: Room Database Integration
 * Week 8: Updated to version 2 - Added cachedAt timestamps for TTL support
 * Week 10: Updated to version 3 - Added SearchHistory table
 * Week 11: Updated to version 4 - Added EPG table
 * Week 12: Updated to version 5 - Added name and iconUrl to FavoriteEntity
 * Phase 1: Updated to version 6 - Added VOD and Series tables
 * Phase 2: Updated to version 7 - Added Season and Episode tables
 *
 * Features:
 * - Local caching of channels, categories
 * - Favorites management
 * - Search history persistence
 * - EPG (Electronic Program Guide) data
 * - Offline data support
 * - TTL-based cache expiry (Week 8)
 * - Type converters for complex data types (Phase 3.2)
 */
@Database(
    entities = [
        ChannelEntity::class,
        CategoryEntity::class,
        FavoriteEntity::class,
        SearchHistoryEntity::class,
        EpgEntity::class,
        VodEntity::class,
        SeriesEntity::class,
        SeasonEntity::class,
        EpisodeEntity::class,
        SeriesEntityV2::class,
        com.tvonnet.debridxtreamiptv.features.seriesv2.data.model.EpisodeEntityV2::class,
        WatchedStateEntity::class
    ],
    version = 14,
    // Phase 8: export the schema JSON (room.schemaLocation in build.gradle) so Room migrations become
    // testable via MigrationTestHelper — the gate for removing fallbackToDestructiveMigration (7.5).
    // NOTE: schemas only exist from v14 forward (export was off historically); pre-14 versions have
    // no exported JSON, so the helper can only prove v14 -> future paths.
    exportSchema = true
)
@TypeConverters(DatabaseConverters::class)
abstract class AppDatabase : RoomDatabase() {

    /**
     * Provides access to Channel operations
     */
    abstract fun channelDao(): ChannelDao

    /**
     * Provides access to Category operations
     */
    abstract fun categoryDao(): CategoryDao

    /**
     * Provides access to Favorites operations
     */
    abstract fun favoriteDao(): FavoriteDao

    /**
     * Provides access to Search History operations
     * Week 10: Search History Persistence
     */
    abstract fun searchHistoryDao(): SearchHistoryDao

    /**
     * Provides access to EPG operations
     * Week 11: EPG (Electronic Program Guide)
     */
    abstract fun epgDao(): EpgDao

    /**
     * Provides access to VOD operations
     * Phase 1: Database Infrastructure
     */
    abstract fun vodDao(): VodDao

    /**
     * Provides access to Series operations
     * Phase 1: Database Infrastructure
     */
    abstract fun seriesDao(): SeriesDao

    /**
     * V2 Rescue Patch: Series DAO
     * Phase 3: High Performance Series Engine
     */
    abstract fun seriesDaoV2(): SeriesDaoV2
    abstract fun episodeDaoV2(): com.tvonnet.debridxtreamiptv.features.seriesv2.data.dao.EpisodeDaoV2
    abstract fun watchedStateDao(): WatchedStateDao
    
    companion object {
        const val DATABASE_NAME = "debrid_xtream_db"
    }
}
