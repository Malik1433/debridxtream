package com.tvonnet.debridxtreamiptv

import android.app.Application
import androidx.hilt.work.HiltWorkerFactory
import androidx.work.Configuration
import androidx.preference.PreferenceManager
import com.tvonnet.debridxtreamiptv.worker.EpgSyncController
import dagger.hilt.android.HiltAndroidApp
import javax.inject.Inject

/**
 * Application class for DebridXtreamIPTV
 * Annotated with @HiltAndroidApp to enable Hilt Dependency Injection
 * Week 13: Added WorkManager configuration and EPG sync scheduler
 */
@HiltAndroidApp
class App : Application(), Configuration.Provider {
    
    @Inject
    lateinit var workerFactory: HiltWorkerFactory
    
    override fun onCreate() {
        super.onCreate()
        
        // Initialize Feature Flags
        // Initialize Feature Flags
        com.tvonnet.debridxtreamiptv.core.featureflag.FeatureFlagManager.init(this)

        // Initialize Global Crash Handler
        com.tvonnet.debridxtreamiptv.util.GlobalCrashHandler.init(this)

        // Align WorkManager with the latest user preference snapshot
        val preferences = PreferenceManager.getDefaultSharedPreferences(this)
        EpgSyncController().syncFromPreferences(this, preferences)
        
        // Schedule Series Cache Pruning (Janitor)
        com.tvonnet.debridxtreamiptv.features.seriesv2.worker.SeriesCachePruningWorker.schedule(this)
    }
    
    /**
     * Week 13: Provide WorkManager configuration for Hilt integration
     */
    override val workManagerConfiguration: Configuration
        get() = Configuration.Builder()
            .setWorkerFactory(workerFactory)
            .setMinimumLoggingLevel(android.util.Log.INFO)
            .build()
}
