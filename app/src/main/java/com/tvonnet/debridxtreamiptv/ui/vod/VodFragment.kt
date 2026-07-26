package com.tvonnet.debridxtreamiptv.ui.vod

import android.animation.ValueAnimator
import android.graphics.Color
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.animation.Animation
import android.view.animation.DecelerateInterpolator
import android.view.animation.OvershootInterpolator
import android.view.animation.TranslateAnimation
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.repeatOnLifecycle
import androidx.paging.CombinedLoadStates
import androidx.paging.LoadState
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.tvonnet.debridxtreamiptv.R
import com.tvonnet.debridxtreamiptv.data.model.XtreamCategory
import com.tvonnet.debridxtreamiptv.data.model.XtreamVodInfo
import com.tvonnet.debridxtreamiptv.data.prefs.CredentialsPreferences
import com.tvonnet.debridxtreamiptv.data.repository.XtreamRepository
import com.tvonnet.debridxtreamiptv.utils.memory.MemoryManager
import com.tvonnet.debridxtreamiptv.util.FAVORITES_CATEGORY_ID
import kotlinx.coroutines.launch

import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

import androidx.fragment.app.viewModels
import kotlinx.coroutines.flow.collectLatest
import com.tvonnet.debridxtreamiptv.features.vodv2.ui.MovieDetailFragmentV2

import com.bumptech.glide.Glide
import com.bumptech.glide.load.resource.drawable.DrawableTransitionOptions

import com.tvonnet.debridxtreamiptv.data.repository.WatchedStateRepository
import com.tvonnet.debridxtreamiptv.data.local.WatchedIdentityBuilder
import java.util.Locale

@AndroidEntryPoint
class VodFragment : Fragment() {

    private enum class FocusTarget { CATEGORIES, MOVIES }

    enum class SortMode { RECENTLY_ADDED, TOP_RATED, A_TO_Z, NEWEST }

    private fun SortMode.toViewModelSortMode(): VodSortMode = when (this) {
        SortMode.RECENTLY_ADDED -> VodSortMode.RECENTLY_ADDED
        SortMode.TOP_RATED -> VodSortMode.TOP_RATED
        SortMode.A_TO_Z -> VodSortMode.A_TO_Z
        SortMode.NEWEST -> VodSortMode.NEWEST
    }

    private companion object {
        const val STATE_LAST_CATEGORY_POS = "vod_last_category_pos"
        const val STATE_LAST_MOVIE_POS = "vod_last_movie_pos"
        const val STATE_LAST_FOCUS_TARGET = "vod_last_focus_target"
        const val STATE_LAST_CATEGORY_ID = "vod_last_category_id"
        const val STATE_LAST_MOVIE_ID = "vod_last_movie_id"
        val SORT_LABELS = listOf("RECENTLY ADDED", "TOP RATED", "A – Z", "NEWEST")
    }

    private val viewModel: VodViewModel by viewModels()

    @Inject lateinit var repository: XtreamRepository
    @Inject lateinit var memoryManager: MemoryManager
    @Inject lateinit var credentialsPrefs: CredentialsPreferences
    @Inject lateinit var watchedStateRepository: WatchedStateRepository

    // Core views
    private lateinit var llSidebarContainer: View
    private lateinit var rvCategoriesSidebar: RecyclerView
    private lateinit var rvMoviesGrid: RecyclerView
    private lateinit var tvCategoryTitle: TextView
    private lateinit var ivBackgroundBackdrop: ImageView
    private var llLoadingState: ViewGroup? = null
    private var llEmptyState: LinearLayout? = null
    private var tvEmptyMessage: TextView? = null
    private var tvEmptyHint: TextView? = null
    private var btnRetry: View? = null
    private var tvOfflineLabel: TextView? = null

    // Sort row / header / grid controls
    private lateinit var llSortChips: LinearLayout
    private lateinit var tvSectionMeta: TextView
    private var btnSearch: View? = null

    // State
    private var selectedCategoryId: String = ""
    private var currentCategoryName: String = ""
    private var activeSortMode: SortMode = SortMode.RECENTLY_ADDED
    private var gridColumnCount: Int = 5

    /** category_id -> synced movie count, for the sidebar count badges. */
    private var categoryCounts: Map<String, Int> = emptyMap()

    private var isMoviesLoadingFromViewModel: Boolean = false
    private var hasLoadError: Boolean = false
    private var lastLoadStates: CombinedLoadStates? = null

    private var lastFocusedCategoryPosition: Int = RecyclerView.NO_POSITION
    private var lastFocusedMoviePosition: Int = RecyclerView.NO_POSITION
    private var lastFocusedCategoryId: String? = null
    private var lastFocusedMovieId: String? = null
    private var lastFocusTarget: FocusTarget = FocusTarget.MOVIES
    private var pendingRestoreFocus: Boolean = false

    private var restoreCategoryPosition: Int = RecyclerView.NO_POSITION
    private var restoreMoviePosition: Int = RecyclerView.NO_POSITION
    private var restoreCategoryId: String? = null
    private var restoreMovieId: String? = null
    private var restoreFocusTarget: FocusTarget = FocusTarget.MOVIES

    private var currentCategories: List<XtreamCategory> = emptyList()
    private var adapterDataObserver: RecyclerView.AdapterDataObserver? = null

    private val favoritesCache by lazy {
        com.tvonnet.debridxtreamiptv.data.cache.FavoritesCache()
    }

    private var watchedMovieKeysCache: Set<String> = emptySet()

