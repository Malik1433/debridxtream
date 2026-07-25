package com.tvonnet.debridxtreamiptv.ui.series

import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.PopupWindow
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.AppCompatButton
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.RecyclerView
import com.tvonnet.debridxtreamiptv.R
import com.tvonnet.debridxtreamiptv.data.model.XtreamEpisodeInfo
import com.tvonnet.debridxtreamiptv.data.model.XtreamSeriesDetailResponse
import com.tvonnet.debridxtreamiptv.data.repository.WatchedStateRepository
import com.tvonnet.debridxtreamiptv.utils.updatePreservingFocus
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * SD-3: the season/episode UI concern lifted out of [SeriesDetailActivity]. Owns the season +
 * episode RecyclerViews, their adapters, the season-selector popup, the resume/continue bar, the
 * "watch now" button state, and the per-season watched-progress load. It is the single source of
 * truth for the currently-selected season/episode and the loaded watched-progress maps; the
 * Activity's playback code (SD-4) reads that state through the exposed accessors and hands the
 * chosen episode back via [onPlayEpisode].
 *
 * Behaviour byte-identical to the old private methods (`showSeasonPopup`, `buildSeasonList`,
 * `showEpisodesForSeason`, `showEpisodes`, `loadSeasonWatchedProgress`, `updateSeasonProgress`,
 * `updateResumeSection`, `renderResumeBar`, `updateWatchNowState`, `updateSeasonButton`); only the
 * `this`/field references were rebound to [activity] and the injected getters.
 */
