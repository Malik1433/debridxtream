package com.tvonnet.debridxtreamiptv.ui.settings

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.core.view.OneShotPreDrawListener
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.LinearLayoutManager
import com.tvonnet.debridxtreamiptv.BuildConfig
import com.tvonnet.debridxtreamiptv.R
import com.tvonnet.debridxtreamiptv.data.cache.CacheHelper
import com.tvonnet.debridxtreamiptv.data.cache.CacheManager
import com.tvonnet.debridxtreamiptv.data.repository.XtreamRepository
import com.tvonnet.debridxtreamiptv.databinding.FragmentSettingsV2Binding
import com.tvonnet.debridxtreamiptv.ui.settings.adapters.SettingItem
import com.tvonnet.debridxtreamiptv.ui.settings.adapters.SettingsCategoryAdapter
import com.tvonnet.debridxtreamiptv.ui.settings.adapters.SettingsDetailAdapter
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
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

    // C1: the screen itself now owns only the two lists, the state subscription and the item
    // mapping below. Choosers live in SettingsSelectorDialogs, anything that mutates app data in
    // SettingsMaintenanceActions, and the value/label vocabulary in SettingsOptionLabels.
    private lateinit var dialogs: SettingsSelectorDialogs
    private lateinit var actions: SettingsMaintenanceActions

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

        // Version lives here and in About; the old duplicate footer block is gone.
        binding.tvSettingsVersion.text = "DEBRIDXTREAM · v${BuildConfig.VERSION_NAME}"

        dialogs = SettingsSelectorDialogs(this, viewModel)
        actions = SettingsMaintenanceActions(this, viewModel, repository, cacheManager, cacheHelper)

        setupAdapters()
        observeState()

        // On a D-pad-only device this screen opened with NOTHING focused — the first press was
        // silently spent finding a target. Two windows matter: the rail's first layout (a bare
        // post{} can run before layout for a synchronous adapter — CC-1), and the moment the
        // activity's nav sidebar closes, which drops activity focus to null AFTER we first drew.
        OneShotPreDrawListener.add(binding.rvSettingsCategories) { assertInitialRailFocus() }
        binding.rvSettingsCategories.postDelayed({ assertInitialRailFocus() }, 400L)
        binding.rvSettingsCategories.postDelayed({ assertInitialRailFocus() }, 1000L)
    }

    /** Focus the first rail item, but never steal — only acts while nothing else holds focus. */
    private fun assertInitialRailFocus() {
        if (_binding == null) return
        if (activity?.currentFocus != null) return
        val first = binding.rvSettingsCategories.findViewHolderForAdapterPosition(0)?.itemView
        if (first?.requestFocus() != true) {
            binding.rvSettingsCategories.requestFocus()
        }
    }

    private fun setupAdapters() {
        // Categories (Left) — NORMAL (IPTV-only) devices don't get the Stremio Addons category.
        categoryAdapter = SettingsCategoryAdapter(
            onCategorySelected = { category ->
                // Every rail item just opens its panel. Selecting a category must never fire a
                // destructive dialog - "Logout" used to do exactly that straight from the rail.
                viewModel.selectCategory(category)
                categoryAdapter.setSelectedCategory(category)
            },
            showDebridCategory = com.tvonnet.debridxtreamiptv.data.licensing.Entitlements
                .isDebridAllowed(requireContext())
        )
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
        // Panel header + accent follow the active category (design "Settings Screen.dc.html").
        val meta = SettingsCategoryAdapter.metaFor(state.selectedCategory)
        binding.tvPanelTitle.text = meta.title
        binding.tvPanelDesc.text = SettingsCategoryAdapter.descFor(state.selectedCategory)
        binding.vPanelDot.background = android.graphics.drawable.GradientDrawable().apply {
            shape = android.graphics.drawable.GradientDrawable.OVAL
            setColor(meta.accent)
        }
        detailAdapter.accent = meta.accent

        detailAdapter.submitList(itemsFor(state))
    }

    // The rows for the selected category, verbatim — each arm's keys, labels and handlers
    // are unchanged; only the enclosing function moved.
    private fun itemsFor(state: SettingsUiState): List<SettingItem> {
        return when (state.selectedCategory) {
            SettingCategory.PLAYBACK -> playbackItems(state)
            SettingCategory.LIVE_TV -> liveTvItems(state)
            SettingCategory.HOME -> homeItems(state)
            SettingCategory.ADDONS -> addonsItems(state)
            SettingCategory.DATA -> dataItems(state)
            SettingCategory.ABOUT -> aboutItems(state)
            SettingCategory.ACCOUNT -> accountItems(state)
        }
    }

    private fun playbackItems(state: SettingsUiState): List<SettingItem> = listOf(
            SettingItem.Selection(
                key = "preferred_audio_lang",
                title = "Preferred Audio Language",
                currentValue = SettingsOptionLabels.audioLangName(state.preferredAudioLang),
                onClick = { dialogs.showPreferredAudioLangSelector(state.preferredAudioLang) }
            ),
            SettingItem.Toggle(
                key = "software_audio",
                title = "Smart Audio Fallback",
                description = "Switch tracks automatically when the main audio format (AC3/EAC3) is not supported",
                isChecked = state.isSoftwareAudioEnabled,
                onToggle = { viewModel.toggleSoftwareAudio(it) }
            )
        )
    private fun liveTvItems(state: SettingsUiState): List<SettingItem> = listOf(
            SettingItem.Selection(
                key = "live_tv_style",
                title = "Live TV Layout",
                currentValue = SettingsOptionLabels.liveTvStyleName(state.liveTvStyle),
                onClick = { dialogs.showLiveTvStyleSelector(state.liveTvStyle) }
            ),
            SettingItem.Toggle(
                key = "resume_last_live",
                title = "Resume Last Channel",
                description = "Open Live TV on the channel you watched last instead of the first",
                isChecked = state.resumeLastLive,
                onToggle = { viewModel.toggleResumeLastLive(it) }
            ),
            SettingItem.Selection(
                key = "epg_zoom",
                title = "TV Guide Timeline Zoom",
                currentValue = SettingsOptionLabels.epgZoomName(state.epgTimelineZoom),
                onClick = { dialogs.showEpgZoomSelector(state.epgTimelineZoom) }
            ),
            SettingItem.Selection(
                key = "epg_density",
                title = "TV Guide Row Density",
                currentValue = SettingsOptionLabels.epgDensityName(state.epgRowDensity),
                onClick = { dialogs.showEpgDensitySelector(state.epgRowDensity) }
            ),
            SettingItem.Toggle(
                key = "epg_genre_tint",
                title = "TV Guide Genre Colours",
                description = "Tint channels and programmes by genre in the TV guide",
                isChecked = state.epgGenreTint,
                onToggle = { viewModel.toggleEpgGenreTint(it) }
            ),
            SettingItem.Toggle(
                key = "epg_auto_sync",
                title = "Auto-Update TV Guide",
                description = "Refresh the guide in the background",
                isChecked = state.isEpgAutoSyncEnabled,
                onToggle = { viewModel.toggleEpgAutoSync(it) }
            ),
            SettingItem.Selection(
                key = "epg_sync_interval",
                title = "Guide Update Interval",
                currentValue = SettingsOptionLabels.epgIntervalName(state.epgSyncIntervalHours),
                onClick = { dialogs.showEpgIntervalSelector(state.epgSyncIntervalHours) }
            ),
            SettingItem.Action(
                key = "epg_sync_now",
                title = "Update TV Guide Now",
                description = "Fetch guide data immediately",
                onClick = { actions.syncEpgNow() }
            )
        )
    private fun homeItems(state: SettingsUiState): List<SettingItem> = listOf(
            SettingItem.Action(
                key = "home_custom_movie",
                title = "Movie Rows",
                description = "Choose which movie categories appear on Home",
                onClick = { actions.showCategorySelector("movie") }
            ),
            SettingItem.Action(
                key = "home_custom_series",
                title = "Series Rows",
                description = "Choose which series categories appear on Home",
                onClick = { actions.showCategorySelector("series") }
            ),
            SettingItem.Action(
                key = "home_custom_live",
                title = "Live TV Rows",
                description = "Choose which channel categories appear on Home",
                onClick = { actions.showCategorySelector("live") }
            )
        )
    private fun addonsItems(state: SettingsUiState): List<SettingItem> = listOf(
            SettingItem.Action(
                key = "manage_stremio_addons",
                title = "Stremio Addons",
                description = if (state.stremioAddonUrls.isEmpty()) {
                    "No addons configured - sources come from the built-in list"
                } else {
                    "${state.stremioAddonUrls.size} addon(s) active"
                },
                onClick = { dialogs.showManageStremioAddonsDialog(state.stremioAddonUrls) }
            ),
            SettingItem.Action(
                key = "manage_debrid",
                title = "Real-Debrid Fallback",
                description = if (state.isDebridAuthenticated) {
                    "Authorised - used only for raw magnet links"
                } else {
                    "Not connected - optional raw magnet fallback"
                },
                onClick = {
                    if (state.isDebridAuthenticated) {
                        dialogs.showDebridLogoutConfirmation()
                    } else {
                        parentFragmentManager.beginTransaction()
                            .replace(R.id.content_container, com.tvonnet.debridxtreamiptv.ui.debrid.DebridAuthFragment())
                            .addToBackStack(null)
                            .commit()
                    }
                }
            )
        )
    private fun dataItems(state: SettingsUiState): List<SettingItem> = listOf(
            SettingItem.Action(
                key = "refresh_iptv",
                title = "Refresh Channels & Catalog",
                description = "Fetch live channels, movies and series from the provider again",
                onClick = { actions.refreshIptvData() }
            ),
            SettingItem.Action(
                key = "clear_cache",
                title = "Clear Cached Data",
                description = "Free up storage - the catalog is downloaded again next time",
                onClick = { actions.showClearCacheDialog() }
            )
        )
    private fun aboutItems(state: SettingsUiState): List<SettingItem> = listOf(
            SettingItem.Info(key = "version", title = "App Version", value = BuildConfig.VERSION_NAME),
            SettingItem.Info(key = "build", title = "Build", value = BuildConfig.VERSION_CODE.toString())
        )
    private fun accountItems(state: SettingsUiState): List<SettingItem> = listOfNotNull(
            state.accountUsername?.takeIf { it.isNotBlank() }?.let { user ->
                SettingItem.Info(key = "account_user", title = "Signed in as", value = user)
            },
            state.accountServer?.takeIf { it.isNotBlank() }?.let { server ->
                SettingItem.Info(key = "account_server", title = "Provider", value = serverHost(server))
            },
            SettingItem.Action(
                key = "logout_account",
                title = "Sign Out",
                description = "Sign out of this provider account on this device",
                onClick = { actions.showAccountLogoutConfirmation() }
            )
        )

    /** Providers are configured by URL; the host alone is what identifies one to a viewer. */
    private fun serverHost(serverUrl: String): String =
        runCatching { android.net.Uri.parse(serverUrl).host }.getOrNull()?.takeIf { it.isNotBlank() }
            ?: serverUrl.removePrefix("http://").removePrefix("https://").substringBefore('/')

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
