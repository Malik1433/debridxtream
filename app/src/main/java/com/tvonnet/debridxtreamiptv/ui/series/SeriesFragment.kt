package com.tvonnet.debridxtreamiptv.ui.series

import android.graphics.Color
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.animation.Animation
import android.view.animation.TranslateAnimation
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.lifecycle.lifecycleScope
import androidx.paging.CombinedLoadStates
import androidx.paging.LoadState
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.bumptech.glide.Glide
import com.bumptech.glide.load.resource.drawable.DrawableTransitionOptions
import com.tvonnet.debridxtreamiptv.BuildConfig
import com.tvonnet.debridxtreamiptv.R
import com.tvonnet.debridxtreamiptv.data.model.XtreamCategory
import com.tvonnet.debridxtreamiptv.data.model.XtreamSeriesInfo
import com.tvonnet.debridxtreamiptv.features.seriesv2.ui.SeriesDetailFragmentV2
import com.tvonnet.debridxtreamiptv.ui.vod.CategorySidebarAdapter
import com.tvonnet.debridxtreamiptv.util.FAVORITES_CATEGORY_ID
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import java.util.Locale

/**
 * IPTV VOD Series browse screen — structural mirror of VodFragment (purple accent).
 */
@AndroidEntryPoint
class SeriesFragment : Fragment() {

    private companion object {
        const val ACCENT_PURPLE = "#A78BFA"
        const val TEXT_MUTED = "#64748B"
        val SORT_LABELS = listOf("RECENTLY ADDED", "TOP RATED", "A – Z", "MOST EPISODES")
    }

    private val viewModel: SeriesViewModel by viewModels()

    // Core views (preserved IDs)
    private lateinit var llSidebarContainer: View
    private lateinit var rvCategoriesSidebar: RecyclerView
    private lateinit var rvSeriesGrid: RecyclerView
    private lateinit var tvCategoryTitle: TextView
    private lateinit var ivBackgroundBackdrop: ImageView
    private var llLoadingState: ViewGroup? = null
    private var llEmptyState: LinearLayout? = null
    private var tvEmptyMessage: TextView? = null
    private var tvEmptyHint: TextView? = null
    private var btnRetry: View? = null
    private var tvOfflineLabel: TextView? = null

    // Header / grid controls
    private lateinit var llSortChips: LinearLayout
    private lateinit var tvSectionMeta: TextView
    private var btnSearch: View? = null

    // State
    private var selectedCategoryId: String = ""
    private var currentCategoryName: String = ""
    private var activeSortMode: SeriesSortMode = SeriesSortMode.RECENTLY_ADDED
    private var gridColumnCount: Int = 5

    /** category_id -> synced series count, for the sidebar count badges. */
    private var categoryCounts: Map<String, Int> = emptyMap()

    private var isSeriesLoadingFromViewModel: Boolean = false
    private var lastLoadStates: CombinedLoadStates? = null

    private var currentCategories: List<XtreamCategory> = emptyList()
    private var adapterDataObserver: RecyclerView.AdapterDataObserver? = null

    // Focus memory must outlive the VIEW: opening a detail page is a replace+backstack, so the
    // pop recreates the view and with it a fresh SeriesFocusController — without this snapshot
    // the armed restore dies with the old instance and BACK drops D-pad focus entirely.
    private var focusSnapshot: Bundle? = null

    // C3 collaborators — same split as the Movies screen (C2): where focus goes, what an empty
    // grid shows, and the small per-card concerns.
    private lateinit var focus: SeriesFocusController
    private lateinit var listState: SeriesListStateUi
    private lateinit var extras: SeriesGridExtras

