package com.tvonnet.debridxtreamiptv.features.seriesv2.ui

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.bumptech.glide.Glide
import com.tvonnet.debridxtreamiptv.databinding.FragmentSeriesDetailV2Binding
import com.tvonnet.debridxtreamiptv.features.seriesv2.data.model.EpisodeEntityV2
import com.tvonnet.debridxtreamiptv.features.seriesv2.ui.model.SeriesDetailUiState
import com.tvonnet.debridxtreamiptv.features.seriesv2.ui.model.SeriesNavigationEvent
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

/**
 * IPTV Series Detail — cinematic layout matching "IPTV Series Detail.dc.html".
 *
 * Opening an episode presents the "SELECT STREAM" panel, which aggregates the same
 * show's streams across every Xtream playlist category it appears in.
 *
 * C4: the screen's surfaces are owned by four collaborators — [SeriesDetailHeaderUi] (title /
 * meta line / summary), [SeriesDetailActions] (the Play·Trailer·Favourite·Season row and its
 * popup), [SeriesEpisodesStripUi] (the strip, its grace-window loading state and focus memory)
 * and [SeriesStreamPanelUi] (the SELECT STREAM panel) — plus [SeriesDetailEnrichment] for the
 * TMDB/OMDb pass. What is left here is the fragment's own job: arguments, the observer wiring,
 * BACK, and launching the player.
 */
@AndroidEntryPoint
class SeriesDetailFragmentV2 : Fragment() {

    private var _binding: FragmentSeriesDetailV2Binding? = null
    private val binding get() = _binding!!

    @javax.inject.Inject
    lateinit var watchedStateRepository: com.tvonnet.debridxtreamiptv.data.repository.WatchedStateRepository

    @javax.inject.Inject
    lateinit var repository: com.tvonnet.debridxtreamiptv.data.repository.XtreamRepository

    // Used to resolve a YouTube trailer from TMDB when the Xtream series has none
    // (parity with the movie detail page, which enriches via TMDB).
    @javax.inject.Inject
    lateinit var tmdbRemoteDataSource: com.tvonnet.debridxtreamiptv.data.debrid.source.TmdbRemoteDataSource

    private val viewModel: SeriesDetailViewModelV2 by viewModels()

    private var header: SeriesDetailHeaderUi? = null
    private var actions: SeriesDetailActions? = null
    private var episodesStrip: SeriesEpisodesStripUi? = null
    private var streamPanel: SeriesStreamPanelUi? = null
    private var enrichment: SeriesDetailEnrichment? = null

    private var page: SeriesDetailPage? = null
    private var activeEpisode: EpisodeEntityV2? = null
    private var toastRunnable: Runnable? = null

    private val actionHost = object : SeriesDetailActions.Host {
        override fun resolvePlayTarget(): EpisodeEntityV2? =
            activeEpisode ?: episodesStrip?.firstEpisode()

        override fun onPlayEpisode(episode: EpisodeEntityV2) = viewModel.onEpisodeClicked(episode)

        override fun selectedSeason(): Int? = viewModel.selectedSeason.value

        override fun onSeasonSelected(seasonNum: Int) = viewModel.onSeasonSelected(seasonNum)

        override fun showToast(message: String) = this@SeriesDetailFragmentV2.showToast(message)
    }

    companion object {
        const val ARG_SERIES_ID = "series_id"
        const val ARG_TITLE = "title"
        const val ARG_BACKDROP_URL = "backdrop_url"
        const val ARG_POSTER_URL = "poster_url"
        const val ARG_TRAILER = "trailer"

        fun newInstance(
            seriesId: String,
            title: String?,
            backdropUrl: String?,
            posterUrl: String?,
            trailer: String? = null
        ): SeriesDetailFragmentV2 = SeriesDetailFragmentV2().apply {
            arguments = Bundle().apply {
                putString(ARG_SERIES_ID, seriesId)
                putString(ARG_TITLE, title)
                putString(ARG_BACKDROP_URL, backdropUrl)
                putString(ARG_POSTER_URL, posterUrl)
                putString(ARG_TRAILER, trailer)
            }
        }
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = FragmentSeriesDetailV2Binding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        // M16, same rule as M14b on Home: a D-pad legend ("◄ ► ▲ ▼ NAVIGATE | OK / ENTER SELECT")
        // is a 10-foot idiom the phone rulebook bans by name. The layout is shared, so it is hidden
        // here rather than removed — the TV still gets it.
        if (!resources.getBoolean(com.tvonnet.debridxtreamiptv.R.bool.ui_uses_dpad_focus)) {
            binding.layoutHintBar.visibility = View.GONE
            relocateActionsForTouch()
        }
        buildCollaborators()
        setupOptimisticUI()
        setupEpisodes()
        enrichment?.setupCredits()
        streamPanel?.setup()
        actions?.setupListeners()
        actions?.setupFocusAnimations()
        setupObservers()
        setupBackHandling()
    }

