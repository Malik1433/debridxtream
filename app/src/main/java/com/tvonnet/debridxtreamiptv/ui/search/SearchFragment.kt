package com.tvonnet.debridxtreamiptv.ui.search

import android.os.Bundle
import android.view.KeyEvent
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import android.widget.ImageButton
import android.widget.ProgressBar
import android.widget.TextView
import androidx.core.widget.addTextChangedListener
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.tvonnet.debridxtreamiptv.R
import com.tvonnet.debridxtreamiptv.data.model.ContentType
import com.tvonnet.debridxtreamiptv.data.model.XtreamSeriesInfo
import com.tvonnet.debridxtreamiptv.data.model.XtreamStream
import com.tvonnet.debridxtreamiptv.data.model.XtreamVodInfo
import com.tvonnet.debridxtreamiptv.data.model.toLiveStreamUrl
import com.tvonnet.debridxtreamiptv.data.prefs.CredentialsPreferences
import com.tvonnet.debridxtreamiptv.features.seriesv2.ui.SeriesDetailFragmentV2
import com.tvonnet.debridxtreamiptv.features.vodv2.ui.MovieDetailFragmentV2
import com.tvonnet.debridxtreamiptv.player.stabilized.PlayerActivity
import com.tvonnet.debridxtreamiptv.utils.RecyclerViewAnimations
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.launch

/**
 * Week 9: Search Fragment
 * 
 * Features:
 * - Global search with keyboard input
 * - Recent searches display
 * - Categorized results (Live TV, VOD, Series)
 * - Android TV D-pad navigation support
 */
@AndroidEntryPoint
class SearchFragment : Fragment() {
    
    private val viewModel: SearchViewModel by viewModels()
    
    private lateinit var etSearchQuery: EditText
    private lateinit var btnClearQuery: ImageButton
    private lateinit var progressBar: ProgressBar
    private lateinit var tvEmptyState: TextView
    private lateinit var tvResultsCount: TextView
    private lateinit var tvBrowseHint: TextView
    
    // Recent searches
    private lateinit var tvRecentTitle: TextView
    private lateinit var rvRecentSearches: RecyclerView
    private lateinit var btnClearHistory: TextView
    private lateinit var recentSearchesAdapter: RecentSearchesAdapter
    
    // Results sections
    private lateinit var tvLiveTitle: TextView
    private lateinit var rvLiveResults: RecyclerView
    private lateinit var liveResultsAdapter: SearchLiveAdapter
    
    private lateinit var tvVodTitle: TextView
    private lateinit var rvVodResults: RecyclerView
    private lateinit var vodResultsAdapter: SearchVodAdapter
    
    private lateinit var tvSeriesTitle: TextView
    private lateinit var rvSeriesResults: RecyclerView
    private lateinit var seriesResultsAdapter: SearchSeriesAdapter

    private lateinit var tvProgramsTitle: TextView
    private lateinit var rvEpgResults: RecyclerView
    private lateinit var epgResultsAdapter: SearchEpgAdapter
    
