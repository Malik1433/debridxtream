package com.tvonnet.debridxtreamiptv.ui.series

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
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
    private lateinit var rvCategoriesSidebar: RecyclerView
    private lateinit var rvSeriesGrid: RecyclerView
    private lateinit var tvCategoryTitle: TextView
    private lateinit var ivBackgroundBackdrop: ImageView
    private var llEmptyState: LinearLayout? = null
    private var llLoadingState: LinearLayout? = null
    private var tvEmptyMessage: TextView? = null
    private var tvLoadingMessage: TextView? = null

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
        
        // Initial load is handled by ViewModel init block
    }

    override fun onResume() {
        super.onResume()
        restoreFocusIfPossible()
    }

    override fun onPause() {
        super.onPause()

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
        rvCategoriesSidebar = view.findViewById(R.id.rv_categories_sidebar)
        rvSeriesGrid = view.findViewById(R.id.rv_series_grid)
        tvCategoryTitle = view.findViewById(R.id.tv_category_title)
        ivBackgroundBackdrop = view.findViewById(R.id.iv_background_backdrop)
        llEmptyState = view.findViewById(R.id.ll_empty_state)
        llLoadingState = view.findViewById(R.id.ll_loading_state)
        tvEmptyMessage = view.findViewById(R.id.tv_empty_message)
        tvLoadingMessage = view.findViewById(R.id.tv_loading_message)
    }

    private fun setupRecyclerViews() {
        // Setup Grid (4 columns for cinematic look)
        rvSeriesGrid.layoutManager = GridLayoutManager(requireContext(), 4)
        
        // Add explicit spacing decoration (32dp margins)
        val spacingPx = resources.getDimensionPixelSize(R.dimen.grid_spacing_standard)
        rvSeriesGrid.addItemDecoration(com.tvonnet.debridxtreamiptv.utils.GridSpacingItemDecoration(4, spacingPx, true))
        
        rvSeriesGrid.adapter = seriesPagingAdapter
        // RecyclerViewAnimations.applySpringAnimation(rvSeriesGrid)

        // Setup Sidebar (temporarily empty or managed by observing categories)
        rvCategoriesSidebar.layoutManager = LinearLayoutManager(requireContext())

        // Track focus for restore on back navigation
        rvCategoriesSidebar.addOnChildAttachStateChangeListener(object : RecyclerView.OnChildAttachStateChangeListener {
            override fun onChildViewAttachedToWindow(view: View) {
                view.setOnFocusChangeListener { v, hasFocus ->
                    if (hasFocus) {
                        lastFocusTarget = FocusTarget.CATEGORIES
                        lastFocusedCategoryPosition = rvCategoriesSidebar.getChildAdapterPosition(v)
                        lastFocusedCategoryId = (v.getTag(R.id.tag_category_id) as? String)

                        // TiviMate Sidebar Scale (Subtle 1.05x)
                        v.animate()
                            .scaleX(1.05f)
                            .scaleY(1.05f)
                            .setDuration(120)
                            .start()
                    } else {
                        v.animate()
                            .scaleX(1.0f)
                            .scaleY(1.0f)
                            .setDuration(120)
                            .start()
                    }
                }
            }

            override fun onChildViewDetachedFromWindow(view: View) {
                view.onFocusChangeListener = null
            }
        })

        rvSeriesGrid.addOnChildAttachStateChangeListener(object : RecyclerView.OnChildAttachStateChangeListener {
            override fun onChildViewAttachedToWindow(view: View) {
                view.setOnFocusChangeListener { v, hasFocus ->
                    if (hasFocus) {
                        lastFocusTarget = FocusTarget.SERIES
                        lastFocusedSeriesPosition = rvSeriesGrid.getChildAdapterPosition(v)
                        lastFocusedSeriesId = (v.getTag(R.id.tag_series_id) as? String)

                        // TiviMate Scale Animation (1.1x)
                        v.animate()
                            .scaleX(1.1f)
                            .scaleY(1.1f)
                            .setDuration(120)
                            .setInterpolator(android.view.animation.DecelerateInterpolator())
                            .start()
                        v.z = 10f // Lift up

                        // Update backdrop on focus
                        val position = lastFocusedSeriesPosition
                        if (position != RecyclerView.NO_POSITION) {
                            try {
                                val series = seriesPagingAdapter.peek(position)
                                if (series != null) {
                                    updateBackdrop(series)
                                }
                            } catch (e: Exception) {
                                // Ignore
                            }
                        }
                    } else {
                        // Restore scale on focus loss
                        v.animate()
                            .scaleX(1.0f)
                            .scaleY(1.0f)
                            .setDuration(120)
                            .setInterpolator(android.view.animation.DecelerateInterpolator())
                            .start()
                        v.z = 0f
                    }
                }
            }

            override fun onChildViewDetachedFromWindow(view: View) {
                view.onFocusChangeListener = null
            }
        })
        
        // Listen to load states for empty view handling
        seriesPagingAdapter.addLoadStateListener { loadState ->
            lastLoadStates = loadState
            updateListLoadingAndEmptyStates(loadState)
        }
    }

    private fun setupObservers() {
        // Observe Paged Data
        viewLifecycleOwner.lifecycleScope.launch {
            viewModel.pagedSeries.collectLatest { pagingData ->
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
                if (isSeriesLoadingFromViewModel != state.isLoadingSeries) {
                    isSeriesLoadingFromViewModel = state.isLoadingSeries
                    lastLoadStates?.let { updateListLoadingAndEmptyStates(it) }
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

    private fun updateListLoadingAndEmptyStates(loadState: CombinedLoadStates) {
        val itemCount = seriesPagingAdapter.itemCount

        val isPagingRefreshLoading =
            loadState.refresh is LoadState.Loading ||
                loadState.source.refresh is LoadState.Loading ||
                loadState.mediator?.refresh is LoadState.Loading

        // Show loading overlay while switching categories or initial refresh with no items yet.
        val shouldShowLoading = (isSeriesLoadingFromViewModel || isPagingRefreshLoading) && itemCount == 0
        showLoadingState(shouldShowLoading)

        // Only show empty state once we are sure refresh finished and VM isn't still loading.
        val isListEmpty =
            !isSeriesLoadingFromViewModel &&
                loadState.refresh is LoadState.NotLoading &&
                itemCount == 0 &&
                !isPagingRefreshLoading
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

        when (restoreFocusTarget) {
            FocusTarget.CATEGORIES -> {
                val count = rvCategoriesSidebar.adapter?.itemCount ?: 0
                if (count <= 0) return

                val resolvedPosition = restoreCategoryId
                    ?.let { id -> currentCategories.indexOfFirst { it.category_id == id }.takeIf { it >= 0 } }
                    ?: restoreCategoryPosition.takeIf { it != RecyclerView.NO_POSITION }

                if (resolvedPosition == null) return
                val position = resolvedPosition.coerceIn(0, count - 1)

                rvCategoriesSidebar.post {
                    rvCategoriesSidebar.scrollToPosition(position)
                    (rvCategoriesSidebar.adapter as? CategorySidebarAdapter)?.setSelected(position)
                    rvCategoriesSidebar.post {
                        val focused =
                            rvCategoriesSidebar.findViewHolderForAdapterPosition(position)
                                ?.itemView
                                ?.requestFocus() == true
                        if (focused) {
                            pendingRestoreFocus = false
                        }
                    }
                }
            }

            FocusTarget.SERIES -> {
                val count = seriesPagingAdapter.itemCount
                if (count <= 0) return

                // Prefer restoring by stable series_id; fall back to adapter position.
                val position = resolveSeriesAdapterPosition()
                    ?.coerceIn(0, count - 1)
                    ?: return

                rvSeriesGrid.post {
                    rvSeriesGrid.scrollToPosition(position)
                    rvSeriesGrid.post {
                        val focused =
                            rvSeriesGrid.findViewHolderForAdapterPosition(position)
                                ?.itemView
                                ?.requestFocus() == true
                        if (focused) {
                            pendingRestoreFocus = false
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
        rvSeriesGrid.visibility = if (show) View.GONE else View.VISIBLE
    }

    private fun showLoadingState(show: Boolean) {
        llLoadingState?.visibility = if (show) View.VISIBLE else View.GONE
    }

    private fun onSeriesClick(series: XtreamSeriesInfo, sharedView: View) {
        val fragment = SeriesDetailFragmentV2().apply {
            arguments = Bundle().apply {
                putString("series_id", series.series_id)
                putString("title", series.name)
                putString("backdrop_url", series.cover)
                putString("poster_url", series.cover)
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
                        iconUrl = series.cover
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
        val imageUrl = series.cover
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
