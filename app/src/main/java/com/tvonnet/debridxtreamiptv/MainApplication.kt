package com.tvonnet.debridxtreamiptv

import android.app.Activity
import android.app.Application
import androidx.hilt.work.HiltWorkerFactory
import com.tvonnet.debridxtreamiptv.util.padForSystemBars
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
        // G3: the diagnostics switch decides whether Crashlytics/Analytics collect at all.
        com.tvonnet.debridxtreamiptv.util.DiagnosticsConsent.applyStored(this)

        installAppCheck()

        registerSystemBarInsetPadding()

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

    /**
     * App Check, Play Integrity provider - MONITOR ONLY (security review 2026-09-24).
     *
     * Play Integrity needs Google Play services on the device, and this app's actual fleet is
     * Fire TV - stock Fire OS does NOT ship Play services (see the Crashlytics comment above,
     * which already had to account for this). If Firestore's rules were made to REQUIRE an
     * App Check token, every customer on a stock Fire TV would be locked out alongside any
     * cracked client - the security fix would be a worse outage than the thing it defends
     * against. So this installs the provider and nothing reads its result: Firebase Console's
     * App Check tab starts reporting verified/unverified traffic, which is the data needed to
     * decide whether enforcement is ever safe here. No Firestore rule depends on this today.
     *
     * Never crashes app start: on a device with no Play services the token fetch fails
     * asynchronously and Firestore simply proceeds without one, exactly as it did before this
     * existed.
     */
    private fun installAppCheck() {
        runCatching {
            com.google.firebase.appcheck.FirebaseAppCheck.getInstance().installAppCheckProviderFactory(
                com.google.firebase.appcheck.playintegrity.PlayIntegrityAppCheckProviderFactory.getInstance()
            )
        }.onFailure {
            android.util.Log.w("MainApplication", "App Check provider install failed (non-fatal)", it)
        }
    }

    /**
     * targetSdk 35 means Android 15 draws every activity edge-to-edge whether it asked to or not,
     * and this app handled no insets anywhere — so on a phone the screen title sat UNDER the system
     * clock, on Settings and on Home alike. The phone rulebook is explicit: nothing under the bars.
     *
     * Done once here rather than in each of the eight chrome activities: it cannot be forgotten in
     * a new one, and there is a single place that names the exceptions.
     *
     * A television reports zero system-bar insets, so this changes nothing on the TV.
     */
    private fun registerSystemBarInsetPadding() {
        registerActivityLifecycleCallbacks(object : ActivityLifecycleCallbacks {
            override fun onActivityCreated(activity: Activity, savedInstanceState: android.os.Bundle?) {
                // Video is meant to reach the edges; padding the player or the trailer would frame
                // the picture in black bars on a phone.
                if (activity is com.tvonnet.debridxtreamiptv.player.stabilized.PlayerActivity) return
                if (activity is com.tvonnet.debridxtreamiptv.ui.trailer.TrailerActivity) return
                activity.findViewById<android.view.View>(android.R.id.content)?.padForSystemBars()
            }

            override fun onActivityStarted(activity: Activity) = Unit
            override fun onActivityResumed(activity: Activity) = Unit
            override fun onActivityPaused(activity: Activity) = Unit
            override fun onActivityStopped(activity: Activity) = Unit
            override fun onActivitySaveInstanceState(activity: Activity, outState: android.os.Bundle) = Unit
            override fun onActivityDestroyed(activity: Activity) = Unit
        })
    }

    override val workManagerConfiguration: Configuration
        get() = Configuration.Builder()
            .setWorkerFactory(workerFactory)
            .build()
}
