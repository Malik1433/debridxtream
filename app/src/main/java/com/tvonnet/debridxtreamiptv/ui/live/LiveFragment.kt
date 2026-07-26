package com.tvonnet.debridxtreamiptv.ui.live

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.view.KeyEvent
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
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
import com.tvonnet.debridxtreamiptv.data.model.XtreamStream
import com.tvonnet.debridxtreamiptv.data.model.toLiveStreamUrl
import com.tvonnet.debridxtreamiptv.data.prefs.CredentialsPreferences
import com.tvonnet.debridxtreamiptv.data.repository.XtreamRepository
import com.tvonnet.debridxtreamiptv.player.stabilized.PlayerActivity
import com.tvonnet.debridxtreamiptv.ui.favorites.FavoritesFragment
import com.tvonnet.debridxtreamiptv.ui.search.SearchFragment
import com.tvonnet.debridxtreamiptv.ui.settings.SettingsFragment
import com.tvonnet.debridxtreamiptv.util.EpgCache
import com.tvonnet.debridxtreamiptv.util.FAVORITES_CATEGORY_ID
import com.tvonnet.debridxtreamiptv.util.GlideUtils
import com.tvonnet.debridxtreamiptv.utils.RecyclerViewAnimations
import com.tvonnet.debridxtreamiptv.utils.memory.MemoryManager
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.collectLatest
import java.text.SimpleDateFormat
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
        private const val CHANNEL_FOCUS_RETRY_COUNT = 3
        private const val CHANNEL_FOCUS_RETRY_DELAY_MS = 50L
        // Quick-jump timings: commit a typed number after a pause; flash the type-ahead letter.
        private const val QUICK_JUMP_COMMIT_MS = 900L
        private const val QUICK_JUMP_LETTER_MS = 700L
    }

    @Inject
    lateinit var repository: XtreamRepository

    @Inject
    lateinit var memoryManager: MemoryManager

    // Playback-tuned client (longer read timeout); only used to build the preview
    // player's DataSource below.
    @Inject
    @javax.inject.Named("playback")
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
    /** LF-1: top-bar header + minute clock (owns the 3 header views + clock job). */
    private var headerController: LiveHeaderController? = null

    // v2: nav rail, the inline channel-search pill, and the Program Guide strip.
    private var navRail: LiveNavRail? = null
    /** LF-2: the inline channel-search pill (owns the search views + debounce job). */
    private var searchController: LiveSearchController? = null
    /** LF-3: EPG preview + Program-Guide strip + now/next warm (owns the EPG jobs + guide adapter). */
    private var epgController: LiveEpgPreviewController? = null
    /** LF-4: category list + chip strip + category-focus (owns the sidebar adapter + chip query). */
    private var categoryController: LiveCategoryController? = null
    // Quick-jump: number-zap (type a channel number) + A–Z type-ahead (press a letter).
    private var tvQuickJump: TextView? = null
    private val quickJumpBuffer = StringBuilder()
    private var quickJumpJob: Job? = null
    private var btnWatch: View? = null
    private var didRestoreFocusForThisView = false
    private var didRestorePreviewForThisView = false
    private var lastChannelLoadStates: CombinedLoadStates? = null

    // Fragment scope coroutines - managed properly
    private var uiStateJob: Job? = null
    private var pagedChannelsJob: Job? = null
    private var loadStateJob: Job? = null
    private var navigationJob: Job? = null
    private var favoritesJob: Job? = null

    private val favoriteStreamIds = ConcurrentHashMap.newKeySet<String>()
    private var favoritesCount: Int = 0
    private var lastUiState: LiveUiState? = null
    private var pendingChannelFocusPosition: Int? = null

    private val livePlayerLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        handleLivePlayerResult(result.resultCode, result.data)
    }

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
            },
            onChannelKey = { position, keyCode, event ->
                handleChannelItemKey(position, keyCode, event)
            }
        ).apply {
            registerAdapterDataObserver(object : RecyclerView.AdapterDataObserver() {
                override fun onChanged() = restoreFocusGrid()
                override fun onItemRangeInserted(p0: Int, p1: Int) = restoreFocusGrid()
                override fun onItemRangeRemoved(p0: Int, p1: Int) = restoreFocusGrid()
            })
        }
    }

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
        
        // Setup UI for the v2 3-column layout (rail | channels | preview + guide)
        rvCategories = view.findViewById<RecyclerView>(R.id.rv_category_chips)
            ?: error("Missing rv_category_chips in fragment_live_3column")
        rvChannels = view.findViewById<RecyclerView>(R.id.rv_channels_horizontal)
            ?: error("Missing rv_channels_horizontal in fragment_live_3column")
        tvEmptyState = view.findViewById(R.id.tv_empty_state)
        tvQuickJump = view.findViewById(R.id.tv_quick_jump)
        btnWatch = view.findViewById(R.id.btn_fullscreen)

        // Loading/empty overlays for channels list
        llLoadingState = view.findViewById(R.id.ll_loading_state)
        tvLoadingMessage = view.findViewById(R.id.tv_loading_message)

        headerController = LiveHeaderController(
            fragment = this,
            tvHeaderCategory = view.findViewById(R.id.tv_category_name),
            tvHeaderChannelCount = view.findViewById(R.id.tv_channel_count),
            tvHeaderClock = view.findViewById(R.id.tv_header_clock),
            favoritesCount = { favoritesCount },
        )
        headerController?.startHeaderClock()
        setupNavRail(view)
        searchController = LiveSearchController(
            fragment = this,
            viewModel = viewModel,
            chipSearch = view.findViewById(R.id.chip_search),
            etChannelSearch = view.findViewById(R.id.et_channel_search),
            tvSearchLabel = view.findViewById(R.id.tv_search_label),
            onChipQueryChanged = { q -> categoryController?.onChipQueryChanged(q) },
            chipQueryIsNotEmpty = { categoryController?.chipQueryIsNotEmpty() == true },
            onFocusDownToChannels = { focusChannelItem(0) },
        )
        searchController?.setup()
        categoryController = LiveCategoryController(
            fragment = this,
            rvCategories = rvCategories,
            viewModel = viewModel,
            favoritesCount = { favoritesCount },
            lastUiState = { lastUiState },
            onBeforeCategorySelect = { searchController?.collapseIfHasText() },
            onShowEmptyState = { message -> showEmptyState(message) },
            onShowLoading = { message -> showLoading(message) },
        )
        epgController = LiveEpgPreviewController(
            fragment = this,
            viewModel = viewModel,
            repository = repository,
            rvChannels = rvChannels,
            rvEpgStrip = view.findViewById(R.id.rv_epg_strip),
            tvEpgStripSubtitle = view.findViewById(R.id.tv_epg_strip_subtitle),
            channelPagingAdapter = channelPagingAdapter,
            previewPanel = { previewPlayerPanel },
            onFocusChannel = { position -> focusChannelItem(position) },
            onUpdateFavoriteButton = { stream -> updateFavoriteButtonState(stream) },
            isPreviewRestored = { didRestorePreviewForThisView },
            markPreviewRestored = { didRestorePreviewForThisView = true },
        )
        epgController?.setupEpgStrip()

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
        
        // v2: categories are a horizontal chip strip in the header, not a vertical sidebar.
        rvCategories.apply {
            layoutManager = LinearLayoutManager(context, LinearLayoutManager.HORIZONTAL, false)
            setHasFixedSize(true)
            isFocusable = false
            isFocusableInTouchMode = false
        }

        // Setup channels RecyclerView (Vertical List for 3-column)
        rvChannels.apply {
            layoutManager = LinearLayoutManager(context, LinearLayoutManager.VERTICAL, false)
            setHasFixedSize(true)
            isFocusable = false
            isFocusableInTouchMode = false
            descendantFocusability = ViewGroup.FOCUS_AFTER_DESCENDANTS
            setItemViewCacheSize(40)
            recycledViewPool.setMaxRecycledViews(0, 60)
            adapter = channelPagingAdapter
        }

        // We DO NOT use FocusTrapHelper here because it breaks RecyclerView scrolling!
        // When you press DOWN on the last visible item, focusSearch() returns null (because the next item isn't laid out yet).
        // FocusTrapHelper would intercept that null and kill the event, preventing the RV from scrolling.
        // v2 chip strip: DOWN drops into the channel list; LEFT off the first chip enters the
        // rail (RIGHT/LEFT between chips is left to the RV so it can scroll).
        rvCategories.setOnKeyListener { _, keyCode, event ->
            if (event.action != KeyEvent.ACTION_DOWN) return@setOnKeyListener false
            when (keyCode) {
                KeyEvent.KEYCODE_DPAD_DOWN -> {
                    focusChannelItem(viewModel.uiState.value.lastFocusedChannelPosition ?: 0)
                    true
                }
                KeyEvent.KEYCODE_DPAD_LEFT -> {
                    if (categoryController?.isFirstCategoryFocused() == true) focusNavRail() else false
                }
                KeyEvent.KEYCODE_DPAD_UP -> true // nothing above the strip
                else -> false
            }
        }

        com.tvonnet.debridxtreamiptv.utils.SpatialNavigationEngine.enforceStrictOrthogonalNavigation(rvChannels)

        // TV Focus Guard. v2 topology: LEFT enters the rail, UP off the top row reaches the
        // chip strip, RIGHT crosses to the Watch button.
        rvChannels.setOnKeyListener { _, keyCode, event ->
            if (event.action != KeyEvent.ACTION_DOWN) return@setOnKeyListener false

            when (keyCode) {
                KeyEvent.KEYCODE_DPAD_UP -> {
                    if (isFirstChannelFocused()) (categoryController?.focusCategoryStrip() == true) else moveChannelFocusBy(delta = -1)
                }
                KeyEvent.KEYCODE_DPAD_DOWN -> {
                    moveChannelFocusBy(delta = 1)
                }
                KeyEvent.KEYCODE_DPAD_LEFT -> focusNavRail()
                KeyEvent.KEYCODE_DPAD_RIGHT -> {
                    btnWatch?.requestFocus() == true
                }
                else -> false
            }
        }
        
        // Smooth/pro: avoid "re-enter" layout animations on return from fullscreen.
        rvChannels.itemAnimator = null
        rvCategories.itemAnimator = null
        rvChannels.layoutAnimation = null
        rvCategories.layoutAnimation = null

        // Prevent directional focus-glint leaks when user scrolls the channel list quickly.
        com.tvonnet.debridxtreamiptv.utils.FocusGlintHelper.attachScrollReset(rvChannels)

        // Batch-warm now/next EPG for whatever window the list settles on (world-standard:
        // one DB query per visible window rather than a get_short_epg call per row).
        rvChannels.addOnScrollListener(object : RecyclerView.OnScrollListener() {
            override fun onScrollStateChanged(recyclerView: RecyclerView, newState: Int) {
                if (newState == RecyclerView.SCROLL_STATE_IDLE) epgController?.warmVisibleEpgCache()
            }
        })

        // Preserve scroll position across temporary detach/reattach.
        with(com.tvonnet.debridxtreamiptv.utils.FocusMemoryManager) {
            channelPagingAdapter.applyFocusMemory()
        }
        
        // Phase 2.5: Initialize EPG cache system
        EpgCache.initialize(repository)

        // Initialize MemoryManager for this fragment. Phase 3: keep a reference so the callback
        // can be removed in onDestroyView — the old anonymous callback captured this LiveFragment
        // and was never removed, leaking the fragment for the app's lifetime.
        emergencyCleanupCallback = object : MemoryManager.EmergencyCleanupCallback {
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
        }.also { memoryManager.addEmergencyCleanupCallback(it) }

        // Observe ViewModel state
        observeViewModel()
        observeFavorites()
        epgController?.observeEpgUpdates()
        epgController?.primeEpgData()
    }

    override fun onResume() {
        super.onResume()
        val state = viewModel.uiState.value
        if (!state.restoreFromFullscreenPending) {
            com.tvonnet.debridxtreamiptv.utils.FocusMemoryManager.restoreFocus(requireView())
        }
        if (state.restoreFromFullscreenPending) {
            didRestoreFocusForThisView = false
            categoryController?.blockCategoryFocusForReturnRestore()
            restoreFocusGrid()
        }
        epgController?.maybeRestorePreviewFromState(state)
        restoreChannelFocusIfNeeded()
        lastChannelLoadStates?.let { renderChannelLoadState(it) }
        viewModel.onEvent(LiveEvent.ConsumeReturnFromFullscreen)
    }
    
    override fun onPause() {
        super.onPause()
        com.tvonnet.debridxtreamiptv.utils.FocusMemoryManager.saveFocus(requireView())
        com.tvonnet.debridxtreamiptv.utils.FocusCoordinator.release("LIVE_GRID")
    }
    
    private var emergencyCleanupCallback: MemoryManager.EmergencyCleanupCallback? = null

    override fun onDestroyView() {
        super.onDestroyView()

        // Phase 3: unregister the emergency-cleanup callback so MemoryManager no longer retains
        // this fragment (fixes the LiveFragment self-leak).
        emergencyCleanupCallback?.let { memoryManager.removeEmergencyCleanupCallback(it) }
        emergencyCleanupCallback = null

        // Cancel all coroutines properly
        try {
            uiStateJob?.cancel()
            pagedChannelsJob?.cancel()
            loadStateJob?.cancel()
            navigationJob?.cancel()
            favoritesJob?.cancel()
            headerController?.cancel()
            searchController?.cancel()
            epgController?.cancel()
            categoryController?.clear()
            quickJumpJob?.cancel()

            // Clear job references
            uiStateJob = null
            pagedChannelsJob = null
            loadStateJob = null
            navigationJob = null
            favoritesJob = null
            headerController = null
            searchController = null
            epgController = null
            categoryController = null
            quickJumpJob = null
            quickJumpBuffer.setLength(0)
            tvQuickJump = null
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
        btnWatch = null

        try {
            navRail?.cleanup()
        } catch (e: Exception) {
            android.util.Log.e("LiveFragment", "Error cleaning up nav rail", e)
        }
        navRail = null

        // Clear adapters to prevent memory leaks
        try {
            rvChannels.adapter = null
            rvCategories.adapter = null
            favoriteStreamIds.clear()
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
                        categoryController?.updateChannelCounts(
                            state.categoryChannelCounts + (FAVORITES_CATEGORY_ID to favoritesCount)
                        )
                        if (state.selectedCategoryId == FAVORITES_CATEGORY_ID) {
                            headerController?.updateHeaderInfo(state)
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
                        viewModel.onEvent(
                            LiveEvent.ChannelLoadStateChanged(loadStates.refresh is LoadState.Loading)
                        )
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

    /**
     * Render UI based on state from ViewModel
     * Phase 2.2: Enhanced category loading with fallback handling
     */
    private fun renderState(state: LiveUiState) {
        android.util.Log.d("LiveFragment", "renderState: categories=${state.categories.size}, error=${state.error}")
        lastUiState = state

        categoryController?.updateCategoryList(state)

        // Handle error state
        if (state.error != null) {
            android.util.Log.e("LiveFragment", "State error: ${state.error}")
            showEmptyState(state.error)
        } else if (state.categories.isNotEmpty()) {
            hideEmptyState()
        }

        headerController?.updateHeaderInfo(state)
        restoreInitialFocusIfNeeded(state)

        // Channels are now handled by PagingAdapter
        // No need to manually set adapter here
    }

    private fun restoreInitialFocusIfNeeded(state: LiveUiState) {
        if (didRestoreFocusForThisView) return
        val cc = categoryController ?: return
        if (cc.displayCategories.isEmpty() || !cc.categoryAdapterSet) return

        val categoryIdToReveal = state.lastFocusedCategoryId ?: state.selectedCategoryId
        if (!categoryIdToReveal.isNullOrBlank()) {
            val positionById = cc.displayCategories.indexOfFirst { it.category_id == categoryIdToReveal }
            val position = if (positionById >= 0) positionById else state.lastFocusedCategoryPosition ?: -1
            if (position >= 0) rvCategories.scrollToPosition(position)
        }

        if (state.lastFocusArea == LiveFocusArea.CATEGORIES) {
            val focusCategoryId = state.lastFocusedCategoryId ?: state.selectedCategoryId
            if (!focusCategoryId.isNullOrBlank()) {
                val positionById = cc.displayCategories.indexOfFirst { it.category_id == focusCategoryId }
                val position = if (positionById >= 0) positionById else state.lastFocusedCategoryPosition ?: -1
                if (position >= 0) {
                    com.tvonnet.debridxtreamiptv.utils.FocusCoordinator.requestFocus("LIVE_GRID") {
                        rvCategories.post {
                            rvCategories.scrollToPosition(position)
                            rvCategories.post {
                                try {
                                    rvCategories.findViewHolderForAdapterPosition(position)?.itemView?.requestFocus()
                                    didRestoreFocusForThisView = true
                                } finally {
                                    com.tvonnet.debridxtreamiptv.utils.FocusCoordinator.release("LIVE_GRID")
                                }
                            }
                        }
                    }
                    return
                }
            }
            com.tvonnet.debridxtreamiptv.utils.FocusCoordinator.requestFocus("LIVE_GRID") {
                rvCategories.post {
                    try {
                        didRestoreFocusForThisView = cc.focusSelectedCategoryItem()
                    } finally {
                        com.tvonnet.debridxtreamiptv.utils.FocusCoordinator.release("LIVE_GRID")
                    }
                }
            }
        }
    }

    private fun restoreFocusGrid() {
        if (didRestoreFocusForThisView) return
        val state = viewModel.uiState.value
        val position = state.lastFocusedChannelPosition ?: 0
        focusChannelItem(position, markRestored = true)
    }

    private fun focusChannelItem(
        position: Int,
        markRestored: Boolean = false,
        attempt: Int = 0
    ) {
        if (!isAdded || channelPagingAdapter.itemCount <= 0) return
        val safePosition = position.coerceIn(0, channelPagingAdapter.itemCount - 1)
        com.tvonnet.debridxtreamiptv.utils.FocusCoordinator.requestFocus("LIVE_GRID") {
            rvChannels.post {
                if (!isAdded) {
                    com.tvonnet.debridxtreamiptv.utils.FocusCoordinator.release("LIVE_GRID")
                    return@post
                }
                rvChannels.scrollToPosition(safePosition)
                rvChannels.postDelayed({
                    if (!isAdded) {
                        com.tvonnet.debridxtreamiptv.utils.FocusCoordinator.release("LIVE_GRID")
                        return@postDelayed
                    }
                    try {
                        val itemView = rvChannels.findViewHolderForAdapterPosition(safePosition)?.itemView
                        val focused = itemView?.requestFocus() == true
                        if (focused) {
                            pendingChannelFocusPosition = null
                            if (markRestored) didRestoreFocusForThisView = true
                            categoryController?.unblockCategoryFocusAfterRestore()
                        } else if (attempt < CHANNEL_FOCUS_RETRY_COUNT) {
                            focusChannelItem(safePosition, markRestored, attempt + 1)
                        } else {
                            pendingChannelFocusPosition = null
                            categoryController?.unblockCategoryFocusAfterRestore()
                        }
                    } finally {
                        com.tvonnet.debridxtreamiptv.utils.FocusCoordinator.release("LIVE_GRID")
                    }
                }, CHANNEL_FOCUS_RETRY_DELAY_MS)
            }
        }
    }

    private fun moveChannelFocusBy(delta: Int): Boolean {
        val itemCount = channelPagingAdapter.itemCount
        if (itemCount <= 0) return true

        val currentPosition = currentFocusedChannelPosition()
            ?: viewModel.uiState.value.lastFocusedChannelPosition
            ?: (rvChannels.layoutManager as? LinearLayoutManager)?.findFirstVisibleItemPosition()
            ?: 0
        if (currentPosition == RecyclerView.NO_POSITION) return true

        val basePosition = pendingChannelFocusPosition ?: currentPosition
        val targetPosition = (basePosition + delta).coerceIn(0, itemCount - 1)
        if (targetPosition == basePosition) return true

        pendingChannelFocusPosition = targetPosition
        focusChannelItem(targetPosition)
        return true
    }

    private fun handleChannelItemKey(position: Int, keyCode: Int, event: KeyEvent): Boolean {
        if (event.action != KeyEvent.ACTION_DOWN) return false
        return when (keyCode) {
            KeyEvent.KEYCODE_DPAD_UP -> {
                if (position == 0) {
                    pendingChannelFocusPosition = null
                    categoryController?.focusCategoryStrip() == true
                } else {
                    moveChannelFocusFrom(position, delta = -1)
                }
            }
            KeyEvent.KEYCODE_DPAD_DOWN -> moveChannelFocusFrom(position, delta = 1)
            KeyEvent.KEYCODE_DPAD_LEFT -> {
                pendingChannelFocusPosition = null
                focusNavRail()
            }
            KeyEvent.KEYCODE_DPAD_RIGHT -> btnWatch?.requestFocus() == true
            in KeyEvent.KEYCODE_0..KeyEvent.KEYCODE_9 ->
                onQuickJumpDigit(position, keyCode - KeyEvent.KEYCODE_0)
            in KeyEvent.KEYCODE_A..KeyEvent.KEYCODE_Z ->
                onQuickJumpLetter(position, ('a' + (keyCode - KeyEvent.KEYCODE_A)))
            else -> false
        }
    }

    // ── Quick-jump: number-zap + A–Z type-ahead ──────────────────────────────
    // Both need a remote/keyboard with number or letter keys; on a plain D-pad they're inert.

    /** Digits accumulate into a channel number; after a short pause we jump to that row. */
    private fun onQuickJumpDigit(fromPosition: Int, digit: Int): Boolean {
        val itemCount = channelPagingAdapter.itemCount
        if (itemCount <= 0) return true
        if (quickJumpBuffer.isEmpty() && digit == 0) return true // ignore leading zeros
        if (quickJumpBuffer.length >= 4) quickJumpBuffer.setLength(0)
        quickJumpBuffer.append(digit)
        showQuickJumpHud(quickJumpBuffer.toString())
        quickJumpJob?.cancel()
        quickJumpJob = viewLifecycleOwner.lifecycleScope.launch {
            delay(QUICK_JUMP_COMMIT_MS)
            commitNumberJump()
        }
        return true
    }

    private fun commitNumberJump() {
        val number = quickJumpBuffer.toString().toIntOrNull()
        quickJumpBuffer.setLength(0)
        hideQuickJumpHud()
        if (number == null) return
        val target = (number - 1).coerceIn(0, channelPagingAdapter.itemCount - 1)
        pendingChannelFocusPosition = target
        focusChannelItem(target)
    }

    /** Type-ahead: jump to the next channel whose cleaned name starts with [letter], wrapping. */
    private fun onQuickJumpLetter(fromPosition: Int, letter: Char): Boolean {
        // A letter ends any pending number entry.
        quickJumpJob?.cancel()
        quickJumpBuffer.setLength(0)

        val items = channelPagingAdapter.snapshot().items
        if (items.isEmpty()) return true
        val size = items.size
        // Scan forward from the row after the current one, wrapping around.
        for (offset in 1..size) {
            val idx = (fromPosition + offset) % size
            if (channelFirstLetter(items[idx].name) == letter) {
                showQuickJumpHud(letter.uppercase())
                quickJumpJob?.cancel()
                quickJumpJob = viewLifecycleOwner.lifecycleScope.launch {
                    delay(QUICK_JUMP_LETTER_MS)
                    hideQuickJumpHud()
                }
                pendingChannelFocusPosition = idx
                focusChannelItem(idx)
                return true
            }
        }
        return true
    }

    /** First alphabetic character of a channel name, provider tags (e.g. "|UK|") stripped. */
    private fun channelFirstLetter(name: String?): Char? =
        com.tvonnet.debridxtreamiptv.util.MediaTitleCleaner.clean(name)
            .firstOrNull { it.isLetter() }
            ?.lowercaseChar()

    private fun showQuickJumpHud(text: String) {
        tvQuickJump?.apply {
            this.text = text
            isVisible = true
        }
    }

    private fun hideQuickJumpHud() {
        tvQuickJump?.isVisible = false
    }

    private fun moveChannelFocusFrom(position: Int, delta: Int): Boolean {
        val itemCount = channelPagingAdapter.itemCount
        if (itemCount <= 0) return true

        val basePosition = pendingChannelFocusPosition ?: position
        val targetPosition = (basePosition + delta).coerceIn(0, itemCount - 1)
        if (targetPosition == basePosition) return true

        pendingChannelFocusPosition = targetPosition
        focusChannelItem(targetPosition)
        return true
    }

    private fun currentFocusedChannelPosition(): Int? {
        val focused = rvChannels.findFocus() ?: return null
        val holder = rvChannels.findContainingViewHolder(focused) ?: return null
        return holder.bindingAdapterPosition.takeIf { it != RecyclerView.NO_POSITION }
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
            epgController?.updatePreviewEpg(stream)
            viewModel.onEvent(LiveEvent.RememberPreviewStream(stream))
            updateFavoriteButtonState(stream)
        }
    }
    
    /**
     * Launch fullscreen player
     */
    private fun launchFullscreen(stream: XtreamStream) {
        viewModel.onEvent(LiveEvent.RememberFullscreenLaunch(stream))
        viewLifecycleOwner.lifecycleScope.launch {
            val credentialsPrefs = CredentialsPreferences(requireContext())
            val serverUrl = credentialsPrefs.getServerUrl() ?: return@launch
            val username = credentialsPrefs.getUsername() ?: return@launch
            val password = credentialsPrefs.getPassword() ?: return@launch
            val categoryId = stream.category_id ?: viewModel.uiState.value.selectedCategoryId
            val streamUrl = stream.toLiveStreamUrl(serverUrl, username, password)
            val channelIds = resolveLiveChannelIds(categoryId, stream.stream_id)

            val intent = PlayerActivity.createIntent(
                context = requireContext(),
                streamUrl = streamUrl,
                title = stream.name ?: getString(R.string.player_epg_channel_unknown),
                channelName = stream.name,
                channelLogo = com.tvonnet.debridxtreamiptv.util.GlobalConfig.resolveIconUrl(stream.stream_icon),
                epgChannelId = stream.epg_channel_id?.takeIf { it.isNotBlank() } ?: stream.stream_id?.toString(),
                contentId = stream.stream_id ?: streamUrl,
                contentType = ContentType.LIVE_TV,
                posterUrl = com.tvonnet.debridxtreamiptv.util.GlobalConfig.resolveIconUrl(stream.stream_icon),
                liveCategoryId = categoryId,
                liveChannelIds = channelIds.takeIf { it.isNotEmpty() },
                baseServerUrl = serverUrl
            )
            val options = ActivityOptionsCompat.makeCustomAnimation(
                requireContext(),
                R.anim.fade_in,
                R.anim.fade_out
            )
            // Tear down the preview's live connection synchronously BEFORE PlayerActivity
            // opens its own. The account is max_connections=1 — lifecycle pause only
            // pauses the preview player, it does not close the socket, so without this
            // the fullscreen session and the (still-connected) preview would be two
            // simultaneous live connections and the server rejects the second. The
            // classic screen's preview is muted/secondary, so a reconnect on return is
            // acceptable (unlike the guide's shared-player hand-off).
            previewPlayerPanel?.releasePlayer()
            livePlayerLauncher.launch(intent, options)
        }
    }

    private fun handleLivePlayerResult(resultCode: Int, data: Intent?) {
        if (resultCode != Activity.RESULT_OK || data == null) return

        val streamId = data.getStringExtra(PlayerActivity.EXTRA_LIVE_RETURN_CHANNEL_ID)
            ?.takeIf { it.isNotBlank() }
            ?: return
        val streamUrl = data.getStringExtra(PlayerActivity.EXTRA_LIVE_RETURN_STREAM_URL)
            ?.takeIf { it.isNotBlank() }
        val channelName = data.getStringExtra(PlayerActivity.EXTRA_LIVE_RETURN_CHANNEL_NAME)
            ?.takeIf { it.isNotBlank() }
        val logoUrl = data.getStringExtra(PlayerActivity.EXTRA_LIVE_RETURN_CHANNEL_LOGO)
            ?.takeIf { it.isNotBlank() }
        val epgChannelId = data.getStringExtra(PlayerActivity.EXTRA_LIVE_RETURN_EPG_CHANNEL_ID)
            ?.takeIf { it.isNotBlank() }
        val categoryId = data.getStringExtra(PlayerActivity.EXTRA_LIVE_RETURN_CATEGORY_ID)
            ?: viewModel.uiState.value.selectedCategoryId

        val returnedStream = XtreamStream(
            num = null,
            name = channelName,
            stream_type = "live",
            stream_id = streamId,
            stream_icon = logoUrl,
            epg_channel_id = epgChannelId,
            added = null,
            category_id = categoryId,
            category_ids = null,
            container_extension = null,
            custom_sid = null,
            direct_source = streamUrl,
            tv_archive = null,
            tv_archive_duration = null
        )

        didRestorePreviewForThisView = true
        previewPlayerPanel?.play(returnedStream, streamUrl)
        epgController?.updatePreviewEpg(returnedStream)
        updateFavoriteButtonState(returnedStream)
        viewModel.onEvent(LiveEvent.RememberPreviewStream(returnedStream))

        val index = channelPagingAdapter.snapshot().items.indexOfFirst { it.stream_id == streamId }
        if (index >= 0) {
            focusChannelItem(index, markRestored = true)
        } else {
            restoreChannelFocusIfNeeded()
        }
    }

    private suspend fun resolveLiveChannelIds(
        categoryId: String?,
        currentStreamId: String?
    ): ArrayList<String> {
        val snapshotIds = channelPagingAdapter.snapshot().items.mapNotNull { it.stream_id }.distinct()
        val cachedIds = if (!categoryId.isNullOrBlank() && categoryId != FAVORITES_CATEGORY_ID) {
            withContext(Dispatchers.IO) {
                repository.getCachedLiveStreams(categoryId)
                    ?.mapNotNull { it.stream_id }
                    .orEmpty()
            }
        } else {
            emptyList()
        }

        val merged = linkedSetOf<String>()
        if (cachedIds.isNotEmpty()) {
            cachedIds.forEach { merged.add(it) }
        }
        snapshotIds.forEach { merged.add(it) }
        currentStreamId?.let { merged.add(it) }
        return ArrayList(merged)
    }

    private fun restoreChannelFocusIfNeeded() {
        if (didRestoreFocusForThisView) return
        val state = viewModel.uiState.value
        if (state.lastFocusArea != LiveFocusArea.CHANNELS) return

        val targetStreamId = state.lastFocusedChannelId ?: state.lastPlayedChannelId ?: return
        val index = channelPagingAdapter.snapshot().items.indexOfFirst { it.stream_id == targetStreamId }
        if (index < 0) return

        focusChannelItem(index, markRestored = true)
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
                // Always keep existing channels visible during loading to preserve focus.
                // The loading overlay will appear on top.
                showLoading(getString(R.string.live_loading_channels), keepChannelsVisible = true)
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
                    epgController?.warmVisibleEpgCache()
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

    /** v2's header is just a clock — the old top bar's weather/profile/search chrome is gone. */
    private fun setupNavRail(view: View) {
        navRail = LiveNavRail(
            host = this,
            root = view,
            onDpadRight = {
                focusChannelItem(viewModel.uiState.value.lastFocusedChannelPosition ?: 0)
                true
            }
        ).also { it.setup() }
    }

    private fun focusNavRail(): Boolean = navRail?.focusActiveItem() == true

    private fun isFirstChannelFocused(): Boolean = currentFocusedChannelPosition() == 0

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
