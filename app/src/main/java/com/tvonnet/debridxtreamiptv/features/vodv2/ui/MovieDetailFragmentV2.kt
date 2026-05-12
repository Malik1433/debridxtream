package com.tvonnet.debridxtreamiptv.features.vodv2.ui

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.bumptech.glide.Glide
import com.bumptech.glide.load.resource.drawable.DrawableTransitionOptions
import com.tvonnet.debridxtreamiptv.data.model.ContentType
import com.tvonnet.debridxtreamiptv.data.prefs.CredentialsPreferences
import com.tvonnet.debridxtreamiptv.databinding.FragmentMovieDetailV2Binding
import com.tvonnet.debridxtreamiptv.player.stabilized.PlayerActivity
import com.tvonnet.debridxtreamiptv.ui.trailer.TrailerActivity
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.launch

@AndroidEntryPoint
class MovieDetailFragmentV2 : Fragment() {

    private var _binding: FragmentMovieDetailV2Binding? = null
    private val binding get() = _binding!!

    private val viewModel: MovieDetailViewModelV2 by viewModels()

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentMovieDetailV2Binding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        val args = requireArguments()
        val streamId = args.getString(ARG_STREAM_ID)
        val title = args.getString(ARG_TITLE)
        val backdropUrl = args.getString(ARG_BACKDROP_URL)
        val posterUrl = args.getString(ARG_POSTER_URL)
        val plot = args.getString(ARG_PLOT)
        val year = args.getString(ARG_YEAR)
        val genre = args.getString(ARG_GENRE)
        val rating = args.getString(ARG_RATING)
        val containerExt = args.getString(ARG_CONTAINER_EXT) ?: "mp4"
        val directSource = args.getString(ARG_DIRECT_SOURCE)
        val trailer = args.getString(ARG_TRAILER)

        binding.tvTitle.text = title ?: "Loading..."
        binding.tvPlot.text = plot?.takeIf { it.isNotBlank() } ?: "No plot available."
        binding.tvYear.text = year?.takeIf { it.isNotBlank() } ?: "N/A"
        binding.tvGenre.text = genre?.takeIf { it.isNotBlank() } ?: "Genre"

        val ratingText = rating?.takeIf { it.isNotBlank() } ?: ""
        binding.tvRating.text = if (ratingText.isNotEmpty()) "★ $ratingText" else "★"

        if (!backdropUrl.isNullOrBlank()) {
            Glide.with(this)
                .load(backdropUrl)
                .transition(DrawableTransitionOptions.withCrossFade(250))
                .into(binding.ivBackdrop)
        }

        binding.pbLoadingCentral.visibility = View.GONE

        binding.btnBack.setOnClickListener {
            parentFragmentManager.popBackStack()
        }

