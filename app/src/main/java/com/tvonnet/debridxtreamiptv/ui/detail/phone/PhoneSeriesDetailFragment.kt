package com.tvonnet.debridxtreamiptv.ui.detail.phone

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.tvonnet.debridxtreamiptv.R
import com.tvonnet.debridxtreamiptv.features.seriesv2.data.dao.EpisodeDaoV2
import com.tvonnet.debridxtreamiptv.features.seriesv2.data.model.EpisodeEntityV2
import com.tvonnet.debridxtreamiptv.features.seriesv2.ui.SeriesDetailViewModelV2
import com.tvonnet.debridxtreamiptv.features.seriesv2.ui.model.SeriesDetailUiState
import com.tvonnet.debridxtreamiptv.features.seriesv2.ui.model.SeriesNavigationEvent
import com.tvonnet.debridxtreamiptv.ui.live.phone.PhoneUi
import com.tvonnet.debridxtreamiptv.util.PortraitScreen
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject
import kotlinx.coroutines.launch

/**
 * PHONE series Detail — the same screen as the movie one, plus the two things a series needs.
 *
 * The handoff is explicit that this must not become a second design: everything above the season
 * bar is identical to a movie and in the same place. What a series adds is the sticky season bar
 * and the episode list, and both exist because of the same fact — twenty seasons and three hundred
 * episodes do not fit any control that shows them all at once.
 *
 * Episodes are read one season at a time (which is twenty-odd rows, not three hundred) so the list
 * can be a plain column inside the page's own scroll, exactly as the design asks. Playback,
 * seasons and watched progress all come from [SeriesDetailViewModelV2], the same collaborator the
 * TV screen uses.
 */
@AndroidEntryPoint
class PhoneSeriesDetailFragment : Fragment(), PortraitScreen {

    @Inject
    lateinit var episodeDao: EpisodeDaoV2

    private val viewModel: SeriesDetailViewModelV2 by viewModels()

    private lateinit var detail: PhoneDetailView
    private var model = PhoneDetailModel(
        title = "", posterUrl = null, backdropUrl = null, rating = null, year = null,
        runtimeMinutes = null, certification = null, genres = null, plot = null,
        creditsLabel = null, creditsName = null, isSeries = true,
        // TRUE from the first paint. The default false meant the very first render took the
        // "finished and empty" branch and printed NO EPISODES before loading had even begun —
        // which read as "there is no loading state" because, on screen, there wasn't.
        episodesLoading = true,
    )
    private var episodes: List<EpisodeEntityV2> = emptyList()
    private var episodesSettled = false
    private var progress: Map<String, Long> = emptyMap()

    private val seriesId get() = arguments?.getString(ARG_SERIES_ID).orEmpty()

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View = PhoneUi.unscaled(this, inflater)
        .inflate(R.layout.fragment_detail_phone, container, false)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        val args = requireArguments()

        detail = PhoneDetailView(view, PhoneUi.unscaled(this, layoutInflater), requireContext(), actions())
        model = model.copy(
            title = args.getString(ARG_TITLE).orEmpty(),
            posterUrl = args.getString(ARG_POSTER_URL),
            backdropUrl = args.getString(ARG_BACKDROP_URL),
            creditsLabel = getString(R.string.phone_created_by),
            similarUnavailableNote = getString(R.string.phone_no_similar),
        )
        detail.render(model)

