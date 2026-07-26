package com.tvonnet.debridxtreamiptv.ui.settings

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
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

        binding.tvSettingsVersion.text = "DEBRIDXTREAM · v${BuildConfig.VERSION_NAME}"
        binding.tvSettingsBuild.text = "BUILD ${BuildConfig.VERSION_NAME}"

        dialogs = SettingsSelectorDialogs(this, viewModel)
        actions = SettingsMaintenanceActions(this, viewModel, repository, cacheManager, cacheHelper)

        setupAdapters()
        observeState()
    }

    private fun setupAdapters() {
        // Categories (Left) — NORMAL (IPTV-only) devices don't get the Stremio Addons category.
        categoryAdapter = SettingsCategoryAdapter(
            onCategorySelected = { category ->
                if (category == SettingCategory.LOGOUT) {
                    actions.showAccountLogoutConfirmation()
                } else {
                    viewModel.selectCategory(category)
                    categoryAdapter.setSelectedCategory(category)
                }
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
                    onClick = { actions.showAccountLogoutConfirmation() }
                )
            )
            SettingCategory.PLAYER -> listOf(
                SettingItem.Selection(
                    key = "engine",
                    title = "Player Engine",
                    currentValue = SettingsOptionLabels.engineName(state.playerEngine),
                    onClick = { dialogs.showEngineSelector(state.playerEngine) }
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
                ),
                SettingItem.Selection(
                    key = "preferred_audio_lang",
                    title = "Preferred Audio Language",
                    currentValue = SettingsOptionLabels.audioLangName(state.preferredAudioLang),
                    onClick = { dialogs.showPreferredAudioLangSelector(state.preferredAudioLang) }
                )
            )
            SettingCategory.VISUALS -> listOf(
                SettingItem.Selection(
                    key = "live_tv_style",
                    title = "Live TV Layout",
                    currentValue = SettingsOptionLabels.liveTvStyleName(state.liveTvStyle),
                    onClick = { dialogs.showLiveTvStyleSelector(state.liveTvStyle) }
                ),
                SettingItem.Toggle(
                    key = "resume_last_live",
                    title = "Resume Last Channel",
                    description = "Open Live TV on the last channel you watched instead of the first",
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
                    title = "TV Guide Genre Colors",
                    description = "Tint channels and programs by genre in the new Live TV guide",
                    isChecked = state.epgGenreTint,
                    onToggle = { viewModel.toggleEpgGenreTint(it) }
                ),
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
                    onClick = { actions.refreshIptvData() }
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
                    currentValue = SettingsOptionLabels.epgIntervalName(state.epgSyncIntervalHours),
                    onClick = { dialogs.showEpgIntervalSelector(state.epgSyncIntervalHours) }
                ),
                SettingItem.Action(
                    key = "epg_sync_now",
                    title = "Sync EPG Now",
                    description = "Manually fetch EPG TV guide data",
                    onClick = { actions.syncEpgNow() }
                ),
                SettingItem.Action(
                    key = "clear_cache",
                    title = "Clear Cached TV Data",
                    description = "Wipe temporary storage to resolve issues",
                    onClick = { actions.showClearCacheDialog() }
                )
            )
            SettingCategory.HOME -> listOf(
                SettingItem.Action(
                    key = "home_custom_movie",
                    title = "Customize Movie Rows",
                    description = "Select categories to show on Home",
                    onClick = { actions.showCategorySelector("movie") }
                ),
                SettingItem.Action(
                    key = "home_custom_series",
                    title = "Customize Series Rows",
                    description = "Select categories to show on Home",
                    onClick = { actions.showCategorySelector("series") }
                ),
                SettingItem.Action(
                    key = "home_custom_live",
                    title = "Customize Live TV Rows",
                    description = "Select categories to show on Home",
                    onClick = { actions.showCategorySelector("live") }
                )
            )
            SettingCategory.DEBRID -> listOf(
                SettingItem.Action(
                    key = "manage_stremio_addons",
                    title = "Manage Custom Stremio Addons",
                    description = "${state.stremioAddonUrls.size} addon(s) active - primary addon path",
                    onClick = { dialogs.showManageStremioAddonsDialog(state.stremioAddonUrls) }
                ),
                SettingItem.Action(
                    key = "manage_debrid",
                    title = "Legacy Real-Debrid Fallback",
                    description = if (state.isDebridAuthenticated) "Status: Authorized (raw magnet fallback only)" else "Optional legacy raw magnet fallback",
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

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
