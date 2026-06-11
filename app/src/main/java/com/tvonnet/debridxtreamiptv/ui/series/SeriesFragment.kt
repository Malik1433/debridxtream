package com.tvonnet.debridxtreamiptv.ui.series

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.animation.Animation
import android.view.animation.TranslateAnimation
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.ImageView
import android.widget.Toast
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.lifecycle.lifecycleScope
import androidx.paging.CombinedLoadStates
import androidx.paging.LoadState
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.tvonnet.debridxtreamiptv.BuildConfig
import com.tvonnet.debridxtreamiptv.R
import com.tvonnet.debridxtreamiptv.data.model.XtreamCategory
import com.tvonnet.debridxtreamiptv.data.model.XtreamSeriesInfo
import com.tvonnet.debridxtreamiptv.utils.RecyclerViewAnimations
import com.tvonnet.debridxtreamiptv.features.seriesv2.ui.SeriesDetailFragmentV2
import com.tvonnet.debridxtreamiptv.ui.vod.CategorySidebarAdapter
import com.tvonnet.debridxtreamiptv.util.FAVORITES_CATEGORY_ID
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import androidx.core.app.ActivityOptionsCompat
import com.bumptech.glide.Glide
import com.bumptech.glide.load.resource.drawable.DrawableTransitionOptions

@AndroidEntryPoint
class SeriesFragment : Fragment() {

    private enum class FocusTarget { CATEGORIES, SERIES }

    private companion object {
        const val STATE_LAST_CATEGORY_POS = "series_last_category_pos"
        const val STATE_LAST_SERIES_POS = "series_last_series_pos"
        const val STATE_LAST_FOCUS_TARGET = "series_last_focus_target"
        const val STATE_LAST_CATEGORY_ID = "series_last_category_id"
        const val STATE_LAST_SERIES_ID = "series_last_series_id"
    }
    
    private val viewModel: SeriesViewModel by viewModels()
    private lateinit var llSidebarContainer: View
    private lateinit var rvCategoriesSidebar: RecyclerView
    private lateinit var rvSeriesGrid: RecyclerView
    private lateinit var tvCategoryTitle: TextView
    private lateinit var ivBackgroundBackdrop: ImageView
    private var llLoadingState: View? = null
    private var llEmptyState: LinearLayout? = null
    private var tvEmptyMessage: TextView? = null
    private var selectedCategoryId: String? = null

    // Tracks ViewModel-driven loading to prevent "No series" flicker on category switch
    private var isSeriesLoadingFromViewModel: Boolean = false
    private var lastLoadStates: CombinedLoadStates? = null

    private var lastFocusedCategoryPosition: Int = RecyclerView.NO_POSITION
    private var lastFocusedSeriesPosition: Int = RecyclerView.NO_POSITION
    private var lastFocusedCategoryId: String? = null
    private var lastFocusedSeriesId: String? = null
    private var lastFocusTarget: FocusTarget = FocusTarget.SERIES
    private var pendingRestoreFocus: Boolean = false

    private var currentCategories: List<XtreamCategory> = emptyList()

    private var restoreCategoryPosition: Int = RecyclerView.NO_POSITION
    private var restoreSeriesPosition: Int = RecyclerView.NO_POSITION
    private var restoreCategoryId: String? = null
    private var restoreSeriesId: String? = null
    private var restoreFocusTarget: FocusTarget = FocusTarget.SERIES
    private var globalFocusChangeListener: android.view.ViewTreeObserver.OnGlobalFocusChangeListener? = null

