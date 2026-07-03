package com.tvonnet.debridxtreamiptv.di

import android.content.Context
import androidx.room.Room
import com.tvonnet.debridxtreamiptv.data.cache.CacheHelper
import com.tvonnet.debridxtreamiptv.data.cache.CacheManager
import com.tvonnet.debridxtreamiptv.data.cache.FavoritesCache
import com.tvonnet.debridxtreamiptv.data.local.AppDatabase
import com.tvonnet.debridxtreamiptv.data.local.migrations.DatabaseMigrations
import com.tvonnet.debridxtreamiptv.data.network.NetworkMonitor
import com.tvonnet.debridxtreamiptv.data.local.dao.CategoryDao
import com.tvonnet.debridxtreamiptv.data.local.dao.ChannelDao
import com.tvonnet.debridxtreamiptv.data.local.dao.EpgDao
import com.tvonnet.debridxtreamiptv.data.local.dao.FavoriteDao
import com.tvonnet.debridxtreamiptv.data.local.dao.SearchHistoryDao
import com.tvonnet.debridxtreamiptv.data.local.dao.SeriesDao
import com.tvonnet.debridxtreamiptv.data.local.dao.VodDao
import com.tvonnet.debridxtreamiptv.data.local.dao.WatchedStateDao
import com.tvonnet.debridxtreamiptv.data.prefs.CredentialsPreferences
import com.tvonnet.debridxtreamiptv.data.prefs.SettingsPreferences
import com.tvonnet.debridxtreamiptv.data.repository.XtreamRepository
import com.tvonnet.debridxtreamiptv.utils.memory.MemoryManager
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.Dispatchers
import javax.inject.Singleton
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit

/**
 * Hilt App Module
 * Provides singleton dependencies for the entire application
 */
@Module
@InstallIn(SingletonComponent::class)
object AppModule {
    
    /**
     * Provides CacheHelper as a singleton
     */
    @Provides
    @Singleton
    fun provideCacheHelper(@ApplicationContext context: Context): CacheHelper {
        return CacheHelper(context)
    }
    
    /**
     * Provides CredentialsPreferences as a singleton
     */
    @Provides
    @Singleton
    fun provideCredentialsPreferences(@ApplicationContext context: Context): CredentialsPreferences {
        return CredentialsPreferences(context)
    }
    
    /**
     * Provides SettingsPreferences as a singleton
     */
    @Provides
    @Singleton
    fun provideSettingsPreferences(@ApplicationContext context: Context): SettingsPreferences {
        return SettingsPreferences(context)
    }

    /**
     * Provides WatchHistoryPreferences as a singleton
     */
    @Provides
    @Singleton
    fun provideWatchHistoryPreferences(@ApplicationContext context: Context): com.tvonnet.debridxtreamiptv.data.prefs.WatchHistoryPreferences {
        return com.tvonnet.debridxtreamiptv.data.prefs.WatchHistoryPreferences(context)
    }
    
    /**
     * Provides CacheManager as a singleton
     * Week 7: Multi-Level Caching Strategy
     */
    @Provides
    @Singleton
    fun provideCacheManager(
        channelDao: ChannelDao,
        categoryDao: CategoryDao
    ): CacheManager {
        return CacheManager(channelDao, categoryDao)
    }
    
    /**
     * Provides MemoryManager as a singleton for memory optimization
     */
    @Provides
    @Singleton
    fun provideMemoryManager(
        @ApplicationContext context: Context
    ): MemoryManager {
        return MemoryManager.getInstance(context)
    }

    /**
     * Provides XtreamRepository as a singleton
     * Week 7: Updated to inject CacheManager
     * Week 10: Updated to inject FavoriteDao and SearchHistoryDao
     * Week 12: Updated to inject FavoritesCache for performance
     * Week 13: Updated to inject EpgDao for background sync
     * Week 15: Updated to inject MemoryManager for optimization
     */
    @Provides
    @Singleton
    fun provideXtreamRepository(
        @ApplicationContext context: Context,
        cacheManager: CacheManager,
        favoriteDao: FavoriteDao,
        searchHistoryDao: SearchHistoryDao,
        epgDao: EpgDao,
        vodDao: VodDao,
        seriesDao: SeriesDao,
        favoritesCache: FavoritesCache,
        memoryManager: MemoryManager
    ): XtreamRepository {
        return XtreamRepository(
            context,
            cacheManager,
            favoriteDao,
            searchHistoryDao,
            epgDao,
            vodDao,
            seriesDao,
            favoritesCache,
            memoryManager
        )
    }
    
