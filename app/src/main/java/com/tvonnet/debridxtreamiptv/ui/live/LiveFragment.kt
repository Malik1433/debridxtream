package com.tvonnet.debridxtreamiptv.ui.live

import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.KeyEvent
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.fragment.app.Fragment
import androidx.fragment.app.commit
import androidx.fragment.app.viewModels
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.core.view.isVisible
import androidx.core.app.ActivityOptionsCompat
import androidx.paging.CombinedLoadStates
import androidx.paging.LoadState
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import androidx.recyclerview.widget.SimpleItemAnimator
import androidx.media3.common.MediaItem
import androidx.media3.exoplayer.ExoPlayer
import okhttp3.OkHttpClient
import com.tvonnet.debridxtreamiptv.BuildConfig
import com.tvonnet.debridxtreamiptv.R
import com.tvonnet.debridxtreamiptv.data.local.entity.EpgEntity
import com.tvonnet.debridxtreamiptv.data.model.ContentType
import com.tvonnet.debridxtreamiptv.data.model.XtreamCategory
import com.tvonnet.debridxtreamiptv.data.model.XtreamStream
import com.tvonnet.debridxtreamiptv.data.prefs.CredentialsPreferences
import com.tvonnet.debridxtreamiptv.data.prefs.WatchHistoryPreferences
import com.tvonnet.debridxtreamiptv.data.repository.XtreamRepository
import com.tvonnet.debridxtreamiptv.player.PlayerActivity
import com.tvonnet.debridxtreamiptv.ui.favorites.FavoritesFragment
import com.tvonnet.debridxtreamiptv.ui.search.SearchFragment
import com.tvonnet.debridxtreamiptv.ui.settings.SettingsFragmentNew
import com.tvonnet.debridxtreamiptv.util.EpgCache
import com.tvonnet.debridxtreamiptv.util.FAVORITES_CATEGORY_ID
import com.tvonnet.debridxtreamiptv.util.GlideUtils
import com.tvonnet.debridxtreamiptv.utils.RecyclerViewAnimations
import com.tvonnet.debridxtreamiptv.utils.memory.MemoryManager
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.collectLatest
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import androidx.lifecycle.Lifecycle
import kotlinx.coroutines.CancellationException
import kotlin.coroutines.cancellation.CancellationException as KtCancellationException
import kotlinx.coroutines.Dispatchers
import com.bumptech.glide.Glide

/**
 * LiveFragment - TV Live Channels Screen
 * Using MVVM pattern with Hilt DI
 * 
 * NEW DESIGN TOGGLE: Set USE_NEW_DESIGN to false to revert to old design
 */
@AndroidEntryPoint
class LiveFragment : Fragment() {

    companion object {
        // Enforced 3-column layout
    }

    @Inject
    lateinit var repository: XtreamRepository

    @Inject
    lateinit var memoryManager: MemoryManager

    @Inject
    lateinit var okHttpClient: OkHttpClient

    private val viewModel: LiveViewModel by viewModels()
    private lateinit var rvCategories: RecyclerView
    private lateinit var rvChannels: RecyclerView
    private var tvEmptyState: android.widget.TextView? = null
    private var llLoadingState: android.view.ViewGroup? = null
    private var tvLoadingMessage: android.widget.TextView? = null

    // Preview player component
    private var previewPlayerPanel: PreviewPlayerPanel? = null
    
    private val previewTimeFormatter = SimpleDateFormat("HH:mm", Locale.getDefault())
    private val topBarTimeFormatter = SimpleDateFormat("HH:mm", Locale.getDefault())
    private val topBarDateFormatter = SimpleDateFormat("EEE, MMM d", Locale.getDefault())
    private var tvHeaderCategory: TextView? = null
    private var tvHeaderChannelCount: TextView? = null
    private var etCategorySearch: EditText? = null
    private var tvTopBarTime: TextView? = null
    private var tvTopBarWeather: TextView? = null
    private var clockJob: Job? = null
    private var sidebarCategoryAdapter: SidebarCategoryAdapter? = null
    private var didRestoreFocusForThisView = false
    private var didRestorePreviewForThisView = false
    private var categoriesFocusBlockedForRestore = false
    private var lastChannelLoadStates: CombinedLoadStates? = null

    // Fragment scope coroutines - managed properly
    private var uiStateJob: Job? = null
    private var pagedChannelsJob: Job? = null
    private var loadStateJob: Job? = null
    private var navigationJob: Job? = null
    private var epgPrimeJob: Job? = null
    private var favoritesJob: Job? = null
    private var epgUpdatesJob: Job? = null
    private var previewEpgRetryJob: Job? = null

    private var currentPreviewEpgKey: String? = null
    private var currentPreviewStreamId: String? = null

    private val favoriteStreamIds = ConcurrentHashMap.newKeySet<String>()
    private var favoritesCount: Int = 0
    private var categorySearchQuery: String = ""
    private var displayCategories: List<XtreamCategory> = emptyList()
    private var lastUiState: LiveUiState? = null

    // Paging adapter for channels
    private val channelPagingAdapter by lazy {
        ChannelPagingAdapter(
            onChannelClick = { stream ->
                viewModel.onEvent(LiveEvent.PlayChannel(stream))
            },
            onChannelLongClick = { stream ->
                handleFavoriteLongPress(stream)
            },
            epgProvider = { stream ->
                // Phase 2.5: Use optimized EPG cache system
                try {
                    val epgKey = stream.epg_channel_id
                        ?.takeIf { it.isNotBlank() }
                        ?: stream.stream_id?.toString()
                    val streamId = stream.stream_id?.toString()
                    if (epgKey.isNullOrBlank()) {
                        Pair(null, null)
                    } else {
                        EpgCache.getEpgData(epgKey, streamId)
                    }
                } catch (e: Exception) {
                    android.util.Log.e("LiveFragment", "Failed to fetch EPG for channel ${stream.stream_id}", e)
                    Pair(null, null)
                }
            },
            favoriteChecker = { streamId ->
                // O(1) fast lookup; avoids main-thread blocking in bind()
                favoriteStreamIds.contains(streamId)
            },
            onChannelFocused = { stream, position ->
                stream.stream_id?.let { viewModel.onEvent(LiveEvent.RememberChannelFocus(it, position)) }
            }
        )
    }
    
