package com.tvonnet.debridxtreamiptv.ui.vod

import android.animation.ValueAnimator
import android.content.Intent
import android.os.Bundle
import android.text.SpannableString
import android.text.Spanned
import android.text.style.ForegroundColorSpan
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
import androidx.paging.CombinedLoadStates
import androidx.paging.LoadState
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.tvonnet.debridxtreamiptv.R
import com.tvonnet.debridxtreamiptv.data.Result
import com.tvonnet.debridxtreamiptv.data.onFailure
import com.tvonnet.debridxtreamiptv.data.onSuccess
import com.tvonnet.debridxtreamiptv.data.model.XtreamCategory
import com.tvonnet.debridxtreamiptv.data.model.XtreamVodInfo
import com.tvonnet.debridxtreamiptv.data.prefs.CredentialsPreferences
import com.tvonnet.debridxtreamiptv.data.repository.XtreamRepository
import com.tvonnet.debridxtreamiptv.utils.RecyclerViewAnimations
import com.tvonnet.debridxtreamiptv.utils.memory.MemoryManager
import com.tvonnet.debridxtreamiptv.util.FAVORITES_CATEGORY_ID
import kotlinx.coroutines.launch
import java.util.Locale

import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

import androidx.fragment.app.viewModels
import kotlinx.coroutines.flow.collectLatest
import com.tvonnet.debridxtreamiptv.features.vodv2.ui.MovieDetailFragmentV2

// Glide imports
import com.bumptech.glide.Glide
import com.bumptech.glide.load.resource.drawable.DrawableTransitionOptions

@AndroidEntryPoint
class VodFragment : Fragment() {

    private enum class FocusTarget { CATEGORIES, MOVIES }

    private companion object {
        const val STATE_LAST_CATEGORY_POS = "vod_last_category_pos"
        const val STATE_LAST_MOVIE_POS = "vod_last_movie_pos"
        const val STATE_LAST_FOCUS_TARGET = "vod_last_focus_target"
        const val STATE_LAST_CATEGORY_ID = "vod_last_category_id"
        const val STATE_LAST_MOVIE_ID = "vod_last_movie_id"
    }
    
    private val viewModel: VodViewModel by viewModels()
    
    @Inject
    lateinit var repository: XtreamRepository

    @Inject
    lateinit var memoryManager: MemoryManager

    @Inject
    lateinit var credentialsPrefs: CredentialsPreferences
    
    private lateinit var rvCategoriesSidebar: RecyclerView
    private lateinit var rvMoviesGrid: RecyclerView
    private lateinit var tvCategoryTitle: TextView
    private lateinit var ivBackgroundBackdrop: ImageView
    private var llLoadingState: ViewGroup? = null
    private var llEmptyState: LinearLayout? = null
    private var tvEmptyMessage: TextView? = null
    private var tvOfflineLabel: TextView? = null
    private var selectedCategoryId: String = ""

    private var isMoviesLoadingFromViewModel: Boolean = false
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
    
