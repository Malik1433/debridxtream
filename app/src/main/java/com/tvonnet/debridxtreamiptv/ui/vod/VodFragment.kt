package com.tvonnet.debridxtreamiptv.ui.vod

import android.content.Intent
import android.os.Bundle
import android.text.SpannableString
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
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
    private var llLoadingState: LinearLayout? = null
    private var llEmptyState: LinearLayout? = null
    private var tvEmptyMessage: TextView? = null
    private var tvLoadingMessage: TextView? = null
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
        )
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
        tvLoadingMessage = view.findViewById(R.id.tv_loading_message)
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
        rvMoviesGrid.layoutManager = GridLayoutManager(context, 4)
        
        // Add explicit spacing decoration (32dp margins)
        val spacingPx = resources.getDimensionPixelSize(R.dimen.grid_spacing_standard)
        rvMoviesGrid.addItemDecoration(com.tvonnet.debridxtreamiptv.utils.GridSpacingItemDecoration(4, spacingPx, true))
        
        rvMoviesGrid.adapter = vodAdapter

        // Track focus for restore on back navigation
        rvCategoriesSidebar.addOnChildAttachStateChangeListener(object : RecyclerView.OnChildAttachStateChangeListener {
            override fun onChildViewAttachedToWindow(view: View) {
                view.setOnFocusChangeListener { v, hasFocus ->
                    if (hasFocus) {
                        lastFocusTarget = FocusTarget.CATEGORIES
                        lastFocusedCategoryPosition = rvCategoriesSidebar.getChildAdapterPosition(v)
                        lastFocusedCategoryId = (v.getTag(R.id.tag_category_id) as? String)
                        
                        // Enhanced Professional Animation: Scale + Slide
                        v.animate()
                            .scaleX(1.05f)
                            .scaleY(1.05f)
                            .translationX(12f) // Slide slightly right
                            .setDuration(150)
                            .setInterpolator(android.view.animation.DecelerateInterpolator())
                            .start()
                            
                        v.elevation = 8f
                    } else {
                        v.animate()
                            .scaleX(1.0f)
                            .scaleY(1.0f)
                            .translationX(0f)
                            .setDuration(150)
                            .setInterpolator(android.view.animation.DecelerateInterpolator())
                            .start()
                            
                        v.elevation = 0f
                    }
                }
            }

            override fun onChildViewDetachedFromWindow(view: View) {
                view.onFocusChangeListener = null
            }
        })

        rvMoviesGrid.addOnChildAttachStateChangeListener(object : RecyclerView.OnChildAttachStateChangeListener {
            override fun onChildViewAttachedToWindow(view: View) {
                view.setOnFocusChangeListener { v, hasFocus ->
                    if (hasFocus) {
                        lastFocusTarget = FocusTarget.MOVIES
                        val position = rvMoviesGrid.getChildAdapterPosition(v)
                        lastFocusedMoviePosition = position
                        lastFocusedMovieId = (v.getTag(R.id.tag_vod_id) as? String)
                        
        
                        // TiviMate Scale Animation (1.1x)
                        v.animate()
                            .scaleX(1.1f)
                            .scaleY(1.1f)
                            .setDuration(150)
                            .setInterpolator(android.view.animation.DecelerateInterpolator())
                            .start()
                        v.z = 15f // Lift up more for glow visibility
                        
                        // Show blue border on focus (handled by selector in XML or manually here if needed)
                        // XML background bg_movie_focus_cyan handles the border

                        // Update background on focus
                        if (position != RecyclerView.NO_POSITION) {
                            try {
                                val movie = vodAdapter.peek(position)
                                if (movie != null) {
                                    updateBackdrop(movie)
                                }
                            } catch (e: Exception) {
                                // Ignore padding issues
                            }
                        }
                    } else {
                        // Restore scale on focus loss
                        v.animate()
                            .scaleX(1.0f)
                            .scaleY(1.0f)
                            .setDuration(150)
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
    }

    override fun onPause() {
        super.onPause()

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
                if (isMoviesLoadingFromViewModel != state.isLoadingMovies) {
                    isMoviesLoadingFromViewModel = state.isLoadingMovies
                    lastLoadStates?.let { updateListLoadingAndEmptyStates(it) }
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
        rvCategoriesSidebar.adapter = categorySidebarAdapter

        restoreFocusIfPossible()
    }
    
    private fun loadCategories() {
        // Triggered by ViewModel init, but can be called manually
        viewModel.onEvent(VodEvent.LoadCategories)
    }
    
    private fun updateListLoadingAndEmptyStates(loadState: CombinedLoadStates) {
        val itemCount = vodAdapter.itemCount

        val isPagingRefreshLoading =
            loadState.refresh is LoadState.Loading ||
                loadState.source.refresh is LoadState.Loading ||
                loadState.mediator?.refresh is LoadState.Loading

        val shouldShowLoading = (isMoviesLoadingFromViewModel || isPagingRefreshLoading) && itemCount == 0
        showLoadingState(shouldShowLoading)

        val isListEmpty =
            !isMoviesLoadingFromViewModel &&
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
            Toast.makeText(context, it.error.message ?: "Failed to load movies", Toast.LENGTH_LONG).show()
        }

        if (!isPagingRefreshLoading && loadState.refresh is LoadState.NotLoading) {
            restoreFocusIfPossible()
        }
    }

    private fun showEmptyState(show: Boolean) {
        llEmptyState?.visibility = if (show) View.VISIBLE else View.GONE
        rvMoviesGrid.visibility = if (show) View.GONE else View.VISIBLE
        if (show) tvEmptyMessage?.text = "No movies in this category"
    }

    private fun showLoadingState(show: Boolean) {
        llLoadingState?.visibility = if (show) View.VISIBLE else View.GONE
    }

    private fun restoreFocusIfPossible() {
        if (!pendingRestoreFocus) return

        when (restoreFocusTarget) {
            FocusTarget.CATEGORIES -> {
                val count = rvCategoriesSidebar.adapter?.itemCount ?: 0
                if (count <= 0) return

                val adapter = rvCategoriesSidebar.adapter as? CategorySidebarAdapter
                val position = resolveCategoryPosition(adapterCount = count) ?: return

                rvCategoriesSidebar.post {
                    rvCategoriesSidebar.scrollToPosition(position)
                    adapter?.setSelected(position)
                    rvCategoriesSidebar.post {
                        val focused =
                            rvCategoriesSidebar.findViewHolderForAdapterPosition(position)
                                ?.itemView
                                ?.requestFocus() == true
                        if (focused) pendingRestoreFocus = false
                    }
                }
            }

            FocusTarget.MOVIES -> {
                val count = vodAdapter.itemCount
                if (count <= 0) return

                val position = resolveMovieAdapterPosition()?.coerceIn(0, count - 1) ?: return

                rvMoviesGrid.post {
                    rvMoviesGrid.scrollToPosition(position)
                    rvMoviesGrid.post {
                        val focused =
                            rvMoviesGrid.findViewHolderForAdapterPosition(position)
                                ?.itemView
                                ?.requestFocus() == true
                        if (focused) pendingRestoreFocus = false
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
}

// VOD Adapter (Week 13: Added favorite indicator support, updated to use item_movie_card)
class VodAdapter(
    private val onMovieClick: (XtreamVodInfo) -> Unit,
    private val favoriteChecker: ((String) -> Boolean)? = null,
    private val onMovieLongClick: ((XtreamVodInfo) -> Unit)? = null
) : androidx.paging.PagingDataAdapter<XtreamVodInfo, VodViewHolder>(VodDiffCallback()) {
    
    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VodViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_movie_card, parent, false)
        return VodViewHolder(view)
    }
    
    override fun onBindViewHolder(holder: VodViewHolder, position: Int) {
        val movie = getItem(position)
        if (movie != null) {
            val isFavorite = movie.stream_id?.let { favoriteChecker?.invoke(it) } ?: false
            holder.bind(movie, isFavorite, onMovieClick, onMovieLongClick)
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
    private val ivMoviePoster = itemView.findViewById<ImageView>(R.id.iv_movie_poster)
    private val ivFavoriteIndicator = itemView.findViewById<ImageView>(R.id.iv_favorite_indicator)

    fun bind(
        movie: XtreamVodInfo,
        isFavorite: Boolean,
        onClick: (XtreamVodInfo) -> Unit,
        onLongClick: ((XtreamVodInfo) -> Unit)? = null
    ) {
        itemView.setTag(R.id.tag_vod_id, movie.stream_id?.toString())
        tvMovieTitle.text = movie.name ?: "Unknown Movie"
        
        // Bind Rating
        val rating = movie.rating?.trim()?.replace(",", ".")?.toFloatOrNull()
        if (rating != null && rating > 0) {
            val starsValue = if (rating > 5f) rating / 2f else rating
            tvMovieRatingValue.text = String.format(Locale.US, "%.1f", starsValue.coerceIn(0f, 5f))
        } else {
            tvMovieRatingValue.text = "N/A"
        }
        
        // Bind Year (Extract from releaseDate or name if needed, assuming valid date string)
        // releaseDate often format: "2024-05-20" or just "2024"
        tvMovieYear.text = movie.releaseDate?.take(4) ?: ""

        // Bind Quality (Placeholder logic, can be real data if available)
        // Check if name contains quality indicators
        val nameLower = movie.name?.lowercase(Locale.ROOT) ?: ""
        val quality = when {
            nameLower.contains("4k") || nameLower.contains("uhd") -> "4K"
            nameLower.contains("1080p") || nameLower.contains("fhd") -> "FHD"
            nameLower.contains("720p") || nameLower.contains("hd") -> "HD"
            else -> "HD" // Default to HD for aesthetics
        }
        tvQualityValue.text = quality
        
        // Show/hide favorite heart icon
        ivFavoriteIndicator?.visibility = if (isFavorite) {
            View.VISIBLE
        } else {
            View.GONE
        }
        
        // Load poster using Glide with 2:3 aspect ratio and 14dp corners (handled by CardView clip)
        val resolved = com.tvonnet.debridxtreamiptv.util.GlobalConfig.resolveIconUrl(movie.stream_icon)
        // Diagnostic Toast

        if (!resolved.isNullOrBlank()) {
            com.tvonnet.debridxtreamiptv.util.GlideUtils.loadMoviePoster(ivMoviePoster, resolved, useRoundedCorners = true)
        } else {
            ivMoviePoster.setImageResource(R.drawable.tv_card_placeholder)
        }
        
        itemView.setOnClickListener {
            onClick(movie)
        }
        
        // Long press to add/remove favorites
        itemView.setOnLongClickListener {
            onLongClick?.invoke(movie)
            true
        }
    }
}

