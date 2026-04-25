package com.tvonnet.debridxtreamiptv.ui.debrid

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import android.view.animation.OvershootInterpolator
import android.view.animation.DecelerateInterpolator
import android.view.animation.AccelerateDecelerateInterpolator
import com.tvonnet.debridxtreamiptv.R
import com.tvonnet.debridxtreamiptv.utils.FocusCoordinator
import com.tvonnet.debridxtreamiptv.utils.FocusMemoryManager
import com.tvonnet.debridxtreamiptv.data.onFailure
import com.tvonnet.debridxtreamiptv.data.onSuccess
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.launch
import javax.inject.Inject
import android.widget.ImageView
import com.bumptech.glide.Glide
import com.bumptech.glide.load.resource.drawable.DrawableTransitionOptions
import androidx.core.content.ContextCompat
import androidx.core.content.res.ResourcesCompat

/**
 * Debrid section fragment - main hub for Real-Debrid content browsing
 * 
 * Features:
 * - Device-code authentication flow
 * - Trending Movies/Series rows
 * - Continue Watching integration
 * - My Library
 * - Customizable content rows
 */
@AndroidEntryPoint
class DebridFragment : Fragment() {
    
    private val viewModel: DebridViewModel by viewModels()

    @Inject
    lateinit var playbackResolver: com.tvonnet.debridxtreamiptv.data.debrid.repository.PlaybackResolver
    
    // UI components
    private lateinit var progressBar: ProgressBar
    private lateinit var errorContainer: View
    private lateinit var errorText: TextView
    private lateinit var loginPrompt: View
    private lateinit var contentArea: View
    private lateinit var rvDebridRows: RecyclerView
    private lateinit var ivBackgroundBackdrop: ImageView
    private lateinit var layoutResolutionOverlay: View
    
    // Sidebar components
    private var sidebarContainer: View? = null
    private var navItemSearch: View? = null
    private var navItemHome: View? = null
    private var navItemDiscover: View? = null
    private var navItemLibrary: View? = null
    private var navItemMovies: View? = null
    private var navItemSeries: View? = null
    private var sidebarBrandLogo: View? = null
    
    private var isSidebarExpanded = false
    private var sidebarFocusController: SidebarFocusController? = null
    
    // Focus Persistence State
    private var lastFocusedRowIndex = 0
    private var lastFocusedItemIndex = 0
    private var lastFocusedItemId: String? = null
    private var activeNavItemId: Int = R.id.nav_item_home
    
