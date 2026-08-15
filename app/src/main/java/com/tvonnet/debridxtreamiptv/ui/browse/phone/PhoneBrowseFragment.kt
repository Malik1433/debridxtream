package com.tvonnet.debridxtreamiptv.ui.browse.phone

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.paging.LoadState
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.tvonnet.debridxtreamiptv.R
import com.tvonnet.debridxtreamiptv.data.model.XtreamCategory
import com.tvonnet.debridxtreamiptv.ui.live.phone.PhoneUi
import com.tvonnet.debridxtreamiptv.ui.live.phone.PhoneBottomNav
import com.tvonnet.debridxtreamiptv.ui.nav.SectionNavigator
import com.tvonnet.debridxtreamiptv.ui.series.SeriesEvent
import com.tvonnet.debridxtreamiptv.ui.series.SeriesSortMode
import com.tvonnet.debridxtreamiptv.ui.series.SeriesViewModel
import com.tvonnet.debridxtreamiptv.ui.vod.VodEvent
import com.tvonnet.debridxtreamiptv.ui.vod.VodSortMode
import com.tvonnet.debridxtreamiptv.ui.vod.VodViewModel
import com.tvonnet.debridxtreamiptv.util.PortraitScreen
import dagger.hilt.android.AndroidEntryPoint
import androidx.paging.map
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/**
 * PHONE Browse — the Movies and Series tabs, portrait, built to the "DX Play Browse" handoff.
 *
 * ONE fragment serves both sections. They differ in four places and nowhere else: the catalogue
 * it reads, the category list, the card's second line and which nav item is lit. Two fragments
 * would be the same fragment twice, and two places to forget a fix.
 *
 * It owns no data of its own. Both ViewModels already expose exactly what this screen needs —
 * categories with counts, a paged flow, a sort mode, a search — and they are the same ones the
 * television screens use, so a fix to either lands on both form factors.
 */
@AndroidEntryPoint
class PhoneBrowseFragment : Fragment(), PortraitScreen {

    private val movies: VodViewModel by viewModels()
    private val series: SeriesViewModel by viewModels()

    private val isSeries: Boolean
        get() = arguments?.getString(ARG_SECTION) == SectionNavigator.SECTION_SERIES

    private lateinit var list: RecyclerView
    private lateinit var adapter: PhoneBrowseAdapter
    private lateinit var continueRow: PhoneBrowseContinueRow
    private lateinit var skeleton: PhoneBrowseSkeleton
    private lateinit var states: PhoneBrowseStates

    @javax.inject.Inject
    lateinit var browseRepository: com.tvonnet.debridxtreamiptv.data.repository.XtreamRepository

    @javax.inject.Inject
    lateinit var browseEpisodeDao: com.tvonnet.debridxtreamiptv.features.seriesv2.data.dao.EpisodeDaoV2

    /**
     * Continue Watching resumes through the SAME router Home uses, so a half-watched movie
     * behaves identically wherever the user picks it up — including the play-then-repair rule
     * that makes a resume start on the link it already holds.
     */
    private val router by lazy {
        com.tvonnet.debridxtreamiptv.ui.home.HomeNavigationRouter(
            object : com.tvonnet.debridxtreamiptv.ui.home.HomeRouterHost {
                override val routerFragment = this@PhoneBrowseFragment
                override val routerRepository = browseRepository
                override val routerCredentials =
                    com.tvonnet.debridxtreamiptv.data.prefs.CredentialsPreferences(requireContext())
                override val routerEpisodeDao = browseEpisodeDao
            }
        )
    }
    private lateinit var categoryRail: LinearLayout
    private lateinit var chrome: PhoneBrowseChrome

    private var categories: List<XtreamCategory> = emptyList()
    private var categoryCounts: Map<String, Int> = emptyMap()
    private var selectedCategoryId: String? = null

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View = PhoneUi.unscaled(this, inflater)
        .inflate(R.layout.fragment_browse_phone, container, false)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        view.findViewById<TextView>(R.id.phone_browse_title)
            .setText(if (isSeries) R.string.nav_series else R.string.nav_movies)