class SeriesSeasonUi(
    private val activity: AppCompatActivity,
    private val rvSeasons: RecyclerView,
    private val rvEpisodes: RecyclerView,
    private val tvEmptyEpisodes: TextView,
    private val btnWatchNow: AppCompatButton,
    private val btnSeasonSelector: AppCompatButton,
    private val tvSeasonProgress: TextView,
    private val tvSeasonEpisodeCount: TextView,
    private val layoutResume: LinearLayout,
    private val tvResumeLabel: TextView,
    private val tvResumeRight: TextView,
    private val vResumeProgress: View,
    private val tvResumeEpTitle: TextView,
    private val watchedStateRepository: WatchedStateRepository,
    private val isDebrid: () -> Boolean,
    private val seriesId: () -> String?,
    private val seriesName: () -> String?,
    private val seriesReleaseDate: () -> String?,
    private val onPlayEpisode: (EpisodeUiModel) -> Unit,
) {
    private val seasonsAdapter: SeriesSeasonAdapter
    private val cinEpisodeAdapter: CinEpisodeAdapter

    var currentDetail: XtreamSeriesDetailResponse? = null
        private set
    var selectedSeasonKey: String? = null
        private set
    var selectedEpisode: EpisodeUiModel? = null
        private set

    // Current season's episodes + their loaded watch progress (episodeId -> pct / watched set)
    var currentEpisodes: List<EpisodeUiModel> = emptyList()
        private set
    private val episodeProgress = mutableMapOf<String, Int>()
    private val episodeProgressMs = mutableMapOf<String, Long>()
    private val episodeWatched = mutableSetOf<String>()

    init {
        seasonsAdapter = SeriesSeasonAdapter(
            onSeasonSelected = { season ->
                selectedSeasonKey = season.key
                showEpisodesForSeason(season.key)
                updateSeasonButton()
            },
            onSeasonFocused = { season ->
                selectedSeasonKey = season.key
                showEpisodesForSeason(season.key)
                updateSeasonButton()
            }
        )
        rvSeasons.adapter = seasonsAdapter

        cinEpisodeAdapter = CinEpisodeAdapter(
            onEpisodeClick = { episode ->
                selectedEpisode = episode
                cinEpisodeAdapter.setSelectedEpisode(episode.id)
                updateWatchNowState()
                onPlayEpisode(episode)
            },
            onEpisodeFocused = { episode ->
                selectedEpisode = episode
                cinEpisodeAdapter.setSelectedEpisode(episode.id)
                updateWatchNowState()
            }
        )
        rvEpisodes.adapter = cinEpisodeAdapter
    }

    // ---- Playback (SD-4) read accessors ------------------------------------------------------

    /** Saved resume position for this episode (null when watched or never started). */
    fun resumePositionMs(episodeId: String): Long? =
        episodeProgressMs[episodeId]?.takeIf { it > 0 && !episodeWatched.contains(episodeId) }

    fun isWatched(episodeId: String): Boolean = episodeWatched.contains(episodeId)

    fun hasStoredProgress(episodeId: String): Boolean = episodeProgress.containsKey(episodeId)

    // ---- Public entry points ------------------------------------------------------------------

    /** Sync the header controls (Watch button + season button) after a series-info change. */
    fun refreshControls() {
        updateWatchNowState()
        updateSeasonButton()
    }

    /**
     * Returning from the player: re-read watched-state so progress bars, the continue bar and
     * the Play/Resume label reflect the latest position.
     */
    fun onResume() {
        if (currentEpisodes.isNotEmpty()) {
            loadSeasonWatchedProgress()
        }
    }

    /** Error/fallback path: show the empty-episodes label (optionally with an error message). */
    fun showEmptyEpisodes(errorMessage: String? = null) {
        if (errorMessage != null) {
            tvEmptyEpisodes.text = errorMessage
        }
        showEpisodes(emptyList())
    }

    fun showSeasonPopup() {
        val episodeKeys = currentDetail?.episodes?.keys
            ?.sortedWith(compareBy { it.toIntOrNull() ?: Int.MAX_VALUE }) ?: return
        if (episodeKeys.isEmpty()) return

        // Rounded panel background (design: #0E0D15, cyan border, 8px radius)
        val panelBg = android.graphics.drawable.GradientDrawable().apply {
            setColor(0xFF0E0D15.toInt())
            cornerRadius = dp(8).toFloat()
            setStroke(dp(1), 0x4000F0FF)
        }

        val popupView = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            background = panelBg
            setPadding(dp(6), dp(6), dp(6), dp(6))
        }

        val headerTv = TextView(activity).apply {
            text = "SELECT SEASON"
            setTextColor(0xFF64748B.toInt())
            textSize = 9f
            typeface = android.graphics.Typeface.MONOSPACE
            letterSpacing = 0.12f
            setPadding(dp(8), dp(5), dp(8), dp(6))
        }
        popupView.addView(headerTv)

        val popup = PopupWindow(
            popupView,
            dp(178),
            ViewGroup.LayoutParams.WRAP_CONTENT,
            true
        ).apply {
            setBackgroundDrawable(android.graphics.drawable.ColorDrawable(android.graphics.Color.TRANSPARENT))
            elevation = dp(12).toFloat()
        }

        episodeKeys.forEachIndexed { index, key ->
            val seasonNum = key.toIntOrNull() ?: (index + 1)
            val epCount = currentDetail?.episodes?.get(key)?.size ?: 0
            val isActive = key == selectedSeasonKey

            val itemBg = android.graphics.drawable.GradientDrawable().apply {
                setColor(if (isActive) 0x1400F0FF else 0x00000000)
                cornerRadius = dp(5).toFloat()
            }
            val itemView = LinearLayout(activity).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(12), 0, dp(10), 0)
                minimumHeight = dp(40)
                isFocusable = true
                isClickable = true
                background = itemBg
                setOnClickListener {
                    selectedSeasonKey = key
                    showEpisodesForSeason(key)
                    updateSeasonButton()
                    popup.dismiss()
                }
                setOnFocusChangeListener { _, hasFocus ->
                    itemBg.setColor(
                        when {
                            hasFocus -> 0x2400F0FF
                            isActive -> 0x1400F0FF
                            else -> 0x00000000
                        }
                    )
                }
            }

            val labelTv = TextView(activity).apply {
                text = "Season $seasonNum"
                setTextColor(if (isActive) 0xFF00F0FF.toInt() else 0xFFE2E8F0.toInt())
                textSize = 14f
                setTypeface(typeface, android.graphics.Typeface.BOLD)
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            }
            itemView.addView(labelTv)

            val countTv = TextView(activity).apply {
                text = "$epCount EP"
                setTextColor(0xFF64748B.toInt())
                textSize = 11f
                typeface = android.graphics.Typeface.MONOSPACE
                setPadding(0, 0, dp(8), 0)
            }
            itemView.addView(countTv)

            val check = TextView(activity).apply {
                text = if (isActive) "●" else ""
                setTextColor(0xFF00F0FF.toInt())
                textSize = 11f
            }
            itemView.addView(check)

            popupView.addView(itemView, LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(2) })
        }

        popupView.measure(
            View.MeasureSpec.makeMeasureSpec(dp(178), View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED)
        )
        popup.showAsDropDown(
            btnSeasonSelector,
            0,
            -btnSeasonSelector.height - popupView.measuredHeight - dp(4),
            Gravity.START or Gravity.BOTTOM
        )
    }

    private fun updateSeasonButton() {
        val seasonNum = selectedSeasonKey?.toIntOrNull()
        btnSeasonSelector.text = if (seasonNum != null) "SEASON $seasonNum ▾" else "SEASON ▾"
    }

    private fun dp(value: Int): Int = (value * activity.resources.displayMetrics.density).toInt()

    fun buildSeasonList(detail: XtreamSeriesDetailResponse) {
        currentDetail = detail
        val seasonItems = when {
            !detail.seasons.isNullOrEmpty() -> {
                detail.seasons.mapIndexedNotNull { index, season ->
                    val key = season.season_number ?: (index + 1).toString()
                    val displayName = season.name?.takeIf { it.isNotBlank() }
                        ?: activity.getString(R.string.series_detail_season_name, key.toIntOrNull() ?: (index + 1))
                    SeasonUiModel(key = key, displayName = displayName)
                }
            }
            !detail.episodes.isNullOrEmpty() -> {
                detail.episodes.keys.sortedWith(compareBy { it.toIntOrNull() ?: Int.MAX_VALUE })
                    .mapIndexed { index, key ->
                        SeasonUiModel(
                            key = key,
                            displayName = activity.getString(
                                R.string.series_detail_season_name,
                                key.toIntOrNull() ?: (index + 1)
                            )
                        )
                    }
            }
            else -> emptyList()
        }

        seasonsAdapter.submitList(seasonItems, selectedSeasonKey)

        if (seasonItems.isNotEmpty()) {
            // Prefer the first season that actually has episodes (skip empty "Specials" / season 0).
            val firstWithEpisodes = seasonItems.firstOrNull { season ->
                !currentDetail?.episodes?.get(season.key).isNullOrEmpty()
            }?.key
            val initialKey = selectedSeasonKey ?: firstWithEpisodes ?: seasonItems.first().key
            selectedSeasonKey = initialKey
            showEpisodesForSeason(initialKey)
            updateSeasonButton()
            rvEpisodes.post { rvEpisodes.requestFocus() }
        } else {
            showEpisodes(emptyList())
        }
    }

    private fun showEpisodesForSeason(seasonKey: String) {
        val episodes = currentDetail?.episodes?.get(seasonKey)
        if (episodes.isNullOrEmpty()) {
            showEpisodes(emptyList())
            return
        }

        val episodeModels = episodes
            .filter { !it.id.isNullOrBlank() }
            .sortedWith(compareBy<XtreamEpisodeInfo> { it.episode_num ?: Int.MAX_VALUE }
                .thenBy { it.title ?: "" })
            .map { episode ->
                EpisodeUiModel(
                    id = episode.id!!,
                    title = episode.title ?: "Episode",
                    episodeNumber = episode.episode_num,
                    description = episode.info?.plot,
                    durationMinutes = parseDurationMinutes(episode),
                    thumbnailUrl = episode.info?.cover ?: episode.thumbnail,
                    containerExtension = episode.container_extension,
                    isWatched = episode.isWatched,
                    resumePosition = episode.resumePosition
                )
            }

        showEpisodes(episodeModels)
    }

    private fun parseDurationMinutes(episode: XtreamEpisodeInfo): Int? {
        val durationSeconds = episode.duration_secs
            ?: episode.info?.duration_secs
        val seconds = durationSeconds?.toLongOrNull()
        return seconds?.let { (it / 60).toInt().coerceAtLeast(1) }
    }

    private fun showEpisodes(episodes: List<EpisodeUiModel>) {
        currentEpisodes = episodes
        if (episodes.isEmpty()) {
            tvEmptyEpisodes.visibility = View.VISIBLE
            cinEpisodeAdapter.submitEpisodes(emptyList(), null)
            selectedEpisode = null
        } else {
            tvEmptyEpisodes.visibility = View.GONE
            val currentSelectionId = selectedEpisode?.id
            val newSelection = episodes.firstOrNull { it.id == currentSelectionId } ?: episodes.first()
            selectedEpisode = newSelection
            cinEpisodeAdapter.submitEpisodes(episodes, newSelection.id)
        }
        updateWatchNowState()
        updateSeasonProgress(episodes)
        updateResumeSection(episodes)
        loadSeasonWatchedProgress()
    }

    /**
     * Loads real watched-state (progress + watched) for every episode in the current
     * season and pushes it to the adapter, the continue bar and the Play button.
     * Bound to the season's episode data, not the focused card.
     */
    private fun loadSeasonWatchedProgress() {
        val episodes = currentEpisodes
        if (episodes.isEmpty()) return
        val seasonNumber = selectedSeasonKey?.toIntOrNull()
        activity.lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) {
                val progress = HashMap<String, Int>()
                val progressMs = HashMap<String, Long>()
                val watched = HashSet<String>()
                for (ep in episodes) {
                    var pct: Int? = null
                    var posMs: Long? = null
                    var isW = ep.isWatched
                    // 1) resume position carried by the episode model (Xtream)
                    val durMin = ep.durationMinutes ?: 0
                    if (ep.resumePosition > 0 && durMin > 0) {
                        pct = ((ep.resumePosition.toFloat() / (durMin * 60_000L)) * 100).toInt().coerceIn(0, 100)
                        posMs = ep.resumePosition
                    }
                    // 2) watched-state store (Debrid + anything with a saved position)
                    if (seasonNumber != null) {
                        val key = if (isDebrid()) {
                            com.tvonnet.debridxtreamiptv.data.local.WatchedIdentityBuilder.debridEpisode(
                                seriesIdentity = seriesId(),
                                seasonNumber = seasonNumber,
                                episodeNumber = ep.episodeNumber,
                                seriesTitle = seriesName(),
                                seriesYear = seriesReleaseDate()?.take(4),
                                fallbackDiscriminator = ep.id.ifBlank { ep.title }
                            )
                        } else {
                            com.tvonnet.debridxtreamiptv.data.local.WatchedIdentityBuilder.iptvEpisode(
                                episodeId = ep.id,
                                seriesId = seriesId(),
                                seasonNumber = seasonNumber,
                                episodeNumber = ep.episodeNumber,
                                fallbackDiscriminator = ep.id.ifBlank { ep.title }
                            )
                        }
                        val state = watchedStateRepository.getState(key)
                        if (state != null) {
                            if (state.isWatched) isW = true
                            if (!state.isWatched && state.durationMs > 0 && state.progressMs > 0) {
                                pct = ((state.progressMs.toFloat() / state.durationMs) * 100).toInt().coerceIn(0, 100)
                                posMs = state.progressMs
                            }
                        }
                    }
                    if (isW) {
                        watched.add(ep.id)
                    } else if (pct != null && pct in 1..99) {
                        progress[ep.id] = pct
                        posMs?.let { progressMs[ep.id] = it }
                    }
                }
                Triple(progress, progressMs, watched)
            }
            episodeProgress.clear()
            episodeProgress.putAll(result.first)
            episodeProgressMs.clear()
            episodeProgressMs.putAll(result.second)
            episodeWatched.clear()
            episodeWatched.addAll(result.third)
            // CC-1: applyOverrides() does a blanket notifyDataSetChanged; on return from the
            // player this fires and would drop focus off the just-watched episode. Preserve
            // the user's focused card (restores to the same episode by stable id).
            rvEpisodes.updatePreservingFocus {
                cinEpisodeAdapter.applyOverrides(episodeProgress, episodeWatched)
            }
            updateResumeSection(episodes)
            updateWatchNowState()
        }
    }

    private fun updateSeasonProgress(episodes: List<EpisodeUiModel>) {
        val watchedCount = episodes.count { it.isWatched }
        tvSeasonProgress.text = "$watchedCount / ${episodes.size} WATCHED"

        // Update season/episode count in metadata
        val totalSeasons = currentDetail?.seasons?.size
            ?: currentDetail?.episodes?.keys?.size ?: 0
        val totalEpisodes = currentDetail?.episodes?.values?.sumOf { it.size } ?: episodes.size
        tvSeasonEpisodeCount.text = "$totalSeasons SEASONS · $totalEpisodes EPISODES"
    }

    private fun updateResumeSection(episodes: List<EpisodeUiModel>) {
        // Bound to the season's in-progress episode data (0 < progress < 100),
        // sourced from episodeProgress which loadSeasonWatchedProgress() fills.
        val resumeEp = episodes.firstOrNull {
            !episodeWatched.contains(it.id) && episodeProgress.containsKey(it.id)
        }
        if (resumeEp != null) {
            val pct = episodeProgress[resumeEp.id] ?: 0
            val durMin = resumeEp.durationMinutes ?: 0
            val minsLeft = if (durMin > 0) {
                ((durMin.toLong() * (100 - pct)) / 100).coerceAtLeast(1)
            } else 1L
            renderResumeBar(resumeEp, pct, minsLeft)
        } else {
            layoutResume.visibility = View.GONE
        }
    }

    private fun renderResumeBar(episode: EpisodeUiModel, pct: Int, minsLeft: Long) {
        layoutResume.visibility = View.VISIBLE
        val seasonNum = selectedSeasonKey?.toIntOrNull() ?: 1
        val epNum = episode.episodeNumber ?: 1
        tvResumeLabel.text = "CONTINUE · S${String.format("%02d", seasonNum)} · E${String.format("%02d", epNum)}"
        tvResumeRight.text = "$pct% · ${minsLeft}M LEFT"
        tvResumeEpTitle.text = episode.title
        vResumeProgress.post {
            val parent = vResumeProgress.parent as? View ?: return@post
            val lp = vResumeProgress.layoutParams
            lp.width = (parent.width * pct / 100f).toInt()
            vResumeProgress.layoutParams = lp
        }
    }

    private fun updateWatchNowState() {
        btnWatchNow.isEnabled = selectedEpisode != null
        val ep = selectedEpisode
        val seasonNum = selectedSeasonKey?.toIntOrNull() ?: 1
        val epNum = ep?.episodeNumber ?: 1
        val epLabel = "S${String.format("%02d", seasonNum)} · E${String.format("%02d", epNum)}"
        val hasResume = ep != null && !episodeWatched.contains(ep.id) &&
            (episodeProgress.containsKey(ep.id) || (ep.resumePosition > 0 && !ep.isWatched))
        btnWatchNow.text = if (hasResume) "Resume $epLabel" else "Play $epLabel"
    }
}
