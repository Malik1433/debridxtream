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

    enum class SortMode { RECENTLY_ADDED, TOP_RATED, A_TO_Z, NEWEST }

    private fun SortMode.toViewModelSortMode(): VodSortMode = when (this) {
        SortMode.RECENTLY_ADDED -> VodSortMode.RECENTLY_ADDED
        SortMode.TOP_RATED -> VodSortMode.TOP_RATED
        SortMode.A_TO_Z -> VodSortMode.A_TO_Z
        SortMode.NEWEST -> VodSortMode.NEWEST
    }

    private companion object {
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
    private var ambientColor: android.view.View? = null
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
    private var lastLoadStates: CombinedLoadStates? = null

    private var currentCategories: List<XtreamCategory> = emptyList()
    private var adapterDataObserver: RecyclerView.AdapterDataObserver? = null

    // Focus memory must outlive the VIEW: opening a detail page is a replace+backstack, so the
    // pop recreates the view and with it a fresh VodFocusController — without this snapshot the
    // armed restore dies with the old instance and BACK lands the user on the search pill.
    private var focusSnapshot: Bundle? = null

    // C2 collaborators. The screen keeps the sidebar/observers/header; these own the three
    // concerns that dominated it: where focus goes, what an empty grid shows, and card overlays.
    private lateinit var focus: VodFocusController
    private lateinit var listState: VodListStateUi
    private lateinit var overlays: VodOverlayController

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
            favoriteChecker = { streamId -> overlays.isFavorite(streamId) },
            watchedChecker = { streamId -> overlays.isWatched(streamId) },
            progressChecker = { streamId -> overlays.progressFor(streamId) },
            onMovieLongClick = { movie -> overlays.handleFavoriteLongPress(movie) }
        ).apply {
            onItemFocused = { position ->
                try {
                    val item = peek(position)
                    focus.onMovieFocused(position, item?.stream_id?.toString())
                    if (item != null) {
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
                override fun onChanged() { focus.reassertGridFocus() }
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
        buildCollaborators()
        (savedInstanceState ?: focusSnapshot)?.let { focus.restoreSavedState(it) }
        setupRecyclerViews()
        setupSortChips()
        setupSearch()
        focus.setupDpadNavigation()
        setupLoadStateListener()
        setupAdapterObserver()

        overlays.observeFavorites()
        overlays.observeWatchedState()

        setupObservers()
    }

    private fun initViews(view: View) {
        llSidebarContainer = view.findViewById(R.id.ll_vod_sidebar_container)
        rvCategoriesSidebar = view.findViewById(R.id.rv_categories_sidebar)
        rvMoviesGrid = view.findViewById(R.id.rv_movies_grid)
        tvCategoryTitle = view.findViewById(R.id.tv_category_title)
        ivBackgroundBackdrop = view.findViewById(R.id.iv_background_backdrop)
        ambientColor = view.findViewById(R.id.ambient_color)
        llLoadingState = view.findViewById(R.id.ll_loading_state)
        llEmptyState = view.findViewById(R.id.ll_empty_state)
        tvEmptyMessage = view.findViewById(R.id.tv_empty_message)
        tvEmptyHint = view.findViewById(R.id.tv_empty_hint)
        btnRetry = view.findViewById(R.id.btn_retry)
        tvOfflineLabel = view.findViewById(R.id.tv_offline_label)
        btnRetry?.setOnClickListener {
            btnRetry?.visibility = View.GONE
            listState.showEmptyState(false)
            listState.showLoadingState(true)
            viewModel.onEvent(VodEvent.Retry)
        }

        // Sort row / header / grid controls
        llSortChips = view.findViewById(R.id.ll_sort_chips)
        tvSectionMeta = view.findViewById(R.id.tv_section_meta)
        btnSearch = view.findViewById(R.id.btn_search)
    }

    private fun buildCollaborators() {
        focus = VodFocusController(
            fragment = this,
            views = VodGridViews(
                sidebar = rvCategoriesSidebar,
                grid = rvMoviesGrid,
                sortChips = llSortChips,
                searchButton = { btnSearch },
            ),
            adapter = { vodAdapter },
            currentCategories = { currentCategories },
            gridColumnCount = { gridColumnCount },
        )
        listState = VodListStateUi(
            fragment = this,
            views = VodListStateViews(
                grid = rvMoviesGrid,
                loading = { llLoadingState },
                empty = { llEmptyState },
                message = { tvEmptyMessage },
                hint = { tvEmptyHint },
                retry = { btnRetry },
            ),
            itemCount = { vodAdapter.itemCount },
            isLoadingFromViewModel = { isMoviesLoadingFromViewModel },
            onRefreshSettled = {
                focus.restoreFocusIfPossible()
                refreshSectionCount()
            },
        )
        overlays = VodOverlayController(
            fragment = this,
            repository = repository,
            watchedStateRepository = watchedStateRepository,
            adapter = { vodAdapter },
        )
    }

    private fun setupRecyclerViews() {
        // M3: a left rail on TV, a top chip rail on the phone. The layout qualifier already
        // moved the list; this reads the same decision so the axis follows it.
        rvCategoriesSidebar.layoutManager = LinearLayoutManager(
            context,
            if (resources.getBoolean(R.bool.browse_categories_are_horizontal)) LinearLayoutManager.HORIZONTAL
            else LinearLayoutManager.VERTICAL,
            false
        )

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
                    focus.focusGridTop()
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

    private fun setupLoadStateListener() {
        vodAdapter.addLoadStateListener { loadState ->
            lastLoadStates = loadState
            listState.updateListLoadingAndEmptyStates(loadState)
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
        if (focus.lastFocusTarget != VodFocusController.FocusTarget.MOVIES) {
            tvSectionMeta.text = "$count titles"
        }
    }

    private fun setupObservers() {
        viewLifecycleOwner.lifecycleScope.launch {
            // Phase 4: gate the pure UI-state collector so it doesn't run (touch views / show
            // toasts) while the fragment is STOPPED; the StateFlow replays the latest value on
            // re-START so nothing is missed.
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.uiState.collect { state -> renderVodState(state) }
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

    // One VodUiState emission, verbatim: sidebar rows + counts, selection, the loading/empty
    // handoff, the error toast, and the section header title.
    private fun renderVodState(state: VodUiState) {
        syncSidebarCategories(state)

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
            lastLoadStates?.let { listState.updateListLoadingAndEmptyStates(it, state.isSwitchingCategory) }
        }

        if (state.error != null) {
            listState.hasLoadError = true
            Toast.makeText(context, state.error, Toast.LENGTH_SHORT).show()
            if (vodAdapter.itemCount == 0 && !state.isLoadingMovies) listState.showEmptyState(true)
        } else if (vodAdapter.itemCount > 0) {
            listState.hasLoadError = false
        }

        updateSectionHeaderTitle(state)

        tvOfflineLabel?.visibility = if (state.error != null) View.VISIBLE else View.GONE

        if (state.isLoadingMovies && vodAdapter.itemCount == 0) {
            listState.showEmptyState(false)
            listState.showLoadingState(true)
        }
    }

    private fun syncSidebarCategories(state: VodUiState) {
        val displayCategories = buildSidebarCategories(state.categories)
        val categoryAdapter = rvCategoriesSidebar.adapter as? CategorySidebarAdapter
        if (categoryAdapter == null && displayCategories.isNotEmpty()) {
            currentCategories = displayCategories
            setupCategorySidebar(displayCategories)
        } else if (categoryAdapter != null && displayCategories != currentCategories) {
            currentCategories = displayCategories
            categoryAdapter.updateCategories(displayCategories, state.selectedCategoryId)
        }
    }

    private fun updateSectionHeaderTitle(state: VodUiState) {
        val id = state.selectedCategoryId ?: return
        val title = when (id) {
            FAVORITES_CATEGORY_ID -> getString(R.string.favorites)
            VodViewModel.ALL_MOVIES_CATEGORY_ID -> "All Movies"
            VodViewModel.RECENTLY_ADDED_VOD_CATEGORY_ID -> "Recently Added"
            else -> state.categories.find { it.category_id == id }?.category_name ?: "Movies"
        }
        currentCategoryName = title
        if (focus.lastFocusTarget == VodFocusController.FocusTarget.CATEGORIES) {
            resetSectionHeader()
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
            focus.onCategoryFocused(position, categories.getOrNull(position)?.category_id)
            resetSectionHeader()
        }
        rvCategoriesSidebar.adapter = categorySidebarAdapter

        focus.restoreFocusIfPossible()
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
                applyAmbientColour(imageUrl)
            }
        }
        pendingBackdrop = task
        backdropHandler.postDelayed(task, 300L)
    }

    /**
     * Wash the page background in the focused poster's own colour.
     *
     * It rides the same debounce as the backdrop, so surfing the grid never repaints it, and it
     * sits UNDER the readability gradient — the point is a hint of the film's palette bleeding into
     * the room, not a tint over the text.
     */
    private fun applyAmbientColour(posterUrl: String) {
        val target = ambientColor ?: return
        com.tvonnet.debridxtreamiptv.util.GlideUtils.loadAmbientArtwork(requireContext(), posterUrl) { art ->
            if (!isAdded) return@loadAmbientArtwork
            val colour = com.tvonnet.debridxtreamiptv.util.ArtworkPalette
                .accentFor(posterUrl, art, fallback = 0x00F0FF)
            target.background = com.tvonnet.debridxtreamiptv.util.AmbientWash.forBackground(colour)
            target.alpha = 0f
            target.animate().alpha(1f).setDuration(700).start()
        }
    }

    override fun onResume() {
        super.onResume()
        focus.restoreFocusIfPossible()
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