    /** streamId -> watch-progress fraction (0..1) for in-progress movies (card strip). */
    private var movieProgressByStreamId: Map<String, Float> = emptyMap()

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        return inflater.inflate(R.layout.fragment_vod, container, false)
    }

    private val vodAdapter by lazy {
        VodAdapter(
            onMovieClick = { movie -> onMovieClick(movie) },
            favoriteChecker = { streamId -> favoritesCache.isFavorite(streamId) },
            watchedChecker = { streamId ->
                val identity = WatchedIdentityBuilder.iptvMovie(streamId)
                watchedMovieKeysCache.contains(identity)
            },
            progressChecker = { streamId -> movieProgressByStreamId[streamId] },
            onMovieLongClick = { movie -> handleFavoriteLongPress(movie) }
        ).apply {
            onItemFocused = { position ->
                lastFocusTarget = FocusTarget.MOVIES
                lastFocusedMoviePosition = position
                try {
                    val item = peek(position)
                    if (item != null) {
                        lastFocusedMovieId = item.stream_id?.toString()
                        updateBackdrop(item)
                        updateSectionHeaderForMovie(item)
                    }
                } catch (_: Exception) {}
            }
        }
    }

    private fun setupAdapterObserver() {
        if (adapterDataObserver == null) {
            adapterDataObserver = object : RecyclerView.AdapterDataObserver() {
                // Only a FULL refresh needs focus re-assertion. Paging appends while
                // scrolling must NOT scrollToPosition — it fought the scroll and could
                // bounce focus onto the sidebar.
                override fun onChanged() { restoreFocus() }
            }
            vodAdapter.registerAdapterDataObserver(adapterDataObserver!!)
        }
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        val serverUrl = credentialsPrefs.getServerUrl()
        val username = credentialsPrefs.getUsername()
        val password = credentialsPrefs.getPassword()
        if (serverUrl != null && username != null && password != null) {
            repository.ensureInitialized(serverUrl, username, password)
        }

        initViews(view)
        restoreSavedState(savedInstanceState)
        setupRecyclerViews()
        setupSortChips()
        setupSearch()
        setupDpadNavigation()
        setupLoadStateListener()
        setupAdapterObserver()

        loadFavoritesCache()
        loadWatchedMovieKeysCache()

        setupObservers()
    }

    private fun initViews(view: View) {
        llSidebarContainer = view.findViewById(R.id.ll_vod_sidebar_container)
        rvCategoriesSidebar = view.findViewById(R.id.rv_categories_sidebar)
        rvMoviesGrid = view.findViewById(R.id.rv_movies_grid)
        tvCategoryTitle = view.findViewById(R.id.tv_category_title)
        ivBackgroundBackdrop = view.findViewById(R.id.iv_background_backdrop)
        llLoadingState = view.findViewById(R.id.ll_loading_state)
        llEmptyState = view.findViewById(R.id.ll_empty_state)
        tvEmptyMessage = view.findViewById(R.id.tv_empty_message)
        tvEmptyHint = view.findViewById(R.id.tv_empty_hint)
        btnRetry = view.findViewById(R.id.btn_retry)
        tvOfflineLabel = view.findViewById(R.id.tv_offline_label)
        btnRetry?.setOnClickListener {
            btnRetry?.visibility = View.GONE
            showEmptyState(false)
            showLoadingState(true)
            viewModel.onEvent(VodEvent.Retry)
        }

        // Sort row / header / grid controls
        llSortChips = view.findViewById(R.id.ll_sort_chips)
        tvSectionMeta = view.findViewById(R.id.tv_section_meta)
        btnSearch = view.findViewById(R.id.btn_search)
    }

    private fun restoreSavedState(savedInstanceState: Bundle?) {
        savedInstanceState?.let { state ->
            lastFocusedCategoryPosition = state.getInt(STATE_LAST_CATEGORY_POS, RecyclerView.NO_POSITION)
            lastFocusedMoviePosition = state.getInt(STATE_LAST_MOVIE_POS, RecyclerView.NO_POSITION)
            lastFocusedCategoryId = state.getString(STATE_LAST_CATEGORY_ID)
            lastFocusedMovieId = state.getString(STATE_LAST_MOVIE_ID)
            val focusTargetName = state.getString(STATE_LAST_FOCUS_TARGET) ?: FocusTarget.MOVIES.name
            lastFocusTarget = runCatching { FocusTarget.valueOf(focusTargetName) }.getOrDefault(FocusTarget.MOVIES)

            restoreCategoryPosition = lastFocusedCategoryPosition
            restoreMoviePosition = lastFocusedMoviePosition
            restoreCategoryId = lastFocusedCategoryId
            restoreMovieId = lastFocusedMovieId
            restoreFocusTarget = lastFocusTarget
            pendingRestoreFocus =
                lastFocusedCategoryPosition != RecyclerView.NO_POSITION ||
                    lastFocusedMoviePosition != RecyclerView.NO_POSITION
        }
    }

    private fun setupRecyclerViews() {
        rvCategoriesSidebar.layoutManager = LinearLayoutManager(context, LinearLayoutManager.VERTICAL, false)

        val gridLayoutManager = GridLayoutManager(context, gridColumnCount)
        gridLayoutManager.initialPrefetchItemCount = 10
        gridLayoutManager.isItemPrefetchEnabled = true
        rvMoviesGrid.layoutManager = gridLayoutManager

        rvCategoriesSidebar.setHasFixedSize(true)
        rvCategoriesSidebar.isFocusable = true
        rvCategoriesSidebar.isFocusableInTouchMode = true
        rvCategoriesSidebar.itemAnimator = null
        rvCategoriesSidebar.descendantFocusability = ViewGroup.FOCUS_AFTER_DESCENDANTS

        rvMoviesGrid.setHasFixedSize(true)
        rvMoviesGrid.isFocusable = true
        rvMoviesGrid.isFocusableInTouchMode = true
        rvMoviesGrid.itemAnimator = null
        rvMoviesGrid.setItemViewCacheSize(20)
        rvMoviesGrid.descendantFocusability = ViewGroup.FOCUS_AFTER_DESCENDANTS

        val spacingPx = resources.getDimensionPixelSize(R.dimen.grid_spacing_medium)
        rvMoviesGrid.addItemDecoration(
            com.tvonnet.debridxtreamiptv.utils.GridSpacingItemDecoration(gridColumnCount, spacingPx, true)
        )

        with(com.tvonnet.debridxtreamiptv.utils.FocusMemoryManager) {
            vodAdapter.applyFocusMemory()
        }
        rvMoviesGrid.adapter = vodAdapter

        com.tvonnet.debridxtreamiptv.utils.FocusTrapHelper.attachFocusTrap(rvCategoriesSidebar, loopFocus = true)
        com.tvonnet.debridxtreamiptv.utils.SpatialNavigationEngine.enforceStrictOrthogonalNavigation(rvMoviesGrid)
        applyAutoSpanCount()
    }

    /**
     * Column count is derived from the grid width so every poster renders at the shared
     * home size (~128dp wide), giving one consistent look across the app instead of a
     * user-selectable 4/5/6 grid.
     */
    private fun applyAutoSpanCount() {
        rvMoviesGrid.post {
            if (!isAdded) return@post
            val w = rvMoviesGrid.width
            if (w <= 0) return@post
            val density = resources.displayMetrics.density
            val spacingPx = resources.getDimensionPixelSize(R.dimen.grid_spacing_medium)
            val targetCell = (resources.getDimensionPixelSize(R.dimen.poster_top_10_width) + spacingPx)
                .coerceAtLeast((100 * density).toInt())
            val span = (w / targetCell).coerceIn(2, 8)
            val lm = rvMoviesGrid.layoutManager as? GridLayoutManager ?: return@post
            if (lm.spanCount != span) {
                lm.spanCount = span
                gridColumnCount = span
                while (rvMoviesGrid.itemDecorationCount > 0) rvMoviesGrid.removeItemDecorationAt(0)
                rvMoviesGrid.addItemDecoration(
                    com.tvonnet.debridxtreamiptv.utils.GridSpacingItemDecoration(span, spacingPx, true)
                )
                rvMoviesGrid.adapter?.notifyItemRangeChanged(0, rvMoviesGrid.adapter?.itemCount ?: 0)
            }
        }
    }

    private fun setupSortChips() {
        llSortChips.removeAllViews()
        val sortModes = SortMode.values()
        sortModes.forEachIndexed { index, mode ->
            val chip = LayoutInflater.from(context)
                .inflate(R.layout.item_vod_sort_chip, llSortChips, false) as TextView
            chip.text = SORT_LABELS[index]
            val isActive = mode == activeSortMode
            updateSortChipStyle(chip, isActive)
            chip.setOnClickListener {
                activeSortMode = mode
                refreshSortChipStyles()
                viewModel.setSortMode(mode.toViewModelSortMode())
            }
            chip.setOnFocusChangeListener { v, hasFocus ->
                if (hasFocus) {
                    (v as TextView).setBackgroundResource(R.drawable.bg_vod_sort_chip_active)
                    (v as TextView).setTextColor(Color.parseColor("#00F0FF"))
                } else {
                    updateSortChipStyle(v as TextView, sortModes[index] == activeSortMode)
                }
            }
            // DOWN from a sort chip steps straight into the grid (the column toggle was
            // removed; posters are a fixed home size now). LEFT/RIGHT traverse the chips.
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
        val sortModes = SortMode.values()
        for (i in 0 until llSortChips.childCount) {
            val chip = llSortChips.getChildAt(i) as? TextView ?: continue
            updateSortChipStyle(chip, sortModes[i] == activeSortMode)
        }
    }

    private fun updateSortChipStyle(chip: TextView, isActive: Boolean) {
        if (isActive) {
            chip.setBackgroundResource(R.drawable.bg_vod_sort_chip_active)
            chip.setTextColor(Color.parseColor("#00F0FF"))
        } else {
            chip.setBackgroundResource(R.drawable.bg_vod_sort_chip_default)
            chip.setTextColor(Color.parseColor("#64748B"))
        }
    }

    private fun setupSearch() {
        btnSearch?.apply {
            isFocusable = true
            isFocusableInTouchMode = true
            setOnClickListener { openSearch() }
            // Visible focus highlight (bg_vod_search_bar has no focus state).
            setOnFocusChangeListener { v, hasFocus ->
                v.setBackgroundResource(
                    if (hasFocus) R.drawable.bg_vod_search_bar_focused
                    else R.drawable.bg_vod_search_bar
                )
            }
        }
    }

    private fun openSearch() {
        if (!isAdded) return
        // Scope search to movies, and to the open real category (virtual "All Movies" /
        // "Recently Added" map to null = search all movies).
        val scopedCategoryId = when (selectedCategoryId) {
            VodViewModel.ALL_MOVIES_CATEGORY_ID,
            VodViewModel.RECENTLY_ADDED_VOD_CATEGORY_ID -> null
            else -> selectedCategoryId
        }
        android.util.Log.i(
            "SearchScope",
            "openSearch(vod) selectedCategoryId=$selectedCategoryId scopedCategoryId=$scopedCategoryId"
        )
        val fragment = com.tvonnet.debridxtreamiptv.ui.search.SearchFragment().apply {
            arguments = Bundle().apply {
                putBoolean("vod_scope", true)
                putString("category_id", scopedCategoryId)
                putString("category_name", currentCategoryName)
            }
        }
        parentFragmentManager.beginTransaction()
            .replace(R.id.content_container, fragment)
            .addToBackStack(null)
            .commit()
    }

    /** Move focus to the search pill (its own view is the focus target). */
    private fun focusSearchBar() {
        val s = btnSearch ?: return
        s.isFocusable = true
        s.isFocusableInTouchMode = true
        if (!s.requestFocus()) s.requestFocus(View.FOCUS_UP)
        // Retry next frame in case layout wasn't settled / focus bounced.
        s.post {
            if (!isAdded) return@post
            s.requestFocus() || s.requestFocus(View.FOCUS_UP)
        }
    }

    /**
     * Move focus from the sidebar into the content column, never dead-ending.
     * Priority: an on-screen grid card → the sort chips row → the grid.
     */
    private fun focusContentFromSidebar() {
        if (rvMoviesGrid.visibility == View.VISIBLE && rvMoviesGrid.childCount > 0) {
            rvMoviesGrid.post {
                if (!isAdded) return@post
                val target = rvMoviesGrid.getChildAt(0)
                if (target != null && target.requestFocus()) return@post
                rvMoviesGrid.requestFocus()
            }
            return
        }
        if (llSortChips.childCount > 0) {
            llSortChips.post {
                if (!isAdded) return@post
                val chip = llSortChips.getChildAt(0)
                if (chip != null && chip.requestFocus()) return@post
                rvMoviesGrid.requestFocus()
            }
            return
        }
        rvMoviesGrid.post { if (isAdded) rvMoviesGrid.requestFocus() }
    }

    // ── Vertical ladder above the grid: sort chips ↔ grid ──────

    private fun focusSortChipsRow() {
        val chip = llSortChips.getChildAt(0)
        if (chip != null) chip.post { if (isAdded) chip.requestFocus() } else focusGridTop()
    }

    /** Return focus to the grid from a control row — the first on-screen card, else the grid itself. */
    private fun focusGridTop() {
        rvMoviesGrid.post {
            if (!isAdded) return@post
            val card = rvMoviesGrid.getChildAt(0)
            if (card != null && card.requestFocus()) return@post
            rvMoviesGrid.requestFocus()
        }
    }

    private fun setupDpadNavigation() {
        rvMoviesGrid.setOnKeyListener { _, keyCode, event ->
            if (event.action != android.view.KeyEvent.ACTION_DOWN) return@setOnKeyListener false

            val focused = rvMoviesGrid.findFocus() ?: return@setOnKeyListener false
            val holder = rvMoviesGrid.findContainingViewHolder(focused) ?: return@setOnKeyListener false
            val position = holder.bindingAdapterPosition

            when (keyCode) {
                android.view.KeyEvent.KEYCODE_DPAD_UP -> {
                    // Top row → the sort chips above the grid (column toggle removed).
                    if (position < gridColumnCount) {
                        focusSortChipsRow()
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

        // RIGHT from any sidebar row enters the content column; UP from the TOP row
        // focuses the search pill. The RecyclerView key listener below only fires when the
        // RV itself holds focus (not a row child), so intercept on each ROW view.
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
                                val pos = rvCategoriesSidebar.getChildAdapterPosition(view)
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

        // Backup for the (rare) case the RecyclerView itself holds focus.
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

    private fun setupLoadStateListener() {
        vodAdapter.addLoadStateListener { loadState ->
            lastLoadStates = loadState
            updateListLoadingAndEmptyStates(loadState)
        }
    }

    private fun updateSectionHeaderForMovie(movie: XtreamVodInfo) {
        tvCategoryTitle.text = com.tvonnet.debridxtreamiptv.util.MediaTitleCleaner
            .clean(movie.name).ifBlank { currentCategoryName }
        val rating = movie.rating?.trim()?.replace(",", ".")?.toFloatOrNull()
        val ratingText = if (rating != null && rating > 0) {
            val stars = if (rating > 5f) rating / 2f else rating
            String.format(Locale.US, "%.1f", stars.coerceIn(0f, 5f))
        } else "N/A"
        val year = movie.releaseDate?.take(4) ?: ""
        val genre = movie.genre?.split(",")?.firstOrNull()?.trim() ?: ""
        tvSectionMeta.text = "★ $ratingText  ·  $year  ·  $genre"
        tvSectionMeta.setTextColor(Color.parseColor("#00F0FF"))
    }

    private fun resetSectionHeader() {
        tvCategoryTitle.text = currentCategoryName
        val count = categoryCounts[selectedCategoryId] ?: vodAdapter.itemCount
        tvSectionMeta.text = "$count titles"
        tvSectionMeta.setTextColor(Color.parseColor("#475569"))
    }

    /**
     * Section "N TITLES" count. Prefer the true DB total for the selected category
     * (same number the sidebar shows) so they agree — Paging's itemCount is only the
     * loaded-so-far count, not the catalog total.
     */
    private fun refreshSectionCount() {
        val count = categoryCounts[selectedCategoryId] ?: vodAdapter.itemCount
        if (lastFocusTarget != FocusTarget.MOVIES) {
            tvSectionMeta.text = "$count titles"
        }
    }

    private fun setupObservers() {
        viewLifecycleOwner.lifecycleScope.launch {
            // Phase 4: gate the pure UI-state collector so it doesn't run (touch views / show
            // toasts) while the fragment is STOPPED; the StateFlow replays the latest value on
            // re-START so nothing is missed.
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
            viewModel.uiState.collect { state ->
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
                    refreshSectionCount()
                }

                state.selectedCategoryId?.let { selectedId ->
                    selectedCategoryId = selectedId
                    (rvCategoriesSidebar.adapter as? CategorySidebarAdapter)?.setSelectedById(selectedId)
                }

                if (isMoviesLoadingFromViewModel != state.isLoadingMovies || state.isSwitchingCategory) {
                    isMoviesLoadingFromViewModel = state.isLoadingMovies
                    lastLoadStates?.let { updateListLoadingAndEmptyStates(it, state.isSwitchingCategory) }
                }

                if (state.error != null) {
                    hasLoadError = true
                    Toast.makeText(context, state.error, Toast.LENGTH_SHORT).show()
                    if (vodAdapter.itemCount == 0 && !state.isLoadingMovies) showEmptyState(true)
                } else if (vodAdapter.itemCount > 0) {
                    hasLoadError = false
                }

                state.selectedCategoryId?.let { id ->
                    val title = when (id) {
                        FAVORITES_CATEGORY_ID -> getString(R.string.favorites)
                        VodViewModel.ALL_MOVIES_CATEGORY_ID -> "All Movies"
                        VodViewModel.RECENTLY_ADDED_VOD_CATEGORY_ID -> "Recently Added"
                        else -> state.categories.find { it.category_id == id }?.category_name ?: "Movies"
                    }
                    currentCategoryName = title
                    if (lastFocusTarget == FocusTarget.CATEGORIES) {
                        resetSectionHeader()
                    }
                }

                tvOfflineLabel?.visibility = if (state.error != null) View.VISIBLE else View.GONE

                if (state.isLoadingMovies && vodAdapter.itemCount == 0) {
                    showEmptyState(false)
                    showLoadingState(true)
                }
            }
            }
        }

        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
            viewModel.pagedMovies.collectLatest { pagingData ->
                vodAdapter.submitData(pagingData)
            }
            }
        }
    }

    private fun buildSidebarCategories(categories: List<XtreamCategory>): List<XtreamCategory> {
        // Build the two virtual rows locally so they ALWAYS render (top two rows), even on
        // the first frame. Order: [All Movies, Recently Added] → Favorites → real.
        val allMovies = XtreamCategory(VodViewModel.ALL_MOVIES_CATEGORY_ID, "All Movies", null)
        val recentlyAdded = XtreamCategory(VodViewModel.RECENTLY_ADDED_VOD_CATEGORY_ID, "Recently Added", null)
        val favorites = XtreamCategory(FAVORITES_CATEGORY_ID, getString(R.string.favorites), null)
        val virtualIds = setOf(
            VodViewModel.ALL_MOVIES_CATEGORY_ID,
            VodViewModel.RECENTLY_ADDED_VOD_CATEGORY_ID
        )
        val real = categories.filterNot {
            it.category_id in virtualIds || it.category_id == FAVORITES_CATEGORY_ID
        }
        return listOf(allMovies, recentlyAdded, favorites) + real
    }

    private fun setupCategorySidebar(categories: List<XtreamCategory>) {
        val categorySidebarAdapter = CategorySidebarAdapter(
            categories,
            accentColor = Color.parseColor("#00F0FF"),
            countProvider = { id -> id?.let { categoryCounts[it] } }
        ) { category ->
            selectedCategoryId = category.category_id ?: ""
            viewModel.onEvent(VodEvent.SelectCategory(selectedCategoryId))
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
        val itemCount = vodAdapter.itemCount

        val isPagingRefreshLoading =
            loadState.refresh is LoadState.Loading ||
                loadState.source.refresh is LoadState.Loading ||
                loadState.mediator?.refresh is LoadState.Loading

        val shouldShowLoading = (isMoviesLoadingFromViewModel || isPagingRefreshLoading)
        showLoadingState(shouldShowLoading, itemCount > 0)

        val isListEmpty =
            !isMoviesLoadingFromViewModel &&
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
            Toast.makeText(context, errorState.error.message ?: "Failed to load movies", Toast.LENGTH_LONG).show()
            // Re-evaluate the empty view so an error with 0 items surfaces Retry.
            if (itemCount == 0) showEmptyState(true)
        } else if (itemCount > 0) {
            hasLoadError = false
        }

        if (!isPagingRefreshLoading && loadState.refresh is LoadState.NotLoading) {
            restoreFocusIfPossible()
            refreshSectionCount()
        }
    }

    private fun showEmptyState(show: Boolean) {
        llEmptyState?.visibility = if (show) View.VISIBLE else View.GONE
        if (show && vodAdapter.itemCount == 0) {
            rvMoviesGrid.visibility = View.GONE
        } else {
            rvMoviesGrid.visibility = View.VISIBLE
        }
        if (show) {
            // Distinguish a load failure (offer Retry) from a genuinely-empty category.
            if (hasLoadError) {
                tvEmptyMessage?.text = "Couldn't load movies"
                tvEmptyHint?.visibility = View.VISIBLE
                tvEmptyHint?.text = "Check your connection and try again"
                btnRetry?.visibility = View.VISIBLE
                btnRetry?.post { if (isAdded) btnRetry?.requestFocus() }
            } else {
                tvEmptyMessage?.text = "No movies in this category"
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
            rvMoviesGrid.alpha = 0f
            rvMoviesGrid.visibility = View.GONE
            startShimmerAnimations(container)
        } else {
            stopShimmerAnimations(container)
            container.visibility = View.GONE
            rvMoviesGrid.visibility = View.VISIBLE
            rvMoviesGrid.alpha = 1.0f
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

        com.tvonnet.debridxtreamiptv.utils.FocusCoordinator.requestFocus("VOD_RESTORE") {
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
                                com.tvonnet.debridxtreamiptv.utils.FocusCoordinator.release("VOD_RESTORE")
                            }
                        }
                    }
                }

                FocusTarget.MOVIES -> {
                    val count = vodAdapter.itemCount
                    if (count <= 0) return@requestFocus

                    val position = resolveMovieAdapterPosition()?.coerceIn(0, count - 1) ?: return@requestFocus

                    rvMoviesGrid.post {
                        if (!isAdded) return@post
                        rvMoviesGrid.scrollToPosition(position)
                        rvMoviesGrid.post {
                            if (!isAdded) return@post
                            try {
                                val focused =
                                    rvMoviesGrid.findViewHolderForAdapterPosition(position)
                                        ?.itemView?.requestFocus() == true
                                if (focused) pendingRestoreFocus = false
                            } finally {
                                com.tvonnet.debridxtreamiptv.utils.FocusCoordinator.release("VOD_RESTORE")
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

    private fun resolveMovieAdapterPosition(): Int? {
        restoreMovieId?.let { targetId ->
            val snapshot = vodAdapter.snapshot()
            val indexInItems = snapshot.items.indexOfFirst { it.stream_id?.toString() == targetId }
            if (indexInItems >= 0) {
                return snapshot.placeholdersBefore + indexInItems
            }
        }
        return restoreMoviePosition.takeIf { it != RecyclerView.NO_POSITION }
    }

    private fun onMovieClick(movie: XtreamVodInfo) {
        val fragment = MovieDetailFragmentV2.newInstance(
            Bundle().apply {
                putString("stream_id", movie.stream_id?.toString())
                putString("title", movie.name)
                putString("backdrop_url", movie.cover ?: movie.stream_icon)
                putString("poster_url", movie.stream_icon)
                putString("plot", movie.plot)
                putString("year", movie.releaseDate)
                putString("genre", movie.genre)
                putString("rating", movie.rating)
                putString("container_ext", movie.container_extension ?: "mp4")
                putString("direct_source", movie.direct_source)
                putString("trailer", movie.youtube_trailer)
            }
        )

        parentFragmentManager.beginTransaction()
            .replace(R.id.content_container, fragment)
            .addToBackStack(null)
            .commit()
    }

    private fun loadFavoritesCache() {
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                try {
                    repository.getAllFavorites().collect { favorites ->
                        favoritesCache.updateCache(favorites)
                        // B-7: repaint only the favorite badge (payload rebind) when the
                        // favorites Flow changes — the toggle no longer re-runs the whole
                        // category pipeline (network fetch + delete/insert + paging invalidate).
                        refreshCardOverlays()
                    }
                } catch (_: Exception) {}
            }
        }
    }

    private fun loadWatchedMovieKeysCache() {
        // B-12: bind to viewLifecycleOwner + repeatOnLifecycle(STARTED) so these
        // table-wide watched-state observers STOP collecting while the grid is
        // backgrounded behind the player — otherwise every progress write (every few
        // seconds during playback) re-ran the query + refreshCardOverlays off-screen.
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                try {
                    watchedStateRepository.observeAllWatchedMovieKeys().collect { keys ->
                        watchedMovieKeysCache = keys.toSet()
                        refreshCardOverlays()
                    }
                } catch (_: Exception) {}
            }
        }
        // In-progress movie positions → card progress strip.
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                try {
                    watchedStateRepository.observeInProgressMovieProgress().collect { rows ->
                        movieProgressByStreamId = rows.mapNotNull { row ->
                            val sid = row.iptvStreamId ?: return@mapNotNull null
                            val frac = if (row.durationMs > 0L) {
                                (row.progressMs.toFloat() / row.durationMs.toFloat()).coerceIn(0.02f, 1f)
                            } else 0.35f
                            sid to frac
                        }.toMap()
                        refreshCardOverlays()
                    }
                } catch (_: Exception) {}
            }
        }
    }

    /**
     * Refresh only the badge/progress overlays on visible cards — a payload rebind that
     * does NOT reload the poster (full notifyDataSetChanged restarted every Glide load,
     * the one scroll-jank source on the grid).
     */
    private fun refreshCardOverlays() {
        if (vodAdapter.snapshot().items.isEmpty()) return
        vodAdapter.notifyItemRangeChanged(0, vodAdapter.itemCount, VodAdapter.PAYLOAD_OVERLAYS)
    }

    private fun handleFavoriteLongPress(movie: XtreamVodInfo) {
        val streamId = movie.stream_id?.toString() ?: return
        val identityKey = WatchedIdentityBuilder.iptvMovie(streamId)
        val isFavorite = favoritesCache.isFavorite(streamId)
        val isWatched = watchedMovieKeysCache.contains(identityKey)

        val favoriteText = if (isFavorite) "Remove from Favorites" else "Add to Favorites"
        val watchedText = if (isWatched) "Mark as Unwatched" else "Mark as Watched"

        androidx.appcompat.app.AlertDialog.Builder(requireContext())
            .setTitle(movie.name)
            .setItems(arrayOf(favoriteText, watchedText)) { _, which ->
                if (which == 0) {
                    lifecycleScope.launch {
                        try {
                            if (isFavorite) {
                                repository.removeFavorite(streamId)
                                Toast.makeText(requireContext(), "Removed from favorites", Toast.LENGTH_SHORT).show()
                            } else {
                                repository.addFavorite(
                                    streamId = streamId,
                                    type = "vod",
                                    name = movie.name ?: "Unknown Movie",
                                    iconUrl = com.tvonnet.debridxtreamiptv.util.GlobalConfig.resolveIconUrl(movie.stream_icon)
                                )
                                Toast.makeText(requireContext(), "Added to favorites", Toast.LENGTH_SHORT).show()
                            }
                            // B-7: no SelectCategory reload — the favorites Flow collector
                            // (loadFavoritesCache) repaints the badge via refreshCardOverlays().
                        } catch (e: Exception) {
                            Toast.makeText(requireContext(), "Error: ${e.message}", Toast.LENGTH_SHORT).show()
                        }
                    }
                } else if (which == 1) {
                    lifecycleScope.launch {
                        try {
                            val historyPrefs = com.tvonnet.debridxtreamiptv.data.prefs.WatchHistoryPreferences(requireContext())
                            val newState = !isWatched
                            val stateEntity = watchedStateRepository.getState(identityKey) ?: com.tvonnet.debridxtreamiptv.data.local.entity.WatchedStateEntity(
                                identityKey = identityKey,
                                contentType = com.tvonnet.debridxtreamiptv.data.local.entity.WatchedStateEntity.CONTENT_TYPE_MOVIE,
                                source = com.tvonnet.debridxtreamiptv.data.local.entity.WatchedStateEntity.SOURCE_XTREAM,
                                iptvStreamId = streamId,
                                title = movie.name,
                                durationMs = 0L,
                                updatedAt = System.currentTimeMillis()
                            )
                            watchedStateRepository.setManualWatched(stateEntity, newState, System.currentTimeMillis())
                            historyPrefs.removeContinueWatchingItem(streamId)
                        } catch (e: Exception) {
                            Toast.makeText(requireContext(), "Error: ${e.message}", Toast.LENGTH_SHORT).show()
                        }
                    }
                }
            }
            .show()
    }

    private val backdropHandler = android.os.Handler(android.os.Looper.getMainLooper())
    private var pendingBackdrop: Runnable? = null

    private fun updateBackdrop(movie: XtreamVodInfo) {
        // Debounce: swap the backdrop only once focus RESTS (~300ms) — surfing the grid
        // must not repaint the whole background on every D-pad step.
        pendingBackdrop?.let { backdropHandler.removeCallbacks(it) }
        val task = Runnable {
            if (!isAdded) return@Runnable
            val imageUrl = com.tvonnet.debridxtreamiptv.util.GlobalConfig.resolveIconUrl(movie.cover)
                ?: com.tvonnet.debridxtreamiptv.util.GlobalConfig.resolveIconUrl(movie.stream_icon)
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

    private fun restoreFocus() {
        // CC-1 (refresh-steals-focus): only re-assert grid focus when the user is ALREADY
        // inside the grid — i.e. a paging append / watched-cache refresh shifted item
        // positions under them. If focus is on the sidebar / sort chips / search, a
        // background data change must NOT yank it into the grid. Return-to-fragment focus is
        // handled separately by restoreFocusIfPossible()/pendingRestoreFocus.
        if (!rvMoviesGrid.hasFocus()) return
        rvMoviesGrid.post {
            val positionToFocus = if (lastFocusedMoviePosition != RecyclerView.NO_POSITION) lastFocusedMoviePosition else 0
            val safePosition = minOf(positionToFocus, vodAdapter.itemCount - 1)

            if (safePosition >= 0) {
                rvMoviesGrid.scrollToPosition(safePosition)
                rvMoviesGrid.post {
                    rvMoviesGrid.findViewHolderForAdapterPosition(safePosition)?.itemView?.requestFocus()
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        restoreFocusIfPossible()
    }

    override fun onPause() {
        super.onPause()
        com.tvonnet.debridxtreamiptv.utils.FocusMemoryManager.saveFocus(requireView())
        com.tvonnet.debridxtreamiptv.utils.FocusCoordinator.release("VOD_RESTORE")

        restoreCategoryPosition = lastFocusedCategoryPosition
        restoreMoviePosition = lastFocusedMoviePosition
        restoreCategoryId = lastFocusedCategoryId
        restoreMovieId = lastFocusedMovieId
        restoreFocusTarget = lastFocusTarget

        pendingRestoreFocus =
            restoreCategoryPosition != RecyclerView.NO_POSITION ||
                restoreMoviePosition != RecyclerView.NO_POSITION
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putInt(STATE_LAST_CATEGORY_POS, lastFocusedCategoryPosition)
        outState.putInt(STATE_LAST_MOVIE_POS, lastFocusedMoviePosition)
        outState.putString(STATE_LAST_CATEGORY_ID, lastFocusedCategoryId)
        outState.putString(STATE_LAST_MOVIE_ID, lastFocusedMovieId)
        outState.putString(STATE_LAST_FOCUS_TARGET, lastFocusTarget.name)
    }

    override fun onDestroyView() {
        pendingBackdrop?.let { backdropHandler.removeCallbacks(it) }
        pendingBackdrop = null
        adapterDataObserver?.let {
            try { vodAdapter.unregisterAdapterDataObserver(it) } catch (_: Exception) {}
        }
        adapterDataObserver = null

        super.onDestroyView()
        if (::rvCategoriesSidebar.isInitialized) rvCategoriesSidebar.adapter = null
        if (::rvMoviesGrid.isInitialized) rvMoviesGrid.adapter = null
        llLoadingState = null
        llEmptyState = null
    }
}

// ═══ VOD ADAPTER ═══

class VodAdapter(
    private val onMovieClick: (XtreamVodInfo) -> Unit,
    private val favoriteChecker: ((String) -> Boolean)? = null,
    private val watchedChecker: ((String) -> Boolean)? = null,
    private val progressChecker: ((String) -> Float?)? = null,
    private val onMovieLongClick: ((XtreamVodInfo) -> Unit)? = null
) : androidx.paging.PagingDataAdapter<XtreamVodInfo, VodViewHolder>(VodDiffCallback()) {

    var onItemFocused: ((position: Int) -> Unit)? = null

    init {
        stateRestorationPolicy = StateRestorationPolicy.PREVENT_WHEN_EMPTY
    }

    companion object {
        /** Payload for a badge/progress-only rebind (skips the poster reload). */
        val PAYLOAD_OVERLAYS = Any()
    }

    private fun overlayState(movie: XtreamVodInfo): Triple<Boolean, Boolean, Float?> {
        val sid = movie.stream_id?.toString()
        val isFavorite = sid?.let { favoriteChecker?.invoke(it) } ?: false
        val isWatched = sid?.let { watchedChecker?.invoke(it) } ?: false
        val progress = sid?.let { progressChecker?.invoke(it) }
        return Triple(isFavorite, isWatched, progress)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VodViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_movie_card, parent, false)
        return VodViewHolder(view)
    }

    override fun onBindViewHolder(holder: VodViewHolder, position: Int) {
        val movie = getItem(position)
        if (movie != null) {
            val (isFavorite, isWatched, progress) = overlayState(movie)
            holder.bind(movie, isFavorite, isWatched, progress, onItemFocused, onMovieClick, onMovieLongClick)
        }
    }

    override fun onBindViewHolder(holder: VodViewHolder, position: Int, payloads: MutableList<Any>) {
        if (payloads.contains(PAYLOAD_OVERLAYS)) {
            val movie = getItem(position) ?: return
            val (isFavorite, isWatched, progress) = overlayState(movie)
            holder.bindOverlays(isFavorite, isWatched, progress) // no poster reload
            return
        }
        super.onBindViewHolder(holder, position, payloads)
    }
}

class VodDiffCallback : androidx.recyclerview.widget.DiffUtil.ItemCallback<XtreamVodInfo>() {
    override fun areItemsTheSame(oldItem: XtreamVodInfo, newItem: XtreamVodInfo): Boolean {
        return oldItem.stream_id == newItem.stream_id
    }
    override fun areContentsTheSame(oldItem: XtreamVodInfo, newItem: XtreamVodInfo): Boolean {
        return oldItem == newItem
    }
}

class VodViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
    private val tvMovieTitle = itemView.findViewById<TextView>(R.id.tv_movie_title)
    private val tvQualityValue = itemView.findViewById<TextView>(R.id.tv_quality_value)
    private val ivMoviePoster = itemView.findViewById<ImageView>(R.id.iv_movie_poster)
    private val ivFavoriteIndicator = itemView.findViewById<ImageView>(R.id.iv_favorite_indicator)
    private val flWatchedBadge = itemView.findViewById<View>(R.id.fl_watched_badge)
    private val flProgressBar = itemView.findViewById<View>(R.id.fl_progress_bar)
    private val progressBarFill = itemView.findViewById<View>(R.id.progress_bar_fill)
    private val glowFocus = itemView.findViewById<View>(R.id.glow_focus)
    private val llQualityBadge = itemView.findViewById<View>(R.id.ll_quality_badge)

    init {
        // Identical to home/top-10 cards: default-corner cyan bloom via duplicateParentState.
        com.tvonnet.debridxtreamiptv.util.FocusGlow.attachSelector(glowFocus)
    }

    /** Badge/progress overlays only — used by the payload rebind (no poster reload). */
    fun bindOverlays(isFavorite: Boolean, isWatched: Boolean, progress: Float?) {
        ivFavoriteIndicator?.visibility = if (isFavorite) View.VISIBLE else View.GONE
        flWatchedBadge?.visibility = if (isWatched) View.VISIBLE else View.GONE
        // Progress strip: only for in-progress (unwatched) movies.
        val frac = progress?.takeIf { it > 0f && !isWatched }
        if (frac != null) {
            flProgressBar?.visibility = View.VISIBLE
            progressBarFill?.post {
                val parent = flProgressBar?.width ?: 0
                progressBarFill.layoutParams = progressBarFill.layoutParams.apply {
                    width = (parent * frac).toInt()
                }
            }
        } else {
            flProgressBar?.visibility = View.GONE
        }
    }

    fun bind(
        movie: XtreamVodInfo,
        isFavorite: Boolean,
        isWatched: Boolean,
        progress: Float?,
        onItemFocused: ((Int) -> Unit)?,
        onClick: (XtreamVodInfo) -> Unit,
        onLongClick: ((XtreamVodInfo) -> Unit)? = null
    ) {
        itemView.isFocusable = true
        itemView.isFocusableInTouchMode = true
        itemView.setTag(R.id.tag_vod_id, movie.stream_id?.toString())

        var cleanName = movie.name?.trim() ?: "Unknown Movie"

        // Extract language tags
        val langRegex = Regex("""(?i)[\|\[\(]?\s*(MULTI|EN|FR|IT|ES|DE|RU|TR|AR|NL|PT|PL)\s*[\]\)]?$""")
        val langMatch = langRegex.find(cleanName)
        if (langMatch != null) {
            cleanName = cleanName.replace(langMatch.value, "").trim()
            if (cleanName.endsWith("|") || cleanName.endsWith("-")) cleanName = cleanName.dropLast(1).trim()
        }

        // Extract quality
        val qualityRegex = Regex("""(?i)[\|\[\(]?\s*(4K|UHD|1080p|720p|FHD|HD)\s*[\]\)]?$""")
        val qMatch = qualityRegex.find(cleanName)
        var qualityBadge = "HD"
        if (qMatch != null) {
            val qExtracted = qMatch.groupValues[1].uppercase(Locale.ROOT)
            qualityBadge = when {
                qExtracted.contains("4K") || qExtracted.contains("UHD") -> "4K"
                qExtracted.contains("1080") || qExtracted.contains("FHD") -> "FHD"
                else -> "HD"
            }
            cleanName = cleanName.replace(qMatch.value, "").trim()
            if (cleanName.endsWith("|") || cleanName.endsWith("-")) cleanName = cleanName.dropLast(1).trim()
        } else {
            val nameLower = cleanName.lowercase(Locale.ROOT)
            if (nameLower.contains("4k") || nameLower.contains("uhd")) qualityBadge = "4K"
            else if (nameLower.contains("1080p") || nameLower.contains("fhd")) qualityBadge = "FHD"
        }

        // Shared cleanup for leading/embedded provider tags + escapes (after quality
        // extraction above, which strips trailing quality tokens and sets the badge).
        cleanName = com.tvonnet.debridxtreamiptv.util.MediaTitleCleaner.clean(cleanName)
            .ifBlank { "Unknown Movie" }
        tvMovieTitle.text = cleanName

        // Quality badge: 4K ONLY — an "HD" pill on every card is pure noise.
        val is4K = qualityBadge == "4K"
        llQualityBadge?.visibility = if (is4K) View.VISIBLE else View.GONE
        if (is4K) {
            tvQualityValue.text = "4K"
            tvQualityValue.setTextColor(Color.parseColor("#FFAA00"))
        }

        // Favorite / watched / progress overlays (shared with the payload rebind).
        bindOverlays(isFavorite, isWatched, progress)

        // Poster
        val resolved = com.tvonnet.debridxtreamiptv.util.GlobalConfig.resolveIconUrl(movie.stream_icon)
        if (!resolved.isNullOrBlank()) {
            com.tvonnet.debridxtreamiptv.util.GlideUtils.loadMoviePoster(ivMoviePoster, resolved, useRoundedCorners = true)
        } else {
            ivMoviePoster.setImageResource(R.drawable.tv_card_placeholder)
        }

        itemView.setOnClickListener { onClick(movie) }
        itemView.setOnLongClickListener {
            onLongClick?.invoke(movie)
            true
        }

        // Focus: identical to home cards — shared cinematic focus util + z-lift.
        // Glow bloom is driven by duplicateParentState + FocusGlow selector.
        itemView.setOnFocusChangeListener { v, hasFocus ->
            com.tvonnet.debridxtreamiptv.util.FocusEffects.applyCinematicFocus(v, hasFocus, scale = 1.06f)
            if (hasFocus) {
                v.z = 20f
                val pos = bindingAdapterPosition
                if (pos != RecyclerView.NO_POSITION) {
                    onItemFocused?.invoke(pos)
                }
                tvMovieTitle.isSelected = true
            } else {
                v.z = 0f
                tvMovieTitle.isSelected = false
            }
        }
    }
}