        categoryRail = view.findViewById(R.id.phone_browse_categories)
        chrome = PhoneBrowseChrome(
            rail = categoryRail,
            filterChip = view.findViewById(R.id.phone_browse_filters),
        )
        states = PhoneBrowseStates(
            view.findViewById(R.id.phone_browse_state),
            PhoneUi.unscaled(this, layoutInflater),
        )
        bindChrome(view)
        bindList(view)
        observeCategories()
        observeItems()
    }

    // ── Chrome ────────────────────────────────────────────────────────────────────────────

    private fun bindChrome(root: View) {
        root.findViewById<View>(R.id.phone_browse_search).setOnClickListener {
            SectionNavigator.navigate(this, SectionNavigator.SECTION_SEARCH)
        }
        root.findViewById<View>(R.id.phone_action_settings).setOnClickListener {
            SectionNavigator.navigate(this, SectionNavigator.SECTION_SETTINGS)
        }
        root.findViewById<View>(R.id.phone_browse_avatar).setOnClickListener {
            SectionNavigator.navigate(this, SectionNavigator.SECTION_SETTINGS)
        }
        root.findViewById<TextView>(R.id.phone_browse_avatar_initial).text = "A"

        root.findViewById<TextView>(R.id.phone_browse_sort).apply {
            text = sortLabel()
            setOnClickListener { openSortSheet() }
        }
        root.findViewById<TextView>(R.id.phone_browse_filters).setOnClickListener {
            openFilterSheet()
        }

        PhoneBottomNav.build(
            root.findViewById(R.id.phone_browse_nav),
            PhoneUi.unscaled(this, layoutInflater),
            if (isSeries) SectionNavigator.SECTION_SERIES else SectionNavigator.SECTION_MOVIES,
        ) { section -> navigateFromBar(section) }
    }

    /**
     * A nav hop from a tab pops to the root first: tabs are siblings, not a stack, so hopping
     * Movies → Series → Movies must not leave three screens behind Back.
     */
    private fun navigateFromBar(section: String) {
        parentFragmentManager.popBackStack(
            null, androidx.fragment.app.FragmentManager.POP_BACK_STACK_INCLUSIVE
        )
        SectionNavigator.navigate(this, section)
    }

    // ── The list ──────────────────────────────────────────────────────────────────────────

    private fun bindList(root: View) {
        list = root.findViewById(R.id.phone_browse_list)
        adapter = PhoneBrowseAdapter(
            onItem = { item -> openDetail(item) },
            onLongPress = { item -> openCardActions(item) },
        )
        continueRow = PhoneBrowseContinueRow(getString(R.string.section_continue_watching)) { item ->
            router.onContinueWatchingItemClick(item)
        }
        skeleton = PhoneBrowseSkeleton()

        // ONE list: the Continue Watching rail, the skeleton, the grid and the paging footer are
        // sections of the same RecyclerView, so the page has a single scroll and the rail leaves
        // no hole behind when it empties.
        val concat = androidx.recyclerview.widget.ConcatAdapter(
            continueRow,
            skeleton,
            adapter.withLoadStateFooter(PhoneBrowseFooter { adapter.retry() }),
        )
        val columns = COLUMNS
        val manager = GridLayoutManager(requireContext(), columns)
        manager.spanSizeLookup = object : GridLayoutManager.SpanSizeLookup() {
            override fun getSpanSize(position: Int): Int {
                // The rail and the footer are full-width rows; posters and skeleton blocks are
                // one column each.
                val railRows = continueRow.itemCount
                if (position < railRows) return columns
                val gridStart = railRows + skeleton.itemCount
                return if (position >= gridStart + adapter.itemCount) columns else 1
            }
        }
        list.layoutManager = manager
        list.adapter = concat
        list.addItemDecoration(
            PhoneGridSpacing(columns, PhoneUi.dp(requireContext(), 10)) { position ->
                // A full-width row must not be pushed in by the grid's column maths.
                manager.spanSizeLookup.getSpanSize(position) == columns
            }
        )
    }

    /**
     * The rail comes and goes with the watch history, so it is re-read every time the screen comes
     * forward rather than once: the user may have just finished something in the player.
     */
    private fun refreshContinueWatching() {
        val all = com.tvonnet.debridxtreamiptv.data.prefs.WatchHistoryPreferences(requireContext())
            .getContinueWatchingList()
        val mine = all.filter { item ->
            if (isSeries) {
                item.contentType == com.tvonnet.debridxtreamiptv.data.model.ContentType.EPISODE ||
                    item.contentType == com.tvonnet.debridxtreamiptv.data.model.ContentType.SERIES
            } else {
                item.contentType == com.tvonnet.debridxtreamiptv.data.model.ContentType.MOVIE
            }
        }
        continueRow.submit(mine)
    }

    override fun onResume() {
        super.onResume()
        refreshContinueWatching()
    }

    /**
     * Long-press is a SHORTCUT, never the only route: everything here is also reachable from the
     * Detail page one tap away, which is the phone rulebook's rule for hidden gestures.
     */
    private fun openCardActions(item: PhoneBrowseItem) {
        viewLifecycleOwner.lifecycleScope.launch {
            // The sheet is opened only once its own state is known: a Favourite row that has to
            // guess whether it is already a favourite would be a row that lies half the time.
            val favourite = runCatching { browseRepository.isFavorite(item.id) }.getOrDefault(false)
            PhoneBrowseSheets.cardActions(
                host = this@PhoneBrowseFragment,
                title = item.title,
                isFavourite = favourite,
                onDetails = { openDetail(item) },
                onFavourite = {
                    viewLifecycleOwner.lifecycleScope.launch {
                        if (favourite) {
                            browseRepository.removeFavorite(item.id)
                        } else {
                            browseRepository.addFavorite(
                                streamId = item.id,
                                type = if (isSeries) "series" else "vod",
                                name = item.title,
                                iconUrl = item.posterUrl,
                            )
                        }
                    }
                },
            )
        }
    }

    private fun openDetail(item: PhoneBrowseItem) {
        val fragment = if (isSeries) {
            com.tvonnet.debridxtreamiptv.ui.detail.phone.DetailScreens.series(
                context = requireContext(),
                seriesId = item.id,
                title = item.title,
                posterUrl = item.posterUrl,
                backdropUrl = item.backdropUrl,
            )
        } else {
            com.tvonnet.debridxtreamiptv.ui.detail.phone.DetailScreens.movie(
                context = requireContext(),
                streamId = item.id,
                title = item.title,
                posterUrl = item.posterUrl,
                backdropUrl = item.backdropUrl,
                containerExtension = item.containerExtension,
            )
        }
        parentFragmentManager.beginTransaction()
            .replace(R.id.content_container, fragment)
            .addToBackStack(null)
            .commit()
    }

    // ── Data ──────────────────────────────────────────────────────────────────────────────

    private fun observeCategories() {
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                if (isSeries) {
                    series.uiState.collect { state ->
                        categories = state.categories
                        categoryCounts = state.categoryCounts
                        selectedCategoryId = state.selectedCategoryId
                        renderCategoryRail()
                    }
                } else {
                    movies.uiState.collect { state ->
                        categories = state.categories
                        categoryCounts = state.categoryCounts
                        selectedCategoryId = state.selectedCategoryId
                        renderCategoryRail()
                    }
                }
            }
        }
    }

    private fun observeItems() {
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                val flow = if (isSeries) {
                    series.pagedSeries.map { paging ->
                        paging.map { PhoneBrowseItem.of(it, currentCategoryName()) }
                    }
                } else {
                    movies.pagedMovies.map { paging ->
                        paging.map { PhoneBrowseItem.of(it, currentCategoryName()) }
                    }
                }
                flow.collect { adapter.submitData(it) }
            }
        }
        // The result count on the control row is the catalogue's own answer, not the number of
        // rows that happen to be loaded — a paged list would otherwise report "60" forever.
        adapter.addLoadStateListener { loadStates ->
            val view = view ?: return@addLoadStateListener
            view.findViewById<TextView>(R.id.phone_browse_count).text =
                categoryCounts[selectedCategoryId]?.let(::formatCount).orEmpty()
            // The skeleton stands in only while the grid has NOTHING to show. Paging reports
            // NotLoading the instant Room answers an unfetched category with an empty page, so
            // an item count of zero is the honest test, not the load state alone.
            val empty = adapter.itemCount == 0
            val failed = loadStates.refresh is LoadState.Error
            skeleton.show(empty && !failed)
            renderState(
                empty = empty,
                failed = failed,
                settled = loadStates.refresh is LoadState.NotLoading,
            )
        }
    }

    /**
     * Which of the four statements this screen is currently making.
     *
     * The order matters: a failure is a failure whatever else is true, an empty catalogue is a
     * different problem from an empty category, and anything with rows to show is not a problem
     * at all.
     */
    private fun renderState(empty: Boolean, failed: Boolean, settled: Boolean) {
        val panel = view?.findViewById<View>(R.id.phone_browse_state) ?: return
        when {
            failed -> {
                panel.isVisible = true
                states.error(onRetry = { adapter.refresh() }, onCached = null)
            }
            // Not settled yet means the skeleton owns the screen; a panel would race it.
            !empty || !settled -> {
                panel.isVisible = false
                states.hide()
            }
            categories.isEmpty() -> {
                panel.isVisible = true
                states.emptyCatalogue(onRecheck = { adapter.refresh() })
            }
            else -> {
                panel.isVisible = true
                states.emptyCategory(
                    categoryName = categories.firstOrNull { it.category_id == selectedCategoryId }
                        ?.category_name.orEmpty(),
                    onAll = { categories.firstOrNull()?.category_id?.let { selectCategory(it) } },
                    onRetry = { adapter.refresh() },
                )
            }
        }
    }

    // ── Category rail ─────────────────────────────────────────────────────────────────────

    private fun renderCategoryRail() = chrome.renderRail(
        inflater = PhoneUi.unscaled(this, layoutInflater),
        data = PhoneBrowseChrome.Rail(categories, categoryCounts, selectedCategoryId),
        onOpener = { openCategorySheet() },
        onPick = { id -> selectCategory(id) },
    )

    /** The name of the category being browsed; the quality badge falls back to it. */
    private fun currentCategoryName(): String? =
        categories.firstOrNull { it.category_id == selectedCategoryId }?.category_name

    private fun selectCategory(id: String) {
        if (isSeries) series.onEvent(SeriesEvent.SelectCategory(id))
        else movies.onEvent(VodEvent.SelectCategory(id))
        list.scrollToPosition(0)
    }

    // ── Sheets ────────────────────────────────────────────────────────────────────────────

    private fun openCategorySheet() {
        PhoneBrowseSheets.categories(
            host = this,
            categories = categories,
            counts = categoryCounts,
            selectedId = selectedCategoryId,
        ) { id -> selectCategory(id) }
    }

    /** What the catalogue is currently narrowed by; empty means the whole category. */
    private var filters = PhoneBrowseFilterSheet.Selection()

    private fun openFilterSheet() {
        PhoneBrowseFilterSheet(
            host = this,
            selection = filters,
            categoryName = currentCategoryName(),
            // Null means "not countable here" — the virtual All categories, where a count is a
            // 141k-row query nobody wants to wait behind. Zero is a real answer and disables the
            // apply button.
            countFor = { groups ->
                if (isSeries) series.countWithFilters(groups) else movies.countWithFilters(groups)
            },
            onApply = { picked ->
                filters = picked
                val groups = picked.groups(currentCategoryName())
                if (isSeries) series.setTitleFilters(groups) else movies.setTitleFilters(groups)
                renderFilterChip()
                list.scrollToPosition(0)
            },
        ).show(PhoneBrowseChrome.languagesIn(adapter.snapshot().items.map { it.title }))
    }

    private fun renderFilterChip() = chrome.renderFilterChip(filters.activeCount)

    private fun openSortSheet() {
        PhoneBrowseSheets.sort(
            host = this,
            isSeries = isSeries,
            current = currentSortKey(),
        ) { key ->
            applySort(key)
            view?.findViewById<TextView>(R.id.phone_browse_sort)?.text = sortLabel()
            list.scrollToPosition(0)
        }
    }

    /** The ViewModels keep their sort private, so the current order lives here and is pushed
     *  down — one owner, and the chip can never disagree with the list. */
    private var sortKey: String = "RECENTLY_ADDED"

    private fun currentSortKey(): String = sortKey

    private fun applySort(key: String) {
        sortKey = key
        if (isSeries) series.setSortMode(SeriesSortMode.valueOf(key))
        else movies.setSortMode(VodSortMode.valueOf(key))
    }

    private fun sortLabel(): String = getString(
        PhoneBrowseSheets.sortLabelRes(currentSortKey())
    )

    private fun formatCount(value: Int): String = "%,d".format(value)

    companion object {
        /** Three columns: two waste a third of a screen whose whole job is density, four drop the
         *  poster to 88dp where titles stop being legible. */
        private const val COLUMNS = 3
        private const val ARG_SECTION = "section"

        fun newInstance(section: String) = PhoneBrowseFragment().apply {
            arguments = Bundle().apply { putString(ARG_SECTION, section) }
        }
    }
}

/** The 10dp column gap and 18dp row gap the handoff specifies, without padding the outer edges. */
private class PhoneGridSpacing(
    private val columns: Int,
    private val gap: Int,
    private val isFullWidth: (Int) -> Boolean = { false },
) : RecyclerView.ItemDecoration() {
    override fun getItemOffsets(
        outRect: android.graphics.Rect,
        view: View,
        parent: RecyclerView,
        state: RecyclerView.State,
    ) {
        val position = parent.getChildAdapterPosition(view)
        if (position < 0 || isFullWidth(position)) return
        val column = position % columns
        outRect.left = column * gap / columns
        outRect.right = gap - (column + 1) * gap / columns
        outRect.bottom = gap * 18 / 10
    }
}