    private lateinit var credentialsPrefs: CredentialsPreferences
    private var lastAutoFocusedQuery: String? = null

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        return inflater.inflate(R.layout.fragment_search, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        android.util.Log.d(TAG, "========================================")
        android.util.Log.d(TAG, "SearchFragment: onViewCreated")
        android.util.Log.d(TAG, "ViewModel: $viewModel")
        android.util.Log.d(TAG, "========================================")

        credentialsPrefs = CredentialsPreferences(requireContext())

        // Initialize repository with credentials
        initializeRepository()

        initViews(view)
        setupSearch()
        setupRecyclerViews()
        observeState()

        // Check for voice query from MainActivity
        arguments?.let { args ->
            val voiceQuery = args.getString("voice_query")
            if (!voiceQuery.isNullOrEmpty()) {
                android.util.Log.d(TAG, "Voice query received: '$voiceQuery'")
                etSearchQuery.setText(voiceQuery)
                etSearchQuery.setSelection(voiceQuery.length) // Move cursor to end
                viewModel.onEvent(SearchEvent.QueryChanged(voiceQuery))
            }
        }

        // Custom Back Handling: Return focus to search bar before exiting
        // Custom Back Handling: Return focus to search bar before exiting
        requireActivity().onBackPressedDispatcher.addCallback(viewLifecycleOwner, object : androidx.activity.OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (::etSearchQuery.isInitialized && !etSearchQuery.hasFocus() && viewModel.uiState.value.hasResults) {
                    // If results are showing and we are not in the search bar, move focus back to search bar
                    etSearchQuery.post {
                        etSearchQuery.post {
                            etSearchQuery.requestFocus()
                        }
                    }
                    if (::rvLiveResults.isInitialized) rvLiveResults.smoothScrollToPosition(0)
                    if (::rvVodResults.isInitialized) rvVodResults.smoothScrollToPosition(0)
                    if (::rvSeriesResults.isInitialized) rvSeriesResults.smoothScrollToPosition(0)
                } else {
                    // Let system handle exit (pop back stack)
                    isEnabled = false
                    requireActivity().onBackPressed()
                }
            }
        })

        android.util.Log.d(TAG, "SearchFragment setup complete!")
    }

    // Public method for MainActivity to set voice query
    fun setVoiceQuery(query: String) {
        android.util.Log.d(TAG, "Setting voice query: '$query'")
        if (::etSearchQuery.isInitialized) {
            etSearchQuery.setText(query)
            etSearchQuery.setSelection(query.length) // Move cursor to end
            viewModel.onEvent(SearchEvent.QueryChanged(query))
        }
    }
    
    private fun initializeRepository() {
        val serverUrl = credentialsPrefs.getServerUrl()
        val username = credentialsPrefs.getUsername()
        val password = credentialsPrefs.getPassword()
        
        android.util.Log.d(TAG, "Initializing repository: serverUrl=$serverUrl, username=$username")
        
        if (serverUrl != null && username != null && password != null) {
            // Repository initialization is handled by Hilt, but we need to ensure it's initialized
            android.util.Log.d(TAG, "Credentials found, repository should be initialized")
        } else {
            android.util.Log.e(TAG, "Missing credentials! Cannot search without data.")
        }
    }
    
    private fun initViews(view: View) {
        // Search bar
        etSearchQuery = view.findViewById(R.id.et_search_query)
        btnClearQuery = view.findViewById(R.id.btn_clear_query)
        progressBar = view.findViewById(R.id.progress_bar)
        tvEmptyState = view.findViewById(R.id.tv_empty_state)
        tvResultsCount = view.findViewById(R.id.tv_results_count)
        tvBrowseHint = view.findViewById(R.id.tv_browse_hint)
        
        // Recent searches
        tvRecentTitle = view.findViewById(R.id.tv_recent_title)
        rvRecentSearches = view.findViewById(R.id.rv_recent_searches)
        btnClearHistory = view.findViewById(R.id.btn_clear_history)
        
        // Results sections
        tvLiveTitle = view.findViewById(R.id.tv_live_title)
        rvLiveResults = view.findViewById(R.id.rv_live_results)
        
        tvVodTitle = view.findViewById(R.id.tv_vod_title)
        rvVodResults = view.findViewById(R.id.rv_vod_results)
        
        tvSeriesTitle = view.findViewById(R.id.tv_series_title)
        rvSeriesResults = view.findViewById(R.id.rv_series_results)

        tvProgramsTitle = view.findViewById(R.id.tv_programs_title)
        rvEpgResults = view.findViewById(R.id.rv_epg_results)
    }
    
    private fun setupSearch() {
        android.util.Log.d(TAG, "========================================")
        android.util.Log.d(TAG, "SearchFragment: setupSearch() called")
        android.util.Log.d(TAG, "ViewModel: ${viewModel}")
        android.util.Log.d(TAG, "========================================")
        
        // Text change listener with debouncing (handled by ViewModel)
        etSearchQuery.addTextChangedListener { text ->
            android.util.Log.d(TAG, "========================================")
            android.util.Log.d(TAG, "TEXT CHANGED: '$text'")
            android.util.Log.d(TAG, "Length: ${text?.length}")
            android.util.Log.d(TAG, "Calling viewModel.onEvent...")
            android.util.Log.d(TAG, "========================================")
            
            viewModel.onEvent(SearchEvent.QueryChanged(text.toString()))
            
            // Show/hide clear button
            btnClearQuery.visibility = if (text.isNullOrEmpty()) View.GONE else View.VISIBLE
        }
        
        // Clear query button
        btnClearQuery.setOnClickListener {
            etSearchQuery.text.clear()
            viewModel.onEvent(SearchEvent.ClearQuery)
        }
        
        // Clear history button
        btnClearHistory.setOnClickListener {
            viewModel.onEvent(SearchEvent.ClearHistory)
        }
        
        // Handle keyboard search action
        etSearchQuery.setOnEditorActionListener { v, actionId, event ->
            android.util.Log.d(TAG, "Editor action: actionId=$actionId")
            if (actionId == EditorInfo.IME_ACTION_SEARCH ||
                actionId == EditorInfo.IME_ACTION_DONE ||
                actionId == EditorInfo.IME_ACTION_NEXT ||
                (event != null && event.keyCode == KeyEvent.KEYCODE_ENTER && event.action == KeyEvent.ACTION_DOWN)
            ) {
                // Hide keyboard
                val imm = requireContext().getSystemService(android.content.Context.INPUT_METHOD_SERVICE) as android.view.inputmethod.InputMethodManager
                imm.hideSoftInputFromWindow(v.windowToken, 0)
                etSearchQuery.clearFocus()
                android.util.Log.d(TAG, "Keyboard hidden")

                // After submitting, move focus into results so the user can navigate with D-pad.
                view?.post {
                    maybeAutoFocusResults(viewModel.uiState.value, force = true)
                }
                true
            } else {
                false
            }
        }

        // D-pad UX: when the search field is focused, DOWN should move focus into results.
        etSearchQuery.setOnKeyListener { _, keyCode, event ->
            if (event.action != KeyEvent.ACTION_DOWN) return@setOnKeyListener false
            if (keyCode != KeyEvent.KEYCODE_DPAD_DOWN) return@setOnKeyListener false

            val state = viewModel.uiState.value
            if (state.isSearching || state.totalResults <= 0) return@setOnKeyListener false

            etSearchQuery.clearFocus()

            val target = when {
                rvLiveResults.visibility == View.VISIBLE && liveResultsAdapter.itemCount > 0 -> rvLiveResults
                rvVodResults.visibility == View.VISIBLE && vodResultsAdapter.itemCount > 0 -> rvVodResults
                rvSeriesResults.visibility == View.VISIBLE && seriesResultsAdapter.itemCount > 0 -> rvSeriesResults
                rvEpgResults.visibility == View.VISIBLE && epgResultsAdapter.itemCount > 0 -> rvEpgResults
                else -> null
            } ?: return@setOnKeyListener false

            target.post {
                target.post {
                    target.requestFocus()
                }
            }
            target.scrollToPosition(0)
            requestFocusOnFirstItem(target) {
                lastAutoFocusedQuery = state.query
            }
            true
        }
        
        // Request focus on search field
        etSearchQuery.post {
            etSearchQuery.post {
                etSearchQuery.requestFocus()
            }
        }
    }
    
    private fun setupRecyclerViews() {
        // Recent searches
        recentSearchesAdapter = RecentSearchesAdapter { query ->
            etSearchQuery.setText(query)
            viewModel.onEvent(SearchEvent.SelectRecentSearch(query))
        }
        rvRecentSearches.apply {
            layoutManager = LinearLayoutManager(context, LinearLayoutManager.HORIZONTAL, false)
            adapter = recentSearchesAdapter
        }
        
        // Live results
        liveResultsAdapter = SearchLiveAdapter { stream ->
            viewModel.onEvent(SearchEvent.SelectLiveStream(stream))
            playLiveStream(stream)
        }
        rvLiveResults.apply {
            layoutManager = GridLayoutManager(context, 4)
            val spacingPx = resources.getDimensionPixelSize(R.dimen.grid_spacing_standard)
            addItemDecoration(com.tvonnet.debridxtreamiptv.utils.GridSpacingItemDecoration(4, spacingPx, true))
            adapter = liveResultsAdapter
        }
        
        // VOD results
        vodResultsAdapter = SearchVodAdapter { vod ->
            viewModel.onEvent(SearchEvent.SelectVodStream(vod))
            openVodDetail(vod)
        }
        rvVodResults.apply {
            layoutManager = GridLayoutManager(context, 4)
            val spacingPx = resources.getDimensionPixelSize(R.dimen.grid_spacing_standard)
            addItemDecoration(com.tvonnet.debridxtreamiptv.utils.GridSpacingItemDecoration(4, spacingPx, true))
            adapter = vodResultsAdapter
        }
        
        // Series results
        seriesResultsAdapter = SearchSeriesAdapter { series ->
            viewModel.onEvent(SearchEvent.SelectSeries(series))
            openSeriesDetail(series)
            android.util.Log.d(TAG, "Series selected: ${series.name}")
        }
        rvSeriesResults.apply {
            layoutManager = GridLayoutManager(context, 4)
            val spacingPx = resources.getDimensionPixelSize(R.dimen.grid_spacing_standard)
            addItemDecoration(com.tvonnet.debridxtreamiptv.utils.GridSpacingItemDecoration(4, spacingPx, true))
            adapter = seriesResultsAdapter
        }

        // Program results
        epgResultsAdapter = SearchEpgAdapter { program ->
            viewModel.onEvent(SearchEvent.SelectEpgProgram(program))
            // When clicking a program, we play the channel associated with it.
            playLiveChannelById(program.channelId)
        }
        rvEpgResults.apply {
            layoutManager = GridLayoutManager(context, 4)
            val spacingPx = resources.getDimensionPixelSize(R.dimen.grid_spacing_standard)
            addItemDecoration(com.tvonnet.debridxtreamiptv.utils.GridSpacingItemDecoration(4, spacingPx, true))
            adapter = epgResultsAdapter
        }
        
        // Week 14: Apply smooth animations to all RecyclerViews
        RecyclerViewAnimations.applyAnimations(rvRecentSearches)
        RecyclerViewAnimations.applyAnimations(rvLiveResults)
        RecyclerViewAnimations.applyAnimations(rvVodResults)
        RecyclerViewAnimations.applyAnimations(rvSeriesResults)
        RecyclerViewAnimations.applyAnimations(rvEpgResults)
    }

    private fun openVodDetail(vod: XtreamVodInfo) {
        val fragment = MovieDetailFragmentV2.newInstance(
            Bundle().apply {
                putString("stream_id", vod.stream_id?.toString())
                putString("title", vod.name)
                putString("backdrop_url", vod.cover ?: vod.stream_icon)
                putString("poster_url", vod.stream_icon)
                putString("plot", vod.plot)
                putString("year", vod.releaseDate)
                putString("genre", vod.genre)
                putString("rating", vod.rating)
                putString("container_ext", vod.container_extension ?: "mp4")
                putString("direct_source", vod.direct_source)
                putString("trailer", vod.youtube_trailer)
            }
        )

        parentFragmentManager.beginTransaction()
            .replace(R.id.content_container, fragment)
            .addToBackStack(null)
            .commit()
    }

    private fun openSeriesDetail(series: XtreamSeriesInfo) {
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
    }
    
    private fun observeState() {
        android.util.Log.d(TAG, "========================================")
        android.util.Log.d(TAG, "SearchFragment: observeState() called")
        android.util.Log.d(TAG, "Starting StateFlow collection...")
        android.util.Log.d(TAG, "========================================")
        
        viewLifecycleOwner.lifecycleScope.launch {
            viewModel.uiState.collect { state ->
                android.util.Log.d(TAG, "========================================")
                android.util.Log.d(TAG, "STATE RECEIVED!")
                android.util.Log.d(TAG, "Query: '${state.query}'")
                android.util.Log.d(TAG, "Live results: ${state.liveResults.size}")
                android.util.Log.d(TAG, "VOD results: ${state.vodResults.size}")
                android.util.Log.d(TAG, "Series results: ${state.seriesResults.size}")
                android.util.Log.d(TAG, "Is searching: ${state.isSearching}")
                android.util.Log.d(TAG, "Error: ${state.error}")
                android.util.Log.d(TAG, "========================================")
                
                renderState(state)
            }
        }
    }
    
    private fun renderState(state: SearchUiState) {
        android.util.Log.d(TAG, ">>> renderState() called")
        android.util.Log.d(TAG, "    hasResults: ${state.hasResults}")
        android.util.Log.d(TAG, "    shouldShowEmptyState: ${state.shouldShowEmptyState}")
        android.util.Log.d(TAG, "    error: ${state.error}")
        
        // Loading state
        progressBar.visibility = if (state.isSearching) View.VISIBLE else View.GONE
        
        // Recent searches section
        val showRecentSearches = state.recentSearches.isNotEmpty() && state.query.isEmpty()
        tvRecentTitle.visibility = if (showRecentSearches) View.VISIBLE else View.GONE
        rvRecentSearches.visibility = if (showRecentSearches) View.VISIBLE else View.GONE
        btnClearHistory.visibility = if (showRecentSearches) View.VISIBLE else View.GONE
        recentSearchesAdapter.submitList(state.recentSearches)
        
        // Error state (show FIRST before empty state check)
        if (state.error != null) {
            android.util.Log.w(TAG, ">>> Showing ERROR: ${state.error}")
            tvEmptyState.visibility = View.VISIBLE
            tvEmptyState.text = state.error
        } else if (state.shouldShowEmptyState) {
            android.util.Log.d(TAG, ">>> Showing EMPTY STATE")
            tvEmptyState.visibility = View.VISIBLE
            tvEmptyState.text = getString(R.string.search_no_results, state.query)
        } else {
            tvEmptyState.visibility = View.GONE
        }
        
        // Results count
        if (state.hasResults) {
            android.util.Log.d(TAG, ">>> Showing RESULTS COUNT: ${state.totalResults}")
            tvResultsCount.visibility = View.VISIBLE
            tvResultsCount.text = getString(R.string.search_results_count, state.totalResults)
        } else {
            tvResultsCount.visibility = View.GONE
        }
        
        // Show hint if search was done but results are limited
        val showHint = state.query.length >= 2 && !state.isSearching && state.totalResults < 10
        tvBrowseHint.visibility = if (showHint) View.VISIBLE else View.GONE
        
        // Live TV results
        val showLive = state.liveResults.isNotEmpty()
        android.util.Log.d(TAG, ">>> Live TV: showLive=$showLive, count=${state.liveResults.size}")
        tvLiveTitle.visibility = if (showLive) View.VISIBLE else View.GONE
        rvLiveResults.visibility = if (showLive) View.VISIBLE else View.GONE
        if (showLive) {
            tvLiveTitle.text = getString(R.string.search_live_tv_results, state.liveResults.size)
            liveResultsAdapter.submitList(state.liveResults)
            android.util.Log.d(TAG, ">>> Live TV adapter updated with ${state.liveResults.size} items")
        }
        
        // VOD results
        val showVod = state.vodResults.isNotEmpty()
        tvVodTitle.visibility = if (showVod) View.VISIBLE else View.GONE
        rvVodResults.visibility = if (showVod) View.VISIBLE else View.GONE
        if (showVod) {
            tvVodTitle.text = getString(R.string.search_vod_results, state.vodResults.size)
            vodResultsAdapter.submitList(state.vodResults)
        }
        
        // Series results
        val showSeries = state.seriesResults.isNotEmpty()
        tvSeriesTitle.visibility = if (showSeries) View.VISIBLE else View.GONE
        rvSeriesResults.visibility = if (showSeries) View.VISIBLE else View.GONE
        if (showSeries) {
            tvSeriesTitle.text = getString(R.string.search_series_results, state.seriesResults.size)
            seriesResultsAdapter.submitList(state.seriesResults)
        }

        // EPG results
        val showEpg = state.epgResults.isNotEmpty()
        tvProgramsTitle.visibility = if (showEpg) View.VISIBLE else View.GONE
        rvEpgResults.visibility = if (showEpg) View.VISIBLE else View.GONE
        if (showEpg) {
            tvProgramsTitle.text = getString(R.string.search_programs_results, state.epgResults.size)
            epgResultsAdapter.submitList(state.epgResults)
        }
        
        // Error state
        if (state.error != null) {
            tvEmptyState.visibility = View.VISIBLE
            tvEmptyState.text = state.error
        }

        maybeAutoFocusResults(state)
    }

    private fun maybeAutoFocusResults(state: SearchUiState, force: Boolean = false) {
        if (!isAdded) return
        val root = view ?: return

        if (state.query.isBlank()) {
            lastAutoFocusedQuery = null
            return
        }

        if (state.isSearching) return
        if (state.totalResults <= 0) return

        if (!force && lastAutoFocusedQuery == state.query) return
        if (etSearchQuery.hasFocus()) return

        val currentFocus = root.findFocus()
        if (!force && currentFocus != null) {
            val alreadyInResults =
                currentFocus.isDescendantOf(rvLiveResults) ||
                    currentFocus.isDescendantOf(rvVodResults) ||
                    currentFocus.isDescendantOf(rvSeriesResults) ||
                    currentFocus.isDescendantOf(rvEpgResults)
            if (alreadyInResults) {
                lastAutoFocusedQuery = state.query
                return
            }
        }

        val target = when {
            rvLiveResults.visibility == View.VISIBLE && liveResultsAdapter.itemCount > 0 -> rvLiveResults
            rvVodResults.visibility == View.VISIBLE && vodResultsAdapter.itemCount > 0 -> rvVodResults
            rvSeriesResults.visibility == View.VISIBLE && seriesResultsAdapter.itemCount > 0 -> rvSeriesResults
            rvEpgResults.visibility == View.VISIBLE && epgResultsAdapter.itemCount > 0 -> rvEpgResults
            else -> null
        } ?: return

        target.scrollToPosition(0)
        requestFocusOnFirstItem(target) {
            lastAutoFocusedQuery = state.query
        }
    }

    private fun requestFocusOnFirstItem(recyclerView: RecyclerView, onFocused: (() -> Unit)? = null) {
        if (!isAdded) return
        if (recyclerView.adapter == null) return

        val existingFirst = recyclerView.findViewHolderForAdapterPosition(0)?.itemView
        if (existingFirst != null) {
            existingFirst.post {
                existingFirst.post {
                    existingFirst.requestFocus()
                }
            }
            onFocused?.invoke()
            return
        }

        val listener = object : RecyclerView.OnChildAttachStateChangeListener {
            override fun onChildViewAttachedToWindow(view: View) {
                val holder = recyclerView.getChildViewHolder(view)
                if (holder.bindingAdapterPosition != 0) return
                recyclerView.removeOnChildAttachStateChangeListener(this)
                view.post {
                    view.post {
                        view.requestFocus()
                    }
                }
                onFocused?.invoke()
            }

            override fun onChildViewDetachedFromWindow(view: View) = Unit
        }

        recyclerView.addOnChildAttachStateChangeListener(listener)
        recyclerView.postDelayed({
            recyclerView.removeOnChildAttachStateChangeListener(listener)
        }, 1500)
    }

    private fun View.isDescendantOf(ancestor: View): Boolean {
        var current: View? = this
        while (current != null) {
            if (current === ancestor) return true
            current = current.parent as? View
        }
        return false
    }
    
    private fun playLiveStream(stream: XtreamStream) {
        val serverUrl = credentialsPrefs.getServerUrl() ?: return
        val username = credentialsPrefs.getUsername() ?: return
        val password = credentialsPrefs.getPassword() ?: return
        
        launchPlayer(stream, serverUrl, username, password)
    }

    private fun playLiveChannelById(channelId: String) {
        val serverUrl = credentialsPrefs.getServerUrl() ?: return
        val username = credentialsPrefs.getUsername() ?: return
        val password = credentialsPrefs.getPassword() ?: return

        // We don't have the full XtreamStream object readily available without a DB lookup.
        // However, for playing, we mainly need the ID and credentials.
        // We can create a "skeleton" stream object or just construct the Intent directly.
        // To be safe and show correct info (Logo, Name), passing just the ID is suboptimal but functional for playback.
        // Ideally, we should fetch it, but to keep UI responsive, we'll blast the intent with what we have.
        // The PlayerActivity will just handle playing. Metadata might be missing initially.

        val streamUrl = toLiveStreamUrl(
            serverUrl,
            username,
            password,
            channelId
        )
        val intent = PlayerActivity.createIntent(
            context = requireContext(),
            streamUrl = streamUrl,
            title = "Channel $channelId", // Fallback name
            channelName = "Channel $channelId",
            channelLogo = null,
            epgChannelId = channelId,
            contentId = channelId,
            contentType = ContentType.LIVE_TV,
            posterUrl = null
        )
        startActivity(intent)
    }

    private fun launchPlayer(stream: XtreamStream, serverUrl: String, username: String, password: String) {
        val streamUrl = stream.toLiveStreamUrl(serverUrl, username, password)
        
        val intent = PlayerActivity.createIntent(
            context = requireContext(),
            streamUrl = streamUrl,
            title = stream.name ?: getString(R.string.player_epg_channel_unknown),
            channelName = stream.name,
            channelLogo = com.tvonnet.debridxtreamiptv.util.GlobalConfig.resolveIconUrl(stream.stream_icon),
            epgChannelId = stream.epg_channel_id?.takeIf { it.isNotBlank() } ?: stream.stream_id?.toString(),
            contentId = stream.stream_id ?: streamUrl,
            contentType = ContentType.LIVE_TV,
            posterUrl = com.tvonnet.debridxtreamiptv.util.GlobalConfig.resolveIconUrl(stream.stream_icon)
        )
        startActivity(intent)
    }
    
    private fun playVodStream(vod: XtreamVodInfo) {
        val serverUrl = credentialsPrefs.getServerUrl() ?: return
        val username = credentialsPrefs.getUsername() ?: return
        val password = credentialsPrefs.getPassword() ?: return
        
        val streamUrl = "$serverUrl/movie/$username/$password/${vod.stream_id}.${vod.container_extension ?: "mp4"}"
        
        val intent = PlayerActivity.createIntent(
            context = requireContext(),
            streamUrl = streamUrl,
            title = vod.name ?: "Movie",
            contentId = vod.stream_id ?: streamUrl,
            contentType = ContentType.MOVIE,
            posterUrl = com.tvonnet.debridxtreamiptv.util.GlobalConfig.resolveIconUrl(vod.stream_icon),
            backdropUrl = vod.cover
        )
        startActivity(intent)
    }
    
    companion object {
        private const val TAG = "SearchFragment"
    }
}
