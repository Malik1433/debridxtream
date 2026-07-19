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

    private enum class FocusTarget { CATEGORIES, SERIES }

    private companion object {
        const val STATE_LAST_CATEGORY_POS = "series_last_category_pos"
        const val STATE_LAST_SERIES_POS = "series_last_series_pos"
        const val STATE_LAST_FOCUS_TARGET = "series_last_focus_target"
        const val STATE_LAST_CATEGORY_ID = "series_last_category_id"
        const val STATE_LAST_SERIES_ID = "series_last_series_id"
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
    private var hasLoadError: Boolean = false
    private var lastLoadStates: CombinedLoadStates? = null

    private var lastFocusedCategoryPosition: Int = RecyclerView.NO_POSITION
    private var lastFocusedSeriesPosition: Int = RecyclerView.NO_POSITION
    private var lastFocusedCategoryId: String? = null
    private var lastFocusedSeriesId: String? = null
    private var lastFocusTarget: FocusTarget = FocusTarget.SERIES
    private var pendingRestoreFocus: Boolean = false

    private var restoreCategoryPosition: Int = RecyclerView.NO_POSITION
    private var restoreSeriesPosition: Int = RecyclerView.NO_POSITION
    private var restoreCategoryId: String? = null
    private var restoreSeriesId: String? = null
    private var restoreFocusTarget: FocusTarget = FocusTarget.SERIES

    private var currentCategories: List<XtreamCategory> = emptyList()
    private var adapterDataObserver: RecyclerView.AdapterDataObserver? = null

    /** series_id -> (seasonCount, episodeCount) from the local episodes cache. */
    private var episodeCountsCache: Map<String, Pair<Int, Int>> = emptyMap()

    // Paging adapter for series (mirror of VodAdapter wiring)
    private val seriesPagingAdapter by lazy {
        SeriesPagingAdapter(
            onSeriesClick = { series, view -> onSeriesClick(series, view) },
            favoriteChecker = { streamId -> viewModel.isFavorite(streamId) },
            onSeriesLongClick = { series -> handleFavoriteLongPress(series) },
            countsProvider = { seriesId -> episodeCountsCache[seriesId] }
        ).apply {
            onItemFocused = { position ->
                lastFocusTarget = FocusTarget.SERIES
                lastFocusedSeriesPosition = position
                try {
                    val item = peek(position)
                    if (item != null) {
                        lastFocusedSeriesId = item.series_id
                        updateBackdrop(item)
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
                override fun onChanged() { restoreFocus() }
            }
            seriesPagingAdapter.registerAdapterDataObserver(adapterDataObserver!!)
        }
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        initViews(view)
        restoreSavedState(savedInstanceState)
        setupRecyclerViews()
        setupSortChips()
        setupSearch()
        setupDpadNavigation()
        setupLoadStateListener()
        setupAdapterObserver()

        loadEpisodeCounts()
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
            showEmptyState(false)
            showLoadingState(true)
            viewModel.onEvent(SeriesEvent.Retry)
        }
        tvOfflineLabel = view.findViewById(R.id.tv_offline_label)

        // Header / grid controls
        llSortChips = view.findViewById(R.id.ll_sort_chips)
        tvSectionMeta = view.findViewById(R.id.tv_section_meta)
        btnSearch = view.findViewById(R.id.btn_search)
    }

    private fun restoreSavedState(savedInstanceState: Bundle?) {
        savedInstanceState?.let { state ->
            lastFocusedCategoryPosition = state.getInt(STATE_LAST_CATEGORY_POS, RecyclerView.NO_POSITION)
            lastFocusedSeriesPosition = state.getInt(STATE_LAST_SERIES_POS, RecyclerView.NO_POSITION)
            lastFocusedCategoryId = state.getString(STATE_LAST_CATEGORY_ID)
            lastFocusedSeriesId = state.getString(STATE_LAST_SERIES_ID)
            val focusTargetName = state.getString(STATE_LAST_FOCUS_TARGET) ?: FocusTarget.SERIES.name
            lastFocusTarget = runCatching { FocusTarget.valueOf(focusTargetName) }.getOrDefault(FocusTarget.SERIES)

            restoreCategoryPosition = lastFocusedCategoryPosition
            restoreSeriesPosition = lastFocusedSeriesPosition
            restoreCategoryId = lastFocusedCategoryId
            restoreSeriesId = lastFocusedSeriesId
            restoreFocusTarget = lastFocusTarget
            pendingRestoreFocus =
                lastFocusedCategoryPosition != RecyclerView.NO_POSITION ||
                    lastFocusedSeriesPosition != RecyclerView.NO_POSITION
        }
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
                    focusGridTop()
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

    /** Move focus to the search pill (its own view is the focus target). */
    private fun focusSearchBar() {
        val s = btnSearch
        if (s == null) {
            android.util.Log.i("SeriesSearch", "focusSearchBar: btnSearch is null")
            return
        }
        // Make sure the pill itself is the d-pad focus target.
        s.isFocusable = true
        s.isFocusableInTouchMode = true

        var ok = s.requestFocus()
        if (!ok) ok = s.requestFocus(View.FOCUS_UP)
        android.util.Log.i(
            "SeriesSearch",
            "focusSearchBar: isFocusable=${s.isFocusable} isShown=${s.isShown} " +
                "w=${s.width} h=${s.height} inTouchMode=${s.isInTouchMode} requestFocus=$ok"
        )

        if (!ok) {
            // Log the ancestor chain to reveal a focus blocker (descendantFocusability,
            // visibility, or zero size). FOCUS_BLOCK_DESCENDANTS == 0x60000.
            var p: android.view.ViewParent? = s.parent
            while (p is android.view.ViewGroup) {
                android.util.Log.i(
                    "SeriesSearch",
                    "ancestor=${p.javaClass.simpleName} descendantFocusability=${p.descendantFocusability} " +
                        "visibility=${p.visibility} w=${p.width} h=${p.height}"
                )
                p = p.parent
            }
        }

        // Always retry on the next frame — covers both a not-yet-laid-out view AND a
        // focus bounce-back (where the immediate requestFocus succeeds but the list
        // re-claims focus in the same frame).
        s.post {
            if (!isAdded) return@post
            val ok2 = s.requestFocus() || s.requestFocus(View.FOCUS_UP)
            android.util.Log.i(
                "SeriesSearch",
                "focusSearchBar retry requestFocus=$ok2 hasFocus=${s.hasFocus()} isFocused=${s.isFocused}"
            )
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

    private fun setupDpadNavigation() {
        rvSeriesGrid.setOnKeyListener { _, keyCode, event ->
            if (event.action != android.view.KeyEvent.ACTION_DOWN) return@setOnKeyListener false

            val focused = rvSeriesGrid.findFocus() ?: return@setOnKeyListener false
            val holder = rvSeriesGrid.findContainingViewHolder(focused) ?: return@setOnKeyListener false
            val position = holder.bindingAdapterPosition

            when (keyCode) {
                android.view.KeyEvent.KEYCODE_DPAD_UP -> {
                    // First row: route UP to the sort chips instead of trapping focus
                    if (position < gridColumnCount) {
                        focusSortChipsRow()
                        true
                    } else false
                }
                android.view.KeyEvent.KEYCODE_DPAD_DOWN -> {
                    // Placeholders are off, so past the last LOADED row there is no focusable
                    // card below — a plain DOWN then escapes to the sidebar (the "focus jumps
                    // to category on fast scroll" bug). Consume it and nudge a scroll so the
                    // next page loads; focus stays on the current card.
                    val count = seriesPagingAdapter.itemCount
                    if (count > 0 && position + gridColumnCount >= count) {
                        rvSeriesGrid.scrollBy(0, focused.height)
                        true
                    } else false
                }
                android.view.KeyEvent.KEYCODE_DPAD_LEFT -> {
                    if (position % gridColumnCount == 0) {
                        rvCategoriesSidebar.post {
                            rvCategoriesSidebar.post { rvCategoriesSidebar.requestFocus() }
                        }
                        true
                    } else false
                }
                else -> false
            }
        }

        // The RV key listener above only fires when the RV ITSELF holds focus — on TV the
        // focused view is the card (a row child), so DOWN/LEFT/UP must be intercepted on
        // each card. Without this, a fast DOWN past the last laid-out row finds no focusable
        // below and escapes to the sidebar ("focus jumps to category" bug).
        rvSeriesGrid.addOnChildAttachStateChangeListener(
            object : RecyclerView.OnChildAttachStateChangeListener {
                override fun onChildViewAttachedToWindow(view: View) {
                    view.setOnKeyListener { _, keyCode, event ->
                        if (event.action != android.view.KeyEvent.ACTION_DOWN) return@setOnKeyListener false
                        val position = rvSeriesGrid.getChildAdapterPosition(view)
                        if (position == RecyclerView.NO_POSITION) return@setOnKeyListener false
                        val count = seriesPagingAdapter.itemCount
                        when (keyCode) {
                            android.view.KeyEvent.KEYCODE_DPAD_DOWN -> {
                                val belowPos = position + gridColumnCount
                                // No card below (last row) OR the row below isn't laid out yet
                                // (fast scroll) → consume, nudge a scroll, keep focus here.
                                val belowView = rvSeriesGrid.layoutManager
                                    ?.findViewByPosition(belowPos)
                                if (belowPos >= count || belowView == null) {
                                    rvSeriesGrid.scrollBy(0, view.height)
                                    true
                                } else false
                            }
                            android.view.KeyEvent.KEYCODE_DPAD_LEFT -> {
                                if (position % gridColumnCount == 0) {
                                    rvCategoriesSidebar.post {
                                        if (isAdded) rvCategoriesSidebar.requestFocus()
                                    }
                                    true
                                } else false
                            }
                            android.view.KeyEvent.KEYCODE_DPAD_UP -> {
                                if (position < gridColumnCount) {
                                    focusSortChipsRow()
                                    true
                                } else false
                            }
                            else -> false
                        }
                    }
                }

                override fun onChildViewDetachedFromWindow(view: View) {
                    view.setOnKeyListener(null)
                }
            }
        )

        // RIGHT from the sidebar must always enter the content column. The RecyclerView
        // key listener below only fires when the RV itself holds focus (not when a row
        // child does), and a plain nextFocusRight dead-ends when the grid is scrolled
        // off-screen below the hero/CW. So intercept the key on each ROW view (which IS
        // the focused view) and jump to the first on-screen content focusable.
        rvCategoriesSidebar.addOnChildAttachStateChangeListener(
            object : RecyclerView.OnChildAttachStateChangeListener {
                override fun onChildViewAttachedToWindow(view: View) {
                    view.setOnKeyListener { _, keyCode, event ->
                        if (event.action != android.view.KeyEvent.ACTION_DOWN) return@setOnKeyListener false
                        when (keyCode) {
                            android.view.KeyEvent.KEYCODE_DPAD_RIGHT -> {
                                focusContentFromSidebar()
                                true
                            }
                            android.view.KeyEvent.KEYCODE_DPAD_UP -> {
                                // From the TOP category row, UP goes to the search pill.
                                // Consume it either way so an unhandled UP can't escape the
                                // app (which previously bounced to the launcher).
                                val pos = rvCategoriesSidebar.getChildAdapterPosition(view)
                                android.util.Log.i("SeriesSearch", "row UP fired, pos=$pos")
                                if (pos <= 0) {
                                    focusSearchBar()
                                    true
                                } else false
                            }
                            else -> false
                        }
                    }
                }

                override fun onChildViewDetachedFromWindow(view: View) {
                    view.setOnKeyListener(null)
                }
            }
        )

        // Kept as a backup for the (rare) case the RecyclerView itself holds focus.
        rvCategoriesSidebar.setOnKeyListener { _, keyCode, event ->
            if (event.action != android.view.KeyEvent.ACTION_DOWN) return@setOnKeyListener false
            when (keyCode) {
                android.view.KeyEvent.KEYCODE_DPAD_RIGHT -> {
                    focusContentFromSidebar()
                    true
                }
                android.view.KeyEvent.KEYCODE_DPAD_LEFT -> true
                else -> false
            }
        }
    }

    /**
     * Move focus from the sidebar into the content column, never dead-ending.
     * Priority: an on-screen grid card → the sort chips row → the grid.
     */
    private fun focusContentFromSidebar() {
        // 1) An on-screen, laid-out grid card.
        if (rvSeriesGrid.visibility == View.VISIBLE && rvSeriesGrid.childCount > 0) {
            rvSeriesGrid.post {
                if (!isAdded) return@post
                val target = rvSeriesGrid.getChildAt(0)
                if (target != null && target.requestFocus()) return@post
                rvSeriesGrid.requestFocus()
            }
            return
        }
        // 2) Sort chips row (always present in the header; DOWN goes to the grid).
        if (llSortChips.childCount > 0) {
            llSortChips.post {
                if (!isAdded) return@post
                val chip = llSortChips.getChildAt(0)
                if (chip != null && chip.requestFocus()) return@post
                rvSeriesGrid.requestFocus()
            }
            return
        }
        // 3) Last resort.
        rvSeriesGrid.post { if (isAdded) rvSeriesGrid.requestFocus() }
    }

    /** Focus the first sort chip in the merged header row. */
    private fun focusSortChipsRow() {
        val chip = llSortChips.getChildAt(0)
        if (chip != null) chip.post { if (isAdded) chip.requestFocus() } else focusGridTop()
    }

    /** Return focus to the grid from the header — the first on-screen card, else the grid. */
    private fun focusGridTop() {
        rvSeriesGrid.post {
            if (!isAdded) return@post
            val card = rvSeriesGrid.getChildAt(0)
            if (card != null && card.requestFocus()) return@post
            rvSeriesGrid.requestFocus()
        }
    }

    private fun setupLoadStateListener() {
        seriesPagingAdapter.addLoadStateListener { loadState ->
            lastLoadStates = loadState
            updateListLoadingAndEmptyStates(loadState)
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
        val counts = series.series_id?.let { episodeCountsCache[it] }
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
                    lastLoadStates?.let { updateListLoadingAndEmptyStates(it, state.isSwitchingCategory) }
                }

                if (state.error != null) {
                    hasLoadError = true
                    Toast.makeText(context, state.error, Toast.LENGTH_SHORT).show()
                    if (seriesPagingAdapter.itemCount == 0 && !state.isLoadingSeries) showEmptyState(true)
                } else if (seriesPagingAdapter.itemCount > 0) {
                    hasLoadError = false
                }

                state.selectedCategoryId?.let { id ->
                    val title = when (id) {
                        FAVORITES_CATEGORY_ID -> getString(R.string.favorites)
                        SeriesViewModel.ALL_SERIES_CATEGORY_ID -> "All Series"
                        SeriesViewModel.RECENTLY_ADDED_CATEGORY_ID -> "Recently Added"
                        else -> state.categories.find { it.category_id == id }?.category_name ?: "Series"
                    }
                    currentCategoryName = title
                    if (lastFocusTarget == FocusTarget.CATEGORIES) {
                        resetSectionHeader()
                    }
                }

                tvOfflineLabel?.visibility =
                    if (state.error != null) View.VISIBLE else View.GONE

                if (state.isLoadingSeries && seriesPagingAdapter.itemCount == 0) {
                    showEmptyState(false)
                    showLoadingState(true)
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
            lastFocusTarget = FocusTarget.CATEGORIES
            lastFocusedCategoryPosition = position
            if (position in categories.indices) {
                lastFocusedCategoryId = categories[position].category_id
            }
            resetSectionHeader()
        }
        rvCategoriesSidebar.adapter = categorySidebarAdapter

        restoreFocusIfPossible()
    }

    private fun updateListLoadingAndEmptyStates(loadState: CombinedLoadStates, isSwitching: Boolean = false) {
        val itemCount = seriesPagingAdapter.itemCount

        val isPagingRefreshLoading =
            loadState.refresh is LoadState.Loading ||
                loadState.source.refresh is LoadState.Loading ||
                loadState.mediator?.refresh is LoadState.Loading

        val shouldShowLoading = (isSeriesLoadingFromViewModel || isPagingRefreshLoading)
        showLoadingState(shouldShowLoading, itemCount > 0)

        val isListEmpty =
            !isSeriesLoadingFromViewModel &&
                !isSwitching &&
                loadState.refresh is LoadState.NotLoading &&
                loadState.source.refresh is LoadState.NotLoading &&
                (loadState.mediator?.refresh == null || loadState.mediator!!.refresh is LoadState.NotLoading) &&
                itemCount == 0 &&
                !isPagingRefreshLoading
        showEmptyState(isListEmpty)

        val errorState = loadState.source.refresh as? LoadState.Error
            ?: loadState.source.append as? LoadState.Error
            ?: loadState.source.prepend as? LoadState.Error
            ?: loadState.refresh as? LoadState.Error
            ?: loadState.mediator?.refresh as? LoadState.Error

        if (errorState != null) {
            hasLoadError = true
            Toast.makeText(context, getDisplayErrorMessage(errorState.error), Toast.LENGTH_LONG).show()
            if (itemCount == 0) showEmptyState(true)
        } else if (itemCount > 0) {
            hasLoadError = false
        }

        if (!isPagingRefreshLoading && loadState.refresh is LoadState.NotLoading) {
            restoreFocusIfPossible()
            refreshSectionCount()
        }
    }

    /**
     * Section "N TITLES" count. Prefer the true DB total for the selected category
     * (the same number shown in the sidebar) so the two always agree — Paging's
     * itemCount is only the loaded-so-far count, not the catalog total.
     */
    private fun refreshSectionCount() {
        val count = categoryCounts[selectedCategoryId] ?: seriesPagingAdapter.itemCount
        // Don't clobber the focused-series meta line; only refresh the plain count.
        if (lastFocusTarget != FocusTarget.SERIES) {
            tvSectionMeta.text = "$count titles"
        }
    }

    private fun showEmptyState(show: Boolean) {
        llEmptyState?.visibility = if (show) View.VISIBLE else View.GONE
        if (show && seriesPagingAdapter.itemCount == 0) {
            rvSeriesGrid.visibility = View.GONE
        } else {
            rvSeriesGrid.visibility = View.VISIBLE
        }
        if (show) {
            if (hasLoadError) {
                tvEmptyMessage?.text = "Couldn't load series"
                tvEmptyHint?.visibility = View.VISIBLE
                tvEmptyHint?.text = "Check your connection and try again"
                btnRetry?.visibility = View.VISIBLE
                btnRetry?.post { if (isAdded) btnRetry?.requestFocus() }
            } else {
                tvEmptyMessage?.text = "No series available"
                tvEmptyHint?.visibility = View.GONE
                btnRetry?.visibility = View.GONE
            }
        } else {
            btnRetry?.visibility = View.GONE
        }
    }

    private fun showLoadingState(show: Boolean, keepContent: Boolean = false) {
        val container = llLoadingState ?: return
        // Full-screen skeleton ONLY when the grid has nothing to show. When content is
        // already on screen (category switch / background sync), never blank or dim it —
        // the sync finishes silently and Paging swaps the rows in place.
        if (show && !keepContent) {
            container.visibility = View.VISIBLE
            container.alpha = 1.0f
            rvSeriesGrid.alpha = 0f
            rvSeriesGrid.visibility = View.GONE
            startShimmerAnimations(container)
        } else {
            stopShimmerAnimations(container)
            container.visibility = View.GONE
            rvSeriesGrid.visibility = View.VISIBLE
            rvSeriesGrid.alpha = 1.0f
        }
    }

    private var shimmerRunning = false

    private fun startShimmerAnimations(container: ViewGroup) {
        if (shimmerRunning) return
        shimmerRunning = true
        for (i in 0 until container.childCount) {
            val shimmer = container.getChildAt(i)?.findViewById<View>(R.id.v_shimmer) ?: continue
            val anim = TranslateAnimation(
                Animation.RELATIVE_TO_PARENT, -1.0f,
                Animation.RELATIVE_TO_PARENT, 1.0f,
                Animation.RELATIVE_TO_PARENT, 0f,
                Animation.RELATIVE_TO_PARENT, 0f
            ).apply {
                duration = 1200L + (i * 80L)
                repeatCount = Animation.INFINITE
                repeatMode = Animation.RESTART
                interpolator = android.view.animation.LinearInterpolator()
            }
            shimmer.startAnimation(anim)
        }
    }

    private fun stopShimmerAnimations(container: ViewGroup) {
        if (!shimmerRunning) return
        shimmerRunning = false
        for (i in 0 until container.childCount) {
            container.getChildAt(i)?.findViewById<View>(R.id.v_shimmer)?.clearAnimation()
        }
    }

    private fun restoreFocusIfPossible() {
        if (!pendingRestoreFocus) return

        com.tvonnet.debridxtreamiptv.utils.FocusCoordinator.requestFocus("SERIES_RESTORE") {
            when (restoreFocusTarget) {
                FocusTarget.CATEGORIES -> {
                    val count = rvCategoriesSidebar.adapter?.itemCount ?: 0
                    if (count <= 0) return@requestFocus

                    val adapter = rvCategoriesSidebar.adapter as? CategorySidebarAdapter
                    val position = resolveCategoryPosition(adapterCount = count) ?: return@requestFocus

                    rvCategoriesSidebar.post {
                        if (!isAdded) return@post
                        rvCategoriesSidebar.scrollToPosition(position)
                        adapter?.setSelected(position)
                        rvCategoriesSidebar.post {
                            if (!isAdded) return@post
                            try {
                                val focused =
                                    rvCategoriesSidebar.findViewHolderForAdapterPosition(position)
                                        ?.itemView?.requestFocus() == true
                                if (focused) pendingRestoreFocus = false
                            } finally {
                                com.tvonnet.debridxtreamiptv.utils.FocusCoordinator.release("SERIES_RESTORE")
                            }
                        }
                    }
                }

                FocusTarget.SERIES -> {
                    val count = seriesPagingAdapter.itemCount
                    if (count <= 0) return@requestFocus

                    val position = resolveSeriesAdapterPosition()?.coerceIn(0, count - 1) ?: return@requestFocus

                    rvSeriesGrid.post {
                        if (!isAdded) return@post
                        rvSeriesGrid.scrollToPosition(position)
                        rvSeriesGrid.post {
                            if (!isAdded) return@post
                            try {
                                val focused =
                                    rvSeriesGrid.findViewHolderForAdapterPosition(position)
                                        ?.itemView?.requestFocus() == true
                                if (focused) pendingRestoreFocus = false
                            } finally {
                                com.tvonnet.debridxtreamiptv.utils.FocusCoordinator.release("SERIES_RESTORE")
                            }
                        }
                    }
                }
            }
        }
    }

    private fun resolveCategoryPosition(adapterCount: Int): Int? {
        val resolvedPosition = restoreCategoryId
            ?.let { id -> currentCategories.indexOfFirst { it.category_id == id }.takeIf { it >= 0 } }
            ?: restoreCategoryPosition.takeIf { it != RecyclerView.NO_POSITION }
        return resolvedPosition?.coerceIn(0, adapterCount - 1)
    }

    private fun resolveSeriesAdapterPosition(): Int? {
        restoreSeriesId?.let { targetId ->
            val snapshot = seriesPagingAdapter.snapshot()
            val indexInItems = snapshot.items.indexOfFirst { it.series_id == targetId }
            if (indexInItems >= 0) {
                return snapshot.placeholdersBefore + indexInItems
            }
        }
        return restoreSeriesPosition.takeIf { it != RecyclerView.NO_POSITION }
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

    private fun handleFavoriteLongPress(series: XtreamSeriesInfo) {
        val streamId = series.series_id ?: return

        viewLifecycleOwner.lifecycleScope.launch {
            try {
                val repository = viewModel.getRepository()
                val isFavorite = viewModel.isFavorite(streamId)

                if (isFavorite) {
                    repository.removeFavorite(streamId)
                    Toast.makeText(requireContext(), "Removed from favorites", Toast.LENGTH_SHORT).show()
                } else {
                    repository.addFavorite(
                        streamId = streamId,
                        type = "series",
                        name = series.name ?: "Unknown Series",
                        iconUrl = com.tvonnet.debridxtreamiptv.util.GlobalConfig.resolveIconUrl(series.cover)
                    )
                    Toast.makeText(requireContext(), "Added to favorites", Toast.LENGTH_SHORT).show()
                }

                // Refresh adapter to show/hide heart icon
                seriesPagingAdapter.refresh()
            } catch (e: Exception) {
                Toast.makeText(requireContext(), "Error: ${e.message}", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun getDisplayErrorMessage(error: Throwable): String {
        return if (BuildConfig.DEBUG) {
            "Error: ${error.message}"
        } else {
            "Unable to load series. Please try again."
        }
    }

    private val backdropHandler = android.os.Handler(android.os.Looper.getMainLooper())
    private var pendingBackdrop: Runnable? = null

    private fun updateBackdrop(series: XtreamSeriesInfo) {
        // Debounce: swap the backdrop only once focus RESTS (~300ms) — surfing the grid
        // must not repaint the whole background on every D-pad step.
        pendingBackdrop?.let { backdropHandler.removeCallbacks(it) }
        val task = Runnable {
            if (!isAdded) return@Runnable
            val imageUrl = com.tvonnet.debridxtreamiptv.util.GlobalConfig.resolveIconUrl(
                series.backdropPathList.firstOrNull() ?: series.cover
            )
            if (!imageUrl.isNullOrBlank()) {
                Glide.with(this)
                    .load(imageUrl)
                    .transition(DrawableTransitionOptions.withCrossFade(700))
                    .placeholder(android.R.color.transparent)
                    .error(android.R.color.transparent)
                    .into(ivBackgroundBackdrop)
            }
        }
        pendingBackdrop = task
        backdropHandler.postDelayed(task, 300L)
    }

    private fun loadEpisodeCounts() {
        viewLifecycleOwner.lifecycleScope.launch {
            try {
                val counts = viewModel.getEpisodeCounts()
                // B-5: only refresh the cache. The old notifyDataSetChanged() here
                // rebound EVERY visible card (restarting each Glide poster load = a
                // poster-reload flash on view-create and every onResume) purely to
                // update episode counts that the series card does NOT display
                // (SeriesPagingViewHolder.bind never renders `counts`). No rebind needed.
                episodeCountsCache = counts
            } catch (_: Exception) {}
        }
    }

    private fun restoreFocus() {
        // CC-1 (refresh-steals-focus): only re-assert grid focus when the user is ALREADY
        // inside the grid (a paging append / cache refresh shifted positions). If focus is on
        // the sidebar / genre pills / search, a background data change must NOT yank it into
        // the grid. Return-to-fragment focus is handled by restoreFocusIfPossible().
        if (!rvSeriesGrid.hasFocus()) return
        rvSeriesGrid.post {
            val positionToFocus = if (lastFocusedSeriesPosition != RecyclerView.NO_POSITION) lastFocusedSeriesPosition else 0
            val safePosition = minOf(positionToFocus, seriesPagingAdapter.itemCount - 1)

            if (safePosition >= 0) {
                rvSeriesGrid.scrollToPosition(safePosition)
                rvSeriesGrid.post {
                    rvSeriesGrid.findViewHolderForAdapterPosition(safePosition)?.itemView?.requestFocus()
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        restoreFocusIfPossible()
        loadEpisodeCounts()
    }

    override fun onPause() {
        super.onPause()
        com.tvonnet.debridxtreamiptv.utils.FocusMemoryManager.saveFocus(requireView())
        com.tvonnet.debridxtreamiptv.utils.FocusCoordinator.release("SERIES_RESTORE")

        restoreCategoryPosition = lastFocusedCategoryPosition
        restoreSeriesPosition = lastFocusedSeriesPosition
        restoreCategoryId = lastFocusedCategoryId
        restoreSeriesId = lastFocusedSeriesId
        restoreFocusTarget = lastFocusTarget

        pendingRestoreFocus =
            restoreCategoryPosition != RecyclerView.NO_POSITION ||
                restoreSeriesPosition != RecyclerView.NO_POSITION
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putInt(STATE_LAST_CATEGORY_POS, lastFocusedCategoryPosition)
        outState.putInt(STATE_LAST_SERIES_POS, lastFocusedSeriesPosition)
        outState.putString(STATE_LAST_CATEGORY_ID, lastFocusedCategoryId)
        outState.putString(STATE_LAST_SERIES_ID, lastFocusedSeriesId)
        outState.putString(STATE_LAST_FOCUS_TARGET, lastFocusTarget.name)
    }

    override fun onDestroyView() {
        pendingBackdrop?.let { backdropHandler.removeCallbacks(it) }
        pendingBackdrop = null
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
