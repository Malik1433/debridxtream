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
import com.tvonnet.debridxtreamiptv.data.local.WatchedIdentityBuilder
import com.tvonnet.debridxtreamiptv.data.local.entity.WatchedStateEntity
import com.tvonnet.debridxtreamiptv.data.model.ContentType
import com.tvonnet.debridxtreamiptv.data.prefs.CredentialsPreferences
import com.tvonnet.debridxtreamiptv.data.prefs.WatchHistoryPreferences
import com.tvonnet.debridxtreamiptv.data.repository.WatchedStateRepository
import com.tvonnet.debridxtreamiptv.features.vodv2.ui.MovieDetailFragmentV2
import com.tvonnet.debridxtreamiptv.features.vodv2.ui.MovieDetailViewModelV2
import com.tvonnet.debridxtreamiptv.player.stabilized.PlayerActivity
import com.tvonnet.debridxtreamiptv.ui.live.phone.PhoneUi
import com.tvonnet.debridxtreamiptv.util.PortraitScreen
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject
import kotlinx.coroutines.launch

/**
 * PHONE movie Detail — portrait, built to the "DX Play Detail" handoff (2026-08-14).
 *
 * A separate fragment from [MovieDetailFragmentV2] for the same reason Home and Live TV are
 * separate: the TV screen is a full-bleed backdrop with text over it and a source panel down the
 * right, this is a poster-led header over a single column. They no longer share an arrangement.
 *
 * What they DO share is everything that matters: the same [MovieDetailViewModelV2] enrichment, the
 * same arguments, the same favourites table, the same watched-state identity and the same
 * PlayerActivity intent — so a film marked watched here is watched on the television, and the
 * resume position is the one the player wrote.
 */
@AndroidEntryPoint
class PhoneMovieDetailFragment : Fragment(), PortraitScreen {

    @Inject
    lateinit var watchedStateRepository: WatchedStateRepository

    @Inject
    lateinit var repository: com.tvonnet.debridxtreamiptv.data.repository.XtreamRepository

    private val viewModel: MovieDetailViewModelV2 by viewModels()

    private lateinit var detail: PhoneDetailView
    private var model = PhoneDetailModel(
        title = "", posterUrl = null, backdropUrl = null, rating = null, year = null,
        runtimeMinutes = null, certification = null, genres = null, plot = null,
        creditsLabel = null, creditsName = null,
    )

    private val streamId get() = arguments?.getString(ARG_STREAM_ID)
    private val movieTitle get() = arguments?.getString(ARG_TITLE).orEmpty()
    private val argYear get() = arguments?.getString(ARG_YEAR)
    private var isFavourite = false
    private var isWatched = false
    private var trailer: String? = null

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View = PhoneUi.unscaled(this, inflater)
        .inflate(R.layout.fragment_detail_phone, container, false)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        val args = requireArguments()
        trailer = args.getString(ARG_TRAILER)

        detail = PhoneDetailView(view, PhoneUi.unscaled(this, layoutInflater), requireContext(), actions())

        // The arguments already carry title, poster and year from the card that was tapped, so the
        // screen has something true to show before any network call returns.
        model = model.copy(
            title = movieTitle,
            posterUrl = args.getString(ARG_POSTER_URL),
            backdropUrl = args.getString(ARG_BACKDROP_URL),
            plot = args.getString(ARG_PLOT),
            year = argYear,
            genres = args.getString(ARG_GENRE),
            rating = args.getString(ARG_RATING),
            hasTrailer = !trailer.isNullOrBlank(),
            creditsLabel = getString(R.string.phone_director),
            // IPTV has no similar-titles data. Say it once rather than draw an empty carousel.
            similarUnavailableNote = getString(R.string.phone_no_similar),
        )
        detail.render(model)

        observeEnrichment()
        observeFavourite()
        observeWatched()

