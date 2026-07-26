package com.tvonnet.debridxtreamiptv.ui.live

import android.os.Bundle
import android.view.KeyEvent
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.activity.result.contract.ActivityResultContracts
import androidx.fragment.app.Fragment
import androidx.fragment.app.commit
import androidx.fragment.app.viewModels
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.paging.CombinedLoadStates
import androidx.paging.LoadState
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import okhttp3.OkHttpClient
import com.tvonnet.debridxtreamiptv.BuildConfig
import com.tvonnet.debridxtreamiptv.R
import com.tvonnet.debridxtreamiptv.data.local.entity.EpgEntity
import com.tvonnet.debridxtreamiptv.data.model.XtreamStream
import com.tvonnet.debridxtreamiptv.data.repository.XtreamRepository
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
    /** LF-5: favourites bus + toggles + preview favourite button (owns the favourite set + count). */
    private var favoritesController: LiveFavoritesController? = null
    /** LF-6: channel-grid focus + quick-jump (D-pad; owns pending-focus + quick-jump state). */
    private var channelFocusController: LiveChannelFocusController? = null
    /** LF-7: fullscreen playback launch + return-restore (release-before-launch landmine lives here). */
    private var playbackLauncher: LivePlaybackLauncher? = null
    private var btnWatch: View? = null
    private var didRestoreFocusForThisView = false
    private var didRestorePreviewForThisView = false
    private var lastChannelLoadStates: CombinedLoadStates? = null

    // Fragment scope coroutines - managed properly
    private var uiStateJob: Job? = null
    private var pagedChannelsJob: Job? = null
    private var loadStateJob: Job? = null
    private var navigationJob: Job? = null

    private var lastUiState: LiveUiState? = null

    private val livePlayerLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        playbackLauncher?.handleLivePlayerResult(result.resultCode, result.data)
    }

    // Paging adapter for channels
    private val channelPagingAdapter by lazy {
        ChannelPagingAdapter(
            onChannelClick = { stream ->
                viewModel.onEvent(LiveEvent.PlayChannel(stream))
            },
            onChannelLongClick = { stream ->
                favoritesController?.handleFavoriteLongPress(stream)
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
                favoritesController?.isFavorite(streamId) == true
            },
            onChannelFocused = { stream, position ->
                stream.stream_id?.let { viewModel.onEvent(LiveEvent.RememberChannelFocus(it, position)) }
            },
            onChannelKey = { position, keyCode, event ->
                channelFocusController?.handleChannelItemKey(position, keyCode, event) == true
            }
        ).apply {
            registerAdapterDataObserver(object : RecyclerView.AdapterDataObserver() {
                override fun onChanged() { channelFocusController?.restoreFocusGrid() }
                override fun onItemRangeInserted(p0: Int, p1: Int) { channelFocusController?.restoreFocusGrid() }
                override fun onItemRangeRemoved(p0: Int, p1: Int) { channelFocusController?.restoreFocusGrid() }
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
        btnWatch = view.findViewById(R.id.btn_fullscreen)

        // Loading/empty overlays for channels list
        llLoadingState = view.findViewById(R.id.ll_loading_state)
        tvLoadingMessage = view.findViewById(R.id.tv_loading_message)

        headerController = LiveHeaderController(
            fragment = this,
            tvHeaderCategory = view.findViewById(R.id.tv_category_name),
            tvHeaderChannelCount = view.findViewById(R.id.tv_channel_count),
            tvHeaderClock = view.findViewById(R.id.tv_header_clock),
            favoritesCount = { favoritesController?.favoritesCount ?: 0 },
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
            onFocusDownToChannels = { channelFocusController?.focusChannelItem(0) },
        )
        searchController?.setup()
        categoryController = LiveCategoryController(
            fragment = this,
            rvCategories = rvCategories,
            viewModel = viewModel,
            favoritesCount = { favoritesController?.favoritesCount ?: 0 },
            lastUiState = { lastUiState },
            onBeforeCategorySelect = { searchController?.collapseIfHasText() },
            onShowEmptyState = { message -> showEmptyState(message) },
            onShowLoading = { message -> showLoading(message) },
        )
        favoritesController = LiveFavoritesController(
            fragment = this,
            repository = repository,
            channelPagingAdapter = channelPagingAdapter,
            previewPanel = { previewPlayerPanel },
            onFavoritesChanged = { count ->
                val state = viewModel.uiState.value
                categoryController?.updateChannelCounts(
                    state.categoryChannelCounts + (FAVORITES_CATEGORY_ID to count)
                )
                if (state.selectedCategoryId == FAVORITES_CATEGORY_ID) {
                    headerController?.updateHeaderInfo(state)
                }
            },
        )
        channelFocusController = LiveChannelFocusController(
            fragment = this,
            viewModel = viewModel,
            rvChannels = rvChannels,
            channelPagingAdapter = channelPagingAdapter,
            tvQuickJump = view.findViewById(R.id.tv_quick_jump),
            onFocusNavRail = { focusNavRail() },
            onFocusCategoryStrip = { categoryController?.focusCategoryStrip() == true },
            onFocusWatch = { btnWatch?.requestFocus() == true },
            onUnblockCategoryFocus = { categoryController?.unblockCategoryFocusAfterRestore() },
            isFocusRestored = { didRestoreFocusForThisView },
            markFocusRestored = { didRestoreFocusForThisView = true },
        )
        playbackLauncher = LivePlaybackLauncher(
            fragment = this,
            viewModel = viewModel,
            repository = repository,
            channelPagingAdapter = channelPagingAdapter,
            previewPanel = { previewPlayerPanel },
            onLaunch = { intent, options -> livePlayerLauncher.launch(intent, options) },
            onUpdatePreviewEpg = { stream -> epgController?.updatePreviewEpg(stream) },
            onUpdateFavoriteButton = { stream -> favoritesController?.updateFavoriteButtonState(stream) },
            onFocusChannelAt = { index -> channelFocusController?.focusChannelItem(index, markRestored = true) },
            onRestoreChannelFocus = { channelFocusController?.restoreChannelFocusIfNeeded() },
            markPreviewRestored = { didRestorePreviewForThisView = true },
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
            onFocusChannel = { position -> channelFocusController?.focusChannelItem(position) },
            onUpdateFavoriteButton = { stream -> favoritesController?.updateFavoriteButtonState(stream) },
            isPreviewRestored = { didRestorePreviewForThisView },
            markPreviewRestored = { didRestorePreviewForThisView = true },
        )
        epgController?.setupEpgStrip()

        // Initialize PreviewPlayerPanel
        previewPlayerPanel = PreviewPlayerPanel(
            requireContext(),
            view,
            okHttpClient,
            onFullscreenClick = { stream -> playbackLauncher?.launchFullscreen(stream) },
            onFavoriteClick = { stream -> favoritesController?.togglePreviewFavorite(stream) }
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
                    channelFocusController?.focusChannelItem(viewModel.uiState.value.lastFocusedChannelPosition ?: 0)
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
                    if (channelFocusController?.isFirstChannelFocused() == true) (categoryController?.focusCategoryStrip() == true) else (channelFocusController?.moveChannelFocusBy(delta = -1) == true)
                }
                KeyEvent.KEYCODE_DPAD_DOWN -> {
                    channelFocusController?.moveChannelFocusBy(delta = 1) == true
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
        favoritesController?.observeFavorites()
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
            channelFocusController?.restoreFocusGrid()
        }
        epgController?.maybeRestorePreviewFromState(state)
        channelFocusController?.restoreChannelFocusIfNeeded()
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
            headerController?.cancel()
            searchController?.cancel()
            epgController?.cancel()
            categoryController?.clear()
            favoritesController?.clear()
            channelFocusController?.clear()

            // Clear job references
            uiStateJob = null
            pagedChannelsJob = null
            loadStateJob = null
            navigationJob = null
            headerController = null
            searchController = null
            epgController = null
            categoryController = null
            favoritesController = null
            channelFocusController = null
            playbackLauncher = null
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
            lastUiState = null
        } catch (e: Exception) {
            android.util.Log.e("LiveFragment", "Error clearing adapters", e)
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
                                playbackLauncher?.navigateToPlayer(stream)
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
                    channelFocusController?.restoreChannelFocusIfNeeded()
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
    
    /** v2's header is just a clock — the old top bar's weather/profile/search chrome is gone. */
    private fun setupNavRail(view: View) {
        navRail = LiveNavRail(
            host = this,
            root = view,
            onDpadRight = {
                channelFocusController?.focusChannelItem(viewModel.uiState.value.lastFocusedChannelPosition ?: 0)
                true
            }
        ).also { it.setup() }
    }

    private fun focusNavRail(): Boolean = navRail?.focusActiveItem() == true

}
