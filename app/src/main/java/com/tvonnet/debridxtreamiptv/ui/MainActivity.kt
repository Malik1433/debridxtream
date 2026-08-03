package com.tvonnet.debridxtreamiptv.ui

import android.Manifest
import android.content.Intent
import android.os.Bundle
import android.speech.RecognizerIntent
import android.util.Log
import android.view.KeyEvent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.fragment.app.commit
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.tvonnet.debridxtreamiptv.R
import com.tvonnet.debridxtreamiptv.data.prefs.CredentialsPreferences
import com.tvonnet.debridxtreamiptv.data.repository.XtreamRepository
import com.tvonnet.debridxtreamiptv.utils.memory.MemoryManager
import com.tvonnet.debridxtreamiptv.util.GlobalConfig
import com.tvonnet.debridxtreamiptv.ui.live.LiveFragment
import com.tvonnet.debridxtreamiptv.ui.vod.VodFragment
import com.tvonnet.debridxtreamiptv.ui.series.SeriesFragment

import com.tvonnet.debridxtreamiptv.ui.search.SearchFragment
import com.tvonnet.debridxtreamiptv.util.VoiceSearchManager
import com.tvonnet.debridxtreamiptv.util.VoiceSearchState
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject
import com.tvonnet.debridxtreamiptv.data.network.NetworkQualityManager
import com.tvonnet.debridxtreamiptv.data.prefs.SettingsPreferences
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

@AndroidEntryPoint
class MainActivity : AppCompatActivity() {

