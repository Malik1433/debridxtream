package com.tvonnet.debridxtreamiptv.ui.settings

import android.content.SharedPreferences
import android.os.Bundle
import android.widget.Toast
import androidx.fragment.app.FragmentManager
import androidx.fragment.app.commit
import androidx.preference.ListPreference
import androidx.preference.Preference
import androidx.preference.PreferenceFragmentCompat
import androidx.preference.SwitchPreferenceCompat
import androidx.lifecycle.lifecycleScope
import com.tvonnet.debridxtreamiptv.R
import com.tvonnet.debridxtreamiptv.data.cache.CacheHelper
import com.tvonnet.debridxtreamiptv.data.cache.CacheManager
import com.tvonnet.debridxtreamiptv.data.prefs.CredentialsPreferences
import com.tvonnet.debridxtreamiptv.data.prefs.DebridPreferences
import com.tvonnet.debridxtreamiptv.data.prefs.WatchHistoryPreferences
import com.tvonnet.debridxtreamiptv.data.repository.XtreamRepository
import com.tvonnet.debridxtreamiptv.ui.LoginFragment
import com.tvonnet.debridxtreamiptv.worker.EpgSyncController
import com.tvonnet.debridxtreamiptv.worker.EpgSyncScheduler
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.launch
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import javax.inject.Inject

/**
 * Week 14: Modern Settings Fragment using PreferenceFragmentCompat
 * 
 * Features:
 * - Professional TV app-style settings
 * - Auto-scrolling support
 * - D-pad friendly navigation
 * - Categorized sections
 * - EPG sync controls
 */