    // Paging adapter for series (mirror of VodAdapter wiring)
    private val seriesPagingAdapter by lazy {
        SeriesPagingAdapter(
            onSeriesClick = { series, view -> onSeriesClick(series, view) },
            favoriteChecker = { streamId -> viewModel.isFavorite(streamId) },
            onSeriesLongClick = { series -> extras.handleFavoriteLongPress(series) },
            countsProvider = { seriesId -> extras.countsFor(seriesId) }
        ).apply {
            onItemFocused = { position ->
                try {
                    val item = peek(position)
                    focus.onSeriesFocused(position, item?.series_id)
                    if (item != null) {
                        extras.updateBackdrop(item)
                        updateSectionHeaderForSeries(item)
                        // NOTE: episode prefetch was removed — getSeriesById writes the
                        // series_v2 row, which invalidated the grid's Room PagingSource and
                        // reset scroll/focus (focus bounced to the sidebar on fast scroll).
                    }
                } catch (_: Exception) {}
            }
        }
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        return inflater.inflate(R.layout.fragment_series_vod, container, false)
    }

    private fun setupAdapterObserver() {
        if (adapterDataObserver == null) {
            adapterDataObserver = object : RecyclerView.AdapterDataObserver() {
                // Only a FULL refresh (notifyDataSetChanged / category switch) needs focus
                // re-assertion. Paging appends (onItemRangeInserted while scrolling) add
                // items at the END and must NOT scrollToPosition — that fought the user's
                // scroll and bounced focus onto the sidebar.
                override fun onChanged() { focus.reassertGridFocus() }
            }
            seriesPagingAdapter.registerAdapterDataObserver(adapterDataObserver!!)
        }
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        initViews(view)
        buildCollaborators()
        (savedInstanceState ?: focusSnapshot)?.let { focus.restoreSavedState(it) }
        setupRecyclerViews()
        setupSortChips()
        setupSearch()
        focus.setupDpadNavigation()
        setupLoadStateListener()
        setupAdapterObserver()

        extras.loadEpisodeCounts()
        setupObservers()
    }

    private fun initViews(view: View) {
        llSidebarContainer = view.findViewById(R.id.ll_series_sidebar_container)
        rvCategoriesSidebar = view.findViewById(R.id.rv_categories_sidebar)
        rvSeriesGrid = view.findViewById(R.id.rv_series_grid)
        tvCategoryTitle = view.findViewById(R.id.tv_category_title)
        ivBackgroundBackdrop = view.findViewById(R.id.iv_background_backdrop)
        llLoadingState = view.findViewById(R.id.ll_loading_state)
        llEmptyState = view.findViewById(R.id.ll_empty_state)
        tvEmptyMessage = view.findViewById(R.id.tv_empty_message)
        tvEmptyHint = view.findViewById(R.id.tv_empty_hint)
        btnRetry = view.findViewById(R.id.btn_retry)
        btnRetry?.setOnClickListener {
            btnRetry?.visibility = View.GONE
            listState.showEmptyState(false)
            listState.showLoadingState(true)
            viewModel.onEvent(SeriesEvent.Retry)
        }
        tvOfflineLabel = view.findViewById(R.id.tv_offline_label)

        // Header / grid controls
        llSortChips = view.findViewById(R.id.ll_sort_chips)
        tvSectionMeta = view.findViewById(R.id.tv_section_meta)
        btnSearch = view.findViewById(R.id.btn_search)
    }

    private fun buildCollaborators() {
        focus = SeriesFocusController(
            fragment = this,
            views = SeriesGridViews(
                sidebar = rvCategoriesSidebar,
                grid = rvSeriesGrid,
                sortChips = llSortChips,
                searchButton = { btnSearch },
            ),
            adapter = { seriesPagingAdapter },
            currentCategories = { currentCategories },
            gridColumnCount = { gridColumnCount },
        )
        listState = SeriesListStateUi(
            fragment = this,
            views = SeriesListStateViews(
                grid = rvSeriesGrid,
                loading = { llLoadingState },
                empty = { llEmptyState },
                message = { tvEmptyMessage },
                hint = { tvEmptyHint },
                retry = { btnRetry },
            ),
            itemCount = { seriesPagingAdapter.itemCount },
            isLoadingFromViewModel = { isSeriesLoadingFromViewModel },
            onRefreshSettled = {
                focus.restoreFocusIfPossible()
                refreshSectionCount()
            },
        )
        extras = SeriesGridExtras(
            fragment = this,
            viewModel = viewModel,
            adapter = { seriesPagingAdapter },
            backdropView = { if (::ivBackgroundBackdrop.isInitialized) ivBackgroundBackdrop else null },
        )
    }

