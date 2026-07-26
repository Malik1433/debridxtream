package com.tvonnet.debridxtreamiptv.ui.settings

import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.fragment.app.Fragment
import androidx.fragment.app.FragmentActivity
import androidx.fragment.app.FragmentManager
import androidx.fragment.app.commit
import androidx.lifecycle.lifecycleScope
import com.tvonnet.debridxtreamiptv.R
import com.tvonnet.debridxtreamiptv.data.cache.CacheHelper
import com.tvonnet.debridxtreamiptv.data.cache.CacheManager
import com.tvonnet.debridxtreamiptv.data.prefs.CredentialsPreferences
import com.tvonnet.debridxtreamiptv.data.prefs.WatchHistoryPreferences
import com.tvonnet.debridxtreamiptv.data.repository.XtreamRepository
import com.tvonnet.debridxtreamiptv.ui.LoginFragment
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * C1-c: the settings actions that **do something to the app's data**, as opposed to setting a
 * preference — force a catalog refresh, sync EPG, clear the cache, choose Home row categories, and
 * log the account out.
 *
 * These were the riskiest lines in [SettingsFragment] and the least visible: account logout wipes
 * caches, watch history and credentials before navigating away, and it is the only path that can
 * leave the app half-signed-out if the order changes. Grouping them makes that order reviewable in
 * one place.
 *
 * Bodies moved verbatim; only `this`/field references were rebound.
 */
class SettingsMaintenanceActions(
    private val fragment: Fragment,
    private val viewModel: SettingsViewModel,
    private val repository: XtreamRepository,
    private val cacheManager: CacheManager,
    private val cacheHelper: CacheHelper,
) {
    private val context get() = fragment.requireContext()

    fun refreshIptvData() {
        Toast.makeText(context, "Refreshing IPTV data...", Toast.LENGTH_SHORT).show()
        fragment.viewLifecycleOwner.lifecycleScope.launch {
            try {
                val result = repository.forceRefresh()
                if (result is com.tvonnet.debridxtreamiptv.data.Result.Success) {
                    Toast.makeText(context, "IPTV data refreshed successfully", Toast.LENGTH_SHORT).show()
                } else {
                    Toast.makeText(context, "Refresh failed. Please try again.", Toast.LENGTH_LONG).show()
                }
            } catch (e: Exception) {
                android.util.Log.e(TAG, "IPTV refresh failed", e)
                Toast.makeText(context, "Error: ${e.message}", Toast.LENGTH_LONG).show()
            }
        }
    }

    fun syncEpgNow() {
        Toast.makeText(context, "Syncing EPG data...", Toast.LENGTH_SHORT).show()
        fragment.viewLifecycleOwner.lifecycleScope.launch {
            try {
                val result = repository.fetchAndSaveEpg()
                if (result is com.tvonnet.debridxtreamiptv.data.Result.Success) {
                    Toast.makeText(context, "EPG synced: ${result.data} programs", Toast.LENGTH_SHORT).show()
                } else {
                    Toast.makeText(context, "EPG sync failed. Please try again.", Toast.LENGTH_LONG).show()
                }
            } catch (e: Exception) {
                android.util.Log.e(TAG, "EPG sync failed", e)
                Toast.makeText(context, "Error: ${e.message}", Toast.LENGTH_LONG).show()
            }
        }
    }

    fun showClearCacheDialog() {
        AlertDialog.Builder(context)
            .setTitle("Clear Cache")
            .setMessage("Are you sure you want to clear all cached TV data?")
            .setPositiveButton("Clear") { _, _ ->
                try {
                    context.cacheDir.deleteRecursively()
                    Toast.makeText(context, "Cache cleared", Toast.LENGTH_SHORT).show()
                } catch (e: Exception) {
                    android.util.Log.e(TAG, "Clear cache failed", e)
                    Toast.makeText(context, "Failed to clear cache", Toast.LENGTH_SHORT).show()
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    fun showAccountLogoutConfirmation() {
        AlertDialog.Builder(context)
            .setTitle("Logout")
            .setMessage("Are you sure you want to log out?")
            .setPositiveButton("Logout") { dialog, _ ->
                performAccountLogout()
                dialog.dismiss()
            }
            .setNegativeButton("Cancel") { dialog, _ -> dialog.dismiss() }
            .show()
    }

    /**
     * Order matters: caches and history go first on IO, credentials last, and only then do we
     * navigate — leaving early would strand a signed-out session holding a populated cache.
     */
    private fun performAccountLogout() {
        val appContext = context.applicationContext
        val activity = fragment.requireActivity()
        fragment.viewLifecycleOwner.lifecycleScope.launch {
            withContext(Dispatchers.IO) {
                runCatching { cacheManager.clearAllCaches() }
                runCatching { cacheHelper.clearCache() }
            }
            runCatching { repository.clearMemoryCache() }
            runCatching { WatchHistoryPreferences(appContext).clearAll() }
            CredentialsPreferences(appContext).clearCredentials()
            viewModel.logoutDebrid()
            navigateToLogin(activity)
        }
    }

    private fun navigateToLogin(activity: FragmentActivity) {
        activity.supportFragmentManager.popBackStack(null, FragmentManager.POP_BACK_STACK_INCLUSIVE)
        activity.supportFragmentManager.commit {
            replace(R.id.content_container, LoginFragment())
        }
    }

    fun showCategorySelector(type: String) {
        if (!fragment.isAdded) return

        val loadingDialog = AlertDialog.Builder(context)
            .setTitle("Loading Categories...")
            .setView(android.widget.ProgressBar(context).apply { setPadding(40, 40, 40, 40) })
            .setCancelable(false)
            .create()

        loadingDialog.show()

        fragment.viewLifecycleOwner.lifecycleScope.launch {
            try {
                val categories = viewModel.getCategories(type)
                if (loadingDialog.isShowing) {
                    loadingDialog.dismiss()
                }

                if (!fragment.isAdded || fragment.isStateSaved) return@launch

                if (categories.isEmpty()) {
                    Toast.makeText(context, "No categories found. Sync active?", Toast.LENGTH_SHORT).show()
                    return@launch
                }

                // Fix: Create instance and configure WITHOUT using .apply { ... } to avoid context scope confusion
                val dialogFragment = CategorySelectionDialog.newInstance(type, categories)

                dialogFragment.setCallback {
                    if (fragment.isAdded) {
                        Toast.makeText(context, "Home preferences saved", Toast.LENGTH_SHORT).show()
                    }
                }

                dialogFragment.show(fragment.childFragmentManager, CategorySelectionDialog.TAG)
            } catch (e: Exception) {
                if (loadingDialog.isShowing) {
                    loadingDialog.dismiss()
                }
                if (fragment.isAdded) {
                    // Log error but avoid showing "not attached" toast if that was the issue
                    val msg = e.message ?: "Unknown error"
                    if (!msg.contains("not been attached yet")) {
                        Toast.makeText(context, "Error: $msg", Toast.LENGTH_SHORT).show()
                    }
                }
            }
        }
    }

    private companion object {
        private const val TAG = "SettingsFragment"
    }
}