        observeSeries()
        observeSeasons()
        observeEpisodes()
        observeEpisodeCounts()
        observeEpisodeProgress()
        observeStreamPanel()
        observeNavigation()
    }

    private fun actions() = PhoneDetailActions(
        onPlay = { playNextUp() },
        onBack = { parentFragmentManager.popBackStack() },
        onToggleFavourite = { },
        onToggleWatched = { },
        onTrailer = { },
        onSources = { },
        onSeasonPicked = { season -> viewModel.onSeasonSelected(season) },
        onEpisode = { episode -> playEpisode(episode.id) },
        onEpisodeOverflow = { episode -> playEpisode(episode.id) },
        onSimilar = { },
        onRetry = { viewModel.onRetry() },
    )

    private fun observeSeries() {
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.uiState.collect { state ->
                    when (state) {
                        is SeriesDetailUiState.Loading -> detail.showLoading()
                        is SeriesDetailUiState.Error -> detail.showError(model)
                        is SeriesDetailUiState.Success -> {
                            val series = state.series
                            model = model.copy(
                                title = series.name?.takeIf { it.isNotBlank() } ?: model.title,
                                plot = series.plot?.takeIf { it.isNotBlank() } ?: model.plot,
                                posterUrl = series.cover?.takeIf { it.isNotBlank() } ?: model.posterUrl,
                                backdropUrl = series.backdropPath.firstOrNull()
                                    ?.takeIf { it.isNotBlank() } ?: model.backdropUrl,
                                rating = series.rating?.takeIf { it.isNotBlank() } ?: model.rating,
                                genres = series.genre?.takeIf { it.isNotBlank() } ?: model.genres,
                                year = series.releaseDate?.take(4)?.takeIf { it.isNotBlank() }
                                    ?: model.year,
                                creditsName = series.director?.takeIf { it.isNotBlank() }
                                    ?: model.creditsName,
                            )
                            detail.render(model)
                        }
                    }
                }
            }
        }
    }

    private fun observeSeasons() {
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.seasons.collect { seasons ->
                    model = model.copy(
                        seasons = seasons.map { season ->
                            PhoneDetailModel.Season(
                                number = season.seasonNum,
                                episodeCount = 0,
                                watchedCount = 0,
                            )
                        }
                    )
                    detail.render(model)
                }
            }
        }
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.selectedSeason.collect { season ->
                    if (season == null) return@collect
                    model = model.copy(selectedSeason = season)
                    detail.render(model)
                }
            }
        }
    }

    /**
     * The season bar's own count, straight from the table.
     *
     * It is a Flow rather than a read because episodes land in the database AFTER this screen
     * opens — the ViewModel fetches them on the way in — so anything read once on the way in
     * finds an empty season and stays that way.
     */
    private fun observeEpisodeCounts() {
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                episodeDao.observeSeasonEpisodeCounts(seriesId).collect { counts ->
                    val bySeason = counts.associate { it.season_number to it.totalEpisodes }
                    model = model.copy(
                        seasons = model.seasons.map {
                            it.copy(episodeCount = bySeason[it.number] ?: it.episodeCount)
                        }
                    )
                    detail.render(model)
                }
            }
        }
    }

    /**
     * The episode list comes from [SeriesDetailViewModelV2.episodes] — the SAME paged flow the
     * television screen renders.
     *
     * The first attempt read the table directly for the selected season, which was one guess too
     * many: it had to guess when the rows exist, and which id and season the ViewModel filed them
     * under. Collecting the ViewModel's flow inherits all three answers, and it updates itself
     * when the fetch lands instead of needing a signal that it did.
     *
     * A season is twenty-odd rows, so the whole page is taken as a snapshot and rendered into the
     * page's own column, exactly as the design asks — no nested scroller.
     */
    private fun observeEpisodes() {
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.episodes.collect { paging ->
                    pagingAdapter.submitData(paging)
                }
            }
        }
        // The adapter is only ever used as a data pump: whenever its snapshot changes, the rows
        // are re-rendered into the page. Nothing is attached to a RecyclerView.
        pagingAdapter.addOnPagesUpdatedListener {
            episodes = pagingAdapter.snapshot().items
            renderEpisodes()
        }
        // A season that has genuinely finished loading with nothing in it must stop shimmering:
        // an endless skeleton is a lie about work still being done.
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.uiState.collect { state ->
                    // Not Paging's LoadState: Room answers an unfetched season instantly with an
                    // empty page, so NotLoading arrives before any work has been done and the
                    // skeleton would vanish after a single frame. THIS fetch is what fills the
                    // table, so it is what "still loading" actually means.
                    episodesSettled = state !is SeriesDetailUiState.Loading
                    renderEpisodes()
                }
            }
        }
    }

    private val pagingAdapter by lazy {
        object : androidx.paging.PagingDataAdapter<EpisodeEntityV2,
            androidx.recyclerview.widget.RecyclerView.ViewHolder>(DIFF) {
            override fun onCreateViewHolder(
                parent: android.view.ViewGroup,
                viewType: Int,
            ): androidx.recyclerview.widget.RecyclerView.ViewHolder =
                object : androidx.recyclerview.widget.RecyclerView.ViewHolder(View(parent.context)) {}

            override fun onBindViewHolder(
                holder: androidx.recyclerview.widget.RecyclerView.ViewHolder,
                position: Int,
            ) = Unit
        }
    }

    private fun observeEpisodeProgress() {
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.episodeProgress.collect { map ->
                    progress = map
                    renderEpisodes()
                }
            }
        }
    }

    /**
     * The three episode states the design asks for, plus NEXT UP — the first unwatched episode,
     * which is what the screen scrolls to and what the Play button plays.
     */
    private fun renderEpisodes() {
        val rows = episodes.map { entity ->
            val watchedMs = progress[entity.episodeId] ?: 0L
            val duration = entity.durationSecs?.toLongOrNull()?.times(1000L) ?: 0L
            PhoneDetailModel.Episode(
                id = entity.episodeId,
                number = entity.episodeNumber,
                title = entity.title.orEmpty(),
                plot = entity.plot,
                // Most IPTV portals send no per-episode still at all. An empty grey box for every
                // row looks broken, so the series art stands in — the same thing the row would
                // have shown if the provider had bothered.
                stillUrl = entity.thumbnail?.takeIf { it.isNotBlank() }
                    ?: model.backdropUrl?.takeIf { it.isNotBlank() }
                    ?: model.posterUrl,
                runtimeMinutes = entity.durationSecs?.toIntOrNull()?.div(60),
                watched = duration > 0 && watchedMs > duration * WATCHED_FRACTION,
                positionMs = watchedMs.takeIf { it > 0 },
                durationMs = duration.takeIf { it > 0 },
            )
        }
        val nextUp = rows.firstOrNull { !it.watched }?.id
        model = model.copy(episodesLoading = rows.isEmpty() && !episodesSettled)
        val season = model.selectedSeason
        model = model.copy(
            episodes = rows,
            nextUpEpisode = nextUp,
            seasons = model.seasons.map {
                if (it.number == season) {
                    it.copy(episodeCount = rows.size, watchedCount = rows.count { r -> r.watched })
                } else {
                    it
                }
            },
        )
        detail.render(model)
    }

    private fun playNextUp() {
        val next = model.nextUpEpisode ?: model.episodes.firstOrNull()?.id ?: return
        playEpisode(next)
    }

    /**
     * Tapping an episode does NOT play it directly — it asks the ViewModel for that episode's
     * streams, which it aggregates across every category the series appears in. That is why the
     * first version of this screen looked dead on a tap: the work was happening, the panel that
     * shows it simply did not exist here.
     */
    private fun playEpisode(episodeId: String) {
        val entity = episodes.firstOrNull { it.episodeId == episodeId } ?: return
        viewModel.onEpisodeClicked(entity)
    }

    /**
     * The stream picker for the tapped episode.
     *
     * Opened as a sheet rather than a pushed screen: an episode usually has a handful of streams,
     * not the sixty a debrid movie can have, and a sheet keeps the episode list behind it. It
     * shows its own spinner while the aggregation runs, so a tap is never silent.
     */
    private fun observeStreamPanel() {
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.streamPanel.collect { panel ->
                    if (!panel.visible) {
                        streamSheet?.dismiss()
                        streamSheet = null
                        return@collect
                    }
                    showStreamSheet(panel)
                }
            }
        }
    }

    private var streamSheet: com.google.android.material.bottomsheet.BottomSheetDialog? = null

    private fun showStreamSheet(
        panel: com.tvonnet.debridxtreamiptv.features.seriesv2.ui.StreamPanelState,
    ) {
        val sheet = streamSheet ?: com.google.android.material.bottomsheet.BottomSheetDialog(
            requireContext()
        ).also { dialog ->
            val content = PhoneUi.unscaled(this, layoutInflater)
                .inflate(R.layout.sheet_phone_streams, null)
            dialog.setContentView(content)
            dialog.setOnDismissListener { viewModel.closeStreamPanel() }
            dialog.show()
            streamSheet = dialog
        }
        val content = sheet.findViewById<View>(R.id.phone_streams_root) ?: return

        content.findViewById<TextView>(R.id.phone_streams_title).text = panel.episodeLabel
        // Two different waits share the spinner line: finding the streams, and — after a row is
        // tapped — resolving the picked one to a playable URL. The second is a network fetch that
        // can take seconds, and while it ran the sheet used to sit frozen with nothing changing,
        // which is exactly "I tapped play and it did nothing for a long time".
        val resolving = panel.resolvingStreamId != null
        content.findViewById<View>(R.id.phone_streams_loading).isVisible =
            panel.loading || resolving
        content.findViewById<TextView>(R.id.phone_streams_loading_label)?.setText(
            if (resolving) R.string.phone_opening_stream else R.string.phone_finding_streams
        )
        content.findViewById<TextView>(R.id.phone_streams_error).apply {
            text = panel.error.orEmpty()
            isVisible = !panel.error.isNullOrBlank() && !panel.loading
        }

        val list = content.findViewById<LinearLayout>(R.id.phone_streams_list)
        list.removeAllViews()
        val inflater = PhoneUi.unscaled(this, layoutInflater)
        panel.filteredGroups.flatMap { it.streams }.forEach { option ->
            val row = inflater.inflate(R.layout.item_phone_source, list, false)
            row.findViewById<TextView>(R.id.phone_srow_quality).text = option.quality.label
            row.findViewById<TextView>(R.id.phone_srow_size).text = option.container
            row.findViewById<TextView>(R.id.phone_srow_name).text = option.name
            row.findViewById<TextView>(R.id.phone_srow_provider).text =
                (listOf(option.categoryTag) + option.languages.map { it.code }).joinToString(" · ")
            row.findViewById<TextView>(R.id.phone_srow_state).apply {
                // The tapped row says what it is doing; the rest dim so a second tap is
                // obviously not going to help.
                isVisible = panel.resolvingStreamId == option.streamId
                if (isVisible) {
                    setText(R.string.phone_opening_stream)
                    setTextColor(
                        androidx.core.content.ContextCompat.getColor(
                            requireContext(), com.tvonnet.debridxtreamiptv.R.color.phone_cyan
                        )
                    )
                }
            }
            row.alpha = if (resolving && panel.resolvingStreamId != option.streamId) 0.4f else 1f
            row.isEnabled = !resolving
            if (!resolving) row.setOnClickListener { viewModel.onStreamSelected(option) }
            list.addView(row)
        }
    }

    /**
     * Opening the player takes a moment — the activity starts, the surface comes up and the first
     * frame has to arrive — and until this existed the screen showed the detail page doing nothing
     * for that whole time, which reads as a tap that failed. The overlay stays up until we come
     * back on screen, so the wait is always visibly OURS.
     */
    private fun startPlayer(intent: android.content.Intent) {
        showOpeningOverlay()
        startActivity(intent)
    }

    private fun showOpeningOverlay() {
        val root = view as? android.view.ViewGroup ?: return
        if (root.findViewById<View>(R.id.phone_opening_overlay) != null) return
        val overlay = PhoneUi.unscaled(this, layoutInflater)
            .inflate(R.layout.view_phone_opening, root, false)
        root.addView(overlay)
    }

    private fun hideOpeningOverlay() {
        val root = view as? android.view.ViewGroup ?: return
        root.findViewById<View>(R.id.phone_opening_overlay)?.let(root::removeView)
    }

    override fun onResume() {
        super.onResume()
        hideOpeningOverlay()
    }

    /** The ViewModel decides HOW an episode plays; this screen only carries the result out. */
    private fun observeNavigation() {
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.navigationEvents.collect { event ->
                    when (event) {
                        is SeriesNavigationEvent.NavigateToPlayer -> startPlayer(
                            com.tvonnet.debridxtreamiptv.player.stabilized.PlayerActivity.createIntent(
                                context = requireContext(),
                                streamUrl = event.streamUrl,
                                title = event.title,
                                startPositionMs = event.resumePositionMs,
                                contentId = event.episodeId,
                                contentType = com.tvonnet.debridxtreamiptv.data.model.ContentType.EPISODE,
                                posterUrl = event.posterUrl,
                                seriesTitle = model.title,
                                seasonNumber = event.seasonNumber,
                                episodeNumber = event.episodeNumber,
                                seriesId = event.seriesId,
                            )
                        )
                        is SeriesNavigationEvent.NavigateBack -> parentFragmentManager.popBackStack()
                        is SeriesNavigationEvent.ShowMessage -> android.widget.Toast
                            .makeText(requireContext(), event.msg, android.widget.Toast.LENGTH_SHORT)
                            .show()
                    }
                }
            }
        }
    }

    companion object {
        // The SAME keys SeriesDetailFragmentV2 uses, and not a private set of my own: the shared
        // SeriesDetailViewModelV2 reads "series_id" straight out of its SavedStateHandle and
        // throws if it is absent. A screen that renames its own arguments cannot borrow another
        // screen's ViewModel.
        private const val ARG_SERIES_ID = "series_id"
        private const val ARG_TITLE = "title"
        private const val ARG_BACKDROP_URL = "backdrop_url"
        private const val ARG_POSTER_URL = "poster_url"

        /** Watched enough of it to count, the same threshold the rest of the app uses. */
        private const val WATCHED_FRACTION = 0.9

        private val DIFF = object :
            androidx.recyclerview.widget.DiffUtil.ItemCallback<EpisodeEntityV2>() {
            override fun areItemsTheSame(old: EpisodeEntityV2, new: EpisodeEntityV2) =
                old.episodeId == new.episodeId

            override fun areContentsTheSame(old: EpisodeEntityV2, new: EpisodeEntityV2) = old == new
        }

        fun newInstance(
            seriesId: String?,
            title: String?,
            backdropUrl: String? = null,
            posterUrl: String? = null,
        ) = PhoneSeriesDetailFragment().apply {
            arguments = Bundle().apply {
                putString(ARG_SERIES_ID, seriesId)
                putString(ARG_TITLE, title)
                putString(ARG_BACKDROP_URL, backdropUrl)
                putString(ARG_POSTER_URL, posterUrl)
            }
        }
    }
}
