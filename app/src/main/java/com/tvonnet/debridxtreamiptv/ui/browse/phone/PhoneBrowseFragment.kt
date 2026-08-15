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
        root.findViewById<View>(R.id.phone_browse_avatar).setOnClickListener {
            SectionNavigator.navigate(this, SectionNavigator.SECTION_SETTINGS)
        }
        root.findViewById<TextView>(R.id.phone_browse_avatar_initial).text = "A"

        root.findViewById<TextView>(R.id.phone_browse_sort).apply {
            text = sortLabel()
            setOnClickListener { openSortSheet() }
        }
        // Filters are the one control this screen does not have real data for yet: quality and
        // language counts mean a query per option, and on this owner's 141k-title "All Movies"
        // that is not a free number. Until the counted version exists the chip does not pretend
        // — it opens nothing and says so once.
        root.findViewById<TextView>(R.id.phone_browse_filters).setOnClickListener {
            android.widget.Toast.makeText(
                requireContext(), R.string.phone_filters_soon, android.widget.Toast.LENGTH_SHORT
            ).show()
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
        adapter = PhoneBrowseAdapter { item -> openDetail(item) }
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
                    series.pagedSeries.map { paging -> paging.map(PhoneBrowseItem::of) }
                } else {
                    movies.pagedMovies.map { paging -> paging.map(PhoneBrowseItem::of) }
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

    /**
     * Selected first, then playlist order. The opener chip leads to the searchable sheet, because
     * at 200 categories scrolling is not a retrieval strategy — typing is.
     */
    /** The chip is Live TV's — the same 48dp two-line category chip, because Browse's rail IS
     *  that rail. A second layout doing the same job is a second place to forget a fix. */
    private fun renderCategoryRail() {
        val inflater = PhoneUi.unscaled(this, layoutInflater)
        categoryRail.removeAllViews()

        val opener = inflater.inflate(R.layout.item_phone_category_chip, categoryRail, false)
        opener.findViewById<TextView>(R.id.phone_chip_name).text =
            getString(R.string.phone_all_prefix) + " " + categories.size
        opener.findViewById<TextView>(R.id.phone_chip_count).text =
            getString(R.string.phone_categories).uppercase()
        opener.setOnClickListener { openCategorySheet() }
        categoryRail.addView(opener)

        val ordered = categories.sortedByDescending { it.category_id == selectedCategoryId }
        ordered.take(RAIL_CHIPS).forEach { category ->
            val chip = inflater.inflate(R.layout.item_phone_category_chip, categoryRail, false)
            val selected = category.category_id == selectedCategoryId
            chip.findViewById<TextView>(R.id.phone_chip_name).apply {
                text = category.category_name
                setTextColor(
                    androidx.core.content.ContextCompat.getColor(
                        requireContext(),
                        if (selected) R.color.phone_cyan else R.color.phone_text_primary
                    )
                )
            }
            chip.findViewById<TextView>(R.id.phone_chip_count).text =
                categoryCounts[category.category_id]?.let { formatCount(it) + " TITLES" }.orEmpty()
            chip.setBackgroundResource(
                if (selected) R.drawable.bg_phone_chip_active else R.drawable.bg_phone_chip
            )
            chip.setOnClickListener { selectCategory(category.category_id.orEmpty()) }
            categoryRail.addView(chip)
        }
    }

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
        private const val RAIL_CHIPS = 12
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