    /**
     * Provides FavoritesCache as a singleton
     * Week 12: Performance optimization for favorite checks
     */
    @Provides
    @Singleton
    fun provideFavoritesCache(): FavoritesCache {
        return FavoritesCache()
    }
    
    /**
     * Provides Room Database as a singleton
     * Week 6: Room Database Integration
     * Week 12: Added proper migrations (QA Recommendation #1)
     * Week 14: Phase 2.1 - Memory Management optimization
     */
    @Provides
    @Singleton
    fun provideAppDatabase(@ApplicationContext context: Context): AppDatabase {
        return Room.databaseBuilder(
            context,
            AppDatabase::class.java,
            AppDatabase.DATABASE_NAME
        )
            // Week 12: Add proper migrations to preserve user data
            .addMigrations(*DatabaseMigrations.getAllMigrations())
            // Fallback for unhandled migrations (dev safety net)
            .fallbackToDestructiveMigration()
            // Week 14: Phase 2.1 - Memory Management fixes
            .setJournalMode(androidx.room.RoomDatabase.JournalMode.WRITE_AHEAD_LOGGING) // Enable WAL for better concurrency
            .build()
    }
    
    /**
     * Provides ChannelDao
     * Week 6: Room Database Integration
     */
    @Provides
    @Singleton
    fun provideChannelDao(database: AppDatabase): ChannelDao {
        return database.channelDao()
    }
    
    /**
     * Provides CategoryDao
     * Week 6: Room Database Integration
     */
    @Provides
    @Singleton
    fun provideCategoryDao(database: AppDatabase): CategoryDao {
        return database.categoryDao()
    }
    
    /**
     * Provides FavoriteDao
     * Week 6: Room Database Integration
     */
    @Provides
    @Singleton
    fun provideFavoriteDao(database: AppDatabase): FavoriteDao {
        return database.favoriteDao()
    }
    
    /**
     * Provides SearchHistoryDao
     * Week 10: Search History Persistence
     */
    @Provides
    @Singleton
    fun provideSearchHistoryDao(database: AppDatabase): SearchHistoryDao {
        return database.searchHistoryDao()
    }
    
    /**
     * Provides EpgDao
     * Week 11: EPG (Electronic Program Guide)
     * Week 13: Required for EpgSyncWorker
     */
    @Provides
    @Singleton
    fun provideEpgDao(database: AppDatabase): EpgDao {
        return database.epgDao()
    }
    
    /**
     * Provides NetworkMonitor
     * Week 8: Network Optimization
     */
    @Provides
    @Singleton
    fun provideNetworkMonitor(@ApplicationContext context: Context): NetworkMonitor {
        return NetworkMonitor(context)
    }

    /**
     * Provides OkHttpClient optimized for streaming
     * Phase 0/2: Connection Pooling & Timeouts
     * Phase 5.1: 4K Pump - Enhanced Pool for High Bitrate
     */
    @Provides
    @Singleton
    fun provideOkHttpClient(): OkHttpClient {
        return OkHttpClient.Builder()
            // 4K Pump: Massive Connection Pool for high-throughput segments
            .connectionPool(okhttp3.ConnectionPool(32, 10, TimeUnit.MINUTES))
            .connectTimeout(15, TimeUnit.SECONDS) // Increased for slow 4K servers
            .readTimeout(15, TimeUnit.SECONDS)    // Vital for large 4K chunks
            .writeTimeout(15, TimeUnit.SECONDS)
            .followRedirects(true)
            .followSslRedirects(true)
            .retryOnConnectionFailure(true)
            .build()
    }
    /**
     * Provides VodDao
     * Phase 1: Database Infrastructure
     */
    @Provides
    @Singleton
    fun provideVodDao(database: AppDatabase): VodDao {
        return database.vodDao()
    }

    /**
     * Provides SeriesDao
     * Phase 1: Database Infrastructure
     */
    @Provides
    @Singleton
    fun provideSeriesDao(database: AppDatabase): SeriesDao {
        return database.seriesDao()
    }
    /**
     * Provides SeriesDaoV2
     * Phase 3: High Performance Series Engine
     */
    @Provides
    @Singleton
    fun provideSeriesDaoV2(database: AppDatabase): com.tvonnet.debridxtreamiptv.features.seriesv2.data.dao.SeriesDaoV2 {
        return database.seriesDaoV2()
    }

    @Provides
    @Singleton
    fun provideEpisodeDaoV2(database: AppDatabase): com.tvonnet.debridxtreamiptv.features.seriesv2.data.dao.EpisodeDaoV2 {
        return database.episodeDaoV2()
    }

    @Provides
    @Singleton
    fun provideWatchedStateDao(database: AppDatabase): WatchedStateDao {
        return database.watchedStateDao()
    }
}

