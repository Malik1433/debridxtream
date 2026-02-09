package com.tvonnet.debridxtreamiptv.ui.settings

import android.content.SharedPreferences
import android.os.Bundle
import android.widget.Toast
import androidx.preference.ListPreference
import androidx.preference.Preference
import androidx.preference.PreferenceFragmentCompat
import androidx.preference.SwitchPreferenceCompat
import com.tvonnet.debridxtreamiptv.R
import com.tvonnet.debridxtreamiptv.worker.EpgSyncController
import com.tvonnet.debridxtreamiptv.worker.EpgSyncScheduler
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Week 14: EPG Settings Fragment
 * 
 * Features:
 * - EPG sync enable/disable
 * - Sync interval configuration (6/12/24/48 hours)
 * - Network-only sync option
 * - Battery saver option
 * - Manual sync trigger
 * - Last sync time display
 * - Sync status display
 */
class EpgSettingsFragment : PreferenceFragmentCompat(), 
    SharedPreferences.OnSharedPreferenceChangeListener {
    
    private var syncEnabledPref: SwitchPreferenceCompat? = null
    private var syncIntervalPref: ListPreference? = null
    private var syncNowPref: Preference? = null
    private var lastSyncPref: Preference? = null
    private var syncStatusPref: Preference? = null
    
    override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
        setPreferencesFromResource(R.xml.preferences_epg, rootKey)
        
        // Get preference references
        syncEnabledPref = findPreference("epg_sync_enabled")
        syncIntervalPref = findPreference("epg_sync_interval")
        syncNowPref = findPreference("epg_sync_now")
        lastSyncPref = findPreference("epg_last_sync")
        syncStatusPref = findPreference("epg_sync_status")
        
        setupSyncEnable()
        setupSyncInterval()
        setupSyncNow()
        setupClearCache()
        updateSyncStatus()
        updateLastSyncTime()
    }
    
    override fun onResume() {
        super.onResume()
        preferenceScreen.sharedPreferences?.registerOnSharedPreferenceChangeListener(this)
        updateSyncStatus()
        updateLastSyncTime()
    }
    
    override fun onPause() {
        super.onPause()
        preferenceScreen.sharedPreferences?.unregisterOnSharedPreferenceChangeListener(this)
    }
    
    override fun onSharedPreferenceChanged(sharedPreferences: SharedPreferences?, key: String?) {
        when (key) {
            "epg_sync_enabled" -> handleSyncEnabledChange()
            "epg_sync_interval" -> handleSyncIntervalChange()
        }
    }
    
    /**
     * Setup sync enable/disable switch
     */
    private fun setupSyncEnable() {
        syncEnabledPref?.setOnPreferenceChangeListener { _, newValue ->
            val enabled = newValue as Boolean
            
            if (enabled) {
                applyCurrentPreferences()
                Toast.makeText(
                    requireContext(),
                    "EPG auto-sync enabled",
                    Toast.LENGTH_SHORT
                ).show()
            } else {
                // Disable sync
                EpgSyncScheduler.cancelSync(requireContext())
                Toast.makeText(
                    requireContext(),
                    "EPG auto-sync disabled",
                    Toast.LENGTH_SHORT
                ).show()
            }
            
            updateSyncStatus()
            true
        }
    }
    
    /**
     * Setup sync interval selector
     */
    private fun setupSyncInterval() {
        syncIntervalPref?.setOnPreferenceChangeListener { _, newValue ->
            applyCurrentPreferences()
            updateSyncStatus()
            true
        }
        
        // Update summary to show current value
        syncIntervalPref?.summaryProvider = ListPreference.SimpleSummaryProvider.getInstance()
    }
    
    /**
     * Setup manual sync button
     */
    private fun setupSyncNow() {
        syncNowPref?.setOnPreferenceClickListener {
            // Trigger immediate sync
            EpgSyncScheduler.scheduleImmediateSync(requireContext())
            
            Toast.makeText(
                requireContext(),
                "EPG sync started...",
                Toast.LENGTH_SHORT
            ).show()
            
            // Update last sync time after a delay (2 seconds)
            android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                updateLastSyncTime()
                updateSyncStatus()
            }, 2000)
            
            true
        }
    }
    
    /**
     * Setup clear cache button
     */
    private fun setupClearCache() {
        val clearCachePref: Preference? = findPreference("clear_cache")
        clearCachePref?.setOnPreferenceClickListener {
            // Show confirmation dialog
            android.app.AlertDialog.Builder(requireContext())
                .setTitle("Clear Cache")
                .setMessage("Are you sure you want to clear all cached data? This will remove EPG data and you'll need to refresh.")
                .setPositiveButton("Clear") { _, _ ->
                    clearCache()
                }
                .setNegativeButton("Cancel", null)
                .show()
            
            true
        }
    }
    
    /**
     * Clear all cached data
     */
    private fun clearCache() {
        try {
            // Clear app cache directory
            requireContext().cacheDir.deleteRecursively()
            
            // Save last sync time to SharedPreferences
            val prefs = requireContext().getSharedPreferences("epg_prefs", android.content.Context.MODE_PRIVATE)
            prefs.edit().remove("last_sync_time").apply()
            
            Toast.makeText(
                requireContext(),
                "Cache cleared successfully",
                Toast.LENGTH_SHORT
            ).show()
            
            updateLastSyncTime()
        } catch (e: Exception) {
            Toast.makeText(
                requireContext(),
                "Failed to clear cache: ${e.message}",
                Toast.LENGTH_LONG
            ).show()
        }
    }
    
    /**
     * Update sync status display
     */
    private fun updateSyncStatus() {
        val isScheduled = try {
            EpgSyncScheduler.isSyncScheduled(requireContext())
        } catch (e: Exception) {
            false
        }
        
            val syncEnabled = syncEnabledPref?.isChecked ?: true
            val interval = syncIntervalPref?.value ?: "6"
            
            val status = when {
                !syncEnabled -> "Disabled"
                isScheduled -> "Active (every $interval hours)"
                else -> "Inactive"
            }
        
        syncStatusPref?.summary = status
    }
    
    /**
     * Update last sync time display
     */
    private fun updateLastSyncTime() {
        try {
            // Get last sync time from SharedPreferences
            val prefs = requireContext().getSharedPreferences("epg_prefs", android.content.Context.MODE_PRIVATE)
            val lastSyncTime = prefs.getLong("last_sync_time", 0L)
            
            val summary = if (lastSyncTime > 0) {
                val dateFormat = SimpleDateFormat("MMM dd, yyyy 'at' hh:mm a", Locale.getDefault())
                val timeAgo = getTimeAgo(lastSyncTime)
                "${dateFormat.format(Date(lastSyncTime))} ($timeAgo)"
            } else {
                "Never synced"
            }
            
            lastSyncPref?.summary = summary
        } catch (e: Exception) {
            lastSyncPref?.summary = "Unknown"
        }
    }
    
    /**
     * Get human-readable time ago string
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
            hours > 0 -> "$hours hour${if (hours > 1) "s" else ""} ago"
            minutes > 0 -> "$minutes minute${if (minutes > 1) "s" else ""} ago"
            seconds > 0 -> "$seconds second${if (seconds > 1) "s" else ""} ago"
            else -> "Just now"
        }
    }
    
    /**
     * Handle sync enabled change
     */
    private fun handleSyncEnabledChange() {
        applyCurrentPreferences()
        updateSyncStatus()
    }
    
    /**
     * Handle sync interval change
     */
    private fun handleSyncIntervalChange() {
        applyCurrentPreferences()
        updateSyncStatus()
    }

    private fun applyCurrentPreferences() {
        preferenceScreen.sharedPreferences?.let { prefs ->
            EpgSyncController().syncFromPreferences(requireContext(), prefs)
        }
    }
}