    // Track if category adapter is already set
    private var categoryAdapterSet = false
    
    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        return inflater.inflate(R.layout.fragment_live_3column, container, false)
    }
    
    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        didRestoreFocusForThisView = false
        didRestorePreviewForThisView = false
        
        // Setup UI for 3-Column Layout
        rvCategories = view.findViewById<RecyclerView>(R.id.rv_sidebar_categories)
            ?: error("Missing rv_sidebar_categories in fragment_live_3column")
        rvChannels = view.findViewById<RecyclerView>(R.id.rv_channels_horizontal)
            ?: error("Missing rv_channels_horizontal in fragment_live_3column")
        tvEmptyState = view.findViewById(R.id.tv_empty_state)
        tvHeaderCategory = view.findViewById(R.id.tv_category_name)
        tvHeaderChannelCount = view.findViewById(R.id.tv_channel_count)
        etCategorySearch = view.findViewById(R.id.et_category_search)

        // Loading/empty overlays for channels list
        llLoadingState = view.findViewById(R.id.ll_loading_state)
        tvLoadingMessage = view.findViewById(R.id.tv_loading_message)
        
        // Setup preview panel
        setupPreviewTopBar(view)
        
        // Initialize PreviewPlayerPanel
        previewPlayerPanel = PreviewPlayerPanel(
            requireContext(),
            view,
            okHttpClient,
            onFullscreenClick = { stream -> launchFullscreen(stream) },
            onFavoriteClick = { stream ->
                viewLifecycleOwner.lifecycleScope.launch {
                    try {
                        val nowFavorite = toggleFavorite(stream)
                        previewPlayerPanel?.setFavoriteState(nowFavorite)
                        Toast.makeText(
                            requireContext(),
                            getString(if (nowFavorite) R.string.favorite_added else R.string.favorite_removed),
                            Toast.LENGTH_SHORT
                        ).show()
                    } catch (e: Exception) {
                        Toast.makeText(requireContext(), "Error: ${e.message}", Toast.LENGTH_SHORT).show()
                    }
                }
            }
        )
        viewLifecycleOwner.lifecycle.addObserver(previewPlayerPanel!!)
        
        // Setup categories RecyclerView (Vertical Sidebar)
        rvCategories.apply {
            layoutManager = LinearLayoutManager(context, LinearLayoutManager.VERTICAL, false)
            setHasFixedSize(true)
        }

        etCategorySearch?.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit
            override fun afterTextChanged(s: Editable?) {
                updateCategorySearch(s?.toString().orEmpty())
            }
        })
        
        // Setup channels RecyclerView (Vertical List for 3-column)
        rvChannels.apply {
            layoutManager = LinearLayoutManager(context, LinearLayoutManager.VERTICAL, false)
            setHasFixedSize(true)
            setItemViewCacheSize(40)
            recycledViewPool.setMaxRecycledViews(0, 60)
            adapter = channelPagingAdapter
        }

        // TV Focus Guard:
        // Prevent DPAD_UP from the first channel jumping to the sidebar Back button.
        // Users should explicitly go LEFT to the sidebar, then UP to reach Back.
        rvChannels.setOnKeyListener { _, keyCode, event ->
            if (event.action != KeyEvent.ACTION_DOWN) return@setOnKeyListener false
            if (keyCode != KeyEvent.KEYCODE_DPAD_UP) return@setOnKeyListener false

            val focused = rvChannels.findFocus() ?: return@setOnKeyListener false
            val holder = rvChannels.findContainingViewHolder(focused) ?: return@setOnKeyListener false
            val position = holder.bindingAdapterPosition
            if (position == 0) {
                true
            } else {
                false
            }
        }
        
        // Smooth/pro: avoid "re-enter" layout animations on return from fullscreen.
        rvChannels.itemAnimator = null
        rvCategories.itemAnimator = RecyclerViewAnimations.createDefaultAnimator().also { animator ->
            (animator as? SimpleItemAnimator)?.supportsChangeAnimations = false
        }
        rvChannels.layoutAnimation = null
        rvCategories.layoutAnimation = null

        // Preserve scroll position across temporary detach/reattach.
        channelPagingAdapter.stateRestorationPolicy = RecyclerView.Adapter.StateRestorationPolicy.PREVENT_WHEN_EMPTY
        
        // Phase 2.5: Initialize EPG cache system
        EpgCache.initialize(repository)

        // Initialize MemoryManager for this fragment
        memoryManager.addEmergencyCleanupCallback(object : MemoryManager.EmergencyCleanupCallback {
            override suspend fun onEmergencyCleanup() {
                try {
                    withContext(Dispatchers.Main.immediate) {
                        if (!isAdded) return@withContext

                        // Avoid invalidating paging (looks like a "reload" on return from fullscreen).
                        // Instead, free the heaviest resources and trim view caches.
                        previewPlayerPanel?.releasePlayer()
                        EpgCache.clearCache()
                        rvChannels.recycledViewPool.clear()
                        rvCategories.recycledViewPool.clear()
                        Glide.get(requireContext()).clearMemory()
                    }
                } catch (e: Exception) {
                    android.util.Log.e("LiveFragment", "Emergency cleanup failed", e)
                }
            }
        })

        // Observe ViewModel state
        observeViewModel()
        observeFavorites()
        observeEpgUpdates()
        primeEpgData()
    }

    override fun onResume() {
        super.onResume()
        val state = viewModel.uiState.value
        if (state.restoreFromFullscreenPending) {
            didRestoreFocusForThisView = false
            blockCategoryFocusForReturnRestore()
            rvChannels.postDelayed({
                if (isAdded && categoriesFocusBlockedForRestore && !didRestoreFocusForThisView) {
                    unblockCategoryFocusAfterRestore()
                }
            }, 1200)
        }
        maybeRestorePreviewFromState(state)
        restoreChannelFocusIfNeeded()
        lastChannelLoadStates?.let { renderChannelLoadState(it) }
        viewModel.onEvent(LiveEvent.ConsumeReturnFromFullscreen)
    }
    
    override fun onDestroyView() {
        super.onDestroyView()

        // Cancel all coroutines properly
        try {
            uiStateJob?.cancel()
            pagedChannelsJob?.cancel()
            loadStateJob?.cancel()
            navigationJob?.cancel()
            epgPrimeJob?.cancel()
            favoritesJob?.cancel()
            epgUpdatesJob?.cancel()
            previewEpgRetryJob?.cancel()
            clockJob?.cancel()
            
            // Clear job references
            uiStateJob = null
            pagedChannelsJob = null
            loadStateJob = null
            navigationJob = null
            epgPrimeJob = null
            favoritesJob = null
            epgUpdatesJob = null
            previewEpgRetryJob = null
            clockJob = null
        } catch (e: Exception) {
            android.util.Log.e("LiveFragment", "Error cancelling coroutines", e)
        }

        // Phase 2.4: Clear Glide cache to prevent memory leaks
        if (isAdded) {
            try {
                GlideUtils.clearCache(requireContext())
            } catch (e: Exception) {
                android.util.Log.e("LiveFragment", "Error clearing Glide cache", e)
            }
        }

        // Release preview player
        try {
            previewPlayerPanel?.releasePlayer()
            previewPlayerPanel = null
        } catch (e: Exception) {
            android.util.Log.e("LiveFragment", "Error releasing preview player", e)
        }
        
        // Remove lifecycle observer if still referenced (mostly auto-handled, but good practice if retaining refs)
        // previewPlayerPanel is nulled, so we rely on LifecycleOwner cleaning up observers
        
        // Null out view references removed from variables list
        tvHeaderCategory = null
        tvHeaderChannelCount = null
        etCategorySearch = null
        tvTopBarTime = null
        tvTopBarWeather = null
        tvTopBarWeather = null

        // Clear adapters to prevent memory leaks
        try {
            rvChannels.adapter = null
            rvCategories.adapter = null
            sidebarCategoryAdapter = null
            categoryAdapterSet = false
            favoriteStreamIds.clear()
            displayCategories = emptyList()
            categorySearchQuery = ""
            favoritesCount = 0
            lastUiState = null
        } catch (e: Exception) {
            android.util.Log.e("LiveFragment", "Error clearing adapters", e)
        }
    }

    private fun observeFavorites() {
        favoritesJob = viewLifecycleOwner.lifecycleScope.launch {
            try {
                viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                    repository.getFavoritesByType("live").collect { favorites ->
                        favoriteStreamIds.clear()
                        favorites.forEach { favoriteStreamIds.add(it.streamId) }
                        favoritesCount = favorites.size
                        val state = viewModel.uiState.value
                        sidebarCategoryAdapter?.updateChannelCounts(
                            state.categoryChannelCounts + (FAVORITES_CATEGORY_ID to favoritesCount)
                        )
                        if (state.selectedCategoryId == FAVORITES_CATEGORY_ID) {
                            updateHeaderInfo(state)
                        }
                    }
                }
            } catch (e: CancellationException) {
                android.util.Log.d("LiveFragment", "Favorites observation cancelled")
            } catch (e: Exception) {
                android.util.Log.e("LiveFragment", "Error observing favorites", e)
            }
        }
    }

    private fun observeEpgUpdates() {
        epgUpdatesJob = viewLifecycleOwner.lifecycleScope.launch {
            try {
                viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                    EpgCache.updates.collect { channelKey ->
                        notifyEpgUpdated(channelKey)
                        if (channelKey == currentPreviewEpgKey) {
                            val (now, next) = EpgCache.getEpgData(channelKey, currentPreviewStreamId)
                            if (now != null || next != null) {
                                previewPlayerPanel?.updateEpg(now, next)
                            }
                        }
                    }
                }
            } catch (e: CancellationException) {
                android.util.Log.d("LiveFragment", "EPG updates observation cancelled")
            } catch (e: Exception) {
                android.util.Log.e("LiveFragment", "Error observing EPG updates", e)
            }
        }
    }

    private fun updatePreviewEpg(stream: XtreamStream) {
        val epgKey = stream.epg_channel_id?.takeIf { it.isNotBlank() } ?: stream.stream_id?.toString()
        val streamId = stream.stream_id?.toString()
        currentPreviewEpgKey = epgKey
        currentPreviewStreamId = streamId
        previewEpgRetryJob?.cancel()

        if (epgKey.isNullOrBlank()) {
            previewPlayerPanel?.updateEpg(null, null)
            return
        }

        // Prime EPG fetch; if cache miss, EpgCache will refresh in background.
        val (now, next) = EpgCache.getEpgData(epgKey, streamId)
        if (now != null || next != null) {
            previewPlayerPanel?.updateEpg(now, next)
            return
        }

        // Avoid leaving "Syncing TV guide" forever: retry briefly, then show "no guide" placeholder.
        previewEpgRetryJob = viewLifecycleOwner.lifecycleScope.launch {
            repeat(10) {
                delay(700)
                val (retryNow, retryNext) = EpgCache.getEpgData(epgKey, streamId)
                if (retryNow != null || retryNext != null) {
                    previewPlayerPanel?.updateEpg(retryNow, retryNext)
                    return@launch
                }
            }
            // Still nothing: switch to "no EPG" state.
            previewPlayerPanel?.updateEpg(null, null)
        }
    }

    private fun notifyEpgUpdated(channelKey: String) {
        // Update only items that match the updated key; no animations (rvChannels.itemAnimator = null).
        val items = channelPagingAdapter.snapshot().items
        if (items.isEmpty()) return
        items.forEachIndexed { index, stream ->
            val key = stream.epg_channel_id?.takeIf { it.isNotBlank() } ?: stream.stream_id?.toString()
            if (key == channelKey) {
                channelPagingAdapter.notifyItemChanged(index)
            }
        }
    }
    
    /**
     * Observe ViewModel StateFlow and navigation events with proper lifecycle management
     * Uses repeatOnLifecycle to prevent JobCancellationException issues
     */
    private fun observeViewModel() {
        // Observe UI state for categories with proper lifecycle management
        uiStateJob = viewLifecycleOwner.lifecycleScope.launch {
            try {
                viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                    viewModel.uiState.collect { state ->
                        try {
                            if (isAdded) {
                                renderState(state)
                            }
                        } catch (e: Exception) {
                            android.util.Log.e("LiveFragment", "Error rendering state", e)
                        }
                    }
                }
            } catch (e: CancellationException) {
                android.util.Log.d("LiveFragment", "UI state observation cancelled")
            } catch (e: Exception) {
                android.util.Log.e("LiveFragment", "Error observing UI state", e)
                if (isAdded) {
                    showEmptyState("Error loading categories: ${e.message}")
                }
            }
        }

        // Observe paged channels with proper lifecycle management
        pagedChannelsJob = viewLifecycleOwner.lifecycleScope.launch {
            try {
                // Keep Paging collection alive while the view exists (not STARTED), so fullscreen transitions don't reset the list.
                viewModel.pagedChannels.collectLatest { pagingData ->
                    try {
                        if (isAdded) {
                            channelPagingAdapter.submitData(pagingData)
                        }
                    } catch (e: Exception) {
                        android.util.Log.e("LiveFragment", "Error submitting paged data", e)
                    }
                }
            } catch (e: CancellationException) {
                android.util.Log.d("LiveFragment", "Paged channels observation cancelled")
            } catch (e: Exception) {
                android.util.Log.e("LiveFragment", "Error observing paged channels", e)
                if (isAdded) {
                    showLoading("Error loading channels: ${e.message}")
                }
            }
        }

        // Observe adapter load state with enhanced error handling
        loadStateJob = viewLifecycleOwner.lifecycleScope.launch {
            try {
                channelPagingAdapter.loadStateFlow.collectLatest { loadStates ->
                    try {
                        if (!isAdded) return@collectLatest
                        lastChannelLoadStates = loadStates
                        if (!viewLifecycleOwner.lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) return@collectLatest

                        renderChannelLoadState(loadStates)
                    } catch (e: Exception) {
                        android.util.Log.e("LiveFragment", "Error handling load state", e)
                    }
                }
            } catch (e: CancellationException) {
                android.util.Log.d("LiveFragment", "Load state observation cancelled")
            } catch (e: Exception) {
                android.util.Log.e("LiveFragment", "Error observing load state", e)
                if (isAdded) {
                    showLoading("Error loading channels")
                }
            }
        }

        // Observe navigation events (play channel) with proper lifecycle management
        navigationJob = viewLifecycleOwner.lifecycleScope.launch {
            try {
                viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                    viewModel.navigationEvent.collect { stream ->
                        try {
                            if (isAdded && stream != null) {
                                navigateToPlayer(stream)
                            }
                        } catch (e: Exception) {
                            android.util.Log.e("LiveFragment", "Error navigating to player", e)
                        }
                    }
                }
            } catch (e: CancellationException) {
                android.util.Log.d("LiveFragment", "Navigation events observation cancelled")
            } catch (e: Exception) {
                android.util.Log.e("LiveFragment", "Error observing navigation events", e)
            }
        }
    }

    private fun primeEpgData() {
        epgPrimeJob = viewLifecycleOwner.lifecycleScope.launch {
            try {
                // Avoid full XMLTV sync here (too heavy on many TV devices). We use short-EPG on-demand via EpgCache.
                withContext(Dispatchers.IO) {
                    runCatching { repository.clearOldEpg() }
                    runCatching { EpgCache.cleanupStaleEntries() }
                }
            } catch (e: CancellationException) {
                android.util.Log.d("LiveFragment", "EPG priming cancelled")
            } catch (e: Exception) {
                android.util.Log.e("LiveFragment", "Error priming EPG data", e)
            }
        }
    }

    private fun updateCategorySearch(query: String) {
        val trimmed = query.trim()
        if (categorySearchQuery == trimmed) return
        categorySearchQuery = trimmed
        lastUiState?.let { updateCategoryList(it) }
    }

    private fun buildDisplayCategories(state: LiveUiState): List<XtreamCategory> {
        val favorites = XtreamCategory(
            category_id = FAVORITES_CATEGORY_ID,
            category_name = getString(R.string.favorites),
            parent_id = null
        )
        val filtered = state.categories.filterNot { it.category_id == FAVORITES_CATEGORY_ID }
        val combined = listOf(favorites) + filtered
        if (categorySearchQuery.isBlank()) return combined
        val query = categorySearchQuery.trim()
        return combined.filter { category ->
            category.category_name?.contains(query, ignoreCase = true) == true
        }
    }

    private fun updateCategoryList(state: LiveUiState) {
        val categories = buildDisplayCategories(state)
        val listChanged = categories != displayCategories
        if (listChanged) {
            displayCategories = categories
        }
        val counts = state.categoryChannelCounts + (FAVORITES_CATEGORY_ID to favoritesCount)

        if (categories.isNotEmpty() && !categoryAdapterSet) {
            try {
                val categoryAdapter = SidebarCategoryAdapter(
                    categories = categories,
                    channelCounts = counts,
                    initialSelectedCategoryId = state.selectedCategoryId,
                    onCategoryClick = { categoryId ->
                        handleCategoryClick(categoryId)
                    },
                    onCategoryFocused = { categoryId, position ->
                        viewModel.onEvent(LiveEvent.RememberCategoryFocus(categoryId, position))
                    }
                )

                rvCategories.adapter = categoryAdapter
                sidebarCategoryAdapter = categoryAdapter
                categoryAdapterSet = true
                android.util.Log.d(
                    "LiveFragment",
                    "Category adapter set successfully with ${categories.size} categories"
                )
            } catch (e: Exception) {
                android.util.Log.e("LiveFragment", "Error setting category adapter", e)
                showEmptyState("Error loading categories: ${e.message}")
            }
        } else if (categoryAdapterSet && listChanged) {
            sidebarCategoryAdapter?.updateCategories(categories, state.selectedCategoryId)
        } else if (categories.isEmpty() && state.isLoadingCategories && state.error == null) {
            showLoading(getString(R.string.live_loading_categories))
        }

        if (categoryAdapterSet) {
            sidebarCategoryAdapter?.updateSelection(state.selectedCategoryId)
            sidebarCategoryAdapter?.updateChannelCounts(counts)
        }
    }

    private fun handleCategoryClick(categoryId: String) {
        if (categorySearchQuery.isNotBlank()) {
            etCategorySearch?.setText("")
        }
        viewModel.onEvent(LiveEvent.SelectCategory(categoryId))
    }
    
    /**
     * Render UI based on state from ViewModel
     * Phase 2.2: Enhanced category loading with fallback handling
     */
    private fun renderState(state: LiveUiState) {
        android.util.Log.d("LiveFragment", "renderState: categories=${state.categories.size}, error=${state.error}")
        lastUiState = state

        updateCategoryList(state)

        // Handle error state
        if (state.error != null) {
            android.util.Log.e("LiveFragment", "State error: ${state.error}")
            showEmptyState(state.error)
        } else if (state.categories.isNotEmpty()) {
            hideEmptyState()
        }

        updateHeaderInfo(state)
        restoreInitialFocusIfNeeded(state)

        // Channels are now handled by PagingAdapter
        // No need to manually set adapter here
    }

    private fun restoreInitialFocusIfNeeded(state: LiveUiState) {
        if (didRestoreFocusForThisView) return
        if (displayCategories.isEmpty() || !categoryAdapterSet) return

        val categoryIdToReveal = state.lastFocusedCategoryId ?: state.selectedCategoryId
        if (!categoryIdToReveal.isNullOrBlank()) {
            val positionById = displayCategories.indexOfFirst { it.category_id == categoryIdToReveal }
            val position = if (positionById >= 0) positionById else state.lastFocusedCategoryPosition ?: -1
            if (position >= 0) rvCategories.scrollToPosition(position)
        }

        if (state.lastFocusArea == LiveFocusArea.CATEGORIES) {
            val focusCategoryId = state.lastFocusedCategoryId ?: state.selectedCategoryId
            if (!focusCategoryId.isNullOrBlank()) {
                val positionById = displayCategories.indexOfFirst { it.category_id == focusCategoryId }
                val position = if (positionById >= 0) positionById else state.lastFocusedCategoryPosition ?: -1
                if (position >= 0) {
                    rvCategories.post {
                        rvCategories.scrollToPosition(position)
                        rvCategories.post {
                            rvCategories.findViewHolderForAdapterPosition(position)?.itemView?.requestFocus()
                            didRestoreFocusForThisView = true
                        }
                    }
                    return
                }
            }
            rvCategories.post {
                rvCategories.requestFocus()
                didRestoreFocusForThisView = true
            }
        }
    }

    private fun maybeRestorePreviewFromState(state: LiveUiState) {
        if (didRestorePreviewForThisView) return
        if (!state.restoreFromFullscreenPending) return

        val historyItem = runCatching {
            WatchHistoryPreferences(requireContext()).getRecentLiveChannelsList().firstOrNull()
        }.getOrNull()
        val historyStreamId = historyItem?.channelId?.takeIf { it.isNotBlank() }
        val streamId = historyStreamId ?: state.lastPlayedChannelId ?: return
        val resolvedName = historyItem?.channelName?.takeIf { it.isNotBlank() } ?: state.lastPlayedChannelName
        val resolvedLogo = historyItem?.channelLogo ?: state.lastPlayedChannelLogo
        val resolvedEpgId = historyItem?.epgChannelId ?: state.lastPlayedEpgChannelId
        val restoredStream = XtreamStream(
            num = null,
            name = resolvedName,
            stream_type = "live",
            stream_id = streamId,
            stream_icon = resolvedLogo,
            epg_channel_id = resolvedEpgId,
            added = null,
            category_id = state.selectedCategoryId,
            category_ids = null,
            container_extension = null,
            custom_sid = null,
            direct_source = null,
            tv_archive = null,
            tv_archive_duration = null
        )

        val sameStream = previewPlayerPanel?.getCurrentStream()?.stream_id == streamId
        val isPlaying = previewPlayerPanel?.isPlaying() == true
        if (!sameStream || !isPlaying) {
            previewPlayerPanel?.play(restoredStream)
        }
        updatePreviewEpg(restoredStream)
        updateFavoriteButtonState(restoredStream)
        val shouldSyncHistory = historyItem != null && historyStreamId != null && (
            historyStreamId != state.lastPlayedChannelId ||
                (historyItem.channelName.isNotBlank() && historyItem.channelName != state.lastPlayedChannelName) ||
                (historyItem.channelLogo != null && historyItem.channelLogo != state.lastPlayedChannelLogo) ||
                (!historyItem.epgChannelId.isNullOrBlank() && historyItem.epgChannelId != state.lastPlayedEpgChannelId)
            )
        if (shouldSyncHistory) {
            viewModel.onEvent(LiveEvent.RememberPreviewStream(restoredStream))
        }
        didRestorePreviewForThisView = true
    }
    
    /**
     * Navigate to PlayerActivity to play a stream
     * In 3-column: 1st click = preview, 2nd click = fullscreen
     */
    private fun navigateToPlayer(stream: XtreamStream) {
        // In 3-column: 1st click = preview, 2nd click = fullscreen
        if (previewPlayerPanel?.getCurrentStream()?.stream_id == stream.stream_id && previewPlayerPanel?.isPlaying() == true) {
            // Second click: go fullscreen
            launchFullscreen(stream)
        } else {
            // First click: play in preview
            previewPlayerPanel?.play(stream)
            updatePreviewEpg(stream)
            viewModel.onEvent(LiveEvent.RememberPreviewStream(stream))
            updateFavoriteButtonState(stream)
        }
    }
    
    /**
     * Launch fullscreen player
     */
    private fun launchFullscreen(stream: XtreamStream) {
        viewModel.onEvent(LiveEvent.RememberFullscreenLaunch(stream))
        val credentialsPrefs = CredentialsPreferences(requireContext())
        val serverUrl = credentialsPrefs.getServerUrl() ?: return
        val username = credentialsPrefs.getUsername() ?: return
        val password = credentialsPrefs.getPassword() ?: return
        val streamId = stream.stream_id ?: return
        
        val streamUrl = "$serverUrl/live/$username/$password/$streamId.ts"
        val categoryId = stream.category_id ?: viewModel.uiState.value.selectedCategoryId
        val channelIds = ArrayList(channelPagingAdapter.snapshot().items.mapNotNull { it.stream_id }.distinct())
        
        val intent = PlayerActivity.createIntent(
            context = requireContext(),
            streamUrl = streamUrl,
            title = stream.name ?: getString(R.string.player_epg_channel_unknown),
            channelName = stream.name,
            channelLogo = stream.stream_icon,
            epgChannelId = stream.epg_channel_id?.takeIf { it.isNotBlank() } ?: stream.stream_id?.toString(),
            contentId = stream.stream_id ?: streamUrl,
            contentType = ContentType.LIVE_TV,
            posterUrl = stream.stream_icon,
            liveCategoryId = categoryId,
            liveChannelIds = channelIds.takeIf { it.isNotEmpty() },
            baseServerUrl = serverUrl
        )
        val options = ActivityOptionsCompat.makeCustomAnimation(
            requireContext(),
            R.anim.fade_in,
            R.anim.fade_out
        )
        startActivity(intent, options.toBundle())
    }

    private fun restoreChannelFocusIfNeeded() {
        if (didRestoreFocusForThisView) return
        val state = viewModel.uiState.value
        if (state.lastFocusArea != LiveFocusArea.CHANNELS) return

        val targetStreamId = state.lastFocusedChannelId ?: state.lastPlayedChannelId ?: return
        val index = channelPagingAdapter.snapshot().items.indexOfFirst { it.stream_id == targetStreamId }
        if (index < 0) return

        rvChannels.post {
            rvChannels.scrollToPosition(index)
            rvChannels.post {
                rvChannels.findViewHolderForAdapterPosition(index)?.itemView?.requestFocus()
                didRestoreFocusForThisView = true
                unblockCategoryFocusAfterRestore()
            }
        }
    }

    private fun blockCategoryFocusForReturnRestore() {
        if (categoriesFocusBlockedForRestore) return
        categoriesFocusBlockedForRestore = true
        rvCategories.descendantFocusability = ViewGroup.FOCUS_BLOCK_DESCENDANTS
        rvCategories.isFocusable = false
    }

    private fun unblockCategoryFocusAfterRestore() {
        if (!categoriesFocusBlockedForRestore) return
        categoriesFocusBlockedForRestore = false
        rvCategories.descendantFocusability = ViewGroup.FOCUS_AFTER_DESCENDANTS
        rvCategories.isFocusable = true
    }
    



    
    /**
     * Show empty state with message
     */
    private fun showEmptyState(message: String) {
        tvEmptyState?.text = message
        tvEmptyState?.visibility = View.VISIBLE
        rvCategories.visibility = View.VISIBLE
        rvChannels.visibility = View.GONE
        llLoadingState?.visibility = View.GONE
    }
    
    /**
     * Show loading message with ProgressBar but keep categories visible
     */
    private fun showLoading(message: String) {
        showLoading(message, keepChannelsVisible = false)
    }

    private fun showLoading(message: String, keepChannelsVisible: Boolean) {
        llLoadingState?.visibility = View.VISIBLE
        tvLoadingMessage?.text = message
        tvEmptyState?.visibility = View.GONE
        rvCategories.visibility = View.VISIBLE  // Keep categories visible
        rvChannels.visibility = if (keepChannelsVisible) View.VISIBLE else View.GONE
    }
    
    /**
     * Hide empty state and show content
     */
    private fun hideEmptyState() {
        llLoadingState?.visibility = View.GONE
        tvEmptyState?.visibility = View.GONE
        rvCategories.visibility = View.VISIBLE
        rvChannels.visibility = View.VISIBLE
    }

    private fun renderChannelLoadState(loadStates: CombinedLoadStates) {
        when (loadStates.refresh) {
            is LoadState.Loading -> {
                val keepContent = channelPagingAdapter.itemCount > 0
                showLoading(getString(R.string.live_loading_channels), keepChannelsVisible = keepContent)
            }

            is LoadState.Error -> {
                val error = (loadStates.refresh as LoadState.Error).error
                val errorMessage = getDisplayErrorMessage(error)
                android.util.Log.e("LiveFragment", "Channel loading error", error)
                showLoading(errorMessage, keepChannelsVisible = channelPagingAdapter.itemCount > 0)
            }

            is LoadState.NotLoading -> {
                if (channelPagingAdapter.itemCount == 0) {
                    showEmptyState(getString(R.string.live_no_channels))
                } else {
                    hideEmptyState()
                    restoreChannelFocusIfNeeded()
                }
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
            "Unable to load channels. Please try again."
        }
    }
    
    /**
     * Show search dialog for filtering channels
     */
    private fun showSearchDialog() {
        val dialogView = LayoutInflater.from(requireContext())
            .inflate(R.layout.dialog_search_channels, null)
        
        val dialog = android.app.AlertDialog.Builder(requireContext())
            .setView(dialogView)
            .create()
        
        // Make dialog background transparent for custom styling
        dialog.window?.setBackgroundDrawableResource(android.R.color.transparent)
        
        val etSearch = dialogView.findViewById<android.widget.EditText>(R.id.et_search)
        val btnClear = dialogView.findViewById<android.widget.Button>(R.id.btn_clear)
        val btnClose = dialogView.findViewById<android.widget.Button>(R.id.btn_close)
        
        // Set current search query if any
        etSearch.setText(viewModel.uiState.value.searchQuery)
        
        // Real-time search as user types
        etSearch.addTextChangedListener(object : android.text.TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: android.text.Editable?) {
                val query = s?.toString() ?: ""
                viewModel.onEvent(LiveEvent.SearchChannels(query))
            }
        })
        
        // Clear button
        btnClear.setOnClickListener {
            etSearch.setText("")
            viewModel.onEvent(LiveEvent.ClearSearch)
        }
        
        // Close button
        btnClose.setOnClickListener {
            dialog.dismiss()
        }
        
        // Auto-focus on search field
        etSearch.requestFocus()
        
        // Show keyboard
        etSearch.postDelayed({
            val imm = requireContext().getSystemService(android.content.Context.INPUT_METHOD_SERVICE) 
                as? android.view.inputmethod.InputMethodManager
            imm?.showSoftInput(etSearch, android.view.inputmethod.InputMethodManager.SHOW_IMPLICIT)
        }, 100)
        
        dialog.show()
    }
    
    /**
     * Handle long press on channel for favorites
     * Week 10: Add/Remove favorites functionality
     */
    private fun handleFavoriteLongPress(channel: XtreamStream) {
        viewLifecycleOwner.lifecycleScope.launch {
            try {
                val nowFavorite = toggleFavorite(channel)
                channel.stream_id?.let { streamId ->
                    if (nowFavorite) favoriteStreamIds.add(streamId) else favoriteStreamIds.remove(streamId)
                    notifyFavoriteChanged(streamId)
                }
                Toast.makeText(
                    requireContext(),
                    getString(if (nowFavorite) R.string.favorite_added else R.string.favorite_removed),
                    Toast.LENGTH_SHORT
                ).show()
                if (previewPlayerPanel?.getCurrentStream()?.stream_id == channel.stream_id) {
                     previewPlayerPanel?.setFavoriteState(nowFavorite)
                }
            } catch (e: Exception) {
                Toast.makeText(
                    requireContext(),
                    "Error: ${e.message}",
                    Toast.LENGTH_SHORT
                ).show()
            }
        }
    }

    private suspend fun toggleFavorite(channel: XtreamStream): Boolean {
        val streamId = channel.stream_id ?: return false
        val displayName = channel.name ?: getString(R.string.player_epg_channel_unknown)
        return withContext(Dispatchers.IO) {
            val isFavorite = repository.isFavorite(streamId)
            if (isFavorite) {
                repository.removeFavorite(streamId)
                false
            } else {
                repository.addFavorite(streamId, "live", displayName, channel.stream_icon)
                true
            }
        }
    }

    private fun notifyFavoriteChanged(streamId: String) {
        val index = channelPagingAdapter.snapshot().items.indexOfFirst { it.stream_id == streamId }
        if (index >= 0) {
            channelPagingAdapter.notifyItemChanged(index)
        }
    }

    private fun setupPreviewTopBar(view: View) {
        tvTopBarTime = view.findViewById(R.id.tv_current_time)
        tvTopBarWeather = view.findViewById(R.id.tv_weather)
        view.findViewById<android.widget.ImageView>(R.id.btn_preview_search)?.apply {
            setOnClickListener { openSearchScreen() }
            contentDescription = getString(R.string.search_content_description)
        }
        view.findViewById<android.widget.ImageView>(R.id.btn_profile)?.apply {
            setOnClickListener { openSettingsScreen() }
            contentDescription = getString(R.string.settings_content_description)
        }
        view.findViewById<android.widget.ImageView>(R.id.btn_back)?.setOnClickListener {
            requireActivity().onBackPressedDispatcher.onBackPressed()
        }
        view.findViewById<android.widget.ImageView>(R.id.icon_favorites)?.apply {
            isFocusable = true
            isClickable = true
            setOnClickListener { openFavoritesScreen() }
        }
        view.findViewById<android.widget.ImageView>(R.id.icon_live_tv)?.apply {
            isFocusable = true
            isClickable = true
            setOnClickListener { rvCategories.requestFocus() }
        }
        // Favorites logic moved to PreviewPlayerPanel
        // btnPreviewFavorite initialization removed from here as it creates conflict / duplication
        // if setupPreviewPanel is removed, we must ensure PreviewPlayerPanel handles it.
        // PreviewPlayerPanel finds the view by ID R.id.btn_favorite.
        // So we just leave this method for other top bar items.
        startTopBarTicker()
        // updateFavoriteButtonState(null) - Removed
    }

    private fun startTopBarTicker() {
        clockJob?.cancel()
        clockJob = viewLifecycleOwner.lifecycleScope.launch {
            while (isActive) {
                val now = Date()
                tvTopBarTime?.text = topBarTimeFormatter.format(now)
                tvTopBarWeather?.text = formatWeatherSummary(now)
                delay(millisUntilNextMinute())
            }
        }
    }

    private fun millisUntilNextMinute(): Long {
        val now = System.currentTimeMillis()
        val remainder = now % 60_000L
        return if (remainder == 0L) 60_000L else 60_000L - remainder
    }

    private fun formatWeatherSummary(now: Date): String {
        val locale = resources.configuration.locales[0]
        val country = locale.displayCountry.takeIf { it.isNotBlank() }
        val fallbackLocation = TimeZone.getDefault().id.substringAfterLast('/').replace('_', ' ')
        val location = country?.takeIf { it.isNotBlank() }
            ?: fallbackLocation.takeIf { it.isNotBlank() }
            ?: getString(R.string.live_preview_weather_unknown_location)
        val daySummary = topBarDateFormatter.format(now)
        return getString(R.string.live_preview_weather_format, location, daySummary)
    }

    private fun openSearchScreen() {
        if (!isAdded) return
        navigateToFragment(SearchFragment())
    }

    private fun openSettingsScreen() {
        if (!isAdded) return
        navigateToFragment(SettingsFragmentNew())
    }

    private fun openFavoritesScreen() {
        if (!isAdded) return
        navigateToFragment(FavoritesFragment())
    }

    private fun navigateToFragment(fragment: Fragment) {
        parentFragmentManager.commit {
            replace(R.id.content_container, fragment)
            addToBackStack(null)
        }
    }

    private fun updateHeaderInfo(state: LiveUiState) {
        if (state.selectedCategoryId == FAVORITES_CATEGORY_ID) {
            tvHeaderCategory?.text = getString(R.string.favorites)
            tvHeaderChannelCount?.text = getString(R.string.live_channel_count_format, favoritesCount)
            return
        }

        val selectedCategory = state.categories.firstOrNull { it.category_id == state.selectedCategoryId }
        tvHeaderCategory?.text = selectedCategory?.category_name?.takeIf { it.isNotBlank() }
            ?: getString(R.string.live_category_placeholder)

        val selectedId = state.selectedCategoryId
        val knownCount = selectedId?.let { id -> state.categoryChannelCounts[id] }
        val channelCountText = when {
            knownCount != null -> getString(R.string.live_channel_count_format, knownCount)
            state.isLoadingChannels -> getString(R.string.live_channel_count_placeholder)
            else -> getString(R.string.live_channel_count_placeholder)
        }
        tvHeaderChannelCount?.text = channelCountText
    }

    
    private fun updateFavoriteButtonState(stream: XtreamStream?) {
        if (stream?.stream_id.isNullOrBlank()) {
             previewPlayerPanel?.setFavoriteState(false) // Or disable?
             return 
        }
        val streamId = stream!!.stream_id!!
        viewLifecycleOwner.lifecycleScope.launch {
            val isFavorite = withContext(Dispatchers.IO) {
                repository.isFavorite(streamId)
            }
            previewPlayerPanel?.setFavoriteState(isFavorite)
        }
    }

}