    private fun setupRecyclerViews() {
        rvCategoriesSidebar.layoutManager = LinearLayoutManager(context, LinearLayoutManager.VERTICAL, false)

        val gridLayoutManager = GridLayoutManager(context, gridColumnCount)
        gridLayoutManager.initialPrefetchItemCount = 10
        gridLayoutManager.isItemPrefetchEnabled = true
        rvSeriesGrid.layoutManager = gridLayoutManager

        rvCategoriesSidebar.setHasFixedSize(true)
        rvCategoriesSidebar.isFocusable = true
        rvCategoriesSidebar.isFocusableInTouchMode = true
        rvCategoriesSidebar.itemAnimator = null
        rvCategoriesSidebar.descendantFocusability = ViewGroup.FOCUS_AFTER_DESCENDANTS

        rvSeriesGrid.setHasFixedSize(true)
        rvSeriesGrid.isFocusable = true
        rvSeriesGrid.isFocusableInTouchMode = true
        rvSeriesGrid.itemAnimator = null
        rvSeriesGrid.setItemViewCacheSize(20)
        rvSeriesGrid.descendantFocusability = ViewGroup.FOCUS_AFTER_DESCENDANTS

        val spacingPx = resources.getDimensionPixelSize(R.dimen.grid_spacing_medium)
        rvSeriesGrid.addItemDecoration(
            com.tvonnet.debridxtreamiptv.utils.GridSpacingItemDecoration(gridColumnCount, spacingPx, true)
        )

        rvSeriesGrid.adapter = seriesPagingAdapter

        com.tvonnet.debridxtreamiptv.utils.FocusTrapHelper.attachFocusTrap(rvCategoriesSidebar, loopFocus = true)
        com.tvonnet.debridxtreamiptv.utils.SpatialNavigationEngine.enforceStrictOrthogonalNavigation(rvSeriesGrid)
        applyAutoSpanCount()
    }

    /**
     * Column count derived from the grid width so every poster renders at the shared home
     * size (~128dp wide) — one consistent look instead of a user-selectable 4/5/6 grid.
     */
    private fun applyAutoSpanCount() {
        rvSeriesGrid.post {
            if (!isAdded) return@post
            val w = rvSeriesGrid.width
            if (w <= 0) return@post
            val density = resources.displayMetrics.density
            val spacingPx = resources.getDimensionPixelSize(R.dimen.grid_spacing_medium)
            val targetCell = (resources.getDimensionPixelSize(R.dimen.poster_top_10_width) + spacingPx)
                .coerceAtLeast((100 * density).toInt())
            val span = (w / targetCell).coerceIn(2, 8)
            val lm = rvSeriesGrid.layoutManager as? GridLayoutManager ?: return@post
            if (lm.spanCount != span) {
                lm.spanCount = span
                gridColumnCount = span
                while (rvSeriesGrid.itemDecorationCount > 0) rvSeriesGrid.removeItemDecorationAt(0)
                rvSeriesGrid.addItemDecoration(
                    com.tvonnet.debridxtreamiptv.utils.GridSpacingItemDecoration(span, spacingPx, true)
                )
                rvSeriesGrid.adapter?.notifyItemRangeChanged(0, rvSeriesGrid.adapter?.itemCount ?: 0)
            }
        }
    }

