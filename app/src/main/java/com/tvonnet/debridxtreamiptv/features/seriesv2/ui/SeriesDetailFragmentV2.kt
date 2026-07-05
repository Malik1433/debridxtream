package com.tvonnet.debridxtreamiptv.features.seriesv2.ui

import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.PopupWindow
import android.widget.TextView
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.paging.LoadState
import androidx.recyclerview.widget.LinearLayoutManager
import com.bumptech.glide.Glide
import com.tvonnet.debridxtreamiptv.databinding.FragmentSeriesDetailV2Binding
import com.tvonnet.debridxtreamiptv.features.seriesv2.data.model.EpisodeEntityV2
import com.tvonnet.debridxtreamiptv.features.seriesv2.ui.model.SeriesDetailUiState
import com.tvonnet.debridxtreamiptv.features.seriesv2.ui.model.SeriesNavigationEvent
import com.tvonnet.debridxtreamiptv.ui.trailer.TrailerActivity
import com.tvonnet.debridxtreamiptv.ui.trailer.TrailerValueParser
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

/**
 * IPTV Series Detail — cinematic layout matching "IPTV Series Detail.dc.html".
 *
 * Opening an episode presents the "SELECT STREAM" panel, which aggregates the same
 * show's streams across every Xtream playlist category it appears in.
 */
@AndroidEntryPoint
class SeriesDetailFragmentV2 : Fragment() {

    private var _binding: FragmentSeriesDetailV2Binding? = null
    private val binding get() = _binding!!

    @javax.inject.Inject
    lateinit var watchedStateRepository: com.tvonnet.debridxtreamiptv.data.repository.WatchedStateRepository

    private val viewModel: SeriesDetailViewModelV2 by viewModels()
    private var adapter: EpisodesAdapterV2? = null
    private var streamAdapter: StreamPanelAdapter? = null

