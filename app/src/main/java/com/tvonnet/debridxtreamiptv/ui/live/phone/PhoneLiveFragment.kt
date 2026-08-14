package com.tvonnet.debridxtreamiptv.ui.live.phone

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.view.isVisible
import androidx.core.widget.doAfterTextChanged
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.bumptech.glide.Glide
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.tvonnet.debridxtreamiptv.R
import com.tvonnet.debridxtreamiptv.data.local.entity.EpgEntity
import com.tvonnet.debridxtreamiptv.data.model.XtreamCategory
import com.tvonnet.debridxtreamiptv.data.model.XtreamStream
import com.tvonnet.debridxtreamiptv.ui.live.LiveEvent
import com.tvonnet.debridxtreamiptv.ui.live.LiveUiState
import com.tvonnet.debridxtreamiptv.ui.live.LiveViewModel
import com.tvonnet.debridxtreamiptv.ui.nav.SectionNavigator
import com.tvonnet.debridxtreamiptv.util.EpgCache
import com.tvonnet.debridxtreamiptv.util.PortraitScreen
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient

/**
 * PHONE Live TV — portrait, built to the "DX Play Live TV" handoff (2026-08-14).
 *
 * The handoff's three decisions, and where each one lives here:
 *
 * 1. **One tap plays into a docked 16:9 player**, not fullscreen. Live TV is a zapping loop — try
 *    a channel, judge it in two seconds, try the next — and charging a back press per hop while
 *    hiding the list being hunted through is the wrong trade. So [playInDock] starts video on the
 *    first tap and the list keeps scrolling underneath. Fullscreen stays deliberate: the video
 *    surface, the expand button, or rotating the handset.
 * 2. **Categories are pinned chips plus a searchable sheet.** Three or four categories carry daily
 *    use and the other 145 are near-never, so those three or four are one tap and the tail is one
 *    tap plus a search field.
 * 3. **The guide is per channel**, opened from the now/next line — a timeline grid is unreadable at
 *    411dp and this app's grid view has no touch handling at all.
 *
 * The dock is driven by [PreviewPlayerPanel], the same collaborator the TV preview uses, because
 * the tuned live player, its AudioTrack-releasing error handling and the fullscreen hand-off are
 * hard-won and must not be reimplemented per form factor. This fragment supplies the same view ids
 * it binds to; only the arrangement differs.
 */
@AndroidEntryPoint
class PhoneLiveFragment : Fragment(), PortraitScreen {

    private val viewModel: LiveViewModel by viewModels()

    @Inject
    lateinit var okHttpClient: OkHttpClient

    @Inject
    lateinit var repository: com.tvonnet.debridxtreamiptv.data.repository.XtreamRepository

    @Inject
    lateinit var favoriteDao: com.tvonnet.debridxtreamiptv.data.local.dao.FavoriteDao

    private lateinit var adapter: PhoneChannelAdapter
    private var dock: PhoneLiveDock? = null
    private var favouriteIds: Set<String> = emptySet()
    private var lastWarmSignature: String? = null
    private val credentials by lazy {
        com.tvonnet.debridxtreamiptv.data.prefs.CredentialsPreferences(requireContext())
    }
    private val settings by lazy {
        com.tvonnet.debridxtreamiptv.data.prefs.SettingsPreferences(requireContext())
    }

    /** Auto-resume is a once-per-visit offer, not a loop that re-docks whatever BACK closed. */
    private var autoResumeSpent = false

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View = PhoneUi.unscaled(this, inflater)
        .inflate(R.layout.fragment_live_phone, container, false)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        PhoneBottomNav.build(
            bar = view.findViewById(R.id.phone_bottom_nav),
            inflater = PhoneUi.unscaled(this, layoutInflater),
            active = SectionNavigator.SECTION_LIVE,
        ) { section -> SectionNavigator.navigate(this, section) }

        // EpgCache is a process-wide object that answers with nothing until it is handed a
        // repository — and until now the only screen that did so was the TV's LiveFragment. On a
        // phone that made every row read "NO GUIDE DATA" even with 102,798 programmes in the
        // database, because the lookup was returning before it ever reached them.
        EpgCache.initialize(repository)

        setUpChannelList(view)
        setUpDock(view)
        setUpSearch(view)

        observeState(view)
        observeChannels()
        observeFavourites()
        observeEpgArrivals()

