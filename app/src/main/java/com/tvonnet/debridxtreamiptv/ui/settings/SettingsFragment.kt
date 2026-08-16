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
import com.tvonnet.debridxtreamiptv.data.repository.ServerDataReset
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
class SettingsFragment :
    Fragment(),
    com.tvonnet.debridxtreamiptv.util.PortraitScreen {

    private var _binding: FragmentSettingsV2Binding? = null
    private val binding get() = _binding!!
    
    private val viewModel: SettingsViewModel by viewModels()

    @Inject
    lateinit var repository: XtreamRepository

    @Inject
    lateinit var serverDataReset: ServerDataReset

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
        // NOT the scaled inflater. The 1.6x exists to rescue screens still wearing the 10-foot
        // layout; this one is authored in real dp now, and scaling it wraps every setting's name
        // onto two lines.
        _binding = FragmentSettingsV2Binding.inflate(
            com.tvonnet.debridxtreamiptv.ui.live.phone.PhoneUi.unscaled(this, inflater),
            container,
            false,
        )
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        // Version lives here and in About; the old duplicate footer block is gone.
        // ROOT, not the default locale: this is a brand name being upper-cased, and Turkish would
        // otherwise turn the dotted i into a dotless one. (detekt's ImplicitDefaultLocale is at 0.)
        val brand = getString(R.string.app_title).uppercase(java.util.Locale.ROOT)
        binding.tvSettingsVersion.text = "$brand · v${BuildConfig.VERSION_NAME}"

        dialogs = SettingsSelectorDialogs(this, viewModel)
        actions = SettingsMaintenanceActions(this, viewModel, repository, serverDataReset)

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
            // M6: a vertical rail beside the detail panel on TV, a top chip rail on the phone —
            // two columns do not fit 411dp, which is why Settings was unreachable there at all.
            layoutManager = LinearLayoutManager(
                context,
                if (resources.getBoolean(R.bool.settings_categories_are_horizontal)) {
                    LinearLayoutManager.HORIZONTAL
                } else {
                    LinearLayoutManager.VERTICAL
                },
                false
            )
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
        binding.tvPanelTitle.setText(meta.titleRes)
        binding.tvPanelDesc.setText(SettingsCategoryAdapter.descFor(state.selectedCategory))
        binding.vPanelDot.background = android.graphics.drawable.GradientDrawable().apply {
            shape = android.graphics.drawable.GradientDrawable.OVAL
            setColor(meta.accent)
        }
        detailAdapter.accent = meta.accent

        detailAdapter.submitList(itemsFor(state))
    }

    // The rows for the selected category, verbatim — each arm's keys, labels and handlers
    // are unchanged; only the enclosing function moved.
    /** "system" when the user has not chosen, else the language tag AppCompat has stored. */
    private fun currentAppLanguageTag(): String =
        androidx.appcompat.app.AppCompatDelegate.getApplicationLocales()
            .takeIf { !it.isEmpty }?.get(0)?.language ?: "system"

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
                title = getString(R.string.c_preferred_audio_language),
                currentValue = SettingsOptionLabels.audioLangName(state.preferredAudioLang),
                onClick = { dialogs.showPreferredAudioLangSelector(state.preferredAudioLang) }
            ),
            SettingItem.Selection(
                key = "preferred_audio_lang_2",
                title = getString(R.string.c_secondary_audio_language),
                currentValue = SettingsOptionLabels.audioLang2Name(state.preferredAudioLang2),
                onClick = { dialogs.showPreferredAudioLang2Selector(state.preferredAudioLang2) }
            ),
            SettingItem.Toggle(
                key = "software_audio",
                title = getString(R.string.s_smart_audio_fallback),
                description = getString(R.string.s_switch_tracks_automatically_when_the_main),
                isChecked = state.isSoftwareAudioEnabled,
                onToggle = { viewModel.toggleSoftwareAudio(it) }
            )
        )
    /**
     * Classic-vs-guide is a 10-foot question. A phone has one Live screen — its own portrait one
     * — so on a handset this row would offer a choice that changes nothing.
     */
    private fun liveTvStyleItem(state: SettingsUiState): List<SettingItem> =
        if (!resources.getBoolean(R.bool.ui_uses_dpad_focus)) {
            emptyList()
        } else {
            listOf(
                SettingItem.Selection(
                    key = "live_tv_style",
                    title = getString(R.string.c_live_tv_layout),
                    currentValue = SettingsOptionLabels.liveTvStyleName(state.liveTvStyle),
                    onClick = { dialogs.showLiveTvStyleSelector(state.liveTvStyle) }
                )
            )
        }

    private fun liveTvItems(state: SettingsUiState): List<SettingItem> =
        liveTvStyleItem(state) + listOf(
            SettingItem.Toggle(
                key = "resume_last_live",
                title = getString(R.string.s_resume_last_channel),
                description = getString(R.string.s_open_live_tv_on_the_channel),
                isChecked = state.resumeLastLive,
                onToggle = { viewModel.toggleResumeLastLive(it) }
            ),
            SettingItem.Selection(
                key = "epg_zoom",
                title = getString(R.string.c_tv_guide_timeline_zoom),
                currentValue = SettingsOptionLabels.epgZoomName(state.epgTimelineZoom),
                onClick = { dialogs.showEpgZoomSelector(state.epgTimelineZoom) }
            ),
            SettingItem.Selection(
                key = "epg_density",
                title = getString(R.string.c_tv_guide_row_density),
                currentValue = SettingsOptionLabels.epgDensityName(state.epgRowDensity),
                onClick = { dialogs.showEpgDensitySelector(state.epgRowDensity) }
            ),
            SettingItem.Toggle(
                key = "epg_genre_tint",
                title = getString(R.string.s_tv_guide_genre_colours),
                description = getString(R.string.s_tint_channels_and_programmes_by_genre),
                isChecked = state.epgGenreTint,
                onToggle = { viewModel.toggleEpgGenreTint(it) }
            ),
            SettingItem.Toggle(
                key = "epg_auto_sync",
                title = getString(R.string.s_auto_update_tv_guide),
                description = getString(R.string.s_refresh_the_guide_in_the_background),
                isChecked = state.isEpgAutoSyncEnabled,
                onToggle = { viewModel.toggleEpgAutoSync(it) }
            ),
            SettingItem.Selection(
                key = "epg_sync_interval",
                title = getString(R.string.s_guide_update_interval),
                currentValue = SettingsOptionLabels.epgIntervalName(state.epgSyncIntervalHours),
                onClick = { dialogs.showEpgIntervalSelector(state.epgSyncIntervalHours) }
            ),
            SettingItem.Action(
                key = "epg_sync_now",
                title = getString(R.string.s_update_tv_guide_now),
                description = getString(R.string.s_fetch_guide_data_immediately),
                onClick = { actions.syncEpgNow() }
            )
        )

    private fun homeItems(state: SettingsUiState): List<SettingItem> = listOf(
            SettingItem.Selection(
                key = "app_language",
                title = getString(R.string.c_app_language),
                // Read from AppCompat rather than a preference of our own — it is the store.
                currentValue = SettingsOptionLabels.appLanguageName(currentAppLanguageTag()),
                onClick = { dialogs.showAppLanguageSelector(currentAppLanguageTag()) }
            ),
            SettingItem.Selection(
                key = "ui_mode",
                title = getString(R.string.c_app_layout),
                currentValue = SettingsOptionLabels.uiModeName(state.uiMode),
                onClick = { dialogs.showUiModeSelector(state.uiMode) }
            ),
            SettingItem.Action(
                key = "home_custom_movie",
                title = getString(R.string.s_movie_rows),
                description = getString(R.string.s_choose_which_movie_categories_appear_on),
                onClick = { actions.showCategorySelector("movie") }
            ),
            SettingItem.Action(
                key = "home_custom_series",
                title = getString(R.string.s_series_rows),
                description = getString(R.string.s_choose_which_series_categories_appear_on),
                onClick = { actions.showCategorySelector("series") }
            ),
            SettingItem.Action(
                key = "home_custom_live",
                title = getString(R.string.s_live_tv_rows),
                description = getString(R.string.s_choose_which_channel_categories_appear_on),
                onClick = { actions.showCategorySelector("live") }
            )
        )
    private fun addonsItems(state: SettingsUiState): List<SettingItem> = listOf(
            SettingItem.Action(
                key = "manage_stremio_addons",
                title = getString(R.string.ui_stremio_addons),
                description = if (state.stremioAddonUrls.isEmpty()) {
                    "No addons configured - sources come from the built-in list"
                } else {
                    "${state.stremioAddonUrls.size} addon(s) active"
                },
                onClick = { dialogs.showManageStremioAddonsDialog(state.stremioAddonUrls) }
            ),
            SettingItem.Action(
                key = "manage_debrid",
                title = getString(R.string.s_real_debrid_fallback),
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
                title = getString(R.string.s_refresh_channels_catalog),
                description = getString(R.string.s_fetch_live_channels_movies_and_series),
                onClick = { actions.refreshIptvData() }
            ),
            SettingItem.Action(
                key = "clear_cache",
                title = getString(R.string.s_clear_cached_data),
                description = getString(R.string.s_free_up_storage_the_catalog_is),
                onClick = { actions.showClearCacheDialog() }
            )
        )
    private fun aboutItems(state: SettingsUiState): List<SettingItem> = listOf(
            SettingItem.Info(key = "version", title = getString(R.string.s_app_version), value = BuildConfig.VERSION_NAME),
            SettingItem.Info(key = "build", title = getString(R.string.s_build), value = BuildConfig.VERSION_CODE.toString()),
            SettingItem.Action(
                key = "check_update",
                title = getString(R.string.s_check_for_update),
                description = getString(R.string.s_ask_the_server_whether_a_newer),
                onClick = {
                    com.tvonnet.debridxtreamiptv.update.UpdateManager.checkNow(requireActivity())
                }
            )
        )
    private fun accountItems(state: SettingsUiState): List<SettingItem> = listOfNotNull(
            state.accountUsername?.takeIf { it.isNotBlank() }?.let { user ->
                SettingItem.Info(key = "account_user", title = getString(R.string.s_signed_in_as), value = user)
            },
            state.accountServer?.takeIf { it.isNotBlank() }?.let { server ->
                SettingItem.Info(key = "account_server", title = getString(R.string.s_provider), value = serverHost(server))
            },
            // D4: above Sign out on purpose. This is the row somebody is looking for when they want
            // to change something; the destructive one should not be the first Action they meet.
            SettingItem.Action(
                key = "manage_on_phone",
                title = getString(R.string.s_manage_on_your_phone),
                description = getString(R.string.s_manage_on_your_phone_desc),
                onClick = { SettingsManageQrDialog.show(requireContext()) }
            ),
            SettingItem.Action(
                key = "logout_account",
                title = getString(R.string.s_sign_out),
                description = getString(R.string.s_sign_out_of_this_provider_account),
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
