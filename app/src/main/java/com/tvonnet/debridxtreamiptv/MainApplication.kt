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

    // Phase 4: an application-lifetime supervised scope replaces GlobalScope for the bootstrap
    // coroutines below — structured (one job failing won't cancel the others or the app), still
    // process-lifetime (these are app-lifetime tasks), and never tied to an Activity.
    private val appScope = kotlinx.coroutines.CoroutineScope(
        kotlinx.coroutines.SupervisorJob() + kotlinx.coroutines.Dispatchers.IO
    )

    override fun onCreate() {
        super.onCreate()
        
        com.tvonnet.debridxtreamiptv.util.GlobalCrashHandler.init(this)
        
        // Reset crash counter if app stays alive for 15 seconds
        appScope.launch {
            kotlinx.coroutines.delay(15000)
            val prefs = getSharedPreferences("app_stability", android.content.Context.MODE_PRIVATE)
            prefs.edit().putInt("startup_crash_count", 0).apply()
        }
        // Pre-warm the player cache in the background to avoid UI thread blocking later
        appScope.launch {
            com.tvonnet.debridxtreamiptv.util.PlayerCacheManager.getCache(this@MainApplication)
        }
        // SY-6: schedule the series cache-pruning janitor. Its schedule() had ZERO
        // callers, so series_v2_core/episodes_v2_core grew unbounded forever. KEEP
        // policy + charging/idle constraints make this a safe fire-and-forget; the
        // worker's deletion is id-scoped and favorites-protected.
        appScope.launch {
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