    private fun setupSortChips() {
        llSortChips.removeAllViews()
        val sortModes = SeriesSortMode.values()
        sortModes.forEachIndexed { index, mode ->
            val chip = LayoutInflater.from(context)
                .inflate(R.layout.item_vod_sort_chip, llSortChips, false) as TextView
            chip.text = SORT_LABELS[index]
            val isActive = mode == activeSortMode
            updateSortChipStyle(chip, isActive)
            chip.setOnClickListener {
                activeSortMode = mode
                refreshSortChipStyles()
                viewModel.setSortMode(mode)
            }
            chip.setOnFocusChangeListener { v, hasFocus ->
                if (hasFocus) {
                    (v as TextView).setBackgroundResource(R.drawable.bg_series_sort_chip_active)
                    (v as TextView).setTextColor(Color.parseColor(ACCENT_PURPLE))
                } else {
                    updateSortChipStyle(v as TextView, sortModes[index] == activeSortMode)
                }
            }
            // DOWN from a sort chip steps straight into the grid; LEFT/RIGHT traverse chips.
            chip.setOnKeyListener { _, keyCode, event ->
                if (event.action == android.view.KeyEvent.ACTION_DOWN &&
                    keyCode == android.view.KeyEvent.KEYCODE_DPAD_DOWN
                ) {
                    focus.focusGridTop()
                    true
                } else false
            }
            llSortChips.addView(chip)
        }
    }

    private fun refreshSortChipStyles() {
        val sortModes = SeriesSortMode.values()
        for (i in 0 until llSortChips.childCount) {
            val chip = llSortChips.getChildAt(i) as? TextView ?: continue
            updateSortChipStyle(chip, sortModes[i] == activeSortMode)
        }
    }

    private fun updateSortChipStyle(chip: TextView, isActive: Boolean) {
        if (isActive) {
            chip.setBackgroundResource(R.drawable.bg_series_sort_chip_active)
            chip.setTextColor(Color.parseColor(ACCENT_PURPLE))
        } else {
            chip.setBackgroundResource(R.drawable.bg_vod_sort_chip_default)
            chip.setTextColor(Color.parseColor(TEXT_MUTED))
        }
    }

    private fun setupSearch() {
        btnSearch?.apply {
            isFocusable = true
            isFocusableInTouchMode = true
            setOnClickListener { openSearch() }
            // Visible focus highlight for the search pill (bg_vod_search_bar has no focus state).
            setOnFocusChangeListener { v, hasFocus ->
                v.setBackgroundResource(
                    if (hasFocus) R.drawable.bg_series_search_bar_focused
                    else R.drawable.bg_vod_search_bar
                )
            }
        }
    }

    private fun openSearch() {
        if (!isAdded) return
        // Scope search to series, and to the open real category (virtual "All Series" /
        // "Recently Added" map to null = search all series).
        val scopedCategoryId = when (selectedCategoryId) {
            SeriesViewModel.ALL_SERIES_CATEGORY_ID,
            SeriesViewModel.RECENTLY_ADDED_CATEGORY_ID -> null
            else -> selectedCategoryId
        }
        android.util.Log.i(
            "SearchScope",
            "openSearch selectedCategoryId=$selectedCategoryId scopedCategoryId=$scopedCategoryId"
        )
        val fragment = com.tvonnet.debridxtreamiptv.ui.search.SearchFragment().apply {
            arguments = Bundle().apply {
                putBoolean("series_scope", true)
                putString("category_id", scopedCategoryId)
                putString("category_name", currentCategoryName)
            }
        }
        parentFragmentManager.beginTransaction()
            .replace(R.id.content_container, fragment)
            .addToBackStack(null)
            .commit()
    }

    private fun setupLoadStateListener() {
        seriesPagingAdapter.addLoadStateListener { loadState ->
            lastLoadStates = loadState
            listState.updateListLoadingAndEmptyStates(loadState)
        }
    }