    // Week 13: Favorites cache for O(1) lookups (lazy loaded)
    private val favoritesCache by lazy {
        com.tvonnet.debridxtreamiptv.data.cache.FavoritesCache()
    }
    
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
                    }
                } catch (e: Exception) {
                    // Ignore peek errors
                }
            }
            registerAdapterDataObserver(object : RecyclerView.AdapterDataObserver() {
                override fun onChanged() {
                    restoreFocus()
                }
                
                override fun onItemRangeInserted(positionStart: Int, itemCount: Int) {
                    restoreFocus()
                }
                
                override fun onItemRangeRemoved(positionStart: Int, itemCount: Int) {
                    restoreFocus()
                }
            })
        }
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        
        // Initialize repository - handled by Hilt
        val serverUrl = credentialsPrefs.getServerUrl()
        val username = credentialsPrefs.getUsername()
        val password = credentialsPrefs.getPassword()
        if (serverUrl != null && username != null && password != null) {
            repository.ensureInitialized(serverUrl, username, password)
        }
        
        // Initialize views
        rvCategoriesSidebar = view.findViewById(R.id.rv_categories_sidebar)
        rvMoviesGrid = view.findViewById(R.id.rv_movies_grid)
        tvCategoryTitle = view.findViewById(R.id.tv_category_title)
        ivBackgroundBackdrop = view.findViewById(R.id.iv_background_backdrop)
        llLoadingState = view.findViewById(R.id.ll_loading_state)
        llEmptyState = view.findViewById(R.id.ll_empty_state)
        tvEmptyMessage = view.findViewById(R.id.tv_empty_message)
        tvOfflineLabel = view.findViewById(R.id.tv_offline_label)

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
        
        // Setup sidebar layout (vertical)
        rvCategoriesSidebar.layoutManager = LinearLayoutManager(context, LinearLayoutManager.VERTICAL, false)
        
        // Setup movies grid layout (4 columns for the spacious cinema look)
        val gridLayoutManager = GridLayoutManager(context, 4)
        gridLayoutManager.initialPrefetchItemCount = 10
        gridLayoutManager.isItemPrefetchEnabled = true
        rvMoviesGrid.layoutManager = gridLayoutManager

        // Apply RecyclerView focus optimization configuration
        rvCategoriesSidebar.setHasFixedSize(true)
        rvCategoriesSidebar.isFocusable = false
        rvCategoriesSidebar.isFocusableInTouchMode = false
        rvCategoriesSidebar.itemAnimator = null
        rvCategoriesSidebar.descendantFocusability = ViewGroup.FOCUS_AFTER_DESCENDANTS

        rvMoviesGrid.setHasFixedSize(true)
        rvMoviesGrid.isFocusable = false
        rvMoviesGrid.isFocusableInTouchMode = false
        rvMoviesGrid.itemAnimator = null
        rvMoviesGrid.setItemViewCacheSize(20)
        rvMoviesGrid.descendantFocusability = ViewGroup.FOCUS_AFTER_DESCENDANTS
        
        // Week 15 Audit: Standardize Sidebar Focus expansion
        val sidebarContainer = view.findViewById<View>(R.id.ll_vod_sidebar_container)
        val titleArea = view.findViewById<View>(R.id.ll_sidebar_title_area)
        if (sidebarContainer != null) {
            com.tvonnet.debridxtreamiptv.utils.SidebarFocusHelper.attachStandardSidebarAnimation(
                sidebarContainer = sidebarContainer,
                focusTrigger = rvCategoriesSidebar,
                titleArea = titleArea
            )
        }
        
        // Add explicit spacing decoration (32dp margins)
        val spacingPx = resources.getDimensionPixelSize(R.dimen.grid_spacing_standard)
        rvMoviesGrid.addItemDecoration(com.tvonnet.debridxtreamiptv.utils.GridSpacingItemDecoration(4, spacingPx, true))
        
        with(com.tvonnet.debridxtreamiptv.utils.FocusMemoryManager) {
            vodAdapter.applyFocusMemory()
        }
        rvMoviesGrid.adapter = vodAdapter

        // Apply global TV focus rules
        com.tvonnet.debridxtreamiptv.utils.FocusTrapHelper.attachFocusTrap(rvCategoriesSidebar, loopFocus = true)
        com.tvonnet.debridxtreamiptv.utils.SpatialNavigationEngine.enforceStrictOrthogonalNavigation(rvMoviesGrid)

        // Focus tracking logic moved directly to Adapters using callback pattern
        // (Removed complex ViewTreeObserver and attach state layout animators here per user requirement)

        // ── D-pad Navigation Guards ────────────────────────────────────────
        val gridColumns = (rvMoviesGrid.layoutManager as? GridLayoutManager)?.spanCount ?: 4
        rvMoviesGrid.setOnKeyListener { _, keyCode, event ->
            if (event.action != android.view.KeyEvent.ACTION_DOWN) return@setOnKeyListener false

            val focused = rvMoviesGrid.findFocus() ?: return@setOnKeyListener false
            val holder = rvMoviesGrid.findContainingViewHolder(focused) ?: return@setOnKeyListener false
            val position = holder.bindingAdapterPosition

            when (keyCode) {
                android.view.KeyEvent.KEYCODE_DPAD_UP -> {
                    position < gridColumns  // Block UP escape from first row
                }
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

        rvCategoriesSidebar.setOnKeyListener { _, keyCode, event ->
            if (event.action != android.view.KeyEvent.ACTION_DOWN) return@setOnKeyListener false
            when (keyCode) {
                android.view.KeyEvent.KEYCODE_DPAD_RIGHT -> {
                    rvMoviesGrid.post {
                        rvMoviesGrid.post {
                            rvMoviesGrid.requestFocus()
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

        vodAdapter.addLoadStateListener { loadState ->
            lastLoadStates = loadState
            updateListLoadingAndEmptyStates(loadState)
        }
        
        // Week 14: Apply smooth animations
        RecyclerViewAnimations.applyAnimations(rvCategoriesSidebar)
        RecyclerViewAnimations.applyAnimations(rvMoviesGrid)
        
        // Week 13: Load favorites into cache
        loadFavoritesCache()
        
        setupObservers()
    }

    override fun onResume() {
        super.onResume()
        restoreFocusIfPossible()
        com.tvonnet.debridxtreamiptv.utils.FocusMemoryManager.restoreFocus(requireView())
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
    
    private fun setupObservers() {
        // Observe UI State for categories and loading
        viewLifecycleOwner.lifecycleScope.launch {
            viewModel.uiState.collect { state ->
                val displayCategories = buildSidebarCategories(state.categories)
                // Handle Categories
                val categoryAdapter = rvCategoriesSidebar.adapter as? CategorySidebarAdapter
                if (categoryAdapter == null && displayCategories.isNotEmpty()) {
                    currentCategories = displayCategories
                    setupCategorySidebar(displayCategories)
                } else if (categoryAdapter != null && displayCategories != currentCategories) {
                    currentCategories = displayCategories
                    categoryAdapter.updateCategories(displayCategories, state.selectedCategoryId)
                }

                // Keep sidebar selection in sync with selected category
                state.selectedCategoryId?.let { selectedId ->
                    selectedCategoryId = selectedId
                    (rvCategoriesSidebar.adapter as? CategorySidebarAdapter)?.setSelectedById(selectedId)
                }
                
                // Handle Loading
                if (isMoviesLoadingFromViewModel != state.isLoadingMovies || state.isSwitchingCategory) {
                    isMoviesLoadingFromViewModel = state.isLoadingMovies
                    lastLoadStates?.let { updateListLoadingAndEmptyStates(it, state.isSwitchingCategory) }
                }
                
                // Handle Error
                if (state.error != null) {
                    Toast.makeText(context, state.error, Toast.LENGTH_SHORT).show()
                }
                
                // Update Title
                state.selectedCategoryId?.let { id ->
                    val title = if (id == FAVORITES_CATEGORY_ID) {
                        getString(R.string.favorites)
                    } else {
                        state.categories.find { it.category_id == id }?.category_name ?: "Movies"
                    }
                    tvCategoryTitle.text = title
                }

                // Update Offline Label
                tvOfflineLabel?.visibility = if (state.error?.contains("network", true) == true) View.VISIBLE else View.GONE

                // Extra guard: if load states aren't available yet, never show empty while VM says loading.
                if (state.isLoadingMovies && vodAdapter.itemCount == 0) {
                    showEmptyState(false)
                    showLoadingState(true)
                }
            }
        }
        
        // Observe Paged Movies
        viewLifecycleOwner.lifecycleScope.launch {
            viewModel.pagedMovies.collectLatest { pagingData ->
                vodAdapter.submitData(pagingData)
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
    
    private fun setupCategorySidebar(categories: List<XtreamCategory>) {
        val categorySidebarAdapter = CategorySidebarAdapter(categories) { category ->
            selectedCategoryId = category.category_id ?: ""
            viewModel.onEvent(VodEvent.SelectCategory(selectedCategoryId))
        }
        categorySidebarAdapter.onItemFocused = { position ->
            lastFocusTarget = FocusTarget.CATEGORIES
            lastFocusedCategoryPosition = position
            if (position in categories.indices) {
                lastFocusedCategoryId = categories[position].category_id
            }
        }
        rvCategoriesSidebar.adapter = categorySidebarAdapter

        restoreFocusIfPossible()
    }
    
    private fun loadCategories() {
        // Triggered by ViewModel init, but can be called manually
        viewModel.onEvent(VodEvent.LoadCategories)
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

        errorState?.let {
            Toast.makeText(context, it.error.message ?: "Failed to load movies", Toast.LENGTH_LONG).show()
        }

        if (!isPagingRefreshLoading && loadState.refresh is LoadState.NotLoading) {
            restoreFocusIfPossible()
        }
    }

    private fun showEmptyState(show: Boolean) {
        llEmptyState?.visibility = if (show) View.VISIBLE else View.GONE
        // NEVER set movies grid to GONE if we have items (Persistent Grid Pattern)
        if (show && vodAdapter.itemCount == 0) {
            rvMoviesGrid.visibility = View.GONE
        } else {
            rvMoviesGrid.visibility = View.VISIBLE
        }
        if (show) tvEmptyMessage?.text = "No movies in this category"
    }

    /**
     * Toggles the shimmer skeleton grid visibility.
     * Persistent Grid Pattern: If keepContent is true, we show the shimmer as an 
     * overlay (veil) over the existing content.
     */
    private fun showLoadingState(show: Boolean, keepContent: Boolean = false) {
        val container = llLoadingState ?: return
        if (show) {
            container.visibility = View.VISIBLE
            // If keeping content, dim the original movies and show shimmer as a veil
            if (keepContent) {
                container.alpha = 0.5f 
                rvMoviesGrid.alpha = 0.6f
            } else {
                container.alpha = 1.0f
                rvMoviesGrid.alpha = 0f
                rvMoviesGrid.visibility = View.GONE
            }
            startShimmerAnimations(container)
        } else {
            stopShimmerAnimations(container)
            container.visibility = View.GONE
            rvMoviesGrid.visibility = View.VISIBLE
            rvMoviesGrid.alpha = 1.0f
        }
    }

    private fun startShimmerAnimations(container: ViewGroup) {
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
        for (i in 0 until container.childCount) {
            container.getChildAt(i)?.findViewById<View>(R.id.v_shimmer)?.clearAnimation()
        }
    }

    private fun restoreFocusIfPossible() {
        if (!pendingRestoreFocus) return

        // Use FocusCoordinator to prevent race conditions with other components
        com.tvonnet.debridxtreamiptv.utils.FocusCoordinator.requestFocus("VOD_RESTORE") {
            when (restoreFocusTarget) {
                FocusTarget.CATEGORIES -> {
                    val count = rvCategoriesSidebar.adapter?.itemCount ?: 0
                    if (count <= 0) return@requestFocus

                    val adapter = rvCategoriesSidebar.adapter as? CategorySidebarAdapter
                    val position = resolveCategoryPosition(adapterCount = count) ?: return@requestFocus

                    rvCategoriesSidebar.post {
                        rvCategoriesSidebar.scrollToPosition(position)
                        adapter?.setSelected(position)
                        rvCategoriesSidebar.post {
                            try {
                                val focused =
                                    rvCategoriesSidebar.findViewHolderForAdapterPosition(position)
                                        ?.itemView
                                        ?.requestFocus() == true
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
                        rvMoviesGrid.scrollToPosition(position)
                        rvMoviesGrid.post {
                            try {
                                val focused =
                                    rvMoviesGrid.findViewHolderForAdapterPosition(position)
                                        ?.itemView
                                        ?.requestFocus() == true
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
    
    private fun loadMoviesForCategory(categoryId: String) {
        // Legacy method kept for compatibility if needed, but logic moved to ViewModel
        viewModel.onEvent(VodEvent.SelectCategory(categoryId))
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
    
    /**
     * Week 13: Load favorites into cache for fast lookups
     */
    private fun loadFavoritesCache() {
        lifecycleScope.launch {
            try {
                repository.getAllFavorites().collect { favorites ->
                    favoritesCache.updateCache(favorites)
                    android.util.Log.d("VodFragment", "Favorites cache updated: ${favorites.size} items")
                }
            } catch (e: Exception) {
                android.util.Log.e("VodFragment", "Failed to load favorites cache", e)
            }
        }
    }
    
    /**
     * Week 13: Handle long press to add/remove favorites
     */
    private fun handleFavoriteLongPress(movie: XtreamVodInfo) {
        val streamId = movie.stream_id ?: return
        
        lifecycleScope.launch {
            try {
                val isFavorite = favoritesCache.isFavorite(streamId)
                
                if (isFavorite) {
                    // Remove from favorites
                    repository.removeFavorite(streamId)
                    Toast.makeText(requireContext(), "Removed from favorites", Toast.LENGTH_SHORT).show()
                    android.util.Log.d("VodFragment", "Removed from favorites: ${movie.name}")
                } else {
                    // Add to favorites
                    repository.addFavorite(
                        streamId = streamId,
                        type = "vod",
                        name = movie.name ?: "Unknown Movie",
                        iconUrl = com.tvonnet.debridxtreamiptv.util.GlobalConfig.resolveIconUrl(movie.stream_icon)
                    )
                    Toast.makeText(requireContext(), "Added to favorites", Toast.LENGTH_SHORT).show()
                    android.util.Log.d("VodFragment", "Added to favorites: ${movie.name}")
                }
                
                // Reload current category to refresh heart icons
                loadMoviesForCategory(selectedCategoryId)
            } catch (e: Exception) {
                android.util.Log.e("VodFragment", "Error toggling favorite", e)
                Toast.makeText(requireContext(), "Error: ${e.message}", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun updateBackdrop(movie: XtreamVodInfo) {
        val imageUrl = com.tvonnet.debridxtreamiptv.util.GlobalConfig.resolveIconUrl(movie.cover) ?: com.tvonnet.debridxtreamiptv.util.GlobalConfig.resolveIconUrl(movie.stream_icon)
        if (!imageUrl.isNullOrBlank()) {
             Glide.with(this)
                .load(imageUrl)
                .transition(DrawableTransitionOptions.withCrossFade(500))
                .placeholder(android.R.color.transparent) // Or a transparent placeholder
                .error(android.R.color.transparent)
                .into(ivBackgroundBackdrop)
        }
    }

    private fun restoreFocus() {
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
}

// VOD Adapter (Week 13: Added favorite indicator support, updated to use item_movie_card)
class VodAdapter(
    private val onMovieClick: (XtreamVodInfo) -> Unit,
    private val favoriteChecker: ((String) -> Boolean)? = null,
    private val onMovieLongClick: ((XtreamVodInfo) -> Unit)? = null
) : androidx.paging.PagingDataAdapter<XtreamVodInfo, VodViewHolder>(VodDiffCallback()) {

    var onItemFocused: ((position: Int) -> Unit)? = null

    init {
        stateRestorationPolicy = StateRestorationPolicy.PREVENT_WHEN_EMPTY
    }
    
    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VodViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_movie_card, parent, false)
        return VodViewHolder(view)
    }
    
    override fun onBindViewHolder(holder: VodViewHolder, position: Int) {
        val movie = getItem(position)
        if (movie != null) {
            val isFavorite = movie.stream_id?.let { favoriteChecker?.invoke(it) } ?: false
            holder.bind(movie, isFavorite, onItemFocused, onMovieClick, onMovieLongClick)
        }
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
    private val tvMovieRatingValue = itemView.findViewById<TextView>(R.id.tv_movie_rating_value)
    private val tvMovieYear = itemView.findViewById<TextView>(R.id.tv_movie_year)
    private val tvQualityValue = itemView.findViewById<TextView>(R.id.tv_quality_value)
    private val tvLangBadge = itemView.findViewById<TextView>(R.id.tv_lang_badge)
    private val ivMoviePoster = itemView.findViewById<ImageView>(R.id.iv_movie_poster)
    private val ivFavoriteIndicator = itemView.findViewById<ImageView>(R.id.iv_favorite_indicator)
    private val glowFocus = itemView.findViewById<View>(R.id.glow_focus)

    private var pulseAnimator: ValueAnimator? = null
    private val focusScale = 1.15f

    fun bind(
        movie: XtreamVodInfo,
        isFavorite: Boolean,
        onItemFocused: ((Int) -> Unit)?,
        onClick: (XtreamVodInfo) -> Unit,
        onLongClick: ((XtreamVodInfo) -> Unit)? = null
    ) {
        itemView.isFocusable = true
        itemView.isFocusableInTouchMode = true
        itemView.setTag(R.id.tag_vod_id, movie.stream_id?.toString())
        
        var cleanName = movie.name?.trim() ?: "Unknown Movie"
        
        // Extract language tags like | EN | MULTI (FR) [IT]
        val langRegex = Regex("""(?i)[\|\[\(]?\s*(MULTI|EN|FR|IT|ES|DE|RU|TR|AR|NL|PT|PL)\s*[\]\)]?$""")
        val langMatch = langRegex.find(cleanName)
        var langBadge = ""
        if (langMatch != null) {
            langBadge = langMatch.groupValues[1].uppercase(Locale.ROOT)
            cleanName = cleanName.replace(langMatch.value, "").trim()
            if (cleanName.endsWith("|") || cleanName.endsWith("-")) cleanName = cleanName.dropLast(1).trim()
        }
        
        // Also extract quality if at the end
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
            // fallback search if not at the very end
            val nameLower = cleanName.lowercase(Locale.ROOT)
            if (nameLower.contains("4k") || nameLower.contains("uhd")) qualityBadge = "4K"
            else if (nameLower.contains("1080p") || nameLower.contains("fhd")) qualityBadge = "FHD"
        }
        
        tvMovieTitle.text = cleanName
        tvQualityValue.text = qualityBadge
        
        if (langBadge.isNotEmpty() && tvLangBadge != null) {
            tvLangBadge.visibility = View.VISIBLE
            tvLangBadge.text = langBadge
        } else {
            tvLangBadge?.visibility = View.GONE
        }
        
        // Bind Rating
        val rating = movie.rating?.trim()?.replace(",", ".")?.toFloatOrNull()
        if (rating != null && rating > 0) {
            val starsValue = if (rating > 5f) rating / 2f else rating
            tvMovieRatingValue.text = String.format(Locale.US, "%.1f", starsValue.coerceIn(0f, 5f))
        } else {
            tvMovieRatingValue.text = "N/A"
        }
        
        // Bind Year
        tvMovieYear.text = movie.releaseDate?.take(4) ?: ""
        
        // Show/hide favorite heart icon
        ivFavoriteIndicator?.visibility = if (isFavorite) View.VISIBLE else View.GONE
        
        // Load poster
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

        // ── Premium Focus Engine ─────────────────────────────────────
        itemView.setOnFocusChangeListener { v, hasFocus ->
            if (hasFocus) {
                val pos = bindingAdapterPosition
                if (pos != RecyclerView.NO_POSITION) {
                    onItemFocused?.invoke(pos)
                }

                v.animate()
                    .scaleX(focusScale)
                    .scaleY(focusScale)
                    .setInterpolator(OvershootInterpolator(1.2f))
                    .setDuration(250)
                    .start()

                glowFocus?.alpha = 1f
                pulseAnimator?.cancel()
                pulseAnimator = ValueAnimator.ofFloat(0.5f, 1f).apply {
                    duration = 800
                    repeatMode = ValueAnimator.REVERSE
                    repeatCount = ValueAnimator.INFINITE
                    addUpdateListener { animator ->
                        glowFocus?.alpha = animator.animatedValue as Float
                    }
                    start()
                }

                tvMovieTitle.isSelected = true
            } else {
                v.animate()
                    .scaleX(1.0f)
                    .scaleY(1.0f)
                    .setInterpolator(DecelerateInterpolator())
                    .setDuration(200)
                    .start()

                pulseAnimator?.cancel()
                glowFocus?.alpha = 0f
                tvMovieTitle.isSelected = false
            }
        }
    }
}