    private var lastFocusedEpisodeId: String? = null
    private var activeEpisode: EpisodeEntityV2? = null
    private var seriesPlot: String? = null
    private var resolvedTrailer: String? = null
    private var currentSeasons: List<SeasonUiModel> = emptyList()
    private var toastRunnable: Runnable? = null
    private var seasonPopup: PopupWindow? = null
    private var pendingStreamFocus = false

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
        setupOptimisticUI()
        setupEpisodes()
        setupStreamPanel()
        setupListeners()
        setupObservers()
        setupBackHandling()
    }

    private fun setupOptimisticUI() {
        val args = arguments ?: return
        binding.tvTitle.text = args.getString(ARG_TITLE) ?: "Loading..."
        resolvedTrailer = args.getString(ARG_TRAILER)
        args.getString(ARG_BACKDROP_URL)?.takeIf { it.isNotEmpty() }?.let {
            Glide.with(this).load(it).dontAnimate().into(binding.ivBackdrop)
        }
        updateTrailerButtonState()
    }

    // ── Episodes strip ─────────────────────────────────────────────────────────

    private fun setupEpisodes() {
        adapter = EpisodesAdapterV2(
            onEpisodeClick = { episode -> viewModel.onEpisodeClicked(episode) },
            onEpisodeFocused = { episode ->
                activeEpisode = episode
                lastFocusedEpisodeId = episode.episodeId
                adapter?.setSelectedEpisode(episode.episodeId)
                updatePlayButton(episode)
            }
        ).also { it.seriesId = arguments?.getString(ARG_SERIES_ID) }

        binding.rvEpisodes.layoutManager = LinearLayoutManager(context, LinearLayoutManager.HORIZONTAL, false)
        binding.rvEpisodes.adapter = adapter
        binding.rvEpisodes.itemAnimator = null

        lifecycleScope.launch {
            adapter?.loadStateFlow?.collect { states ->
                val empty = states.refresh is LoadState.NotLoading && (adapter?.itemCount ?: 0) == 0
                val loading = states.refresh is LoadState.Loading ||
                    states.mediator?.refresh is LoadState.Loading
                binding.pbLoadingCentral.visibility =
                    if ((loading || (adapter?.itemCount ?: 0) == 0) && states.refresh !is LoadState.Error && !empty)
                        View.VISIBLE else View.GONE
                binding.tvEmptyEpisodes.visibility = if (empty) View.VISIBLE else View.GONE
                if (empty) binding.tvEmptyEpisodes.text = "No episodes"
            }
        }
    }

    // ── Stream panel ───────────────────────────────────────────────────────────

    private fun setupStreamPanel() {
        streamAdapter = StreamPanelAdapter(onStreamClick = { option ->
            showToast("CONNECTING…")
            // Panel closes from the ViewModel once the stream resolves; on failure it
            // stays open so the user can pick another category.
            viewModel.onStreamSelected(option)
        })
        binding.rvStreams.adapter = streamAdapter

        binding.viewScrim.setOnClickListener { viewModel.closeStreamPanel() }
        binding.chipQuality.setOnClickListener { viewModel.cycleFilter(FilterKey.QUALITY) }
        binding.chipLanguage.setOnClickListener { viewModel.cycleFilter(FilterKey.LANGUAGE) }
        binding.chipContainer.setOnClickListener { viewModel.cycleFilter(FilterKey.CONTAINER) }
        binding.chipQuality.isFocusable = true
        binding.chipLanguage.isFocusable = true
        binding.chipContainer.isFocusable = true

        // Panel starts off-screen (to the right)
        binding.panelStreams.visibility = View.GONE
        binding.viewScrim.visibility = View.GONE
    }

    private fun setupListeners() {
        binding.btnPlay.setOnClickListener {
            val ep = activeEpisode ?: adapter?.snapshot()?.items?.firstOrNull()
            if (ep != null) viewModel.onEpisodeClicked(ep)
            else Toast.makeText(context, "No episodes available", Toast.LENGTH_SHORT).show()
        }
        binding.btnFavorite.setOnClickListener { showToast("ADDED TO FAVORITES") }
        binding.btnTrailer.setOnClickListener {
            val trailerValue = resolvedTrailer
            if (trailerValue.isNullOrBlank() || TrailerValueParser.parse(trailerValue) == null) {
                Toast.makeText(context, "No trailer available", Toast.LENGTH_SHORT).show()
            } else {
                startActivity(TrailerActivity.createIntent(requireContext(), trailerValue))
            }
        }
        binding.btnSeasonSelector.setOnClickListener { showSeasonPopup() }
    }

    private fun setupBackHandling() {
        requireActivity().onBackPressedDispatcher.addCallback(viewLifecycleOwner, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                when {
                    viewModel.streamPanel.value.visible -> viewModel.closeStreamPanel()
                    seasonPopup?.isShowing == true -> seasonPopup?.dismiss()
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
                    val set = keys.toSet()
                    if (adapter?.watchedKeys != set) {
                        adapter?.watchedKeys = set
                        adapter?.notifyItemRangeChanged(0, adapter?.itemCount ?: 0, Any())
                    }
                }
            }
        }

        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch { viewModel.uiState.collect { if (it is SeriesDetailUiState.Success) bindMetadata(it) } }
                launch { viewModel.seriesTotals.collect { (seasons, eps) -> bindSeriesTotals(seasons, eps) } }
                launch { viewModel.seasonProgress.collect { (w, t) -> binding.tvSeasonProgress.text = "$w / $t WATCHED" } }
                launch { viewModel.categoryListingCount.collect { bindSummary(it) } }
                launch {
                    viewModel.seasons.collect {
                        currentSeasons = it
                        updateSeasonButton(viewModel.selectedSeason.value)
                    }
                }
                launch { viewModel.selectedSeason.collect { updateSeasonButton(it) } }
                launch {
                    viewModel.episodes.collectLatest { data ->
                        adapter?.submitData(data)
                        if (lastFocusedEpisodeId != null) restoreFocus()
                    }
                }
                launch { viewModel.streamPanel.collect { renderStreamPanel(it) } }
                launch {
                    viewModel.navigationEvents.collect { event ->
                        when (event) {
                            is SeriesNavigationEvent.NavigateToPlayer -> launchPlayer(event)
                            SeriesNavigationEvent.NavigateBack -> parentFragmentManager.popBackStack()
                            is SeriesNavigationEvent.ShowMessage -> Toast.makeText(context, event.msg, Toast.LENGTH_SHORT).show()
                        }
                    }
                }
            }
        }
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
            seriesId = event.seriesId
        )
        startActivity(intent)
    }

    // ── Metadata binding ───────────────────────────────────────────────────────

    private fun bindMetadata(state: SeriesDetailUiState.Success) {
        val s = state.series
        seriesPlot = s.plot
        if (!s.youtubeTrailer.isNullOrBlank()) resolvedTrailer = s.youtubeTrailer

        binding.tvTitle.text = s.title ?: s.name ?: binding.tvTitle.text
        binding.tvPlot.text = s.plot?.takeIf { it.isNotBlank() } ?: "No plot available."

        val year = s.year?.takeIf { it.isNotBlank() } ?: s.releaseDate?.take(4)?.takeIf { it.isNotBlank() }
        binding.tvYear.apply {
            text = year; visibility = if (year.isNullOrBlank()) View.GONE else View.VISIBLE
        }
        val genre = s.genre?.takeIf { it.isNotBlank() }
        binding.tvGenre.apply {
            text = genre; visibility = if (genre == null) View.GONE else View.VISIBLE
        }
        val rating = s.rating?.toDoubleOrNull()
        if (rating != null && rating > 0) {
            binding.layoutRating.visibility = View.VISIBLE
            binding.tvRatingPercentage.text = String.format("%.1f", rating)
        } else {
            binding.layoutRating.visibility = View.GONE
        }
        updateMetaDots()

        val cover = s.cover
        if (!cover.isNullOrEmpty() && adapter?.seriesCoverUrl != cover) {
            adapter?.seriesCoverUrl = com.tvonnet.debridxtreamiptv.util.GlobalConfig.resolveIconUrl(cover)
            adapter?.notifyItemRangeChanged(0, adapter?.itemCount ?: 0, Any())
        }
        updateTrailerButtonState()
    }

    private fun bindSeriesTotals(seasons: Int, episodes: Int) {
        if (seasons <= 0 && episodes <= 0) {
            binding.tvSeasonEpisodeCount.visibility = View.GONE
        } else {
            binding.tvSeasonEpisodeCount.visibility = View.VISIBLE
            val sLabel = if (seasons == 1) "SEASON" else "SEASONS"
            val eLabel = if (episodes == 1) "EPISODE" else "EPISODES"
            binding.tvSeasonEpisodeCount.text = "$seasons $sLabel · $episodes $eLabel"
        }
        updateMetaDots()
    }

    private fun bindSummary(categoryCount: Int) {
        if (categoryCount >= 2) {
            binding.layoutSummary.visibility = View.VISIBLE
            binding.tvSummary.text = "AVAILABLE IN $categoryCount CATEGORIES · MULTI-SOURCE"
        } else {
            binding.layoutSummary.visibility = View.GONE
        }
    }

    private fun updateMetaDots() {
        val yearVisible = binding.tvYear.visibility == View.VISIBLE
        val countVisible = binding.tvSeasonEpisodeCount.visibility == View.VISIBLE
        val genreVisible = binding.tvGenre.visibility == View.VISIBLE
        binding.tvDot1.visibility = if (yearVisible && countVisible) View.VISIBLE else View.GONE
        binding.tvDot2.visibility = if (countVisible && genreVisible) View.VISIBLE else View.GONE
    }

    private fun updatePlayButton(episode: EpisodeEntityV2) {
        val label = "S%02d · E%02d".format(episode.seasonNumber, episode.episodeNumber)
        val resumed = episode.resumePosition > 0 && episode.duration > 0 &&
            episode.resumePosition < episode.duration * 1000L
        binding.btnPlay.text = if (resumed) "Resume $label" else "Play $label"
    }

    // ── Season selector ────────────────────────────────────────────────────────

    private fun updateSeasonButton(selected: Int?) {
        binding.btnSeasonSelector.text = "SEASON ${selected ?: currentSeasons.firstOrNull()?.seasonNum ?: 1}"
    }

    private fun showSeasonPopup() {
        val seasons = currentSeasons
        if (seasons.isEmpty()) return
        val ctx = requireContext()
        val selected = viewModel.selectedSeason.value

        val content = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(0xFF0E0D15.toInt())
            setPadding(dp(6), dp(6), dp(6), dp(6))
        }
        content.addView(TextView(ctx).apply {
            text = "SELECT SEASON"
            setTextColor(0xFF64748B.toInt())
            textSize = 5f
            typeface = Typeface.MONOSPACE
            letterSpacing = 0.12f
            setPadding(dp(5), dp(3), dp(5), dp(4))
        })

        val popup = PopupWindow(content, dp(118), ViewGroup.LayoutParams.WRAP_CONTENT, true).apply {
            setBackgroundDrawable(android.graphics.drawable.ColorDrawable(0xFF0E0D15.toInt()))
            elevation = 12f
        }
        seasonPopup = popup

        seasons.forEach { season ->
            val isActive = season.seasonNum == selected
            val row = LinearLayout(ctx).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(7), 0, dp(7), 0)
                minimumHeight = dp(22)
                isFocusable = true
                isClickable = true
                if (isActive) setBackgroundColor(0x1400F0FF)
                setOnClickListener {
                    viewModel.onSeasonSelected(season.seasonNum)
                    updateSeasonButton(season.seasonNum)
                    popup.dismiss()
                }
                setOnFocusChangeListener { v, hasFocus ->
                    v.setBackgroundColor(if (hasFocus) 0x2400F0FF else if (isActive) 0x1400F0FF else 0x00000000)
                }
            }
            row.addView(TextView(ctx).apply {
                text = "Season ${season.seasonNum}"
                setTextColor(if (isActive) 0xFF00F0FF.toInt() else 0xFFE2E8F0.toInt())
                textSize = 7f
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            })
            if (isActive) {
                row.addView(TextView(ctx).apply {
                    text = "●"
                    setTextColor(0xFF00F0FF.toInt())
                    textSize = 6f
                })
            }
            content.addView(row)
        }

        content.measure(View.MeasureSpec.UNSPECIFIED, View.MeasureSpec.UNSPECIFIED)
        popup.showAsDropDown(binding.btnSeasonSelector, 0, -binding.btnSeasonSelector.height - content.measuredHeight, Gravity.START or Gravity.BOTTOM)
    }

    // ── Stream panel rendering ─────────────────────────────────────────────────

    private fun renderStreamPanel(state: StreamPanelState) {
        val panel = binding.panelStreams
        val scrim = binding.viewScrim

        if (!state.visible) {
            if (panel.visibility == View.VISIBLE) {
                panel.animate().translationX(panel.width.toFloat()).setDuration(200).withEndAction {
                    panel.visibility = View.GONE
                    panel.translationX = 0f
                }.start()
                scrim.animate().alpha(0f).setDuration(200).withEndAction { scrim.visibility = View.GONE }.start()
            }
            setBackgroundInteractable(true)
            return
        }

        setBackgroundInteractable(false)
        if (panel.visibility != View.VISIBLE) {
            pendingStreamFocus = true
            panel.visibility = View.VISIBLE
            panel.post {
                panel.translationX = panel.width.toFloat()
                panel.animate().translationX(0f).setDuration(260).start()
            }
            scrim.visibility = View.VISIBLE
            scrim.alpha = 0f
            scrim.animate().alpha(1f).setDuration(200).start()
        }

        binding.tvEpisodeLabel.text = state.episodeLabel
        styleChip(binding.chipQuality, "Quality", state.filters.quality)
        styleChip(binding.chipLanguage, "Language", state.filters.language)
        styleChip(binding.chipContainer, "Format", state.filters.container)

        binding.pbStreams.visibility = if (state.loading) View.VISIBLE else View.GONE

        val groups = state.filteredGroups
        val streamCount = state.filteredStreamCount
        binding.tvScanLine.text = "$streamCount ${if (streamCount == 1) "stream" else "streams"} across " +
            "${groups.size} ${if (groups.size == 1) "category" else "categories"}"

        when {
            state.loading -> {
                binding.tvNoStreams.visibility = View.GONE
                streamAdapter?.submit(emptyList(), null)
            }
            groups.isEmpty() -> {
                binding.tvNoStreams.visibility = View.VISIBLE
                binding.tvNoStreams.text = state.error ?: "NO STREAMS MATCH CURRENT FILTERS"
                streamAdapter?.submit(emptyList(), null)
            }
            else -> {
                binding.tvNoStreams.visibility = View.GONE
                streamAdapter?.submit(groups, state.resolvingStreamId)
                if (pendingStreamFocus) {
                    pendingStreamFocus = false
                    binding.rvStreams.post {
                        val pos = streamAdapter?.firstRowPosition() ?: -1
                        if (pos >= 0) binding.rvStreams.findViewHolderForAdapterPosition(pos)?.itemView?.requestFocus()
                    }
                }
            }
        }
    }

    private fun styleChip(tv: TextView, label: String, value: String) {
        val active = value != "All"
        tv.text = "$label: $value"
        tv.setTextColor(if (active) 0xFF00F0FF.toInt() else 0xFF94A3B8.toInt())
        val fill = if (active) 0x1400F0FF else 0x08FFFFFF
        val stroke = if (active) 0x4000F0FF else 0x12FFFFFF
        tv.background = GradientDrawable().apply {
            setColor(fill)
            cornerRadius = dp(4).toFloat()
            setStroke(dp(1), stroke)
        }
    }

    /** Prevent background focus escaping while the stream panel is open. */
    private fun setBackgroundInteractable(enabled: Boolean) {
        binding.btnPlay.isFocusable = enabled
        binding.btnTrailer.isFocusable = enabled
        binding.btnFavorite.isFocusable = enabled
        binding.btnSeasonSelector.isFocusable = enabled
        binding.rvEpisodes.descendantFocusability =
            if (enabled) ViewGroup.FOCUS_AFTER_DESCENDANTS else ViewGroup.FOCUS_BLOCK_DESCENDANTS
    }

    private fun restoreFocus() {
        val targetId = lastFocusedEpisodeId ?: return
        binding.rvEpisodes.post {
            val snapshot = adapter?.snapshot()?.items ?: return@post
            val position = snapshot.indexOfFirst { it.episodeId == targetId }
            if (position != -1) {
                binding.rvEpisodes.scrollToPosition(position)
                binding.rvEpisodes.post {
                    binding.rvEpisodes.findViewHolderForAdapterPosition(position)?.itemView?.requestFocus()
                }
            }
        }
    }

    private fun updateTrailerButtonState() {
        if (_binding == null) return
        val hasTrailer = TrailerValueParser.parse(resolvedTrailer) != null
        binding.btnTrailer.isEnabled = hasTrailer
        binding.btnTrailer.alpha = if (hasTrailer) 1.0f else 0.45f
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

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    override fun onDestroyView() {
        super.onDestroyView()
        seasonPopup?.dismiss()
        seasonPopup = null
        toastRunnable = null
        _binding = null
    }
}