    private fun buildCollaborators() {
        val b = binding
        val pageState = SeriesDetailPage(
            seriesId = arguments?.getString(ARG_SERIES_ID),
            posterUrl = arguments?.getString(ARG_POSTER_URL),
            backdropUrl = arguments?.getString(ARG_BACKDROP_URL),
            isViewAlive = { _binding != null }
        )
        page = pageState
        header = SeriesDetailHeaderUi(b)
        enrichment = SeriesDetailEnrichment(
            binding = b,
            lifecycleOwner = viewLifecycleOwner,
            tmdbRemoteDataSource = tmdbRemoteDataSource,
            page = pageState,
            onTrailerResolved = { actions?.updateTrailerButtonState() }
        )
        episodesStrip = SeriesEpisodesStripUi(
            binding = b,
            page = pageState,
            onEpisodeClick = { episode -> viewModel.onEpisodeClicked(episode) },
            onEpisodeFocused = { episode ->
                activeEpisode = episode
                actions?.updatePlayButton(episode)
            }
        )
        actions = SeriesDetailActions(
            fragment = this,
            binding = b,
            repository = repository,
            watchedStateRepository = watchedStateRepository,
            page = pageState,
            host = actionHost
        )
        streamPanel = SeriesStreamPanelUi(
            binding = b,
            viewModel = viewModel,
            onStreamSelected = { option ->
                showToast("CONNECTING…")
                // Panel closes from the ViewModel once the stream resolves; on failure it
                // stays open so the user can pick another category.
                viewModel.onStreamSelected(option)
            }
        )
    }

    private fun setupOptimisticUI() {
        val args = arguments ?: return
        binding.tvTitle.text = args.getString(ARG_TITLE) ?: "Loading..."
        page?.trailer = args.getString(ARG_TRAILER)
        args.getString(ARG_BACKDROP_URL)?.takeIf { it.isNotEmpty() }?.let {
            Glide.with(this).load(it).dontAnimate().into(binding.ivBackdrop)
        }
        actions?.updateTrailerButtonState()
        // Enrich from the arg title on open — independent of get_series_info, so even
        // dead listings (no episodes) get cast/creator/runtime/ratings.
        enrichment?.enrichFromTmdb(args.getString(ARG_TITLE), null)
    }

    private fun setupEpisodes() {
        val adapter = episodesStrip?.setup() ?: return
        lifecycleScope.launch {
            adapter.loadStateFlow.collect { states -> episodesStrip?.onLoadStates(states) }
        }
    }

    /**
     * D6 (owner, 2026-08-09): on a phone the series page does not need a Play button — every
     * episode card in the strip already plays on one tap, and the button sat below the fold in the
     * scrolled column anyway. So on TOUCH ONLY: Play is gone, and Trailer + favourite move out of
     * the scrolled column into the always-visible season row. The views are REPARENTED, not
     * duplicated, so every existing binding/click listener keeps working; the TV never runs this.
     */
    private fun relocateActionsForTouch() {
        val d = resources.displayMetrics.density
        binding.btnPlay.visibility = View.GONE
        val seasonRow = binding.btnSeasonSelector.parent as? android.view.ViewGroup ?: return
        listOf<View>(binding.btnTrailer, binding.btnFavorite).forEach { v ->
            (v.parent as? android.view.ViewGroup)?.removeView(v)
            seasonRow.addView(
                v,
                android.widget.LinearLayout.LayoutParams(
                    android.view.ViewGroup.LayoutParams.WRAP_CONTENT,
                    // 48dp: the touch minimum. The row is wrap_content, so it grows to hold them.
                    (48 * d).toInt()
                ).apply { marginStart = (10 * d).toInt() }
            )
        }
    }

