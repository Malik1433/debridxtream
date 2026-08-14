package com.tvonnet.debridxtreamiptv.ui.detail.phone

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

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
    )
    private var episodes: List<EpisodeEntityV2> = emptyList()
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

        detail = PhoneDetailView(view, PhoneUi.unscaled(this, layoutInflater), actions())
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
        observeEpisodeProgress()
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
                    loadEpisodes(season)
                }
            }
        }
    }

    /** One season is twenty-odd rows, so this is a list read, not a paging problem. */
    private fun loadEpisodes(season: Int) {
        viewLifecycleOwner.lifecycleScope.launch {
            episodes = withContext(Dispatchers.IO) {
                runCatching { episodeDao.getEpisodesForSeasonList(seriesId, season) }
                    .getOrDefault(emptyList())
            }
            renderEpisodes()
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
                stillUrl = entity.thumbnail,
                runtimeMinutes = entity.durationSecs?.toIntOrNull()?.div(60),
                watched = duration > 0 && watchedMs > duration * WATCHED_FRACTION,
                positionMs = watchedMs.takeIf { it > 0 },
                durationMs = duration.takeIf { it > 0 },
            )
        }
        val nextUp = rows.firstOrNull { !it.watched }?.id
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

    private fun playEpisode(episodeId: String) {
        val entity = episodes.firstOrNull { it.episodeId == episodeId } ?: return
        viewModel.onEpisodeClicked(entity)
    }

    /** The ViewModel decides HOW an episode plays; this screen only carries the result out. */
    private fun observeNavigation() {
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.navigationEvents.collect { event ->
                    when (event) {
                        is SeriesNavigationEvent.NavigateToPlayer -> startActivity(
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
        private const val ARG_SERIES_ID = "seriesId"
        private const val ARG_TITLE = "title"
        private const val ARG_BACKDROP_URL = "backdropUrl"
        private const val ARG_POSTER_URL = "posterUrl"

        /** Watched enough of it to count, the same threshold the rest of the app uses. */
        private const val WATCHED_FRACTION = 0.9

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