    // Phase 3.2: Voice Search Integration
    private lateinit var voiceSearchManager: VoiceSearchManager
    private val requestPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        if (isGranted) {
            voiceSearchManager.startVoiceSearch()
        } else {
            Log.w("MainActivity", "Microphone permission denied for voice search")
        }
    }

    @Inject
    lateinit var repository: XtreamRepository

    @Inject
    lateinit var networkQualityManager: NetworkQualityManager

    @Inject
    lateinit var settingsPreferences: SettingsPreferences
    
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Licensing gate: this device must be activated (admin-controlled via Firestore).
        // Decision is cache-first (instant, survives brief outages); the realtime listener
        // is kept alive so a deactivation propagates to the cache for the next launch, and
        // ActivationActivity auto-advances the moment the admin activates.
        if (!startBackgroundChannelsAndGate()) return

        // Check if already logged in
        val credentialsPrefs = CredentialsPreferences(this)
        
        if (!credentialsPrefs.isLoggedIn()) {
            // User not logged in - show login fragment in container
            setContentView(R.layout.activity_main)
            if (savedInstanceState == null) {
                supportFragmentManager.commit {
                    replace(R.id.content_container, LoginFragment())
                }
            }
            return
        }
        
        // User is logged in - show beautiful home screen
        setContentView(R.layout.activity_main)
        
        // Initialize repository with saved credentials
        // repository is injected by Hilt
        val serverUrl = credentialsPrefs.getServerUrl()
        val username = credentialsPrefs.getUsername()
        val password = credentialsPrefs.getPassword()
        
        if (serverUrl != null && username != null && password != null) {
            repository.initialize(serverUrl, username, password)
            // Phase 3.3: Initialize GlobalConfig for system-wide access
            GlobalConfig.baseUrl = serverUrl
            GlobalConfig.username = username
            GlobalConfig.password = password
            scheduleLaunchEpgRefresh()
        }

        // Load beautiful cinematic home screen by default
        if (savedInstanceState == null) {
            supportFragmentManager.commit {
                replace(R.id.content_container, com.tvonnet.debridxtreamiptv.ui.home.HomeFragment())
            }
        }

        // Auto-update: offer (or force) an update when the admin publishes a newer
        // versionCode in app_config/version. One check per process; no-op when current.
        com.tvonnet.debridxtreamiptv.update.UpdateManager.checkOnLaunch(this)

        // Check if we need to open settings directly
        handleNavigationIntent(intent)
        
        // Phase 3.2: Initialize Voice Search Manager
        initializeVoiceSearch()

        // Phase 1: Smart Network Detection.
        // ST-6: defer the 1MB speed test ~30s past launch so it doesn't fight the
        // first home content fetch (TMDB + posters) for the Fire TV's Wi-Fi.
        lifecycleScope.launch {
            kotlinx.coroutines.delay(30_000)
            val quality = networkQualityManager.runSpeedTest()
            settingsPreferences.saveNetworkQuality(quality.name)
            Log.i("MainActivity", "Network Quality Optimized: ${quality.name}")
        }
    }

    // Auto-refresh EPG on app launch so the guide is never empty on first open.
    // ensureEpgData() is guarded (only syncs when the table is empty or the last
    // sync is older than the refresh interval), so this does NOT re-download the
    // full XMLTV on every launch. Also (re)register the periodic background sync
    // here so EPG stays fresh even if the user never opens Settings.
    private fun scheduleLaunchEpgRefresh() {
        lifecycleScope.launch(Dispatchers.IO) {
            // ST-7: give the first home render a ~10s head start before the
            // EPG XMLTV download/parse competes for network + memory on launch.
            kotlinx.coroutines.delay(10_000)
            runCatching {
                com.tvonnet.debridxtreamiptv.worker.EpgSyncController()
                    .syncFromPreferences(applicationContext)
            }
            runCatching { repository.ensureEpgData() }
                .onSuccess { synced -> Log.i("MainActivity", "EPG auto-check on launch: synced=$synced") }
                .onFailure { Log.w("MainActivity", "EPG auto-check on launch failed", it) }
        }
    }

    // Returns false when the licence gate sent us to ActivationActivity and onCreate must stop.
    private fun startBackgroundChannelsAndGate(): Boolean {
        val license = com.tvonnet.debridxtreamiptv.data.licensing.LicenseManager.getInstance(this)
        license.start()
        // Permanent companion channel: the phone/web config page can push updated
        // settings anytime via the device key (see CompanionConfigSync).
        com.tvonnet.debridxtreamiptv.ui.companion.CompanionConfigSync.start(this)
        // Give this device an identity so ownership-based rules become possible (§7 U3).
        // Fire-and-forget by design — nothing below waits on it, and it is a no-op while
        // anonymous sign-in is disabled in the console.
        com.tvonnet.debridxtreamiptv.data.licensing.DeviceIdentity.start(this)
        // §7 U6: read this account's playlists instead of waiting to be pushed to. Flag-gated,
        // default OFF — it rewrites the credentials we log in with.
        com.tvonnet.debridxtreamiptv.ui.companion.AccountPlaylistSync.start(this)
        if (!license.isEntitledCached()) {
            startActivity(Intent(this, com.tvonnet.debridxtreamiptv.ui.licensing.ActivationActivity::class.java))
            finish()
            return false
        }
        // Lock promptly if the admin revokes this device WHILE the app is running
        // (deactivate or delete) — don't wait for the next cold launch.
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                license.state.collect { st ->
                    if (st is com.tvonnet.debridxtreamiptv.data.licensing.LicenseState.Locked && !isFinishing) {
                        startActivity(Intent(this@MainActivity, com.tvonnet.debridxtreamiptv.ui.licensing.ActivationActivity::class.java))
                        finish()
                    }
                }
            }
        }
        return true
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleNavigationIntent(intent)
    }

    private fun handleNavigationIntent(intent: Intent) {
        if (intent.getBooleanExtra("OPEN_SETTINGS", false)) {
             supportFragmentManager.commit {
                replace(R.id.content_container, com.tvonnet.debridxtreamiptv.ui.settings.SettingsFragment())
                addToBackStack(null)
            }
        }
    }

    private fun initializeVoiceSearch() {
        voiceSearchManager = VoiceSearchManager.getInstance(this)
        Log.d("MainActivity", "Voice search manager initialized")

        // Observe voice search state to pass results to SearchFragment
        observeVoiceSearchState()
    }

    private fun observeVoiceSearchState() {
        lifecycleScope.launch {
            voiceSearchManager.voiceSearchState.collect { state ->
                Log.d("MainActivity", "Voice search state: $state")

                when (state) {
                    is VoiceSearchState.Success -> {
                        Log.d("MainActivity", "Voice search successful: ${state.results}")
                        // Pass the first result to SearchFragment
                        val query = state.results.firstOrNull()
                        if (query != null) {
                            passVoiceQueryToSearchFragment(query)
                        }
                    }
                    is VoiceSearchState.Error -> {
                        Log.e("MainActivity", "Voice search error: ${state.message}")
                    }
                    else -> {
                        // Handle other states as needed
                    }
                }
            }
        }
    }

    private fun passVoiceQueryToSearchFragment(query: String) {
        // Find the current SearchFragment and pass the voice query
        supportFragmentManager.fragments.forEach { fragment ->
            if (fragment is SearchFragment) {
                Log.d("MainActivity", "Passing voice query to SearchFragment: '$query'")
                fragment.setVoiceQuery(query)
                return@forEach
            }
        }

        // If SearchFragment is not found, navigate to it and set query
        Log.d("MainActivity", "SearchFragment not found, navigating with query: '$query'")
        supportFragmentManager.commit {
            replace(R.id.content_container, SearchFragment().apply {
                arguments = Bundle().apply {
                    putString("voice_query", query)
                }
            })
            addToBackStack(null)
        }
    }

    override fun onBackPressed() {
        val currentFragment = supportFragmentManager.findFragmentById(R.id.content_container)
        val fragmentName = currentFragment?.javaClass?.simpleName ?: "null"

        // Priority 0: Debrid Stremio home consumes BACK for its own overlays/tabs first
        // (it is added with addToBackStack, so it must be handled before the pop below).
        if (currentFragment is com.tvonnet.debridxtreamiptv.ui.debrid.stremio.StremioHomeFragment) {
            if (currentFragment.handleBackPress()) return
        }

        // Priority 1: If there is a back stack history, pop it
        if (supportFragmentManager.backStackEntryCount > 0) {
            super.onBackPressed()
            return
        }
        
        // Priority 2: Pre-login screens are the root when signed out — exit the app
        // instead of falling through to the "navigate back to Home" branch, which
        // would open a blank Home with no credentials.
        if (currentFragment is LoginFragment) {
            if (!currentFragment.handleBackPress()) finish()
            return
        }
        if (currentFragment is InitialSyncFragment) {
            finish()
            return
        }

        // Priority 3: Root Level Handling
        if (currentFragment is com.tvonnet.debridxtreamiptv.ui.home.HomeFragment) {
            // 2a. If at Home, check scroll first
            val handled = currentFragment.handleBackPress()
            if (handled) return

            // 2b. If scrolled to top, Show Exit Dialog
             com.tvonnet.debridxtreamiptv.ui.dialog.ExitDialog.newInstance()
                 .show(supportFragmentManager, com.tvonnet.debridxtreamiptv.ui.dialog.ExitDialog.TAG)
        } else {
            // 3. We are at a root fragment that is NOT Home (e.g. Live TV, Debrid).
            // Navigate back to Home.
            supportFragmentManager.commit {
                replace(R.id.content_container, com.tvonnet.debridxtreamiptv.ui.home.HomeFragment())
            }
        }
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (event.action == KeyEvent.ACTION_DOWN) {
            when (event.keyCode) {
                // Phase 3.2: Voice search activation with microphone button
                KeyEvent.KEYCODE_SEARCH -> {
                    if (!::voiceSearchManager.isInitialized) return true
                    startVoiceSearch()
                    return true
                }
                // Alternative voice search trigger for TV remotes
                KeyEvent.KEYCODE_VOICE_ASSIST -> {
                    if (!::voiceSearchManager.isInitialized) return true
                    startVoiceSearch()
                    return true
                }
            }
        }
        return super.dispatchKeyEvent(event)
    }

    private fun startVoiceSearch() {
        Log.d("MainActivity", "Voice search triggered")

        // Check microphone permission
        if (androidx.core.content.ContextCompat.checkSelfPermission(
                this, Manifest.permission.RECORD_AUDIO
            ) == android.content.pm.PackageManager.PERMISSION_GRANTED) {

            // Permission already granted, start voice search
            if (voiceSearchManager.startVoiceSearch()) {
                // Navigate to search fragment to show voice search UI
                navigateToSearchFragment()
            }
        } else {
            // Request microphone permission
            requestPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
        }
    }

    private fun navigateToSearchFragment() {
        supportFragmentManager.commit {
            replace(R.id.content_container, SearchFragment())
            addToBackStack(null)
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        if (::voiceSearchManager.isInitialized) {
            voiceSearchManager.destroy()
        }
    }
}
