package com.tvonnet.debridxtreamiptv.ui.settings

import com.tvonnet.debridxtreamiptv.ui.settings.MediaFusionConfigActivity

import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.fragment.app.Fragment
import androidx.fragment.app.FragmentManager
import androidx.fragment.app.commit
import androidx.fragment.app.viewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.LinearLayoutManager
import com.tvonnet.debridxtreamiptv.BuildConfig
import com.tvonnet.debridxtreamiptv.R
import com.tvonnet.debridxtreamiptv.data.cache.CacheHelper
import com.tvonnet.debridxtreamiptv.data.cache.CacheManager
import com.tvonnet.debridxtreamiptv.data.prefs.CredentialsPreferences
import com.tvonnet.debridxtreamiptv.data.prefs.WatchHistoryPreferences
import com.tvonnet.debridxtreamiptv.data.repository.XtreamRepository
import com.tvonnet.debridxtreamiptv.databinding.FragmentSettingsV2Binding
import com.tvonnet.debridxtreamiptv.ui.LoginFragment
import com.tvonnet.debridxtreamiptv.worker.EpgSyncScheduler
import com.tvonnet.debridxtreamiptv.ui.settings.adapters.SettingItem
import com.tvonnet.debridxtreamiptv.ui.settings.adapters.SettingsCategoryAdapter
import com.tvonnet.debridxtreamiptv.ui.settings.adapters.SettingsDetailAdapter
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject

@AndroidEntryPoint
class SettingsFragment : Fragment() {

    private var _binding: FragmentSettingsV2Binding? = null
    private val binding get() = _binding!!
    
    private val viewModel: SettingsViewModel by viewModels()

    @Inject
    lateinit var repository: XtreamRepository

    @Inject
    lateinit var cacheManager: CacheManager

    @Inject
    lateinit var cacheHelper: CacheHelper
    
    private lateinit var categoryAdapter: SettingsCategoryAdapter
    private lateinit var detailAdapter: SettingsDetailAdapter

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentSettingsV2Binding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        // Sidebar lock removed: cinematic sidebars are fixed-width and don't auto-collapse,
        // so no lock is needed. Origin's SidebarFocusHelper no longer exposes setLocked().