        viewModel.start(title = movieTitle, yearHint = argYear, existingTrailer = trailer)
    }

    override fun onResume() {
        super.onResume()
        // The player writes the resume position on its way out, so this is re-read every time the
        // screen comes forward rather than once at creation. Coming forward is also when the
        // player is definitely up, so the OPENING overlay comes down here.
        (view as? android.view.ViewGroup)?.let { root ->
            root.findViewById<View>(R.id.phone_opening_overlay)?.let(root::removeView)
        }
        refreshResume()
    }

    private fun actions() = PhoneDetailActions(
        onPlay = { play() },
        onBack = { parentFragmentManager.popBackStack() },
        onToggleFavourite = { toggleFavourite() },
        onToggleWatched = { toggleWatched() },
        onTrailer = { openTrailer() },
        onSources = { },
        onSeasonPicked = { },
        onEpisode = { },
        onEpisodeOverflow = { },
        onSimilar = { },
        onRetry = { viewModel.start(title = movieTitle, yearHint = argYear, existingTrailer = trailer) },
    )

    private fun observeEnrichment() {
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.uiState.collect { state ->
                    model = model.copy(
                        // Enrichment only ever ADDS: an empty TMDB field must not wipe what the
                        // card already gave us.
                        plot = state.plot?.takeIf { it.isNotBlank() } ?: model.plot,
                        backdropUrl = state.backdropUrl?.takeIf { it.isNotBlank() } ?: model.backdropUrl,
                        posterUrl = state.posterUrl?.takeIf { it.isNotBlank() } ?: model.posterUrl,
                        rating = state.rating?.takeIf { it.isNotBlank() } ?: model.rating,
                        year = state.year?.takeIf { it.isNotBlank() } ?: model.year,
                        genres = state.genre?.takeIf { it.isNotBlank() } ?: model.genres,
                        runtimeMinutes = state.runtimeMinutes ?: model.runtimeMinutes,
                        certification = state.ageRating ?: model.certification,
                        creditsName = state.director ?: model.creditsName,
                        hasTrailer = model.hasTrailer || !state.trailerValue.isNullOrBlank(),
                        cast = state.cast.take(CAST_LIMIT).map {
                            PhoneDetailModel.CastMember(
                                name = it.name.orEmpty(),
                                role = it.character,
                                photoUrl = com.tvonnet.debridxtreamiptv.data.debrid.model.TmdbImageUrl
                                    .getProfileUrl(it.profilePath),
                            )
                        },
                    )
                    state.trailerValue?.takeIf { it.isNotBlank() }?.let { trailer = it }
                    detail.render(model)
                }
            }
        }
    }

    private fun refreshResume() {
        val resume = WatchHistoryPreferences(requireContext()).getMovieResumeItem(
            contentId = streamId,
            tmdbId = null,
            imdbId = null,
            title = movieTitle,
        )
        val position = resume?.currentPosition ?: 0L
        val duration = resume?.totalDuration ?: 0L
        model = model.copy(
            playback = if (position > 0 && duration > 0) {
                PhoneDetailModel.Playback(position, duration)
            } else {
                null
            }
        )
        if (::detail.isInitialized) detail.render(model)
    }

    private fun observeFavourite() {
        val id = streamId ?: return
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                repository.getFavoritesByType("vod").collect { favourites ->
                    isFavourite = favourites.any { it.streamId == id }
                    model = model.copy(isFavourite = isFavourite)
                    detail.render(model)
                }
            }
        }
    }

    private fun toggleFavourite() {
        val id = streamId ?: return
        viewLifecycleOwner.lifecycleScope.launch {
            runCatching {
                if (isFavourite) {
                    repository.removeFavorite(id)
                } else {
                    repository.addFavorite(
                        streamId = id,
                        type = "vod",
                        name = movieTitle,
                        iconUrl = com.tvonnet.debridxtreamiptv.util.GlobalConfig
                            .resolveIconUrl(model.posterUrl),
                    )
                }
            }
        }
    }

    /** The same identity the TV screen uses, so watched means watched on both. */
    private fun watchedIdentity() =
        WatchedIdentityBuilder.iptvMovie(streamId.orEmpty(), movieTitle, argYear)

    private fun observeWatched() {
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                watchedStateRepository.observeState(watchedIdentity()).collect { state ->
                    isWatched = state?.isWatched == true
                    model = model.copy(isWatched = isWatched)
                    detail.render(model)
                }
            }
        }
    }

    private fun toggleWatched() {
        val id = streamId ?: return
        viewLifecycleOwner.lifecycleScope.launch {
            val identity = watchedIdentity()
            val entity = watchedStateRepository.getState(identity) ?: WatchedStateEntity(
                identityKey = identity,
                contentType = WatchedStateEntity.CONTENT_TYPE_MOVIE,
                source = WatchedStateEntity.SOURCE_XTREAM,
                iptvStreamId = id,
                title = movieTitle,
                year = argYear,
                tmdbId = null,
                imdbId = null,
                durationMs = 0L,
                updatedAt = System.currentTimeMillis(),
            )
            watchedStateRepository.setManualWatched(entity, !isWatched, System.currentTimeMillis())
            WatchHistoryPreferences(requireContext()).removeContinueWatchingItem(id)
        }
    }

    private fun showOpeningOverlay() {
        val root = view as? android.view.ViewGroup ?: return
        if (root.findViewById<View>(R.id.phone_opening_overlay) != null) return
        root.addView(
            PhoneUi.unscaled(this, layoutInflater)
                .inflate(R.layout.view_phone_opening, root, false)
        )
    }

    private fun openTrailer() {
        val value = trailer?.takeIf { it.isNotBlank() } ?: return
        startActivity(
            com.tvonnet.debridxtreamiptv.ui.trailer.TrailerActivity.createIntent(
                requireContext(), value
            )
        )
    }

    /** The same URL and the same intent the TV screen builds — including the resume position. */
    private fun play() {
        val id = streamId ?: return
        val prefs = CredentialsPreferences(requireContext())
        val serverUrl = prefs.getServerUrl()
        val username = prefs.getUsername()
        val password = prefs.getPassword()
        if (serverUrl.isNullOrBlank() || username.isNullOrBlank() || password.isNullOrBlank()) return

        val direct = arguments?.getString(ARG_DIRECT_SOURCE)?.takeIf { it.startsWith("http") }
        // Built exactly like the TV screen's buildXtreamMovieUrl, and for the same reasons: a
        // portal URL often ends in a slash, and the container is per-title. Defaulting the
        // extension to mp4 for an mkv gives a 404, which the player shows as opening and closing
        // again immediately — which is precisely what it did.
        val base = serverUrl.trimEnd('/')
        val extension = arguments?.getString(ARG_CONTAINER_EXT)
            ?.trim()?.trimStart('.')?.ifBlank { "mp4" } ?: "mp4"
        val url = direct ?: "$base/movie/$username/$password/$id.$extension"
        // The player takes a moment to come up. Without this the screen sat there looking
        // untouched for that whole time, which reads as a tap that did nothing.
        showOpeningOverlay()
        startActivity(
            PlayerActivity.createIntent(
                context = requireContext(),
                streamUrl = url,
                title = movieTitle,
                contentId = id,
                contentType = ContentType.MOVIE,
                posterUrl = model.posterUrl,
                backdropUrl = model.backdropUrl,
                startPositionMs = model.playback?.positionMs ?: 0L,
            )
        )
    }

    companion object {
        private const val ARG_STREAM_ID = "streamId"
        private const val ARG_TITLE = "title"
        private const val ARG_BACKDROP_URL = "backdropUrl"
        private const val ARG_POSTER_URL = "posterUrl"
        private const val ARG_PLOT = "plot"
        private const val ARG_YEAR = "year"
        private const val ARG_GENRE = "genre"
        private const val ARG_RATING = "rating"
        private const val ARG_CONTAINER_EXT = "containerExtension"
        private const val ARG_DIRECT_SOURCE = "directSource"
        private const val ARG_TRAILER = "trailer"
        private const val CAST_LIMIT = 12

        /** Same argument names as [MovieDetailFragmentV2], so callers need no branch. */
        fun newInstance(
            streamId: String?,
            title: String?,
            backdropUrl: String? = null,
            posterUrl: String? = null,
            plot: String? = null,
            year: String? = null,
            genre: String? = null,
            rating: String? = null,
            containerExtension: String? = null,
            directSource: String? = null,
            trailer: String? = null,
        ) = PhoneMovieDetailFragment().apply {
            arguments = Bundle().apply {
                putString(ARG_STREAM_ID, streamId)
                putString(ARG_TITLE, title)
                putString(ARG_BACKDROP_URL, backdropUrl)
                putString(ARG_POSTER_URL, posterUrl)
                putString(ARG_PLOT, plot)
                putString(ARG_YEAR, year)
                putString(ARG_GENRE, genre)
                putString(ARG_RATING, rating)
                putString(ARG_CONTAINER_EXT, containerExtension)
                putString(ARG_DIRECT_SOURCE, directSource)
                putString(ARG_TRAILER, trailer)
            }
        }
    }
}