        viewModel.onEvent(LiveEvent.LoadCategories)
    }

    // ── list ────────────────────────────────────────────────────────────────

    private fun setUpChannelList(view: View) {
        adapter = PhoneChannelAdapter(
            actions = PhoneChannelAdapter.Actions(
                onPlay = { channel -> playInDock(view, channel) },
                onGuide = { channel -> openGuide(channel) },
                onOverflow = { channel -> showChannelOptions(channel) },
                onToggleFavourite = { channel -> toggleFavourite(channel) },
            ),
            facts = PhoneChannelAdapter.Facts(
                epg = { channel -> epgFor(channel) },
                isFavourite = { channel -> channel.stream_id in favouriteIds },
                playingStreamId = { dock?.playingStreamId },
                serverUrl = { credentials.getServerUrl().orEmpty() },
            ),
        )
        val list = view.findViewById<RecyclerView>(R.id.phone_live_channels)
        list.layoutManager = LinearLayoutManager(requireContext())
        list.adapter = adapter
        list.addOnScrollListener(object : RecyclerView.OnScrollListener() {
            override fun onScrollStateChanged(rv: RecyclerView, newState: Int) {
                if (newState == RecyclerView.SCROLL_STATE_IDLE) warmVisibleEpg(rv)
            }
        })
    }

    private fun epgFor(channel: XtreamStream): Pair<EpgEntity?, EpgEntity?> {
        val key = channel.epg_channel_id?.takeIf { it.isNotBlank() }
            ?: channel.stream_id
            ?: return null to null
        return EpgCache.getEpgData(key, channel.stream_id)
    }

    /**
     * Warm now/next for the visible window in ONE batch when the list settles.
     *
     * The per-row fetch this replaces fired a `get_short_epg` HTTP call per channel — the defect
     * the browse list already learned the hard way. The signature guard keeps a settle at the same
     * window from re-firing it.
     */
    private fun warmVisibleEpg(list: RecyclerView) {
        val items = adapter.snapshot().items
        if (items.isEmpty()) return
        val lm = list.layoutManager as? LinearLayoutManager ?: return
        val start = (lm.findFirstVisibleItemPosition() - EPG_WARM_BUFFER).coerceAtLeast(0)
        val end = (lm.findLastVisibleItemPosition() + EPG_WARM_BUFFER)
            .coerceAtMost(items.lastIndex)
        if (end < start) return

        val signature = "${viewModel.uiState.value.selectedCategoryId.orEmpty()}:$start-$end"
        if (signature == lastWarmSignature) return
        lastWarmSignature = signature

        val entries = (start..end).mapNotNull { i ->
            val stream = items.getOrNull(i) ?: return@mapNotNull null
            val epgId = stream.epg_channel_id?.takeIf { it.isNotBlank() }
            val streamId = stream.stream_id
            val key = epgId ?: streamId ?: return@mapNotNull null
            EpgCache.WarmEntry(cacheKey = key, epgChannelId = epgId, streamId = streamId)
        }
        if (entries.isNotEmpty()) EpgCache.warmNowNext(entries)
    }

    /**
     * The warm is asynchronous, so the rows that painted "NO GUIDE DATA" a moment ago have to be
     * told when their programme actually arrives. Without this the list only ever shows EPG for
     * channels whose data happened to be cached before they were bound — which, on a cold start,
     * is none of them.
     *
     * Debounced, because a warm of thirty channels emits thirty keys.
     */
    @OptIn(kotlinx.coroutines.FlowPreview::class)
    private fun observeEpgArrivals() {
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                EpgCache.updates
                    .debounce(EPG_REBIND_DEBOUNCE_MS)
                    .collect { adapter.notifyDataSetChanged() }
            }
        }
    }

    private fun observeChannels() {
        viewLifecycleOwner.lifecycleScope.launch {
            // Collected on the VIEW lifecycle rather than STARTED: a fullscreen hop must not
            // reset the list the user was scrolling.
            viewModel.pagedChannels.collect { adapter.submitData(it) }
        }
        adapter.addLoadStateListener { states ->
            val view = view ?: return@addLoadStateListener
            val loading = states.refresh is androidx.paging.LoadState.Loading
            val failed = states.refresh is androidx.paging.LoadState.Error
            renderListState(view, loading, failed)
            // The header carries the result count while searching, and that number is only known
            // once the page has actually landed.
            bindHeader(view, viewModel.uiState.value)
            if (!loading && !failed) {
                view.findViewById<RecyclerView>(R.id.phone_live_channels)?.let { warmVisibleEpg(it) }
            }
        }
    }

    /** Loading and error own the LIST area only — the chrome above never blanks with them. */
    private fun renderListState(view: View, loading: Boolean, failed: Boolean) {
        val holder = view.findViewById<LinearLayout>(R.id.phone_live_state) ?: return
        val list = view.findViewById<RecyclerView>(R.id.phone_live_channels)
        holder.removeAllViews()
        holder.isVisible = loading || failed
        list?.isVisible = !loading && !failed
        val inflater = PhoneUi.unscaled(this, layoutInflater)
        when {
            loading -> holder.addView(
                inflater.inflate(R.layout.item_phone_live_skeleton, holder, false)
            )
            failed -> {
                val panel = inflater.inflate(R.layout.item_phone_error_panel, holder, false)
                panel.findViewById<View>(R.id.phone_error_retry).setOnClickListener {
                    adapter.retry()
                }
                // "Use cached" is the favourites category: whatever the playlist is doing, these
                // are the channels this user already told us they watch.
                panel.findViewById<View>(R.id.phone_error_cached).setOnClickListener {
                    viewModel.onEvent(LiveEvent.SelectCategory(com.tvonnet.debridxtreamiptv.util.FAVORITES_CATEGORY_ID))
                }
                holder.addView(panel)
            }
        }
    }

    // ── chrome ──────────────────────────────────────────────────────────────

    private fun observeState(view: View) {
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.uiState.collect { state ->
                    bindChips(view, state)
                    bindHeader(view, state)
                    bindResumeCard(view, state)
                }
            }
        }
    }

    /**
     * The pinned chips: the "All N" button first, then the categories. Which ones are pinned is
     * simply the order the playlist gives — the handoff's "recent/pinned" needs a usage history
     * this app does not keep yet, and inventing one silently would be worse than the honest order.
     */
    private fun bindChips(view: View, state: LiveUiState) {
        val rail = view.findViewById<LinearLayout>(R.id.phone_live_chips) ?: return
        rail.removeAllViews()
        if (state.categories.isEmpty()) return
        val inflater = PhoneUi.unscaled(this, layoutInflater)

        val all = inflater.inflate(R.layout.item_phone_category_chip, rail, false)
        all.findViewById<TextView>(R.id.phone_chip_name).text =
            getString(R.string.phone_all_categories)
        all.findViewById<TextView>(R.id.phone_chip_count).text = state.categories.size.toString()
        all.setOnClickListener { showCategorySheet(state.categories, state.categoryChannelCounts) }
        rail.addView(all)

        state.categories.take(PINNED_CHIPS).forEach { category ->
            val chip = inflater.inflate(R.layout.item_phone_category_chip, rail, false)
            val selected = category.category_id == state.selectedCategoryId
            chip.findViewById<TextView>(R.id.phone_chip_name).apply {
                text = category.category_name
                setTextColor(color(if (selected) R.color.phone_cyan else R.color.phone_text_primary))
            }
            chip.findViewById<TextView>(R.id.phone_chip_count).text =
                state.categoryChannelCounts[category.category_id]?.toString().orEmpty()
            chip.setBackgroundResource(
                if (selected) R.drawable.bg_phone_chip_active else R.drawable.bg_phone_chip
            )
            chip.setOnClickListener {
                category.category_id?.let { viewModel.onEvent(LiveEvent.SelectCategory(it)) }
                view.findViewById<RecyclerView>(R.id.phone_live_channels)?.scrollToPosition(0)
            }
            rail.addView(chip)
        }
    }

    private fun bindHeader(view: View, state: LiveUiState) {
        val searching = state.searchQuery.isNotBlank()
        val selected = state.categories.firstOrNull { it.category_id == state.selectedCategoryId }
        view.findViewById<TextView>(R.id.phone_live_category_name)?.text =
            if (searching) getString(R.string.phone_search_results) else selected?.category_name.orEmpty()
        view.findViewById<TextView>(R.id.phone_live_category_count)?.text =
            if (searching) "" else state.categoryChannelCounts[state.selectedCategoryId]?.toString().orEmpty()
        // The right-hand slot answers whatever question the screen is currently in the middle of:
        // how many results while searching, and how to use the dock once something is playing.
        view.findViewById<TextView>(R.id.phone_live_header_right)?.text = when {
            searching -> getString(R.string.phone_result_count, adapter.itemCount)
            dock?.playingStreamId != null -> getString(R.string.phone_tap_to_zap)
            else -> ""
        }
    }

    /** Shown only while nothing is docked — otherwise the dock IS the answer to "what was I on". */
    private fun bindResumeCard(view: View, state: LiveUiState) {
        val card = view.findViewById<View>(R.id.phone_live_resume) ?: return
        val name = state.lastPlayedChannelName
        val id = state.lastPlayedChannelId
        if (dock?.playingStreamId != null || name.isNullOrBlank() || id.isNullOrBlank()) {
            card.isVisible = false
            return
        }
        val last = lastPlayedStream(state, name, id)
        card.isVisible = true
        view.findViewById<TextView>(R.id.phone_resume_name)?.text = name
        val logo = view.findViewById<ImageView>(R.id.phone_resume_logo)
        if (logo != null) Glide.with(this).load(state.lastPlayedChannelLogo).fitCenter().into(logo)
        card.setOnClickListener { playInDock(view, last) }

        // "Resume last channel" is an existing setting the TV guide screen already honours. A
        // user who turned it on asked for the channel to be ON when they arrive, not for a card
        // offering it — so on a phone it starts in the dock. Once per visit, never after a BACK
        // that deliberately collapsed the dock.
        if (!autoResumeSpent && settings.isResumeLastLiveEnabled()) {
            autoResumeSpent = true
            playInDock(view, last)
        }
    }

    private fun lastPlayedStream(state: LiveUiState, name: String, id: String) = XtreamStream(
        num = null, name = name, stream_type = "live", stream_id = id,
        stream_icon = state.lastPlayedChannelLogo,
        epg_channel_id = state.lastPlayedEpgChannelId,
        added = null, category_id = null, category_ids = null,
        container_extension = null, custom_sid = null, direct_source = null,
        tv_archive = null, tv_archive_duration = null,
    )

    // ── the dock ────────────────────────────────────────────────────────────

    private fun setUpDock(view: View) {
        dock = PhoneLiveDock(
            root = view,
            okHttpClient = okHttpClient,
            lifecycle = viewLifecycleOwner.lifecycle,
            onFullscreen = { stream -> openFullscreen(stream) },
            onFavourite = { stream -> toggleFavourite(stream) },
            onChanged = {
                // Opening or closing the dock changes three things around it: whether the resume
                // card belongs on screen, what the header says, and which row is marked.
                bindResumeCard(view, viewModel.uiState.value)
                bindHeader(view, viewModel.uiState.value)
                adapter.notifyDataSetChanged()
            },
        )
    }

    /** The first tap: play here, now. Nothing is "selected" first. */
    private fun playInDock(view: View, channel: XtreamStream) {
        view.findViewById<View>(R.id.phone_live_resume)?.isVisible = false
        dock?.play(channel, epgFor(channel))
        viewModel.onEvent(LiveEvent.RememberPreviewStream(channel))
    }

    private fun openFullscreen(stream: XtreamStream) {
        viewModel.onEvent(LiveEvent.RememberFullscreenLaunch(stream))
        val serverUrl = com.tvonnet.debridxtreamiptv.data.prefs.CredentialsPreferences(
            requireContext()
        ).getServerUrl().orEmpty()
        val url = repository.buildLiveStreamUrl(stream, serverUrl)
        startActivity(
            com.tvonnet.debridxtreamiptv.player.stabilized.PlayerActivity.createIntent(
                context = requireContext(),
                streamUrl = url,
                title = stream.name.orEmpty(),
                channelName = stream.name,
                channelLogo = stream.stream_icon,
                epgChannelId = stream.epg_channel_id ?: stream.stream_id,
                contentId = stream.stream_id ?: url,
                contentType = com.tvonnet.debridxtreamiptv.data.model.ContentType.LIVE_TV,
                posterUrl = stream.stream_icon,
                liveCategoryId = stream.category_id,
            )
        )
    }

    /** BACK collapses the dock before it leaves the screen. See [PhoneLiveDock.collapse]. */
    fun onBackPressed(): Boolean {
        autoResumeSpent = true
        return dock?.collapse() == true
    }

    // ── categories, search, guide, favourites ───────────────────────────────

    private fun showCategorySheet(categories: List<XtreamCategory>, counts: Map<String, Int>) {
        val sheet = BottomSheetDialog(requireContext())
        val content = PhoneUi.unscaled(this, layoutInflater)
            .inflate(R.layout.sheet_phone_categories, null)
        sheet.setContentView(content)

        content.findViewById<TextView>(R.id.phone_sheet_count).text = categories.size.toString()
        val list = content.findViewById<RecyclerView>(R.id.phone_sheet_list)
        list.layoutManager = LinearLayoutManager(requireContext())
        val sheetAdapter = PhoneCategoryAdapter(counts) { category ->
            category.category_id?.let { viewModel.onEvent(LiveEvent.SelectCategory(it)) }
            view?.findViewById<RecyclerView>(R.id.phone_live_channels)?.scrollToPosition(0)
            sheet.dismiss()
        }
        list.adapter = sheetAdapter
        sheetAdapter.submit(categories)

        content.findViewById<EditText>(R.id.phone_sheet_search).doAfterTextChanged { text ->
            val query = text?.toString()?.trim().orEmpty()
            sheetAdapter.submit(
                if (query.isEmpty()) categories
                else categories.filter { it.category_name?.contains(query, ignoreCase = true) == true }
            )
        }
        sheet.show()
    }

    private fun setUpSearch(view: View) {
        val bar = view.findViewById<View>(R.id.phone_live_search_bar)
        val appBar = view.findViewById<View>(R.id.phone_live_app_bar)
        val input = view.findViewById<EditText>(R.id.phone_live_search_input)

        view.findViewById<View>(R.id.phone_live_search)?.setOnClickListener {
            appBar?.isVisible = false
            bar?.isVisible = true
            input?.requestFocus()
            // The SYSTEM keyboard. The app's on-screen D-pad keyboard is a 10-foot control and
            // has no business on a handset.
            PhoneUi.showKeyboard(input)
        }
        view.findViewById<View>(R.id.phone_live_search_back)?.setOnClickListener {
            input?.setText("")
            PhoneUi.hideKeyboard(input)
            bar?.isVisible = false
            appBar?.isVisible = true
            viewModel.onEvent(LiveEvent.ClearSearch)
        }
        input?.doAfterTextChanged { text ->
            viewModel.onEvent(LiveEvent.SearchChannels(text?.toString().orEmpty()))
        }
    }

    private fun openGuide(channel: XtreamStream) {
        if (!isAdded || parentFragmentManager.isStateSaved) return
        parentFragmentManager.beginTransaction()
            .replace(R.id.content_container, PhoneGuideFragment.newInstance(channel))
            .addToBackStack(null)
            .commit()
    }

    private fun showChannelOptions(channel: XtreamStream) {
        val sheet = BottomSheetDialog(requireContext())
        val content = LinearLayout(requireContext()).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundResource(R.drawable.bg_phone_sheet)
            setPadding(0, PhoneUi.dp(this.context, 12), 0, PhoneUi.dp(this.context, 12))
        }
        val hasGuide = epgFor(channel).first != null
        val options = buildList {
            if (hasGuide) add(R.string.phone_full_guide to { openGuide(channel) })
            add(R.string.phone_play_fullscreen to { openFullscreen(channel) })
            val labelRes = if (channel.stream_id in favouriteIds) {
                R.string.remove_from_favorites
            } else {
                R.string.add_to_favorites
            }
            add(labelRes to { toggleFavourite(channel) })
        }
        options.forEach { (labelRes, action) ->
            content.addView(
                TextView(requireContext()).apply {
                    text = getString(labelRes)
                    setTextColor(color(R.color.phone_text_primary))
                    textSize = 13.5f
                    gravity = android.view.Gravity.CENTER_VERTICAL
                    setPadding(PhoneUi.dp(context, 20), 0, PhoneUi.dp(context, 20), 0)
                    layoutParams = LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT,
                        PhoneUi.dp(context, 56)
                    )
                    setOnClickListener { action(); sheet.dismiss() }
                }
            )
        }
        sheet.setContentView(content)
        sheet.show()
    }

    private fun toggleFavourite(channel: XtreamStream) {
        val id = channel.stream_id ?: return
        viewLifecycleOwner.lifecycleScope.launch {
            if (id in favouriteIds) {
                favoriteDao.deleteFavoriteByStreamId(id)
            } else {
                favoriteDao.insertFavorite(
                    com.tvonnet.debridxtreamiptv.data.local.entity.FavoriteEntity(
                        streamId = id,
                        type = "live",
                        name = channel.name.orEmpty(),
                        iconUrl = channel.stream_icon,
                    )
                )
            }
        }
    }

    private fun observeFavourites() {
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                favoriteDao.getFavoritesByType("live").collect { rows ->
                    favouriteIds = rows.map { it.streamId }.toSet()
                    adapter.notifyDataSetChanged()
                }
            }
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        dock?.release()
        dock = null
    }

    private fun color(res: Int) =
        androidx.core.content.ContextCompat.getColor(requireContext(), res)

    private companion object {
        /** How many categories ride the rail before the rest go to the sheet. */
        const val PINNED_CHIPS = 8

        /** Rows either side of the visible window to warm, so a short flick finds EPG ready. */
        const val EPG_WARM_BUFFER = 6

        /** One re-bind per warm, not one per channel in it. */
        const val EPG_REBIND_DEBOUNCE_MS = 400L
    }
}