// Simple category adapter
class CategoryAdapter(
    private val categories: List<XtreamCategory>,
    private val onCategoryClick: (String) -> Unit
) : RecyclerView.Adapter<CategoryViewHolder>() {
    
    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): CategoryViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_category_chip, parent, false)
        return CategoryViewHolder(view)
    }
    
    override fun onBindViewHolder(holder: CategoryViewHolder, position: Int) {
        holder.bind(categories[position], onCategoryClick)
    }
    
    override fun getItemCount() = categories.size
}

class CategoryViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
    private val tvCategoryName = itemView.findViewById<android.widget.TextView>(R.id.tv_category_name)
    
    fun bind(category: XtreamCategory, onClick: (String) -> Unit) {
        tvCategoryName.text = category.category_name ?: "Unknown"
        itemView.setOnClickListener {
            category.category_id?.let { onClick(it) }
        }
    }
}

// Enhanced category adapter with icons for new design
class CategoryAdapterNew(
    private val categories: List<XtreamCategory>,
    private val onCategoryClick: (String) -> Unit
) : RecyclerView.Adapter<CategoryViewHolderNew>() {
    
    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): CategoryViewHolderNew {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_category_pill_new, parent, false)
        return CategoryViewHolderNew(view)
    }
    
    override fun onBindViewHolder(holder: CategoryViewHolderNew, position: Int) {
        holder.bind(categories[position], onCategoryClick)
    }
    
    override fun getItemCount() = categories.size
}