@AndroidEntryPoint
class SettingsFragmentNew : PreferenceFragmentCompat(),
    SharedPreferences.OnSharedPreferenceChangeListener {
    
    @Inject
    lateinit var repository: XtreamRepository

    @Inject
    lateinit var cacheManager: CacheManager

    @Inject
    lateinit var cacheHelper: CacheHelper

    @Inject
    lateinit var debridPreferences: DebridPreferences
    
    override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
        setPreferencesFromResource(R.xml.preferences_settings, rootKey)
        
        // Week 14: Repository is injected via Hilt with proper DAOs (EpgDao available)
        // Initialize with saved credentials
        val credentialsPrefs = com.tvonnet.debridxtreamiptv.data.prefs.CredentialsPreferences(requireContext())
        val serverUrl = credentialsPrefs.getServerUrl()
        val username = credentialsPrefs.getUsername()
        val password = credentialsPrefs.getPassword()
        if (serverUrl != null && username != null && password != null) {
            repository.initialize(serverUrl, username, password)
        }
        
        setupPreferences()
    }
    
    override fun onResume() {
        super.onResume()
        preferenceScreen.sharedPreferences?.registerOnSharedPreferenceChangeListener(this)
        updateEpgStatus()
    }
    
    override fun onPause() {
        super.onPause()
        preferenceScreen.sharedPreferences?.unregisterOnSharedPreferenceChangeListener(this)
    }
    
    override fun onSharedPreferenceChanged(sharedPreferences: SharedPreferences?, key: String?) {
        when (key) {
            "epg_auto_sync" -> handleEpgAutoSyncChange()
            "epg_auto_sync" -> handleEpgAutoSyncChange()
            "epg_sync_interval" -> handleEpgIntervalChange()
            "enable_series_v2_engine" -> {
                val enabled = sharedPreferences?.getBoolean(key, false) ?: false
                com.tvonnet.debridxtreamiptv.core.featureflag.FeatureFlagManager.setSeriesV2Enabled(enabled)
                
                Toast.makeText(requireContext(), "Restarting to apply changes...", Toast.LENGTH_LONG).show()
                view?.postDelayed({ restartApp() }, 1000)
            }
        }
    }
    
    private fun setupPreferences() {
        // IPTV Data - Refresh Now
        findPreference<Preference>("refresh_now")?.setOnPreferenceClickListener {
            refreshIptvData()
            true
        }
        
        // EPG - Sync Now
        findPreference<Preference>("epg_sync_now")?.setOnPreferenceClickListener {
            syncEpgNow()
            true
        }
        
        // Performance - Clear Cache
        findPreference<Preference>("clear_cache")?.setOnPreferenceClickListener {
            showClearCacheDialog()
            true
        }

        findPreference<Preference>("logout")?.setOnPreferenceClickListener {
            confirmLogout()
            true
        }
        
        // Update EPG status on load
        updateEpgStatus()
    }
    
    /**
     * Refresh IPTV data (all content)
     */
    private fun refreshIptvData() {
        Toast.makeText(requireContext(), "Refreshing IPTV data...", Toast.LENGTH_SHORT).show()
        
        lifecycleScope.launch {
            try {
                val result = repository.forceRefresh()
                
                if (result is com.tvonnet.debridxtreamiptv.data.Result.Success) {
                    Toast.makeText(
                        requireContext(),
                        "IPTV data refreshed successfully",
                        Toast.LENGTH_SHORT
                    ).show()
                } else {
                    Toast.makeText(
                        requireContext(),
                        "Refresh failed. Please try again.",
                        Toast.LENGTH_LONG
                    ).show()
                }
            } catch (e: Exception) {
                Toast.makeText(
                    requireContext(),
                    "Error: ${e.message}",
                    Toast.LENGTH_LONG
                ).show()
            }
        }
    }
    
    /**
     * Sync EPG data now (manual trigger)
     */
    private fun syncEpgNow() {
        Toast.makeText(requireContext(), "Syncing EPG data...", Toast.LENGTH_SHORT).show()
        
        // Trigger immediate WorkManager sync
        EpgSyncScheduler.scheduleImmediateSync(requireContext())
        
        // Also fetch directly for instant update
        lifecycleScope.launch {
            try {
                val result = repository.fetchAndSaveEpg()
                
                if (result is com.tvonnet.debridxtreamiptv.data.Result.Success) {
                    val count = result.data
                    Toast.makeText(
                        requireContext(),
                        "EPG synced: $count programs",
                        Toast.LENGTH_SHORT
                    ).show()
                    
                    android.util.Log.d("SettingsFragment", "EPG manual sync success: $count programs")
                    
                    // Update status after sync
                    android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                        updateEpgStatus()
                    }, 1000)
                } else {
                    Toast.makeText(
                        requireContext(),
                        "EPG sync failed. Please try again.",
                        Toast.LENGTH_LONG
                    ).show()
                }
            } catch (e: Exception) {
                Toast.makeText(
                    requireContext(),
                    "Error: ${e.message}",
                    Toast.LENGTH_LONG
                ).show()
                android.util.Log.e("SettingsFragment", "EPG sync error", e)
            }
        }
    }
    
    /**
     * Show clear cache confirmation dialog
     */
    private fun showClearCacheDialog() {
        android.app.AlertDialog.Builder(requireContext())
            .setTitle("Clear Cache")
            .setMessage("Are you sure you want to clear all cached data?")
            .setPositiveButton("Clear") { _, _ ->
                clearCache()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }
    
    /**
     * Clear app cache
     */
    private fun clearCache() {
        try {
            requireContext().cacheDir.deleteRecursively()
            Toast.makeText(requireContext(), "Cache cleared", Toast.LENGTH_SHORT).show()
        } catch (e: Exception) {
            Toast.makeText(requireContext(), "Failed to clear cache", Toast.LENGTH_SHORT).show()
        }
    }

    private fun confirmLogout() {
        android.app.AlertDialog.Builder(requireContext())
            .setTitle("Logout")
            .setMessage("Are you sure you want to log out?")
            .setPositiveButton("Logout") { _, _ ->
                performLogout()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun performLogout() {
        val appContext = requireContext().applicationContext
        val activity = requireActivity()
        lifecycleScope.launch {
            withContext(Dispatchers.IO) {
                runCatching { cacheManager.clearAllCaches() }
                runCatching { cacheHelper.clearCache() }
            }
            runCatching { repository.clearMemoryCache() }
            runCatching { WatchHistoryPreferences(appContext).clearAll() }
            runCatching { debridPreferences.clearAll() }
            CredentialsPreferences(appContext).clearCredentials()
            navigateToLogin(activity)
        }
    }

    private fun navigateToLogin(activity: androidx.fragment.app.FragmentActivity) {
        activity.supportFragmentManager.popBackStack(null, FragmentManager.POP_BACK_STACK_INCLUSIVE)
        activity.supportFragmentManager.commit {
            replace(R.id.content_container, LoginFragment())
        }
    }
    
    /**
     * Handle EPG auto-sync toggle
     */
    private fun handleEpgAutoSyncChange() {
        val enabled = preferenceScreen.sharedPreferences?.getBoolean("epg_auto_sync", true) ?: true
        
        applyEpgPreferences()
        updateEpgStatus()
    }
    
    /**
     * Handle EPG sync interval change
     */
    private fun handleEpgIntervalChange() {
        applyEpgPreferences()
        updateEpgStatus()
    }
    
    /**
     * Update EPG sync status displays
     */
    private fun updateEpgStatus() {
        try {
            val statusPref = findPreference<Preference>("epg_sync_status")
            val lastSyncPref = findPreference<Preference>("epg_last_sync")
            
            // Update status
            val isScheduled = EpgSyncScheduler.isSyncScheduled(requireContext())
            val enabled = preferenceScreen.sharedPreferences?.getBoolean("epg_auto_sync", true) ?: true
            val interval = preferenceScreen.sharedPreferences?.getString("epg_sync_interval", "6") ?: "6"
            
            val status = when {
                !enabled -> "Disabled"
                isScheduled -> "Active (every $interval hours)"
                else -> "Inactive"
            }
            
            statusPref?.summary = status
            
            // Update last sync time
            val prefs = requireContext().getSharedPreferences("epg_prefs", android.content.Context.MODE_PRIVATE)
            val lastSyncTime = prefs.getLong("last_sync_time", 0L)
            
            val lastSyncText = if (lastSyncTime > 0) {
                val dateFormat = SimpleDateFormat("MMM dd, hh:mm a", Locale.getDefault())
                val timeAgo = getTimeAgo(lastSyncTime)
                "${dateFormat.format(Date(lastSyncTime))} ($timeAgo)"
            } else {
                "Never synced"
            }
            
            lastSyncPref?.summary = lastSyncText
            
        } catch (e: Exception) {
            android.util.Log.e("SettingsFragment", "Error updating EPG status", e)
        }
    }
    
    /**
     * Get human-readable time ago
     */
    private fun getTimeAgo(timeMillis: Long): String {
        val now = System.currentTimeMillis()
        val diff = now - timeMillis
        
        val seconds = diff / 1000
        val minutes = seconds / 60
        val hours = minutes / 60
        val days = hours / 24
        
        return when {
            days > 0 -> "$days day${if (days > 1) "s" else ""} ago"
            hours > 0 -> "$hours hr${if (hours > 1) "s" else ""} ago"
            minutes > 0 -> "$minutes min ago"
            seconds > 30 -> "Just now"
            else -> "Now"
        }
    }

    private fun applyEpgPreferences() {
        preferenceScreen.sharedPreferences?.let { prefs ->
            EpgSyncController().syncFromPreferences(requireContext(), prefs)
        }
    }
    private fun restartApp() {
        // Create restart intent
        val packageManager = requireContext().packageManager
        val intent = packageManager.getLaunchIntentForPackage(requireContext().packageName)
        val componentName = intent?.component
        val mainIntent = android.content.Intent.makeRestartActivityTask(componentName)
        
        // Restart
        requireContext().startActivity(mainIntent)
        Runtime.getRuntime().exit(0)
    }
}
