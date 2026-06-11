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
    private var sidebarBrandLogo: View? = null

    // Focus Persistence State
    private var lastFocusedRowIndex = 0
    private var lastFocusedItemIndex = 0
    private var lastFocusedItemId: String? = null
    private var activeNavItemId: Int = R.id.nav_item_discover
    private var pendingRestoreFocus = false

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

        savedInstanceState?.let {
            lastFocusedRowIndex = it.getInt("state_last_row_pos", 0)
            lastFocusedItemIndex = it.getInt("state_last_item_pos", 0)
            lastFocusedItemId = it.getString("state_last_item_id")
        }

        initializeViews(view)
        setupRecyclerView()
        observeViewModel()

        // Check auth status and load content or show login
        viewModel.checkAuthStatus()

        // Initial Sidebar Focus
        navItemDiscover?.post {
            navItemDiscover?.requestFocus()
        }
    }

    override fun onResume() {
        super.onResume()
        restoreFocusIfPossible()
        viewModel.refreshContinueWatching()
    }

    override fun onPause() {
        super.onPause()
        FocusMemoryManager.saveFocus(requireView())
        FocusCoordinator.release("DEBRID_RESTORE")
        pendingRestoreFocus = true
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putInt("state_last_row_pos", lastFocusedRowIndex)
        outState.putInt("state_last_item_pos", lastFocusedItemIndex)
        outState.putString("state_last_item_id", lastFocusedItemId)
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
            sidebarContainer = view.findViewById(R.id.sidebar_container)
            navItemSearch = view.findViewById(R.id.nav_item_search)
            navItemHome = view.findViewById(R.id.nav_item_home)
            navItemDiscover = view.findViewById(R.id.nav_item_discover)
            sidebarBrandLogo = view.findViewById(R.id.sidebar_brand_logo)

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
                updateHero(item)
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
        configureSidebarItem(navItemDiscover, R.drawable.ic_discover, "Discover")

        // 2. Click Listeners
        navItemSearch?.setOnClickListener {
            val intent = android.content.Intent(requireContext(), com.tvonnet.debridxtreamiptv.ui.debrid.search.DebridSearchActivity::class.java)
            startActivity(intent)
        }

        navItemHome?.setOnClickListener {
            rvDebridRows.smoothScrollToPosition(0)
            rvDebridRows.postDelayed({
                if (!isAdded) return@postDelayed
                val firstRow = rvDebridRows.findViewHolderForAdapterPosition(0) as? DebridRowViewHolder
                val firstContentRv = firstRow?.itemView?.findViewById<RecyclerView>(R.id.rv_row_items)
                firstContentRv?.findViewHolderForAdapterPosition(0)?.itemView?.requestFocus()
            }, 150)
        }

        navItemDiscover?.setOnClickListener {
            val intent = com.tvonnet.debridxtreamiptv.ui.debrid.discover.DebridDiscoverActivity
                .createIntent(requireContext())
            startActivity(intent)
        }

        // 3. Focus Routing (D-Pad Logic)
        setupSidebarFocusRouting()
    }

    private fun configureSidebarItem(itemView: View?, iconRes: Int, label: String) {
        itemView?.let { view ->
            val icon = view.findViewById<ImageView>(R.id.iv_icon)
            val text = view.findViewById<TextView>(R.id.tv_label)
            icon?.setImageResource(iconRes)
            text?.text = label
            view.contentDescription = label

            view.onFocusChangeListener = View.OnFocusChangeListener { v, hasFocus ->
                if (hasFocus) {
                    activeNavItemId = v.id // Update active destination
                    v.animate().scaleX(1.05f).scaleY(1.05f).setDuration(150).start()
                } else {
                    v.animate().scaleX(1.0f).scaleY(1.0f).setDuration(150).start()
                }
            }
        }
    }

    private fun setupSidebarFocusRouting() {
        // Lateral Navigation: Sidebar -> Content Viewport
        val lateralListener = View.OnKeyListener { v, keyCode, event ->
            if (event.action == android.view.KeyEvent.ACTION_DOWN && keyCode == android.view.KeyEvent.KEYCODE_DPAD_RIGHT) {
                restoreFocusIfPossible()
                return@OnKeyListener true
            }
            false
        }

        navItemSearch?.setOnKeyListener(lateralListener)
        navItemHome?.setOnKeyListener(lateralListener)
        navItemDiscover?.setOnKeyListener(lateralListener)

        // Vertical Navigation: Sidebar boundary
        navItemSearch?.nextFocusUpId = navItemSearch?.id ?: View.NO_ID
        navItemSearch?.nextFocusDownId = navItemHome?.id ?: View.NO_ID
        navItemHome?.nextFocusUpId = navItemSearch?.id ?: View.NO_ID
        navItemHome?.nextFocusDownId = navItemDiscover?.id ?: View.NO_ID
        navItemDiscover?.nextFocusUpId = navItemHome?.id ?: View.NO_ID
        navItemDiscover?.nextFocusDownId = navItemDiscover?.id ?: View.NO_ID
    }

    private fun scrollToDebridRow(possibleKeywords: List<String>, fallbackMessage: String) {
        val currentRows = debridRowsAdapter.currentList
        for (i in currentRows.indices) {
            val title = currentRows[i].title?.lowercase() ?: ""
            if (possibleKeywords.any { title.contains(it) }) {
                rvDebridRows.smoothScrollToPosition(i)
                rvDebridRows.postDelayed({
                    if (!isAdded) return@postDelayed
                    val rowHolder = rvDebridRows.findViewHolderForAdapterPosition(i) as? DebridRowViewHolder
                    val horizontalRv = rowHolder?.itemView?.findViewById<RecyclerView>(R.id.rv_row_items)
                    horizontalRv?.findViewHolderForAdapterPosition(0)?.itemView?.requestFocus()
                }, 150)
                return
            }
        }
        Toast.makeText(requireContext(), fallbackMessage, Toast.LENGTH_SHORT).show()
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
                }
                is DebridUiState.Content -> {
                    progressBar.visibility = View.GONE
                    errorContainer.visibility = View.GONE
                    loginPrompt.visibility = View.GONE
                    contentArea.visibility = View.VISIBLE

                    debridRowsAdapter.updateRows(state.rows)

                    // Unified Restoration: Only hijack if sidebar doesn't have focus
                    if (sidebarContainer?.hasFocus() != true) {
                        restoreFocusIfPossible()
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

    private fun updateHero(item: DebridContentItem) {
        val heroView = view?.findViewById<View>(R.id.debrid_hero) ?: return

        // Ensure hero is visible but completely passive (cannot steal focus)
        if (heroView.visibility != View.VISIBLE) {
            heroView.visibility = View.VISIBLE
        }
        heroView.isFocusable = false
        heroView.isFocusableInTouchMode = false
        heroView.isClickable = false

        val btnPrimary = heroView.findViewById<View>(R.id.btn_hero_primary)
        if (btnPrimary != null) {
            btnPrimary.visibility = View.GONE
            btnPrimary.isFocusable = false
            btnPrimary.isFocusableInTouchMode = false
            btnPrimary.isClickable = false
        }

        val tvTitle = heroView.findViewById<TextView>(R.id.tv_hero_title)
        val metadataRow = heroView.findViewById<View>(R.id.ll_hero_metadata)
        val tvRating = heroView.findViewById<TextView>(R.id.tv_hero_rating)
        val tvYear = heroView.findViewById<TextView>(R.id.tv_hero_year)
        val tvType = heroView.findViewById<TextView>(R.id.tv_hero_type)
        val tvQuality = heroView.findViewById<TextView>(R.id.tv_hero_quality)
        val tvSynopsis = heroView.findViewById<TextView>(R.id.tv_hero_synopsis)

        tvTitle?.text = item.title.trim().ifBlank { "Untitled" }

        val rating = item.rating?.trim().orEmpty()
        val hasRating = rating.isNotEmpty() && rating != "0" && rating != "0.0"
        tvRating?.visibility = if (hasRating) View.VISIBLE else View.GONE
        if (hasRating) {
            tvRating?.text = rating
        }

        val year = item.year?.trim().orEmpty()
        val hasYear = year.isNotEmpty()
        tvYear?.visibility = if (hasYear) View.VISIBLE else View.GONE
        if (hasYear) {
            tvYear?.text = year
        }

        val typeLabel = when (item.type?.trim()?.lowercase()) {
            "movie" -> "MOVIE"
            "series", "show" -> "SERIES"
            else -> null
        }
        val hasType = typeLabel != null
        tvType?.visibility = if (hasType) View.VISIBLE else View.GONE
        if (typeLabel != null) {
            tvType?.text = typeLabel
        }
        tvQuality?.visibility = View.GONE
        metadataRow?.visibility = if (hasRating || hasYear || hasType) View.VISIBLE else View.GONE

        val overview = item.overview?.trim().orEmpty()
        tvSynopsis?.visibility = if (overview.isNotEmpty()) View.VISIBLE else View.GONE
        if (overview.isNotEmpty()) {
            tvSynopsis?.text = overview
        }
    }

    /**
     * Component 3: Consolidated Restoration and Boundary Handling
     */
    private fun restoreFocusIfPossible() {
        if (!isAdded || view == null) return

        FocusCoordinator.requestFocus("DEBRID_RESTORE") {
            rvDebridRows.post {
                if (!isAdded) return@post

                // Step 1: Attempt to find the saved Row
                val safeRowIndex = if (lastFocusedRowIndex != RecyclerView.NO_POSITION) lastFocusedRowIndex else 0
                val rowHolder = rvDebridRows.findViewHolderForAdapterPosition(safeRowIndex) as? DebridRowViewHolder

                if (rowHolder != null) {
                    val horizontalRv = rowHolder.itemView.findViewById<RecyclerView>(R.id.rv_row_items)
                    horizontalRv?.post {
                        if (!isAdded) return@post

                        var restored = false
                        // Try by ID first
                        if (lastFocusedItemId != null) {
                            val rowData = debridRowsAdapter.currentList.getOrNull(safeRowIndex)
                            if (rowData != null) {
                                val indexById = rowData.items.indexOfFirst { it.id == lastFocusedItemId }
                                if (indexById != -1) {
                                    val itemHolder = horizontalRv.findViewHolderForAdapterPosition(indexById)
                                    if (itemHolder != null && itemHolder.itemView.isFocusable) {
                                        itemHolder.itemView.requestFocus()
                                        restored = true
                                    }
                                }
                            }
                        }

                        // Fallback to Index
                        if (!restored) {
                            val safeItemIndex = if (lastFocusedItemIndex != RecyclerView.NO_POSITION) lastFocusedItemIndex else 0
                            val itemHolder = horizontalRv.findViewHolderForAdapterPosition(safeItemIndex)
                            if (itemHolder != null && itemHolder.itemView.isFocusable) {
                                itemHolder.itemView.requestFocus()
                                restored = true
                            }
                        }

                        // Fallback to first item in row
                        if (!restored) {
                            horizontalRv.findViewHolderForAdapterPosition(0)?.itemView?.requestFocus()
                        }
                        pendingRestoreFocus = false
                    }
                } else {
                    // Fallback to very first card if no memory valid
                    val firstVisibleRow = rvDebridRows.findViewHolderForAdapterPosition(0) as? DebridRowViewHolder
                    val firstContentRv = firstVisibleRow?.itemView?.findViewById<RecyclerView>(R.id.rv_row_items)
                    if (firstContentRv != null) {
                        firstContentRv.post {
                            if (!isAdded) return@post
                            firstContentRv.findViewHolderForAdapterPosition(0)?.itemView?.requestFocus()
                            pendingRestoreFocus = false
                        }
                    } else {
                        // Fallback to sidebar
                        returnToSidebar()
                        pendingRestoreFocus = false
                    }
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