    private fun updateSectionHeaderForSeries(series: XtreamSeriesInfo) {
        tvCategoryTitle.text = com.tvonnet.debridxtreamiptv.util.MediaTitleCleaner
            .clean(series.name).ifBlank { currentCategoryName }
        val rating = series.rating?.trim()?.replace(",", ".")?.toFloatOrNull()
        val ratingText = if (rating != null && rating > 0) {
            val stars = if (rating > 5f) rating / 2f else rating
            String.format(Locale.US, "%.1f", stars.coerceIn(0f, 5f))
        } else "N/A"
        val year = series.releaseDate?.take(4) ?: ""
        val counts = series.series_id?.let { extras.countsFor(it) }
        val middle = if (counts != null) "${counts.first}S · ${counts.second}EP"
        else series.genre?.split(",")?.firstOrNull()?.trim() ?: ""
        tvSectionMeta.text = "★ $ratingText  ·  $middle  ·  $year"
        tvSectionMeta.setTextColor(Color.parseColor(ACCENT_PURPLE))
    }

    private fun resetSectionHeader() {
        tvCategoryTitle.text = currentCategoryName
        val count = categoryCounts[selectedCategoryId] ?: seriesPagingAdapter.itemCount
        tvSectionMeta.text = "$count titles"
        tvSectionMeta.setTextColor(Color.parseColor("#475569"))
    }

    private fun setupObservers() {
        viewLifecycleOwner.lifecycleScope.launch {
            viewModel.uiState.collectLatest { state ->
                val displayCategories = buildSidebarCategories(state.categories)
                val categoryAdapter = rvCategoriesSidebar.adapter as? CategorySidebarAdapter
                if (categoryAdapter == null && displayCategories.isNotEmpty()) {
                    currentCategories = displayCategories
                    setupCategorySidebar(displayCategories)
                } else if (categoryAdapter != null && displayCategories != currentCategories) {
                    currentCategories = displayCategories
                    categoryAdapter.updateCategories(displayCategories, state.selectedCategoryId)
                }

                // Sidebar count badges — refresh rows when the counts change.
                if (state.categoryCounts != categoryCounts) {
                    categoryCounts = state.categoryCounts
                    rvCategoriesSidebar.adapter?.notifyDataSetChanged()
                    // Keep the section "N TITLES" in sync with the sidebar total.
                    refreshSectionCount()
                }

                state.selectedCategoryId?.let { selectedId ->
                    selectedCategoryId = selectedId
                    (rvCategoriesSidebar.adapter as? CategorySidebarAdapter)?.setSelectedById(selectedId)
                }

                if (isSeriesLoadingFromViewModel != state.isLoadingSeries || state.isSwitchingCategory) {
                    isSeriesLoadingFromViewModel = state.isLoadingSeries
                    lastLoadStates?.let { listState.updateListLoadingAndEmptyStates(it, state.isSwitchingCategory) }
                }

                if (state.error != null) {
                    listState.hasLoadError = true
                    Toast.makeText(context, state.error, Toast.LENGTH_SHORT).show()
                    if (seriesPagingAdapter.itemCount == 0 && !state.isLoadingSeries) listState.showEmptyState(true)
                } else if (seriesPagingAdapter.itemCount > 0) {
                    listState.hasLoadError = false
                }

                state.selectedCategoryId?.let { id ->
                    val title = when (id) {
                        FAVORITES_CATEGORY_ID -> getString(R.string.favorites)
                        SeriesViewModel.ALL_SERIES_CATEGORY_ID -> "All Series"
                        SeriesViewModel.RECENTLY_ADDED_CATEGORY_ID -> "Recently Added"
                        else -> state.categories.find { it.category_id == id }?.category_name ?: "Series"
                    }
                    currentCategoryName = title
                    if (focus.lastFocusTarget == SeriesFocusController.FocusTarget.CATEGORIES) {
                        resetSectionHeader()
                    }
                }

                tvOfflineLabel?.visibility =
                    if (state.error != null) View.VISIBLE else View.GONE

                if (state.isLoadingSeries && seriesPagingAdapter.itemCount == 0) {
                    listState.showEmptyState(false)
                    listState.showLoadingState(true)
                }
            }
        }

        viewLifecycleOwner.lifecycleScope.launch {
            viewModel.pagedSeries.collectLatest { pagingData ->
                seriesPagingAdapter.submitData(pagingData)
            }
        }
    }