    // Adapter
    private lateinit var debridRowsAdapter: DebridRowsAdapter
    
    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        return inflater.inflate(R.layout.fragment_debrid, container, false)
    }
    
    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        
        initializeViews(view)
        setupRecyclerView()
        observeViewModel()
        
        // Check auth status and load content or show login
        viewModel.checkAuthStatus()
        
        // Phase 3: Initial Sidebar Focus
        navItemHome?.post {
            navItemHome?.requestFocus()
        }
    }
    
    override fun onResume() {
        super.onResume()
        // Phase 3: Restore focus on resume
        FocusMemoryManager.restoreFocus(rvDebridRows)
        // Tier 2: Atomic Continue Watching sync — refresh the CW row instantly on return
        // from PlayerActivity so progress/position updates are visible immediately.
        viewModel.refreshContinueWatching()
    }

    override fun onPause() {
        super.onPause()
        // Phase 3: Save focus on pause
        FocusMemoryManager.saveFocus(rvDebridRows)
        FocusCoordinator.release("DEBRID_HOME")
    }
    
    private fun initializeViews(view: View) {
        val tag = "DebridFragment"
        try {
            progressBar = view.findViewById(R.id.progress_bar)
            errorContainer = view.findViewById(R.id.error_container)
            errorText = view.findViewById(R.id.tv_error)
            loginPrompt = view.findViewById(R.id.login_prompt_container)
            contentArea = view.findViewById(R.id.content_area)
            rvDebridRows = view.findViewById(R.id.rv_debrid_rows)
            ivBackgroundBackdrop = view.findViewById(R.id.iv_background_backdrop)
            layoutResolutionOverlay = view.findViewById(R.id.layout_resolution_overlay)
            
            // Sidebar Initialization
            sidebarContainer = view.findViewById(R.id.debrid_sidebar)
            navItemSearch = view.findViewById(R.id.nav_item_search)
            navItemHome = view.findViewById(R.id.nav_item_home)
            navItemDiscover = view.findViewById(R.id.nav_item_discover)
            navItemLibrary = view.findViewById(R.id.nav_item_library)
            navItemMovies = view.findViewById(R.id.nav_item_movies)
            navItemSeries = view.findViewById(R.id.nav_item_series)
            sidebarBrandLogo = view.findViewById(R.id.sidebar_brand_logo)
            
            // Initialize Sidebar Focus Controller
            val focusPill = view.findViewById<View>(R.id.sidebar_focus_pill)
            if (sidebarContainer != null && focusPill != null) {
                sidebarFocusController = SidebarFocusController(
                    sidebarContainer!!,
                    focusPill,
                    ContextCompat.getColor(requireContext(), R.color.white),
                    ContextCompat.getColor(requireContext(), R.color.sidebar_text_muted)
                )
            }
            
            setupSidebar()
            
            // Setup login button
            view.findViewById<View>(R.id.btn_login_debrid)?.setOnClickListener {
                navigateToLogin()
            }
            view.findViewById<View>(R.id.btn_retry_debrid)?.setOnClickListener {
                viewModel.loadCatalog(force = true)
            }
        } catch (e: Exception) {
            android.util.Log.e(tag, "Error in initializeViews: ${e.message}", e)
        }
    }
    
    private fun setupRecyclerView() {
        debridRowsAdapter = DebridRowsAdapter(
            onItemClick = { item -> 
                saveFocusState(item) // Track state before navigation
                onContentItemClick(item) 
            },
            onItemFocused = { item -> 
                updateFocusMemory(item)
                updateBackdrop(item) 
            },
            onRowLoadMore = { rowId -> viewModel.loadNextPage(rowId) },
            onLeftBoundary = { returnToSidebar() }
        )
        
        rvDebridRows.apply {
            layoutManager = LinearLayoutManager(context)
            adapter = debridRowsAdapter
            itemAnimator = null // Phase 3: Disable animator for focus stability
            setHasFixedSize(true)
        }
    }

    private fun setupSidebar() {
        val context = context ?: return
        
        // 1. Configure Item Visuals (Icon + Label)
        configureSidebarItem(navItemSearch, R.drawable.ic_search, "Search")
        configureSidebarItem(navItemHome, R.drawable.ic_home, "Home")
        configureSidebarItem(navItemDiscover, R.drawable.ic_movie_filter_white_24dp, "Discover")
        configureSidebarItem(navItemLibrary, R.drawable.ic_dns, "Library")
        configureSidebarItem(navItemMovies, R.drawable.ic_movie, "Movies")
        configureSidebarItem(navItemSeries, R.drawable.ic_series, "Series")
        
        // 2. Click Listeners
        navItemSearch?.setOnClickListener {
            val intent = android.content.Intent(requireContext(), com.tvonnet.debridxtreamiptv.ui.debrid.search.DebridSearchActivity::class.java)
            startActivity(intent)
        }
        
        navItemHome?.setOnClickListener {
            // Already here, maybe scroll to top
            rvDebridRows.smoothScrollToPosition(0)
        }
        
        navItemDiscover?.setOnClickListener {
            val intent = com.tvonnet.debridxtreamiptv.ui.debrid.discover.DebridDiscoverActivity.createIntent(requireContext())
            startActivity(intent)
        }
        
        val comingSoonToast = {
            android.widget.Toast.makeText(requireContext(), "Coming Soon", android.widget.Toast.LENGTH_SHORT).show()
        }
        navItemLibrary?.setOnClickListener { comingSoonToast() }
        navItemMovies?.setOnClickListener { comingSoonToast() }
        navItemSeries?.setOnClickListener { comingSoonToast() }
        
        // 3. Focus Routing (D-Pad Logic)
        setupSidebarFocusRouting()
        
        // 4. Initial Selection (Dimmed highlight)
        view?.findViewById<View>(activeNavItemId)?.let {
            sidebarFocusController?.setSelected(it)
        }
    }

    private fun setupSidebarExpansionLogic() {
        // Redundant as configureSidebarItem now handles unique focus per-item
    }

    private fun animateSidebar(expand: Boolean) {
        if (isSidebarExpanded == expand) return
        isSidebarExpanded = expand
        
        // 80ms Delay logic for expansion to prevent flicker (as requested)
        val delay = if (expand) 80L else 0L
        
        sidebarContainer?.postDelayed({
            // Re-verify state after delay
            if (isSidebarExpanded != expand) return@postDelayed
            
            // Use Controller to toggle labels cleanly (alpha only, no layout change)
            val navItems = listOf(navItemSearch, navItemHome, navItemDiscover, navItemLibrary, navItemMovies, navItemSeries)
            sidebarFocusController?.updateLabelState(expand, navItems)
            
            // Toggle Branding Logo
            sidebarBrandLogo?.let { logo ->
                if (expand) {
                    logo.visibility = View.VISIBLE
                    logo.animate().alpha(1f).setDuration(200).start()
                } else {
                    logo.animate().alpha(0f).setDuration(150).withEndAction {
                        logo.visibility = View.GONE
                    }.start()
                }
            }
        }, delay)
    }

    private fun configureSidebarItem(itemView: View?, iconRes: Int, label: String) {
        itemView?.let { view ->
            val icon = view.findViewById<ImageView>(R.id.iv_icon)
            val text = view.findViewById<TextView>(R.id.tv_label)
            icon?.setImageResource(iconRes)
            text?.text = label
            
            // "Alive" Focus Interaction handled by SidebarFocusController
            view.onFocusChangeListener = View.OnFocusChangeListener { v, hasFocus ->
                // Check if we are moving WITHIN the sidebar or leaving it
                val currentFocus = view.rootView?.findFocus()
                val navItems = listOf(navItemSearch, navItemHome, navItemDiscover, navItemLibrary, navItemMovies, navItemSeries)
                val isEnteringSidebar = navItems.any { it == currentFocus }
                
                sidebarFocusController?.onFocusChanged(v, hasFocus, isEnteringSidebar)
                
                if (hasFocus) {
                    activeNavItemId = v.id // Update active destination
                    animateSidebar(true)
                } else {
                    // Optimized collapse check
                    if (!isEnteringSidebar) {
                        animateSidebar(false)
                    }
                }
            }
        }
    }

    private fun setupSidebarFocusRouting() {
        // Lateral Navigation: Sidebar -> Content Viewport (Unified deterministic memory)
        val lateralListener = View.OnKeyListener { v, keyCode, event ->
            if (event.action == android.view.KeyEvent.ACTION_DOWN && keyCode == android.view.KeyEvent.KEYCODE_DPAD_RIGHT) {
                restoreContentFocus()
                return@OnKeyListener true
            }
            false
        }
        
        navItemSearch?.setOnKeyListener(lateralListener)
        navItemHome?.setOnKeyListener(lateralListener)
        navItemDiscover?.setOnKeyListener(lateralListener)
        navItemLibrary?.setOnKeyListener(lateralListener)
        navItemMovies?.setOnKeyListener(lateralListener)
        navItemSeries?.setOnKeyListener(lateralListener)
        
        // Vertical Navigation: Sidebar boundary
        navItemSearch?.nextFocusUpId = navItemSearch?.id ?: View.NO_ID
        navItemSeries?.nextFocusDownId = navItemSeries?.id ?: View.NO_ID
        
        // Content -> Sidebar Routing
        // This is handled by spatial navigation mostly, but we can harden it if needed.
    }
    
    private fun observeViewModel() {
        viewLifecycleOwner.lifecycleScope.launch {
            viewModel.uiState.collect { state ->
                updateUI(state)
            }
        }
    }
    
    private fun updateUI(state: DebridUiState) {
        if (!isAdded || view == null) return
        
        try {
            when (state) {
                is DebridUiState.Loading -> {
                    progressBar.visibility = View.VISIBLE
                    errorContainer.visibility = View.GONE
                    loginPrompt.visibility = View.GONE
                    contentArea.visibility = View.GONE
                }
                is DebridUiState.NotAuthenticated -> {
                    progressBar.visibility = View.GONE
                    errorContainer.visibility = View.GONE
                    loginPrompt.visibility = View.VISIBLE
                    contentArea.visibility = View.GONE
                }
                is DebridUiState.Authenticated -> {
                    progressBar.visibility = View.GONE
                    errorContainer.visibility = View.GONE
                    loginPrompt.visibility = View.GONE
                    contentArea.visibility = View.VISIBLE
                    
                    // Load catalog content
                    viewModel.loadCatalog()
                }
                is DebridUiState.Content -> {
                    progressBar.visibility = View.GONE
                    errorContainer.visibility = View.GONE
                    loginPrompt.visibility = View.GONE
                    contentArea.visibility = View.VISIBLE
                    
                    debridRowsAdapter.updateRows(state.rows)
                    
                    // Unified Restoration: Only hijack if sidebar doesn't have focus
                    if (sidebarContainer?.hasFocus() != true) {
                        restoreContentFocus()
                    }
                }
                is DebridUiState.Error -> {
                    progressBar.visibility = View.GONE
                    errorContainer.visibility = View.VISIBLE
                    errorText.text = state.message
                    loginPrompt.visibility = View.GONE
                    contentArea.visibility = View.GONE
                }
            }
        } catch (e: Exception) {
            android.util.Log.e("DebridFragment", "Error updating UI: ${e.message}", e)
        }
    }
    
    private fun navigateToLogin() {
        // Navigate to DebridAuthFragment
        val authFragment = DebridAuthFragment()
        parentFragmentManager.beginTransaction()
            .replace(R.id.content_container, authFragment)
            .addToBackStack(null)
            .commit()
    }
    
    private fun onContentItemClick(item: DebridContentItem) {
        val context = requireContext()
        if (item.isContinueWatching && tryResumeDebrid(item)) {
            return
        }
        if (item.type == "movie") {
            val intent = android.content.Intent(context, com.tvonnet.debridxtreamiptv.ui.vod.MovieDetailActivity::class.java).apply {
                putExtra(com.tvonnet.debridxtreamiptv.ui.vod.MovieDetailActivity.EXTRA_MOVIE_ID, item.id)
                putExtra(com.tvonnet.debridxtreamiptv.ui.vod.MovieDetailActivity.EXTRA_MOVIE_NAME, item.title)
                putExtra(com.tvonnet.debridxtreamiptv.ui.vod.MovieDetailActivity.EXTRA_MOVIE_ICON, item.posterUrl)
                putExtra(com.tvonnet.debridxtreamiptv.ui.vod.MovieDetailActivity.EXTRA_MOVIE_BACKDROP, item.backdropUrl)
                putExtra(com.tvonnet.debridxtreamiptv.ui.vod.MovieDetailActivity.EXTRA_MOVIE_YEAR, item.year)
                putExtra(com.tvonnet.debridxtreamiptv.ui.vod.MovieDetailActivity.EXTRA_MOVIE_RATING, item.rating)
                putExtra(com.tvonnet.debridxtreamiptv.ui.vod.MovieDetailActivity.EXTRA_MOVIE_CATEGORY_ID, "debrid")
            }
            startActivity(intent)
        } else if (item.type == "show" || item.type == "series") {
            val intent = android.content.Intent(context, com.tvonnet.debridxtreamiptv.ui.series.SeriesDetailActivity::class.java).apply {
                putExtra(com.tvonnet.debridxtreamiptv.ui.series.SeriesDetailActivity.EXTRA_SERIES_ID, item.id)
                putExtra(com.tvonnet.debridxtreamiptv.ui.series.SeriesDetailActivity.EXTRA_SERIES_NAME, item.title)
                putExtra(com.tvonnet.debridxtreamiptv.ui.series.SeriesDetailActivity.EXTRA_SERIES_COVER, item.posterUrl)
                putExtra(com.tvonnet.debridxtreamiptv.ui.series.SeriesDetailActivity.EXTRA_IS_DEBRID, true)
            }
            startActivity(intent)
        } else {
            android.widget.Toast.makeText(context, "Unknown content type: ${item.type}", android.widget.Toast.LENGTH_SHORT).show()
        }
    }

    /**
     * Handles resume playback for Debrid "Continue Watching" items.
     *
     * Architecture (Stremio-Mode): Debrid links are **ephemeral** — they contain short-lived
     * tokens that expire after a few hours. Instead of playing the saved URL directly (which
     * leads to "Link Expired" errors), we ALWAYS re-resolve a fresh link via Real-Debrid
     * when metadata (infoHash / magnet) is available.
     *
     * @param item The Continue Watching item to resume.
     * @return true if this method handled the click, false to fall through to normal detail navigation.
     */
    private fun tryResumeDebrid(item: DebridContentItem): Boolean {
        val resumePosition = item.resumePositionMs ?: 0L

        // ALWAYS return true to intercept the click and handle asynchronously via Resolver
        viewLifecycleOwner.lifecycleScope.launch {
            layoutResolutionOverlay.visibility = View.VISIBLE
            val result = playbackResolver.resolve(
                source = item.source,
                streamUrl = item.streamUrl,
                isExpired = item.isExpired(),
                infoHash = item.debridInfoHash,
                magnet = item.debridMagnet,
                seasonNumber = item.seasonNumber,
                episodeNumber = item.episodeNumber,
                episodeTitle = item.episodeTitle
            )

            layoutResolutionOverlay.visibility = View.GONE
            
            when (result) {
                is com.tvonnet.debridxtreamiptv.data.debrid.repository.ResolutionResult.Success -> {
                    if (!isAdded) return@launch
                    launchPlayer(item, result.url, resumePosition)
                }
                is com.tvonnet.debridxtreamiptv.data.debrid.repository.ResolutionResult.RefreshRequired -> {
                    if (!isAdded) return@launch
                    android.util.Log.i("DebridResume", "Resolution failed/expired: falling back to details.")
                    navigateToDetail(item)
                }
                is com.tvonnet.debridxtreamiptv.data.debrid.repository.ResolutionResult.Error -> {
                    if (!isAdded) return@launch
                    Toast.makeText(requireContext(), result.message, Toast.LENGTH_LONG).show()
                }
            }
        }
        return true
    }

    private fun launchPlayer(item: DebridContentItem, url: String, resumePosition: Long) {
        val contentType = item.contentType
            ?: if (item.type == "series") com.tvonnet.debridxtreamiptv.data.model.ContentType.EPISODE
            else com.tvonnet.debridxtreamiptv.data.model.ContentType.MOVIE

        val intent = com.tvonnet.debridxtreamiptv.player.stabilized.PlayerActivity.createIntent(
            context = requireContext(),
            streamUrl = url,
            title = item.title,
            startPositionMs = resumePosition,
            contentId = item.tmdbId ?: item.id,
            contentType = contentType,
            playbackSource = if (item.source == "debrid") com.tvonnet.debridxtreamiptv.player.stabilized.PlaybackSource.DEBRID 
                            else com.tvonnet.debridxtreamiptv.player.stabilized.PlaybackSource.IPTV,
            posterUrl = item.posterUrl,
            backdropUrl = item.backdropUrl,
            tmdbId = item.tmdbId,
            imdbId = item.imdbId,
            seriesTitle = item.seriesTitle,
            episodeTitle = item.episodeTitle,
            seasonNumber = item.seasonNumber,
            episodeNumber = item.episodeNumber,
            debridInfoHash = item.debridInfoHash,
            debridMagnet = item.debridMagnet
        )
        startActivity(intent)
    }

    private fun navigateToDetail(item: DebridContentItem) {
        // Force immediate detail navigation (ignoring resume state)
        if (item.type == "movie") {
            val intent = android.content.Intent(requireContext(), com.tvonnet.debridxtreamiptv.ui.vod.MovieDetailActivity::class.java).apply {
                putExtra(com.tvonnet.debridxtreamiptv.ui.vod.MovieDetailActivity.EXTRA_MOVIE_ID, item.id)
                putExtra(com.tvonnet.debridxtreamiptv.ui.vod.MovieDetailActivity.EXTRA_MOVIE_NAME, item.title)
                putExtra(com.tvonnet.debridxtreamiptv.ui.vod.MovieDetailActivity.EXTRA_MOVIE_ICON, item.posterUrl)
                putExtra(com.tvonnet.debridxtreamiptv.ui.vod.MovieDetailActivity.EXTRA_MOVIE_BACKDROP, item.backdropUrl)
                putExtra(com.tvonnet.debridxtreamiptv.ui.vod.MovieDetailActivity.EXTRA_MOVIE_YEAR, item.year)
                putExtra(com.tvonnet.debridxtreamiptv.ui.vod.MovieDetailActivity.EXTRA_MOVIE_RATING, item.rating)
                putExtra(com.tvonnet.debridxtreamiptv.ui.vod.MovieDetailActivity.EXTRA_MOVIE_CATEGORY_ID, "debrid")
            }
            startActivity(intent)
        } else {
            val intent = android.content.Intent(requireContext(), com.tvonnet.debridxtreamiptv.ui.series.SeriesDetailActivity::class.java).apply {
                putExtra(com.tvonnet.debridxtreamiptv.ui.series.SeriesDetailActivity.EXTRA_SERIES_ID, item.id)
                putExtra(com.tvonnet.debridxtreamiptv.ui.series.SeriesDetailActivity.EXTRA_SERIES_NAME, item.title)
                putExtra(com.tvonnet.debridxtreamiptv.ui.series.SeriesDetailActivity.EXTRA_SERIES_COVER, item.posterUrl)
                putExtra(com.tvonnet.debridxtreamiptv.ui.series.SeriesDetailActivity.EXTRA_IS_DEBRID, true)
            }
            startActivity(intent)
        }
    }


    private fun updateBackdrop(item: DebridContentItem) {
        val imageUrl = item.backdropUrl ?: item.posterUrl
        if (!imageUrl.isNullOrBlank()) {
            ivBackgroundBackdrop.animate().alpha(0.6f).setDuration(300).start()
            Glide.with(this)
                .load(imageUrl)
                .transition(DrawableTransitionOptions.withCrossFade(600))
                .placeholder(android.R.color.transparent)
                .error(android.R.color.transparent)
                .into(ivBackgroundBackdrop)
        }
    }

    /**
     * Component 3: Consolidated Restoration and Boundary Handling
     */
    private fun restoreContentFocus() {
        if (!isAdded || view == null) return

        rvDebridRows.post {
            // Step 1: Attempt to find the saved Row
            val rowHolder = rvDebridRows.findViewHolderForAdapterPosition(lastFocusedRowIndex) as? DebridRowViewHolder
            if (rowHolder != null) {
                val horizontalRv = rowHolder.itemView.findViewById<RecyclerView>(R.id.rv_row_items)
                horizontalRv?.post {
                    // Step 2: Attempt to find the exact Item in that row
                    val itemHolder = horizontalRv.findViewHolderForAdapterPosition(lastFocusedItemIndex)
                    if (itemHolder != null && itemHolder.itemView.isFocusable) {
                        itemHolder.itemView.requestFocus()
                        return@post
                    }
                    
                    // Fallback to first item in row
                    horizontalRv.findViewHolderForAdapterPosition(0)?.itemView?.requestFocus()
                }
            } else {
                // Fallback to very first card if no memory valid
                val firstVisibleRow = rvDebridRows.findViewHolderForAdapterPosition(0) as? DebridRowViewHolder
                val firstContentRv = firstVisibleRow?.itemView?.findViewById<RecyclerView>(R.id.rv_row_items)
                firstContentRv?.post {
                    firstContentRv.findViewHolderForAdapterPosition(0)?.itemView?.requestFocus()
                }
            }
        }
    }

    private fun returnToSidebar() {
        view?.findViewById<View>(activeNavItemId)?.requestFocus()
    }

    private fun saveFocusState(item: DebridContentItem) {
        // Item is clicked, capture its identity
        lastFocusedItemId = item.id
    }

    private fun updateFocusMemory(item: DebridContentItem) {
        // Identify row and position for nested restoration
        for (i in 0 until debridRowsAdapter.itemCount) {
            val row = debridRowsAdapter.currentList[i]
            val index = row.items.indexOf(item)
            if (index != -1) {
                lastFocusedRowIndex = i
                lastFocusedItemIndex = index
                break
            }
        }
    }
}