class CategoryViewHolderNew(itemView: View) : RecyclerView.ViewHolder(itemView) {
    private val tvCategoryName = itemView.findViewById<android.widget.TextView>(R.id.tv_category_name)
    private val tvCategoryIcon = itemView.findViewById<android.widget.TextView>(R.id.tv_category_icon)
    
    fun bind(category: XtreamCategory, onClick: (String) -> Unit) {
        val categoryName = category.category_name ?: "Unknown"
        tvCategoryName.text = categoryName
        
        // Set icon based on category name
        tvCategoryIcon.text = getCategoryIcon(categoryName)
        
        itemView.setOnClickListener {
            category.category_id?.let { onClick(it) }
        }
    }
    
    private fun getCategoryIcon(categoryName: String): String {
        return when {
            categoryName.contains("sport", ignoreCase = true) -> "🏆"
            categoryName.contains("news", ignoreCase = true) -> "📰"
            categoryName.contains("movie", ignoreCase = true) || 
            categoryName.contains("film", ignoreCase = true) -> "🎬"
            categoryName.contains("entertainment", ignoreCase = true) ||
            categoryName.contains("show", ignoreCase = true) -> "🎭"
            categoryName.contains("music", ignoreCase = true) -> "🎵"
            categoryName.contains("kids", ignoreCase = true) ||
            categoryName.contains("child", ignoreCase = true) -> "🧸"
            categoryName.contains("doc", ignoreCase = true) -> "📚"
            categoryName.contains("relig", ignoreCase = true) -> "🕌"
            categoryName.contains("edu", ignoreCase = true) -> "🎓"
            else -> "📺"
        }
    }
}
