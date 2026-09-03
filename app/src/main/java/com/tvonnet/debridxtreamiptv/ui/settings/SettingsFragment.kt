package com.tvonnet.debridxtreamiptv.ui.settings

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.activity.addCallback
import androidx.core.view.OneShotPreDrawListener
import androidx.core.view.isVisible
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
    lateinit var parental: com.tvonnet.debridxtreamiptv.data.parental.ParentalControls
    private val parentalRows by lazy { SettingsParentalRows(requireContext(), parental) { updateDetails(viewModel.uiState.value) } }

    @Inject
    lateinit var serverDataReset: ServerDataReset

    private lateinit var categoryAdapter: SettingsCategoryAdapter
    private lateinit var detailAdapter: SettingsDetailAdapter

    // C1: the screen itself now owns only the two lists, the state subscription and the item
    // mapping below. Choosers live in SettingsSelectorDialogs, anything that mutates app data in
    // SettingsMaintenanceActions, and the value/label vocabulary in SettingsOptionLabels.
    private lateinit var dialogs: SettingsSelectorDialogs
    private lateinit var actions: SettingsMaintenanceActions

    /** G1: what each category's row says it holds, on the phone's list. */
    private lateinit var summaries: SettingsCategorySummaries

    /**
     * G1: the phone shows the categories and one category's settings as two PAGES in this one
     * layout — the designer's "two panels become two destinations", with every bound id still
     * present because the television inflates the same fragment.
     *
     * A television has both on screen at once, so it never opens or closes anything: the guard
     * below is the whole difference between the two form factors.
     */
    private val isTwoPagePhone: Boolean
        get() = !resources.getBoolean(R.bool.ui_uses_dpad_focus)

    private var backPressHandler: androidx.activity.OnBackPressedCallback? = null

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
        summaries = SettingsCategorySummaries(requireContext())
        setupTwoPageNavigation()

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

    /**
     * G1: Back closes the open category before it leaves Settings.
     *
     * Registered rather than relying on the fragment back stack, because both pages live in one
     * fragment — without this, Back from a category would leave Settings entirely and the customer
     * would have to come back in to change the next thing.
     */
    private fun setupTwoPageNavigation() {
        if (!isTwoPagePhone) return
        // Safe calls, not stub views on the television: these three exist only in the phone layout,
        // so ViewBinding types them nullable — and that nullability IS the guard. Adding empty
        // copies to the TV layout to satisfy the compiler would put views there that mean nothing.
        binding.settingsDetailBack?.setOnClickListener { closeDetailPage() }
        backPressHandler = requireActivity().onBackPressedDispatcher.addCallback(viewLifecycleOwner) {
            if (binding.settingsDetailPage?.isVisible == true) closeDetailPage() else {
                isEnabled = false
                requireActivity().onBackPressedDispatcher.onBackPressed()
            }
        }
    }

    private fun openDetailPage() {
        if (!isTwoPagePhone) return
        binding.settingsDetailPage?.isVisible = true
        binding.rvSettingsDetails.scrollToPosition(0)
        backPressHandler?.isEnabled = true
    }

    private fun closeDetailPage() {
        binding.settingsDetailPage?.isVisible = false
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
                openDetailPage()
            },
            showDebridCategory = com.tvonnet.debridxtreamiptv.data.licensing.Entitlements
                .isDebridAllowed(requireContext()),
            liveSubtitle = { category -> summaries.of(category) },
        )
        binding.rvSettingsCategories.apply {
            // G1: a vertical rail beside the detail panel on TV; on the phone a vertical LIST that
            // navigates. Both are vertical now — the horizontal chip rail was the previous phone
            // attempt, and it was the TV's shape squeezed sideways rather than a phone screen.
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
        binding.tvPanelTitle.setText(meta.titleRes)
        binding.tvPanelDesc.setText(SettingsCategoryAdapter.descFor(state.selectedCategory))
        binding.vPanelDot.background = android.graphics.drawable.GradientDrawable().apply {
            shape = android.graphics.drawable.GradientDrawable.OVAL
            setColor(meta.accent)
        }
        detailAdapter.accent = meta.accent

        val items = itemsFor(state)
        // G4: a category that cannot act yet says so, above rows that stay visible but inert.
        val blocked = SettingsUnavailable.of(
            context = requireContext(),
            category = state.selectedCategory,
            hasServer = !state.accountUsername.isNullOrBlank() && !state.accountServer.isNullOrBlank(),
            // One stat() per category change, not per row: hasCache is a memory flag plus a
            // File.exists on the scoped cache file.
            hasCatalogue = repository.hasCache(),
        )
        detailAdapter.inertKeys =
            blocked?.let { items.map { item -> item.id }.toSet() - it.liveKeys }.orEmpty()
        detailAdapter.submitList(noticeFor(blocked) + items)
    }

    /** The amber strip as a row, or nothing. Refresh is the only fix this screen can perform itself. */
    private fun noticeFor(blocked: SettingsUnavailable.Blocked?): List<SettingItem> {
        if (blocked == null) return emptyList()
        return listOf(
            SettingItem.Notice(
                key = "blocked_notice",
                cause = blocked.cause,
                fix = blocked.fix,
                actionLabel = blocked.actionLabel,
                onAction = blocked.actionLabel?.let { { actions.refreshIptvData() } },
            )
        )
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
            // G4: while the catalogue is downloading, this row IS the progress. Before, it showed
            // a Toast and then looked untouched for minutes, so people tapped it again.
            actions.refreshRow(
                getString(R.string.s_refresh_channels_catalog),
                getString(R.string.s_fetch_live_channels_movies_and_series),
            ),
            SettingItem.Toggle(
                key = "diagnostics",
                title = getString(R.string.s_diagnostics_title),
                description = getString(R.string.s_diagnostics_desc),
                isChecked = state.isDiagnosticsEnabled,
                onToggle = { viewModel.toggleDiagnostics(it) }
            ),
            SettingItem.Action(
                key = "clear_cache",
                title = getString(R.string.s_clear_cached_data),
                description = getString(R.string.s_free_up_storage_the_catalog_is),
                onClick = { actions.showClearCacheDialog() },
                isDestructive = true
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
            // G4: a read-only row with nothing to show SAYS so. It used to vanish, which left the
            // category looking broken rather than empty — and a customer who is not signed in is
            // exactly the one who needs to be told what this screen is for.
            SettingItem.Info(
                key = "account_user",
                title = getString(R.string.s_signed_in_as),
                value = state.accountUsername?.takeIf { it.isNotBlank() }
                    ?: getString(R.string.s_not_signed_in),
            ),
            state.accountServer?.takeIf { it.isNotBlank() }?.let { server ->
                SettingItem.Info(key = "account_server", title = getString(R.string.s_provider), value = serverHost(server))
            },
            // A5: always present — owner's decision. Hiding it when the account had fewer than two
            // servers meant it disappeared exactly where somebody would go looking for it.
            SettingItem.Selection(
                key = "active_server",
                title = getString(R.string.s_your_servers),
                currentValue = SettingsServerPicker.currentLabel(requireActivity()),
                onClick = { SettingsServerPicker.show(requireActivity()) }
            ),
            // D4: above Sign out on purpose. This is the row somebody is looking for when they want
            // to change something; the destructive one should not be the first Action they meet.
            SettingItem.Action(
                key = "manage_on_phone",
                title = getString(R.string.s_manage_on_your_phone),
                description = getString(R.string.s_manage_on_your_phone_desc),
                onClick = { SettingsManageQrDialog.show(requireContext()) }
            ),
        ) + parentalRows.items() + listOf(
            SettingItem.Action(
                key = "logout_account",
                title = getString(R.string.s_sign_out),
                description = getString(R.string.s_sign_out_of_this_provider_account),
                onClick = { actions.showAccountLogoutConfirmation() },
                isDestructive = true
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