    // Paging adapter for series (Week 13: Added favorite indicators)
    private val seriesPagingAdapter by lazy {
        SeriesPagingAdapter(
            onSeriesClick = { series, view ->
                onSeriesClick(series, view)
            },
            favoriteChecker = { streamId ->
                viewModel.isFavorite(streamId)
            },
            onSeriesLongClick = { series ->
                handleFavoriteLongPress(series)
            }
        )
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        // Week 14: Restored missing lifecycle method
        return inflater.inflate(R.layout.fragment_series_vod, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        android.util.Log.d("SeriesFragment", "IPTV_SERIES_SCREEN_ACTIVE: onViewCreated entry")
        
        initViews(view)
        setupRecyclerViews()
        setupObservers()

        savedInstanceState?.let { state ->
            lastFocusedCategoryPosition = state.getInt(STATE_LAST_CATEGORY_POS, RecyclerView.NO_POSITION)
            lastFocusedSeriesPosition = state.getInt(STATE_LAST_SERIES_POS, RecyclerView.NO_POSITION)
            lastFocusedCategoryId = state.getString(STATE_LAST_CATEGORY_ID)
            lastFocusedSeriesId = state.getString(STATE_LAST_SERIES_ID)
            val focusTargetName = state.getString(STATE_LAST_FOCUS_TARGET) ?: FocusTarget.SERIES.name
            lastFocusTarget = runCatching {
                FocusTarget.valueOf(focusTargetName)
            }.getOrDefault(FocusTarget.SERIES)

            restoreCategoryPosition = lastFocusedCategoryPosition
            restoreSeriesPosition = lastFocusedSeriesPosition
            restoreCategoryId = lastFocusedCategoryId
            restoreSeriesId = lastFocusedSeriesId
            restoreFocusTarget = lastFocusTarget
            pendingRestoreFocus =
                lastFocusedCategoryPosition != RecyclerView.NO_POSITION ||
                    lastFocusedSeriesPosition != RecyclerView.NO_POSITION
        }
        
        // Global Focus Engine for Sidebar Expansion (Lumina Style Overlay)
        view.viewTreeObserver.addOnGlobalFocusChangeListener { _, newFocus ->
            if (newFocus == null || !isAdded) return@addOnGlobalFocusChangeListener
            
            val isSidebarFocused = isDescendantOf(newFocus, llSidebarContainer)
            updateSidebarState(isSidebarFocused)
        }
        
        // Initial load is handled by ViewModel init block
    }

    private fun isDescendantOf(view: View, parent: View): Boolean {
        var current: android.view.ViewParent? = view.parent
        while (current != null) {
            if (current === parent) return true
            current = current.parent
        }
        return false
    }

    private fun updateSidebarState(expanded: Boolean) {
        val targetWidth = if (expanded) {
            resources.getDimensionPixelSize(R.dimen.sidebar_width_expanded)
        } else {
            resources.getDimensionPixelSize(R.dimen.sidebar_width_collapsed)
        }
        
        if (llSidebarContainer.width == targetWidth) return
        
        // Update Adapter for name visibility
        (rvCategoriesSidebar.adapter as? CategorySidebarAdapter)?.setExpanded(expanded)
        
        // Animate width without pushing grid (Lumina overlay pattern)
        val params = llSidebarContainer.layoutParams
        val startWidth = llSidebarContainer.width
        
        android.animation.ValueAnimator.ofInt(startWidth, targetWidth).apply {
            duration = 250
            interpolator = android.view.animation.DecelerateInterpolator()
            addUpdateListener { animator ->
                params.width = animator.animatedValue as Int
                llSidebarContainer.layoutParams = params
            }
            start()
        }
        
        // Handle Sidebar title visibility
        view?.findViewById<View>(R.id.ll_sidebar_title_area)?.animate()
            ?.alpha(if (expanded) 1f else 0f)
            ?.setDuration(200)
            ?.start()
    }

    override fun onResume() {
        super.onResume()
        restoreFocusIfPossible()
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

    private fun initViews(view: View) {
        llSidebarContainer = view.findViewById(R.id.ll_series_sidebar_container)
        rvCategoriesSidebar = view.findViewById(R.id.rv_categories_sidebar)
        rvSeriesGrid = view.findViewById(R.id.rv_series_grid)
        tvCategoryTitle = view.findViewById(R.id.tv_category_title)
        ivBackgroundBackdrop = view.findViewById(R.id.iv_background_backdrop)
        llEmptyState = view.findViewById(R.id.ll_empty_state)
        llLoadingState = view.findViewById(R.id.ll_loading_state)
        tvEmptyMessage = view.findViewById(R.id.tv_empty_message)
    }

    private fun setupRecyclerViews() {
        // Setup Grid (4 columns for cinematic look)
        rvSeriesGrid.layoutManager = GridLayoutManager(requireContext(), 4)
        
        // Add explicit spacing decoration (consistent gaps for 4 columns)
        val spacingPx = resources.getDimensionPixelSize(R.dimen.grid_spacing_medium)
        rvSeriesGrid.addItemDecoration(com.tvonnet.debridxtreamiptv.utils.GridSpacingItemDecoration(4, spacingPx, true))
        
        rvSeriesGrid.adapter = seriesPagingAdapter
        
        // Null Animators: Prevent focus jitter during updates
        rvSeriesGrid.itemAnimator = null
        rvCategoriesSidebar.itemAnimator = null

        // Setup Sidebar
        rvCategoriesSidebar.layoutManager = LinearLayoutManager(requireContext())
        rvCategoriesSidebar.setHasFixedSize(true)
        rvCategoriesSidebar.isFocusable = true
        rvCategoriesSidebar.isFocusableInTouchMode = true

        // Apply global TV focus rules
        com.tvonnet.debridxtreamiptv.utils.FocusTrapHelper.attachFocusTrap(rvCategoriesSidebar, loopFocus = true)

        // Track focus for backdrop updates (existing behavior)
        rvSeriesGrid.addOnChildAttachStateChangeListener(object : RecyclerView.OnChildAttachStateChangeListener {
            override fun onChildViewAttachedToWindow(view: View) {
                val previousListener = view.onFocusChangeListener
                view.onFocusChangeListener = View.OnFocusChangeListener { v, hasFocus ->
                    previousListener?.onFocusChange(v, hasFocus)

                    if (hasFocus) {
                        lastFocusTarget = FocusTarget.SERIES
                        lastFocusedSeriesId = (v.getTag(R.id.tag_series_id) as? String)
                        lastFocusedSeriesPosition = rvSeriesGrid.getChildAdapterPosition(v)

                        // Update backdrop on focus
                        val position = lastFocusedSeriesPosition
                        if (position != RecyclerView.NO_POSITION) {
                            try {
                                val series = seriesPagingAdapter.peek(position)
                                if (series != null) {
                                    updateBackdrop(series)
                                }
                            } catch (e: Exception) {}
                        }
                    }
                }
            }

            override fun onChildViewDetachedFromWindow(view: View) {}
        })

        // ── D-pad Navigation Guards ────────────────────────────────────────
        val gridColumns = (rvSeriesGrid.layoutManager as? GridLayoutManager)?.spanCount ?: 4
        rvSeriesGrid.setOnKeyListener { _, keyCode, event ->
            if (event.action != android.view.KeyEvent.ACTION_DOWN) return@setOnKeyListener false

            val focused = rvSeriesGrid.findFocus() ?: return@setOnKeyListener false
            val holder = rvSeriesGrid.findContainingViewHolder(focused) ?: return@setOnKeyListener false
            val position = holder.bindingAdapterPosition

            when (keyCode) {
                // Block UP escape from first row
                android.view.KeyEvent.KEYCODE_DPAD_UP -> {
                    position < gridColumns  // true = consume, false = allow
                }
                // LEFT on first column → move to sidebar
                android.view.KeyEvent.KEYCODE_DPAD_LEFT -> {
                    if (position % gridColumns == 0) {
                        rvCategoriesSidebar.post {
                            rvCategoriesSidebar.post {
                                rvCategoriesSidebar.requestFocus()
                            }
                        }
                        true
                    } else false
                }
                else -> false
            }
        }

        // Sidebar: block LEFT escape, RIGHT → grid
        rvCategoriesSidebar.setOnKeyListener { _, keyCode, event ->
            if (event.action != android.view.KeyEvent.ACTION_DOWN) return@setOnKeyListener false
            when (keyCode) {
                android.view.KeyEvent.KEYCODE_DPAD_RIGHT -> {
                    rvSeriesGrid.post {
                        rvSeriesGrid.post {
                            rvSeriesGrid.requestFocus()
                        }
                    }
                    true
                }
                android.view.KeyEvent.KEYCODE_DPAD_LEFT -> {
                    true  // Block escaping left out of the sidebar
                }
                else -> false
            }
        }
        
        // Listen to load states for empty view handling
        seriesPagingAdapter.addLoadStateListener { loadState ->
            lastLoadStates = loadState
            updateListLoadingAndEmptyStates(loadState)
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        globalFocusChangeListener?.let {
            rvCategoriesSidebar.viewTreeObserver.removeOnGlobalFocusChangeListener(it)
        }
        globalFocusChangeListener = null
        if (::rvSeriesGrid.isInitialized) {
            rvSeriesGrid.adapter = null
        }
        if (::rvCategoriesSidebar.isInitialized) {
            rvCategoriesSidebar.adapter = null
        }
    }

    private fun setupObservers() {
        // Observe Paged Data
        viewLifecycleOwner.lifecycleScope.launch {
            viewModel.pagedSeries.collectLatest { pagingData ->
                android.util.Log.d("SeriesFragment", "New PagingData submitted")
                seriesPagingAdapter.submitData(pagingData)
            }
        }
        
        // Observe Categories
        viewLifecycleOwner.lifecycleScope.launch {
            viewModel.uiState.collectLatest { state ->
                val displayCategories = buildSidebarCategories(state.categories)
                // Update Sidebar
                val categoryAdapter = rvCategoriesSidebar.adapter as? CategorySidebarAdapter
                if (categoryAdapter == null && displayCategories.isNotEmpty()) {
                    currentCategories = displayCategories
                    val adapter = CategorySidebarAdapter(displayCategories) { category ->
                        viewModel.onEvent(SeriesEvent.SelectCategory(category.category_id ?: ""))
                    }
                    rvCategoriesSidebar.adapter = adapter

                    // Restore sidebar focus/scroll if needed
                    restoreFocusIfPossible()
                } else if (categoryAdapter != null && displayCategories != currentCategories) {
                    currentCategories = displayCategories
                    categoryAdapter.updateCategories(displayCategories, state.selectedCategoryId)
                }

                // Keep sidebar selection in sync with selected category
                state.selectedCategoryId?.let { selectedId ->
                    selectedCategoryId = selectedId
                    (rvCategoriesSidebar.adapter as? CategorySidebarAdapter)?.setSelectedById(selectedId)
                }

                // Update title
                val title = if (state.selectedCategoryId == FAVORITES_CATEGORY_ID) {
                    getString(R.string.favorites)
                } else {
                    state.categories.find { it.category_id == state.selectedCategoryId }?.category_name ?: "All Series"
                }
                tvCategoryTitle.text = title
                
                // Show/Hide global loading
                if (state.isLoadingCategories) {
                     showLoadingState(true)
                }

                // Prevent empty flicker while series are being fetched for a category
                if (isSeriesLoadingFromViewModel != state.isLoadingSeries || state.isSwitchingCategory) {
                    isSeriesLoadingFromViewModel = state.isLoadingSeries
                    lastLoadStates?.let { updateListLoadingAndEmptyStates(it, state.isSwitchingCategory) }
                }

                // Extra guard: if load states aren't available yet, never show empty while VM says loading.
                if (state.isLoadingSeries && seriesPagingAdapter.itemCount == 0) {
                    showEmptyState(false)
                    showLoadingState(true)
                }
            }
        }
    }

    private fun buildSidebarCategories(categories: List<XtreamCategory>): List<XtreamCategory> {
        val favorites = XtreamCategory(
            category_id = FAVORITES_CATEGORY_ID,
            category_name = getString(R.string.favorites),
            parent_id = null
        )
        val filtered = categories.filterNot { it.category_id == FAVORITES_CATEGORY_ID }
        return listOf(favorites) + filtered
    }

    private fun updateListLoadingAndEmptyStates(loadState: CombinedLoadStates, isSwitching: Boolean = false) {
        val itemCount = seriesPagingAdapter.itemCount

        val isPagingRefreshLoading =
            loadState.refresh is LoadState.Loading ||
                loadState.source.refresh is LoadState.Loading ||
                loadState.mediator?.refresh is LoadState.Loading

        // Robust loading state: track both VM and Paging status
        val shouldShowLoading = (isSeriesLoadingFromViewModel || isPagingRefreshLoading || isSwitching)
        showLoadingState(shouldShowLoading, itemCount > 0)

        // Only show empty state once we are sure refresh finished and VM isn't still loading/switching.
        val isListEmpty =
            !isSeriesLoadingFromViewModel &&
                !isSwitching && 
                loadState.refresh is LoadState.NotLoading &&
                loadState.source.refresh is LoadState.NotLoading &&
                (loadState.mediator?.refresh == null || loadState.mediator!!.refresh is LoadState.NotLoading) &&
                itemCount == 0 &&
                !isPagingRefreshLoading
        
        android.util.Log.d("SeriesFragment", "EmptyStateCheck: count=$itemCount, vmLoading=$isSeriesLoadingFromViewModel, isSwitching=$isSwitching, pagingLoading=$isPagingRefreshLoading -> showEmpty=$isListEmpty")
        
        // Hardened empty state: only show if we are definitively NOT loading anything
        showEmptyState(isListEmpty)

        val errorState = loadState.source.refresh as? LoadState.Error
            ?: loadState.source.append as? LoadState.Error
            ?: loadState.source.prepend as? LoadState.Error
            ?: loadState.refresh as? LoadState.Error
            ?: loadState.mediator?.refresh as? LoadState.Error

        errorState?.let {
            Toast.makeText(context, getDisplayErrorMessage(it.error), Toast.LENGTH_LONG).show()
        }

        // Try to restore grid focus after paging refresh finishes
        if (!isPagingRefreshLoading && loadState.refresh is LoadState.NotLoading) {
            restoreFocusIfPossible()
        }
    }

    private fun restoreFocusIfPossible() {
        if (!pendingRestoreFocus) return

        // Use FocusCoordinator to prevent race conditions with other components
        com.tvonnet.debridxtreamiptv.utils.FocusCoordinator.requestFocus("SERIES_RESTORE") {
            when (restoreFocusTarget) {
                FocusTarget.CATEGORIES -> {
                    val count = rvCategoriesSidebar.adapter?.itemCount ?: 0
                    if (count <= 0) return@requestFocus

                    val resolvedPosition = restoreCategoryId
                        ?.let { id -> currentCategories.indexOfFirst { it.category_id == id }.takeIf { it >= 0 } }
                        ?: restoreCategoryPosition.takeIf { it != RecyclerView.NO_POSITION }

                    if (resolvedPosition == null) return@requestFocus
                    val position = resolvedPosition.coerceIn(0, count - 1)

                    rvCategoriesSidebar.post {
                        if (!isAdded) return@post
                        rvCategoriesSidebar.scrollToPosition(position)
                        (rvCategoriesSidebar.adapter as? CategorySidebarAdapter)?.setSelected(position)
                        rvCategoriesSidebar.post {
                            if (!isAdded) return@post
                            try {
                                val focused =
                                    rvCategoriesSidebar.findViewHolderForAdapterPosition(position)
                                        ?.itemView
                                        ?.requestFocus() == true
                                if (focused) {
                                    pendingRestoreFocus = false
                                }
                            } finally {
                                com.tvonnet.debridxtreamiptv.utils.FocusCoordinator.release("SERIES_RESTORE")
                            }
                        }
                    }
                }

                FocusTarget.SERIES -> {
                    val count = seriesPagingAdapter.itemCount
                    if (count <= 0) return@requestFocus

                    val position = resolveSeriesAdapterPosition()
                        ?.coerceIn(0, count - 1)
                        ?: return@requestFocus

                    rvSeriesGrid.post {
                        if (!isAdded) return@post
                        rvSeriesGrid.scrollToPosition(position)
                        rvSeriesGrid.post {
                            if (!isAdded) return@post
                            try {
                                val focused =
                                    rvSeriesGrid.findViewHolderForAdapterPosition(position)
                                        ?.itemView
                                        ?.requestFocus() == true
                                if (focused) {
                                    pendingRestoreFocus = false
                                }
                            } finally {
                                com.tvonnet.debridxtreamiptv.utils.FocusCoordinator.release("SERIES_RESTORE")
                            }
                        }
                    }
                }
            }
        }
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

    private fun showEmptyState(show: Boolean) {
        llEmptyState?.visibility = if (show) View.VISIBLE else View.GONE
        // Persistent Grid Pattern: Never hide the grid if it has items
        if (show && seriesPagingAdapter.itemCount == 0) {
            rvSeriesGrid.visibility = View.GONE
        } else {
            rvSeriesGrid.visibility = View.VISIBLE
        }
    }

    /**
     * Toggles the loading overlay visibility.
     * Persistent Grid Pattern: If keepContent is true, we show the loading indicator
     * as an overlay over the existing content.
     */
    private fun showLoadingState(show: Boolean, keepContent: Boolean = false) {
        val container = llLoadingState ?: return
        if (show) {
            container.visibility = View.VISIBLE
            container.alpha = 1.0f
            
            // If keeping content (e.g. background refresh), dim the original series grid
            // Otherwise, hide it completely to avoid "skeleton" card misalignment
            if (keepContent) {
                rvSeriesGrid.alpha = 0.4f
            } else {
                rvSeriesGrid.alpha = 0f
                rvSeriesGrid.visibility = View.GONE
            }
        } else {
            container.visibility = View.GONE
            // Restore grid visibility; showEmptyState will handle hiding it if itemCount is 0
            rvSeriesGrid.visibility = View.VISIBLE
            rvSeriesGrid.alpha = 1.0f
        }
    }

    // Removed startShimmerAnimations and stopShimmerAnimations as they are no longer needed for the stable overlay.

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

        android.util.Log.d(
            "SeriesFragment",
            "Series clicked: ${series.name} (ID: ${series.series_id})"
        )
    }
    
    /**
     * Week 13: Handle long press to add/remove favorites
     */
    private fun handleFavoriteLongPress(series: XtreamSeriesInfo) {
        val streamId = series.series_id ?: return
        
        viewLifecycleOwner.lifecycleScope.launch {
            try {
                val repository = viewModel.getRepository()
                val isFavorite = viewModel.isFavorite(streamId)
                
                if (isFavorite) {
                    // Remove from favorites
                    repository.removeFavorite(streamId)
                    Toast.makeText(requireContext(), "Removed from favorites", Toast.LENGTH_SHORT).show()
                    android.util.Log.d("SeriesFragment", "Removed from favorites: ${series.name}")
                } else {
                    // Add to favorites
                    repository.addFavorite(
                        streamId = streamId,
                        type = "series",
                        name = series.name ?: "Unknown Series",
                        iconUrl = com.tvonnet.debridxtreamiptv.util.GlobalConfig.resolveIconUrl(series.cover)
                    )
                    Toast.makeText(requireContext(), "Added to favorites", Toast.LENGTH_SHORT).show()
                    android.util.Log.d("SeriesFragment", "Added to favorites: ${series.name}")
                }
                
                // Refresh adapter to show/hide heart icon
                seriesPagingAdapter.refresh()
            } catch (e: Exception) {
                android.util.Log.e("SeriesFragment", "Error toggling favorite", e)
                Toast.makeText(requireContext(), "Error: ${e.message}", Toast.LENGTH_SHORT).show()
            }
        }
    }
    
    /**
     * Get user-friendly error message (sanitized for production)
     */
    private fun getDisplayErrorMessage(error: Throwable): String {
        return if (BuildConfig.DEBUG) {
            "Error: ${error.message}"
        } else {
            "Unable to load series. Please try again."
        }
    }

    private fun updateBackdrop(series: XtreamSeriesInfo) {
        val imageUrl = com.tvonnet.debridxtreamiptv.util.GlobalConfig.resolveIconUrl(series.cover)
        if (!imageUrl.isNullOrBlank()) {
            Glide.with(this)
                .load(imageUrl)
                .transition(DrawableTransitionOptions.withCrossFade(500))
                .placeholder(android.R.color.transparent)
                .error(android.R.color.transparent)
                .into(ivBackgroundBackdrop)
        }
    }
}