        binding.btnPlay.setOnClickListener {
            if (streamId.isNullOrBlank()) {
                Toast.makeText(context, "Invalid movie id", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            val prefs = CredentialsPreferences(requireContext())
            val serverUrl = prefs.getServerUrl()
            val username = prefs.getUsername()
            val password = prefs.getPassword()
            if (serverUrl.isNullOrBlank() || username.isNullOrBlank() || password.isNullOrBlank()) {
                Toast.makeText(context, "Missing credentials", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            val streamUrl = buildStreamUrl(
                serverUrl = serverUrl,
                username = username,
                password = password,
                streamId = streamId,
                containerExt = containerExt,
                directSource = directSource
            )

            val intent = PlayerActivity.createIntent(
                context = requireContext(),
                streamUrl = streamUrl,
                title = title,
                contentId = streamId,
                contentType = ContentType.MOVIE,
                posterUrl = posterUrl,
                backdropUrl = backdropUrl
            )
            startActivity(intent)
        }

        binding.btnTrailer.visibility = View.VISIBLE
        var resolvedTrailer: String? = trailer
        updateTrailerButtonState(resolvedTrailer, isLoading = false)

        binding.btnTrailer.setOnClickListener {
            val currentTrailer = resolvedTrailer
            if (currentTrailer.isNullOrBlank()) {
                Toast.makeText(context, "No trailer available", Toast.LENGTH_SHORT).show()
            } else {
                startActivity(TrailerActivity.createIntent(requireContext(), currentTrailer))
            }
        }

        // Enrich with TMDB when IPTV provider doesn't provide trailer/meta.
        viewModel.start(title = title, yearHint = year, existingTrailer = trailer)
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.uiState.collect { state ->
                    resolvedTrailer = state.trailerValue ?: resolvedTrailer
                    updateTrailerButtonState(resolvedTrailer, isLoading = state.isLoading)

                    state.plot?.takeIf { it.isNotBlank() }?.let { binding.tvPlot.text = it }
                    state.year?.takeIf { it.isNotBlank() }?.let { binding.tvYear.text = it }
                    state.genre?.takeIf { it.isNotBlank() }?.let { binding.tvGenre.text = it }
                    state.rating?.takeIf { it.isNotBlank() }?.let { binding.tvRating.text = "★ $it" }

                    state.backdropUrl?.takeIf { it.isNotBlank() }?.let { url ->
                        Glide.with(this@MovieDetailFragmentV2)
                            .load(url)
                            .transition(DrawableTransitionOptions.withCrossFade(250))
                            .into(binding.ivBackdrop)
                    }
                }
            }
        }

        binding.btnFavorite.setOnClickListener {
            Toast.makeText(context, "Favorite (coming soon)", Toast.LENGTH_SHORT).show()
        }

        setupFocusAnimations()
        setupInitialFocus()
    }

    private fun setupFocusAnimations() {
        val buttons = listOf(
            binding.btnPlay,
            binding.btnTrailer,
            binding.btnFavorite,
            binding.btnBack
        )

        buttons.forEach { view ->
            view.alpha = 0.8f
            view.setOnFocusChangeListener { v, hasFocus ->
                if (hasFocus) {
                    v.animate()
                        .scaleX(1.1f)
                        .scaleY(1.1f)
                        .alpha(1.0f)
                        .setDuration(200)
                        .setInterpolator(android.view.animation.OvershootInterpolator())
                        .start()
                } else {
                    v.animate()
                        .scaleX(1.0f)
                        .scaleY(1.0f)
                        .alpha(0.8f)
                        .setDuration(200)
                        .start()
                }
            }
        }
    }

    private fun setupInitialFocus() {
        binding.root.post {
            binding.root.post {
                binding.btnPlay.requestFocus()
            }
        }
    }

    private fun buildStreamUrl(
        serverUrl: String,
        username: String,
        password: String,
        streamId: String,
        containerExt: String?,
        directSource: String?
    ): String {
        val direct = directSource?.trim().orEmpty()
        if (direct.isNotBlank()) {
            // If provider gives a full URL, prefer it.
            if (direct.startsWith("http://", true) || direct.startsWith("https://", true)) return direct
            if (direct.startsWith("rtmp://", true)) return direct
            if (direct.startsWith("rtsp://", true)) return direct
        }

        val baseUrl = serverUrl.trimEnd('/')
        val ext = containerExt?.trim()?.trimStart('.')?.ifBlank { "mp4" } ?: "mp4"
        return "$baseUrl/movie/$username/$password/$streamId.$ext"
    }

    private fun updateTrailerButtonState(trailerValue: String?, isLoading: Boolean) {
        val hasTrailer = !trailerValue.isNullOrBlank()
        // TMDB enrichment can be loading while we already have a provider trailer.
        // Keep trailer playable whenever a trailer value exists.
        binding.btnTrailer.isEnabled = hasTrailer
        binding.btnTrailer.alpha = if (hasTrailer) 1f else 0.6f
        binding.pbLoadingCentral.visibility = if (isLoading && !hasTrailer) View.VISIBLE else View.GONE
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    companion object {
        private const val ARG_STREAM_ID = "stream_id"
        private const val ARG_TITLE = "title"
        private const val ARG_BACKDROP_URL = "backdrop_url"
        private const val ARG_POSTER_URL = "poster_url"
        private const val ARG_PLOT = "plot"
        private const val ARG_YEAR = "year"
        private const val ARG_GENRE = "genre"
        private const val ARG_RATING = "rating"
        private const val ARG_CONTAINER_EXT = "container_ext"
        private const val ARG_DIRECT_SOURCE = "direct_source"
        private const val ARG_TRAILER = "trailer"

        fun newInstance(args: Bundle): MovieDetailFragmentV2 {
            return MovieDetailFragmentV2().apply { arguments = args }
        }

        fun newInstance(
            streamId: String,
            title: String?,
            backdropUrl: String?,
            posterUrl: String?,
            plot: String? = null,
            year: String? = null,
            genre: String? = null,
            rating: String? = null,
            containerExt: String? = null,
            directSource: String? = null,
            trailer: String? = null
        ): MovieDetailFragmentV2 {
            return MovieDetailFragmentV2().apply {
                arguments = Bundle().apply {
                    putString(ARG_STREAM_ID, streamId)
                    putString(ARG_TITLE, title)
                    putString(ARG_BACKDROP_URL, backdropUrl)
                    putString(ARG_POSTER_URL, posterUrl)
                    putString(ARG_PLOT, plot)
                    putString(ARG_YEAR, year)
                    putString(ARG_GENRE, genre)
                    putString(ARG_RATING, rating)
                    putString(ARG_CONTAINER_EXT, containerExt)
                    putString(ARG_DIRECT_SOURCE, directSource)
                    putString(ARG_TRAILER, trailer)
                }
            }
        }
    }
}
