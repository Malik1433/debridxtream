package com.tvonnet.debridxtreamiptv

import android.app.Application
import androidx.hilt.work.HiltWorkerFactory
import androidx.work.Configuration
import dagger.hilt.android.HiltAndroidApp
import javax.inject.Inject
import kotlinx.coroutines.launch

@HiltAndroidApp
class MainApplication : Application(), Configuration.Provider {

    @Inject
    lateinit var workerFactory: HiltWorkerFactory

    override fun onCreate() {
        super.onCreate()
        
        com.tvonnet.debridxtreamiptv.util.GlobalCrashHandler.init(this)
        
        // Reset crash counter if app stays alive for 15 seconds
        kotlinx.coroutines.GlobalScope.launch(kotlinx.coroutines.Dispatchers.IO) {
            kotlinx.coroutines.delay(15000)
            val prefs = getSharedPreferences("app_stability", android.content.Context.MODE_PRIVATE)
            prefs.edit().putInt("startup_crash_count", 0).apply()
        }
        // Pre-warm the player cache in the background to avoid UI thread blocking later
        kotlinx.coroutines.GlobalScope.launch(kotlinx.coroutines.Dispatchers.IO) {
            com.tvonnet.debridxtreamiptv.util.PlayerCacheManager.getCache(this@MainApplication)
        }
        // SY-6: schedule the series cache-pruning janitor. Its schedule() had ZERO
        // callers, so series_v2_core/episodes_v2_core grew unbounded forever. KEEP
        // policy + charging/idle constraints make this a safe fire-and-forget; the
        // worker's deletion is id-scoped and favorites-protected.
        kotlinx.coroutines.GlobalScope.launch(kotlinx.coroutines.Dispatchers.IO) {
            runCatching {
                com.tvonnet.debridxtreamiptv.features.seriesv2.worker.SeriesCachePruningWorker
                    .schedule(this@MainApplication)
            }
        }
    }

    override val workManagerConfiguration: Configuration
        get() = Configuration.Builder()
            .setWorkerFactory(workerFactory)
            .build()
}