        setupAdapters()
        observeState()
    }

    private fun setupAdapters() {
        // Categories (Left)
        categoryAdapter = SettingsCategoryAdapter { category ->
            if (category == SettingCategory.LOGOUT) {
                showAccountLogoutConfirmation()
            } else {
                viewModel.selectCategory(category)
                categoryAdapter.setSelectedCategory(category)
            }
        }
        binding.rvSettingsCategories.apply {
            layoutManager = LinearLayoutManager(context)
            adapter = categoryAdapter
        }

        // Details (Right)
        detailAdapter = SettingsDetailAdapter()
        binding.rvSettingsDetails.apply {
            layoutManager = LinearLayoutManager(context)
            adapter = detailAdapter
        }
    }

    private fun observeState() {
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.uiState.collectLatest { state ->
                    updateDetails(state)
                }
            }
        }
    }

    private fun updateDetails(state: SettingsUiState) {
        val items = when (state.selectedCategory) {
            SettingCategory.GENERAL -> listOf(
                SettingItem.Toggle(
                    key = "autoplay",
                    title = "Auto-Play Next Episode",
                    description = "Automatically start next episode when current one ends",
                    isChecked = state.isAutoPlayEnabled,
                    onToggle = { viewModel.toggleAutoPlay(it) }
                ),
                SettingItem.Action(
                    key = "logout_account",
                    title = "Logout",
                    description = "Sign out of this account",
                    onClick = { showAccountLogoutConfirmation() }
                )
            )
            SettingCategory.PLAYER -> listOf(
                SettingItem.Selection(
                    key = "engine",
                    title = "Player Engine",
                    currentValue = getEngineName(state.playerEngine),
                    onClick = { showEngineSelector(state.playerEngine) }
                ),
                SettingItem.Toggle(
                    key = "tunneling",
                    title = "Tunneling Mode (AFR)",
                    description = "May improve smooth playback on some TVs (Experimental)",
                    isChecked = state.isTunnelingEnabled,
                    onToggle = { viewModel.toggleTunneling(it) }
                ),
                SettingItem.Toggle(
                    key = "software_audio",
                    title = "Smart Audio Fallback",
                    description = "Automatically switch tracks if the main audio format (like AC3/EAC3) is unsupported",
                    isChecked = state.isSoftwareAudioEnabled,
                    onToggle = { viewModel.toggleSoftwareAudio(it) }
                )
            )
            SettingCategory.VISUALS -> listOf(
                SettingItem.Toggle(
                    key = "scale",
                    title = "Card Animation",
                    description = "Scale and glow effect when focusing on items",
                    isChecked = state.isUiScaleEnabled,
                    onToggle = { viewModel.toggleUiScale(it) }
                )
            )
            SettingCategory.IPTV_EPG -> listOf(
                SettingItem.Action(
                    key = "refresh_iptv",
                    title = "Refresh IPTV Data",
                    description = "Force update live channels, movies, and series",
                    onClick = { refreshIptvData() }
                ),
                SettingItem.Toggle(
                    key = "epg_auto_sync",
                    title = "Auto EPG Sync",
                    description = "Automatically refresh EPG in background",
                    isChecked = state.isEpgAutoSyncEnabled,
                    onToggle = { viewModel.toggleEpgAutoSync(it) }
                ),
                SettingItem.Selection(
                    key = "epg_sync_interval",
                    title = "EPG Sync Interval",
                    currentValue = getEpgIntervalName(state.epgSyncIntervalHours),
                    onClick = { showEpgIntervalSelector(state.epgSyncIntervalHours) }
                ),
                SettingItem.Action(
                    key = "epg_sync_now",
                    title = "Sync EPG Now",
                    description = "Manually fetch EPG TV guide data",
                    onClick = { syncEpgNow() }
                ),
                SettingItem.Action(
                    key = "clear_cache",
                    title = "Clear Cached TV Data",
                    description = "Wipe temporary storage to resolve issues",
                    onClick = { showClearCacheDialog() }
                )
            )
            SettingCategory.HOME -> listOf(
                SettingItem.Action(
                    key = "home_custom_movie",
                    title = "Customize Movie Rows",
                    description = "Select categories to show on Home",
                    onClick = { showCategorySelector("movie") }
                ),
                SettingItem.Action(
                    key = "home_custom_series",
                    title = "Customize Series Rows",
                    description = "Select categories to show on Home",
                    onClick = { showCategorySelector("series") }
                ),
                SettingItem.Action(
                    key = "home_custom_live",
                    title = "Customize Live TV Rows",
                    description = "Select categories to show on Home",
                    onClick = { showCategorySelector("live") }
                )
            )
            SettingCategory.DEBRID -> listOf(
                SettingItem.Action(
                    key = "manage_debrid",
                    title = if (state.isDebridAuthenticated) "Manage Real-Debrid" else "Login to Real-Debrid",
                    description = if (state.isDebridAuthenticated) "Status: Authorized (Click to Logout)" else "Link your account to unlock premium streams",
                    onClick = {
                        if (state.isDebridAuthenticated) {
                            showLogoutConfirmation()
                        } else {
                            parentFragmentManager.beginTransaction()
                                .replace(R.id.content_container, com.tvonnet.debridxtreamiptv.ui.debrid.DebridAuthFragment())
                                .addToBackStack(null)
                                .commit()
                        }
                    }
                ),
                SettingItem.Action(
                    key = "config_mediafusion",
                    title = "Configure MediaFusion",
                    description = "Setup catalogs via Companion App",
                    onClick = {
                        val intent = Intent(context, MediaFusionConfigActivity::class.java)
                        startActivity(intent)
                    }
                ),
                SettingItem.Action(
                    key = "manage_scraper_sources",
                    title = "Manage Scraper Sources",
                    description = "${state.addonRegistryUrls.size} custom source(s) active  •  Add unlimited JSON registries",
                    onClick = { showManageScraperSourcesDialog(state.addonRegistryUrls) }
                )
            )
            SettingCategory.ABOUT -> listOf(
                SettingItem.Action(
                    key = "version",
                    title = "Version",
                    description = "${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})",
                    onClick = {}
                )
            )
            SettingCategory.LOGOUT -> emptyList()
        }
        detailAdapter.submitList(items)
    }

    private fun getEngineName(engine: String): String {
        return when (engine) {
            "exo" -> "ExoPlayer (Default)"
            "vlc" -> "VLC Player"
            "mx" -> "MX Player"
            else -> "ExoPlayer"
        }
    }

    private fun getRegistryName(url: String): String {
        return when (url) {
            com.tvonnet.debridxtreamiptv.data.prefs.DebridPreferences.DEFAULT_REGISTRY_URL -> "DebridXtream Default"
            com.tvonnet.debridxtreamiptv.data.prefs.DebridPreferences.PUREFIRE_REGISTRY_URL -> "PureFire Optimized (Hindi)"
            else -> "Custom Source"
        }
    }

    private fun getEpgIntervalName(hours: String): String {
        return "Every $hours hours"
    }

    private fun showEpgIntervalSelector(current: String) {
        val options = arrayOf("Every 3 hours", "Every 6 hours", "Every 12 hours", "Every 24 hours")
        val values = arrayOf("3", "6", "12", "24")
        val checkedItem = values.indexOf(current).takeIf { it != -1 } ?: 1

        AlertDialog.Builder(requireContext())
            .setTitle("Select EPG Sync Interval")
            .setSingleChoiceItems(options, checkedItem) { dialog, which ->
                viewModel.setEpgSyncInterval(values[which])
                dialog.dismiss()
            }
            .show()
    }

    private fun showRegistrySelector(current: String) {
        val options = arrayOf("DebridXtream Default", "PureFire Optimized (Hindi)", "Custom URL...")
        val values = arrayOf(
            com.tvonnet.debridxtreamiptv.data.prefs.DebridPreferences.DEFAULT_REGISTRY_URL,
            com.tvonnet.debridxtreamiptv.data.prefs.DebridPreferences.PUREFIRE_REGISTRY_URL,
            "custom"
        )
        val checkedItem = when {
            current == values[0] -> 0
            current == values[1] -> 1
            else -> 2
        }

        AlertDialog.Builder(requireContext())
            .setTitle("Select Addon Source")
            .setSingleChoiceItems(options, checkedItem) { dialog, which ->
                if (values[which] == "custom") {
                    showCustomRegistryInput(current)
                } else {
                    viewModel.setAddonRegistryUrl(values[which])
                    Toast.makeText(context, "Addon Source Updated", Toast.LENGTH_SHORT).show()
                }
                dialog.dismiss()
            }
            .show()
    }

    private fun showCustomRegistryInput(current: String) {
        val input = android.widget.EditText(requireContext()).apply {
            setText(if (current.startsWith("http")) current else "")
            setHint("https://your-url.json")
            setTextColor(android.graphics.Color.WHITE)
        }
        
        AlertDialog.Builder(requireContext())
            .setTitle("Enter Custom Registry URL")
            .setView(input)
            .setPositiveButton("Save") { dialog, _ ->
                val url = input.text.toString().trim()
                if (url.startsWith("http")) {
                    viewModel.setAddonRegistryUrl(url)
                    Toast.makeText(context, "Custom Source Saved", Toast.LENGTH_SHORT).show()
                } else {
                    Toast.makeText(context, "Invalid URL", Toast.LENGTH_SHORT).show()
                }
                dialog.dismiss()
            }
            .setNegativeButton("Cancel") { dialog, _ -> dialog.dismiss() }
            .show()
    }

    /**
     * Displays a dialog allowing the user to manage (add/remove) scraper registry URLs.
     * Each URL is shown as a list item with a "Remove" action.
     * An "Add Source" button is shown at the bottom.
     *
     * @param currentUrls The current set of active registry URLs.
     */
    private fun showManageScraperSourcesDialog(currentUrls: Set<String>) {
        val urls = currentUrls.toMutableList()

        // Build a simple text list with item indices
        val buildItems = {
            if (urls.isEmpty()) {
                arrayOf("No custom sources added yet.\nTap '+ Add Source' below to add one.")
            } else {
                urls.mapIndexed { index, url ->
                    val shortName = try {
                        val segments = android.net.Uri.parse(url).pathSegments
                        segments.lastOrNull() ?: url.takeLast(50)
                    } catch (_: Exception) {
                        url.takeLast(50)
                    }
                    "${index + 1}. $shortName"
                }.toTypedArray()
            }
        }

        val dialog = AlertDialog.Builder(requireContext())
            .setTitle("Scraper Sources (${urls.size})")
            .setItems(buildItems()) { _, which ->
                if (urls.isNotEmpty() && which < urls.size) {
                    // Confirm removal
                    val urlToRemove = urls[which]
                    AlertDialog.Builder(requireContext())
                        .setTitle("Remove Source?")
                        .setMessage(urlToRemove)
                        .setPositiveButton("Remove") { d, _ ->
                            viewModel.removeAddonRegistryUrl(urlToRemove)
                            Toast.makeText(context, "Source removed", Toast.LENGTH_SHORT).show()
                            d.dismiss()
                        }
                        .setNegativeButton("Cancel") { d, _ -> d.dismiss() }
                        .show()
                }
            }
            .setPositiveButton("+ Add Source") { dialog, _ ->
                dialog.dismiss()
                showAddScraperSourceInput()
            }
            .setNegativeButton("Close") { dialog, _ -> dialog.dismiss() }
            .show()
    }

    /**
     * Shows a text input dialog for the user to paste a new JSON registry URL.
     */
    private fun showAddScraperSourceInput() {
        val input = android.widget.EditText(requireContext()).apply {
            setHint("https://example.com/scrapers.json")
            setTextColor(android.graphics.Color.WHITE)
            setHintTextColor(android.graphics.Color.GRAY)
            setPadding(40, 30, 40, 30)
        }

        AlertDialog.Builder(requireContext())
            .setTitle("Add Scraper Source")
            .setMessage("Paste the full URL of a JSON scraper registry:")
            .setView(input)
            .setPositiveButton("Add") { dialog, _ ->
                val url = input.text.toString().trim()
                if (url.startsWith("http") && (url.endsWith(".json") || url.contains("/"))) {
                    viewModel.addAddonRegistryUrl(url)
                    Toast.makeText(context, "✅ Source added!", Toast.LENGTH_SHORT).show()
                } else {
                    Toast.makeText(context, "Invalid URL — must start with http", Toast.LENGTH_SHORT).show()
                }
                dialog.dismiss()
            }
            .setNegativeButton("Cancel") { dialog, _ -> dialog.dismiss() }
            .show()
    }

    private fun showEngineSelector(current: String) {
        val options = arrayOf("ExoPlayer (Default)", "VLC Player", "MX Player")
        val values = arrayOf("exo", "vlc", "mx")
        val checkedItem = values.indexOf(current).takeIf { it != -1 } ?: 0

        AlertDialog.Builder(requireContext())
            .setTitle("Select Player Engine")
            .setSingleChoiceItems(options, checkedItem) { dialog, which ->
                viewModel.setPlayerEngine(values[which])
                dialog.dismiss()
            }
            .show()
    }

    private fun refreshIptvData() {
        Toast.makeText(requireContext(), "Refreshing IPTV data...", Toast.LENGTH_SHORT).show()
        viewLifecycleOwner.lifecycleScope.launch {
            try {
                val result = repository.forceRefresh()
                if (result is com.tvonnet.debridxtreamiptv.data.Result.Success) {
                    Toast.makeText(requireContext(), "IPTV data refreshed successfully", Toast.LENGTH_SHORT).show()
                } else {
                    Toast.makeText(requireContext(), "Refresh failed. Please try again.", Toast.LENGTH_LONG).show()
                }
            } catch (e: Exception) {
                Toast.makeText(requireContext(), "Error: \${e.message}", Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun syncEpgNow() {
        Toast.makeText(requireContext(), "Syncing EPG data...", Toast.LENGTH_SHORT).show()
        EpgSyncScheduler.scheduleImmediateSync(requireContext())
        viewLifecycleOwner.lifecycleScope.launch {
            try {
                val result = repository.fetchAndSaveEpg()
                if (result is com.tvonnet.debridxtreamiptv.data.Result.Success) {
                    Toast.makeText(requireContext(), "EPG synced: \${result.data} programs", Toast.LENGTH_SHORT).show()
                } else {
                    Toast.makeText(requireContext(), "EPG sync failed. Please try again.", Toast.LENGTH_LONG).show()
                }
            } catch (e: Exception) {
                Toast.makeText(requireContext(), "Error: \${e.message}", Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun showClearCacheDialog() {
        AlertDialog.Builder(requireContext())
            .setTitle("Clear Cache")
            .setMessage("Are you sure you want to clear all cached TV data?")
            .setPositiveButton("Clear") { _, _ ->
                try {
                    requireContext().cacheDir.deleteRecursively()
                    Toast.makeText(requireContext(), "Cache cleared", Toast.LENGTH_SHORT).show()
                } catch (e: Exception) {
                    Toast.makeText(requireContext(), "Failed to clear cache", Toast.LENGTH_SHORT).show()
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun showLogoutConfirmation() {
        AlertDialog.Builder(requireContext())
            .setTitle("Log Out of Real-Debrid?")
            .setMessage("You will lose access to premium cached streams. Are you sure?")
            .setPositiveButton("Log Out") { dialog, _ ->
                viewModel.logoutDebrid()
                dialog.dismiss()
                Toast.makeText(context, "Logged out of Real-Debrid", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("Cancel") { dialog, _ ->
                dialog.dismiss()
            }
            .show()
    }

    private fun showAccountLogoutConfirmation() {
        AlertDialog.Builder(requireContext())
            .setTitle("Logout")
            .setMessage("Are you sure you want to log out?")
            .setPositiveButton("Logout") { dialog, _ ->
                performAccountLogout()
                dialog.dismiss()
            }
            .setNegativeButton("Cancel") { dialog, _ ->
                dialog.dismiss()
            }
            .show()
    }

    private fun performAccountLogout() {
        val appContext = requireContext().applicationContext
        val activity = requireActivity()
        viewLifecycleOwner.lifecycleScope.launch {
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

    private fun navigateToLogin(activity: androidx.fragment.app.FragmentActivity) {
        activity.supportFragmentManager.popBackStack(null, FragmentManager.POP_BACK_STACK_INCLUSIVE)
        activity.supportFragmentManager.commit {
            replace(R.id.content_container, LoginFragment())
        }
    }
    
    private fun showCategorySelector(type: String) {
        if (!isAdded) return
        
        val loadingDialog = AlertDialog.Builder(requireContext())
            .setTitle("Loading Categories...")
            .setView(android.widget.ProgressBar(context).apply { setPadding(40, 40, 40, 40) })
            .setCancelable(false)
            .create()

        loadingDialog.show()

        viewLifecycleOwner.lifecycleScope.launch {
            try {
                val categories = viewModel.getCategories(type)
                if (loadingDialog.isShowing) {
                    loadingDialog.dismiss()
                }
                
                if (!isAdded || isStateSaved) return@launch

                if (categories.isEmpty()) {
                    Toast.makeText(requireContext(), "No categories found. Sync active?", Toast.LENGTH_SHORT).show()
                    return@launch
                }
                
                // Fix: Create instance and configure WITHOUT using .apply { ... } to avoid context scope confusion
                val dialogFragment = CategorySelectionDialog.newInstance(type, categories)
                
                dialogFragment.setCallback { 
                    // Use requireContext() explicitly to ensure we are using the attached Fragment's context
                    if (isAdded) {
                        Toast.makeText(requireContext(), "Home preferences saved", Toast.LENGTH_SHORT).show()
                    }
                }
                
                dialogFragment.show(childFragmentManager, CategorySelectionDialog.TAG)
                
            } catch (e: Exception) {
                if (loadingDialog.isShowing) {
                    loadingDialog.dismiss()
                }
                if (isAdded) {
                    // Log error but avoid showing "not attached" toast if that was the issue
                    val msg = e.message ?: "Unknown error"
                    if (!msg.contains("not been attached yet")) {
                        Toast.makeText(requireContext(), "Error: $msg", Toast.LENGTH_SHORT).show()
                    }
                }
            }
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