    private fun buildSidebarCategories(categories: List<XtreamCategory>): List<XtreamCategory> {
        // Build the two virtual rows locally so they ALWAYS render (top two rows), even on
        // the first frame before the ViewModel's category list has propagated. Order:
        // [All Series, Recently Added] → Favorites → real.
        val allSeries = XtreamCategory(SeriesViewModel.ALL_SERIES_CATEGORY_ID, "All Series", null)
        val recentlyAdded = XtreamCategory(SeriesViewModel.RECENTLY_ADDED_CATEGORY_ID, "Recently Added", null)
        val favorites = XtreamCategory(FAVORITES_CATEGORY_ID, getString(R.string.favorites), null)
        val virtualIds = setOf(
            SeriesViewModel.ALL_SERIES_CATEGORY_ID,
            SeriesViewModel.RECENTLY_ADDED_CATEGORY_ID
        )
        val real = categories.filterNot {
            it.category_id in virtualIds || it.category_id == FAVORITES_CATEGORY_ID
        }
        return listOf(allSeries, recentlyAdded, favorites) + real
    }

    private fun setupCategorySidebar(categories: List<XtreamCategory>) {
        val categorySidebarAdapter = CategorySidebarAdapter(
            categories,
            layoutRes = R.layout.item_series_category,
            activeBgRes = R.drawable.bg_series_category_active,
            accentColor = Color.parseColor(ACCENT_PURPLE),
            countProvider = { id -> id?.let { categoryCounts[it] } }
        ) { category ->
            selectedCategoryId = category.category_id ?: ""
            viewModel.onEvent(SeriesEvent.SelectCategory(selectedCategoryId))
        }
        categorySidebarAdapter.onItemFocused = { position ->
            focus.onCategoryFocused(position, categories.getOrNull(position)?.category_id)
            resetSectionHeader()
        }
        rvCategoriesSidebar.adapter = categorySidebarAdapter

        focus.restoreFocusIfPossible()
    }

    /**
     * Section "N TITLES" count. Prefer the true DB total for the selected category
     * (the same number shown in the sidebar) so the two always agree — Paging's
     * itemCount is only the loaded-so-far count, not the catalog total.
     */
    private fun refreshSectionCount() {
        val count = categoryCounts[selectedCategoryId] ?: seriesPagingAdapter.itemCount
        // Don't clobber the focused-series meta line; only refresh the plain count.
        if (focus.lastFocusTarget != SeriesFocusController.FocusTarget.SERIES) {
            tvSectionMeta.text = "$count titles"
        }
    }

    private fun onSeriesClick(series: XtreamSeriesInfo, sharedView: View) {
        val fragment = SeriesDetailFragmentV2().apply {
            arguments = Bundle().apply {
                putString("series_id", series.series_id)
                putString("title", series.name)
                putString("backdrop_url", series.cover)
                putString("poster_url", series.cover)
                putString("trailer", series.youtube_trailer)
            }
        }
        parentFragmentManager.beginTransaction()
            .replace(R.id.content_container, fragment)
            .addToBackStack(null)
            .commit()
    }

    override fun onResume() {
        super.onResume()
        focus.restoreFocusIfPossible()
        extras.loadEpisodeCounts()
    }

    override fun onPause() {
        super.onPause()
        com.tvonnet.debridxtreamiptv.utils.FocusMemoryManager.saveFocus(requireView())
        focus.onPause()
        focusSnapshot = Bundle().also { focus.saveState(it) }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        focus.saveState(outState)
    }

    override fun onDestroyView() {
        if (::extras.isInitialized) extras.clear()
        adapterDataObserver?.let {
            try { seriesPagingAdapter.unregisterAdapterDataObserver(it) } catch (_: Exception) {}
        }
        adapterDataObserver = null

        super.onDestroyView()
        if (::rvCategoriesSidebar.isInitialized) rvCategoriesSidebar.adapter = null
        if (::rvSeriesGrid.isInitialized) rvSeriesGrid.adapter = null
        llLoadingState = null
        llEmptyState = null
    }
}
