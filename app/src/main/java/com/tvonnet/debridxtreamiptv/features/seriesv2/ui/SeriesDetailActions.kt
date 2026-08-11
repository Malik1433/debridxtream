package com.tvonnet.debridxtreamiptv.features.seriesv2.ui

import android.graphics.Typeface
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.PopupWindow
import android.widget.TextView
import android.widget.Toast
import androidx.fragment.app.Fragment
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.tvonnet.debridxtreamiptv.R
import com.tvonnet.debridxtreamiptv.data.repository.WatchedStateRepository
import com.tvonnet.debridxtreamiptv.data.repository.XtreamRepository
import com.tvonnet.debridxtreamiptv.databinding.FragmentSeriesDetailV2Binding
import com.tvonnet.debridxtreamiptv.features.seriesv2.data.model.EpisodeEntityV2
import com.tvonnet.debridxtreamiptv.ui.trailer.TrailerActivity
import com.tvonnet.debridxtreamiptv.ui.trailer.TrailerValueParser
import kotlinx.coroutines.launch

/**
 * C4: the top action row — Play / Trailer / Favourite / Season — and the season popup it opens.
 *
 * Grouped by what the user can *do* rather than by widget type, because these four controls share
 * one concern: D-pad focus. They are the only focusable things on the page above the episodes
 * strip, so the focus scale animation, the disabled-Trailer alpha and the popup's own focus
 * handling all have to agree (scale only, never alpha, or the two fight each other).
 *
 * Bodies moved verbatim from `SeriesDetailFragmentV2`; only `this`/field references were rebound.
 */