    private fun setupBackHandling() {
        requireActivity().onBackPressedDispatcher.addCallback(viewLifecycleOwner, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                when {
                    viewModel.streamPanel.value.visible -> viewModel.closeStreamPanel()
                    actions?.isSeasonPopupShowing == true -> actions?.dismissSeasonPopup()
                    else -> {
                        isEnabled = false
                        parentFragmentManager.popBackStack()
                    }
                }
            }
        })
    }

    // ── Observers ──────────────────────────────────────────────────────────────

    private fun setupObservers() {
        // Watched keys → refresh strip
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                watchedStateRepository.observeAllWatchedEpisodeKeys().collectLatest { keys ->
                    episodesStrip?.applyWatchedKeys(keys.toSet())
                }
            }
        }

        // In-progress positions → episode-strip resume bars.
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.episodeProgress.collectLatest { progress ->
                    episodesStrip?.applyProgress(progress)
                }
            }
        }

        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch { viewModel.uiState.collect { if (it is SeriesDetailUiState.Success) bindMetadata(it) } }
                launch { viewModel.seriesTotals.collect { (seasons, eps) -> header?.bindSeriesTotals(seasons, eps) } }
                launch { viewModel.seasonProgress.collect { (w, t) -> binding.tvSeasonProgress.text = "$w / $t WATCHED" } }
                launch { viewModel.categoryListingCount.collect { header?.bindSummary(it) } }
                launch {
                    viewModel.seasons.collect {
                        actions?.setSeasons(it)
                        actions?.updateSeasonButton(viewModel.selectedSeason.value)
                    }
                }
                launch { viewModel.selectedSeason.collect { actions?.updateSeasonButton(it) } }
                launch {
                    viewModel.episodes.collectLatest { data ->
                        episodesStrip?.adapter?.submitData(data)
                        if (episodesStrip?.lastFocusedEpisodeId != null) episodesStrip?.restoreFocus()
                    }
                }
                launch { viewModel.streamPanel.collect { streamPanel?.render(it) } }
                launch { viewModel.navigationEvents.collect { handleNavigationEvent(it) } }
            }
        }
    }

    private fun handleNavigationEvent(event: SeriesNavigationEvent) {
        when (event) {
            is SeriesNavigationEvent.NavigateToPlayer -> launchPlayer(event)
            SeriesNavigationEvent.NavigateBack -> parentFragmentManager.popBackStack()
            is SeriesNavigationEvent.ShowMessage -> Toast.makeText(context, event.msg, Toast.LENGTH_SHORT).show()
        }
    }

    private fun bindMetadata(state: SeriesDetailUiState.Success) {
        val s = state.series
        if (!s.youtubeTrailer.isNullOrBlank()) page?.trailer = s.youtubeTrailer

        val year = header?.bindMetadata(state)

        s.cover?.takeIf { it.isNotEmpty() }?.let { episodesStrip?.applyCoverUrl(it) }
        actions?.updateTrailerButtonState()
        enrichment?.enrichFromTmdb(s.title ?: s.name, year)
    }

    private fun launchPlayer(event: SeriesNavigationEvent.NavigateToPlayer) {
        val seriesTitle = arguments?.getString(ARG_TITLE) ?: binding.tvTitle.text?.toString()
        val intent = com.tvonnet.debridxtreamiptv.player.stabilized.PlayerActivity.createIntent(
            context = requireContext(),
            streamUrl = event.streamUrl,
            title = event.title,
            channelName = "Series ID: ${event.seriesId}",
            contentId = event.episodeId,
            contentType = com.tvonnet.debridxtreamiptv.data.model.ContentType.EPISODE,
            posterUrl = arguments?.getString(ARG_POSTER_URL) ?: event.posterUrl,
            backdropUrl = null,
            seriesTitle = seriesTitle,
            episodeTitle = event.title,
            seasonNumber = event.seasonNumber,
            episodeNumber = event.episodeNumber,
            seriesId = event.seriesId,
            startPositionMs = event.resumePositionMs
        )
        startActivity(intent)
    }

    private fun showToast(message: String, next: String? = null) {
        val handler = binding.layoutToast.handler ?: android.os.Handler(android.os.Looper.getMainLooper())
        toastRunnable?.let { handler.removeCallbacks(it) }
        binding.tvToast.text = message
        binding.layoutToast.visibility = View.VISIBLE
        toastRunnable = Runnable {
            if (next != null) showToast(next, null)
            else binding.layoutToast.visibility = View.GONE
        }
        handler.postDelayed(toastRunnable!!, 1500)
    }

    override fun onDestroyView() {
        super.onDestroyView()
        actions?.onDestroyView()
        page = null
        header = null
        actions = null
        episodesStrip = null
        streamPanel = null
        enrichment = null
        toastRunnable = null
        _binding = null
    }
}