class SeriesDetailActions(
    private val fragment: Fragment,
    private val binding: FragmentSeriesDetailV2Binding,
    private val repository: XtreamRepository,
    private val watchedStateRepository: WatchedStateRepository,
    private val page: SeriesDetailPage,
    private val host: Host,
) {
    /** What the action row needs back from the screen that owns it. */
    interface Host {
        /** The focused episode, or the first one if focus has not entered the strip yet. */
        fun resolvePlayTarget(): EpisodeEntityV2?
        fun onPlayEpisode(episode: EpisodeEntityV2)
        /** The season the strip is currently filtered to, or null before the first load. */
        fun selectedSeason(): Int?
        fun onSeasonSelected(seasonNum: Int)
        fun showToast(message: String)
    }

    private var currentSeasons: List<SeasonUiModel> = emptyList()
    private var seasonPopup: PopupWindow? = null

    /** The episode whose label the Play button currently shows. */
    private var labelledEpisodeId: String? = null

    val isSeasonPopupShowing: Boolean get() = seasonPopup?.isShowing == true

    /**
     * Clear D-pad focus feedback on the top action row — the series page had none, so the
     * Play button barely showed focus (the movie page, which has this, felt "perfect").
     * Scale only (no alpha) so it never fights the Trailer button's enabled/disabled alpha.
     */
    fun setupFocusAnimations() {
        listOf(binding.btnPlay, binding.btnTrailer, binding.btnFavorite, binding.btnSeasonSelector).forEach { v ->
            v.setOnFocusChangeListener { view, hasFocus ->
                view.animate().cancel()
                if (hasFocus) {
                    view.elevation = 8f // draw above neighbours without reordering the row
                    view.animate().scaleX(1.08f).scaleY(1.08f).setDuration(180)
                        .setInterpolator(android.view.animation.DecelerateInterpolator()).start()
                } else {
                    view.animate().scaleX(1f).scaleY(1f).setDuration(140)
                        .withEndAction { view.elevation = 0f }.start()
                }
            }
        }
    }

    fun setupListeners() {
        binding.btnPlay.setOnClickListener {
            val ep = host.resolvePlayTarget()
            if (ep != null) host.onPlayEpisode(ep)
            else Toast.makeText(fragment.requireContext(), fragment.requireContext().getString(R.string.ui_no_episodes_available), Toast.LENGTH_SHORT).show()
        }
        setupFavoriteButton()
        binding.btnTrailer.setOnClickListener {
            val trailerValue = page.trailer
            if (trailerValue.isNullOrBlank() || TrailerValueParser.parse(trailerValue) == null) {
                Toast.makeText(fragment.requireContext(), fragment.requireContext().getString(R.string.c_no_trailer_available), Toast.LENGTH_SHORT).show()
            } else {
                fragment.startActivity(TrailerActivity.createIntent(fragment.requireContext(), trailerValue))
            }
        }
        binding.btnSeasonSelector.setOnClickListener { showSeasonPopup() }
    }

    /** Real favorite toggle for the series (reuses XtreamRepository favorites, type "series"). */
    private fun setupFavoriteButton() {
        val id = page.seriesId ?: run {
            binding.btnFavorite.visibility = View.GONE
            return
        }
        var isFav = false
        binding.btnFavorite.setImageResource(R.drawable.ic_favorite_border)
        binding.btnFavorite.setOnClickListener {
            fragment.viewLifecycleOwner.lifecycleScope.launch {
                try {
                    if (isFav) {
                        repository.removeFavorite(id)
                        host.showToast("REMOVED FROM FAVORITES")
                    } else {
                        repository.addFavorite(
                            streamId = id,
                            type = "series",
                            name = binding.tvTitle.text?.toString() ?: "Series",
                            iconUrl = com.tvonnet.debridxtreamiptv.util.GlobalConfig.resolveIconUrl(
                                page.favoriteIconUrl
                            )
                        )
                        host.showToast("ADDED TO FAVORITES")
                    }
                } catch (e: Exception) {
                    host.showToast("ERROR: ${e.message}")
                }
            }
        }
        fragment.viewLifecycleOwner.lifecycleScope.launch {
            fragment.viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                repository.getFavoritesByType("series").collect { favorites ->
                    isFav = favorites.any { it.streamId == id }
                    binding.btnFavorite.setImageResource(
                        if (isFav) R.drawable.ic_favorite else R.drawable.ic_favorite_border
                    )
                }
            }
        }
    }

    fun updateTrailerButtonState() {
        if (!page.isViewAlive()) return
        val hasTrailer = TrailerValueParser.parse(page.trailer) != null
        binding.btnTrailer.isEnabled = hasTrailer
        binding.btnTrailer.alpha = if (hasTrailer) 1.0f else 0.45f
    }

    fun updatePlayButton(episode: EpisodeEntityV2) {
        val label = "S%02d · E%02d".format(episode.seasonNumber, episode.episodeNumber)
        // Default label immediately; EpisodeEntityV2.resumePosition is never populated
        // in the V2 flow, so the real progress comes from WatchedStateRepository.
        labelledEpisodeId = episode.episodeId
        binding.btnPlay.text = fragment.getString(R.string.f_play_label, label)
        fragment.viewLifecycleOwner.lifecycleScope.launch {
            val key = com.tvonnet.debridxtreamiptv.data.local.WatchedIdentityBuilder.iptvEpisode(
                episodeId = episode.episodeId,
                seriesId = page.seriesId,
                seasonNumber = episode.seasonNumber,
                episodeNumber = episode.episodeNumber,
                fallbackDiscriminator = episode.episodeId.ifBlank { episode.title }
            )
            val state = watchedStateRepository.getState(key)
            val resumed = state != null && !state.isWatched && state.progressMs > 0L &&
                (state.durationMs <= 0L || state.progressMs < state.durationMs)
            if (!page.isViewAlive()) return@launch
            // Ignore stale results if focus moved to another episode meanwhile.
            if (labelledEpisodeId != episode.episodeId) return@launch
            binding.btnPlay.text = if (resumed) "Resume $label" else "Play $label"
        }
    }

    // ── Season selector ────────────────────────────────────────────────────────

    fun setSeasons(seasons: List<SeasonUiModel>) {
        currentSeasons = seasons
    }

    fun updateSeasonButton(selected: Int?) {
        // Caret matches the Debrid series page (SeriesDetailActivity: "SEASON n ▾").
        binding.btnSeasonSelector.text = "SEASON ${selected ?: currentSeasons.firstOrNull()?.seasonNum ?: 1} ▾"
    }

    private fun showSeasonPopup() {
        val seasons = currentSeasons
        if (seasons.isEmpty()) return
        val ctx = fragment.requireContext()
        val selected = host.selectedSeason()

        val content = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(0xFF0E0D15.toInt())
            setPadding(dp(6), dp(6), dp(6), dp(6))
        }
        content.addView(TextView(ctx).apply {
            text = context.getString(R.string.c_select_season)
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

        seasons.forEach { season -> content.addView(seasonRow(season, selected, popup)) }

        content.measure(View.MeasureSpec.UNSPECIFIED, View.MeasureSpec.UNSPECIFIED)
        popup.showAsDropDown(
            binding.btnSeasonSelector, 0,
            -binding.btnSeasonSelector.height - content.measuredHeight,
            Gravity.START or Gravity.BOTTOM
        )
    }

    private fun seasonRow(season: SeasonUiModel, selected: Int?, popup: PopupWindow): View {
        val ctx = fragment.requireContext()
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
                host.onSeasonSelected(season.seasonNum)
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
        return row
    }

    fun dismissSeasonPopup() {
        seasonPopup?.dismiss()
    }

    fun onDestroyView() {
        seasonPopup?.dismiss()
        seasonPopup = null
    }

    private fun dp(v: Int): Int = (v * fragment.resources.displayMetrics.density).toInt()
}
