package com.tvonnet.debridxtreamiptv.features.seriesv2.ui

import com.tvonnet.debridxtreamiptv.R

import android.view.View
import androidx.core.view.OneShotPreDrawListener
import androidx.paging.CombinedLoadStates
import androidx.paging.LoadState
import androidx.recyclerview.widget.LinearLayoutManager
import com.tvonnet.debridxtreamiptv.databinding.FragmentSeriesDetailV2Binding
import com.tvonnet.debridxtreamiptv.features.seriesv2.data.model.EpisodeEntityV2
import com.tvonnet.debridxtreamiptv.utils.isFocusInsideThis

/**
 * C4: the episodes strip — its adapter, the loading/empty decision, the shimmer skeleton and
 * focus restoration across a season switch.
 *
 * The whole reason this is its own collaborator is the **grace window**: Room paging reports
 * "empty" instantly on a cold open while `get_series_info` is still on the wire, so an empty
 * strip may only be called empty *after* [EPISODES_GRACE_MS]. Getting that wrong shows
 * "No episodes" on a series that is merely still loading.
 *
 * Bodies moved verbatim from `SeriesDetailFragmentV2`; only `this`/field references were rebound.
 */
class SeriesEpisodesStripUi(
    private val binding: FragmentSeriesDetailV2Binding,
    private val page: SeriesDetailPage,
    private val onEpisodeClick: (EpisodeEntityV2) -> Unit,
    private val onEpisodeFocused: (EpisodeEntityV2) -> Unit,
) {
    companion object {
        /** Empty strip within this window after open = still loading, not "No episodes". */
        const val EPISODES_GRACE_MS = 12_000L
    }

    var adapter: EpisodesAdapterV2? = null
        private set

    /** The episode the user last had focus on, so a season switch can land back on it. */
    var lastFocusedEpisodeId: String? = null
        private set

    private var episodeShimmerRunning = false
    private var pageOpenedAt = 0L
    private var lastEpisodeLoadStates: CombinedLoadStates? = null

    fun setup(): EpisodesAdapterV2 {
        val created = EpisodesAdapterV2(
            onEpisodeClick = { episode -> onEpisodeClick(episode) },
            onEpisodeFocused = { episode ->
                lastFocusedEpisodeId = episode.episodeId
                adapter?.setSelectedEpisode(episode.episodeId)
                onEpisodeFocused(episode)
            }
        ).also { it.seriesId = page.seriesId }
        adapter = created

        binding.rvEpisodes.layoutManager =
            LinearLayoutManager(binding.root.context, LinearLayoutManager.HORIZONTAL, false)
        binding.rvEpisodes.adapter = created
        binding.rvEpisodes.itemAnimator = null

        // CC-1 for the PAGING strip: a Room-driven refresh (get_series_info clear+insert, its
        // fallback strategies, a season switch) lands WHILE the user is D-padding the strip.
        // When the diff removes/re-adds the focused card, the framework hands focus to the
        // first focusable ABOVE the strip — the "focus jumps up to the header" bug. At
        // pages-updated time the outgoing child still holds focus, so "the user was in the
        // strip" is captured here; after the layout that applies the update, focus having
        // LEFT the strip can only mean the update took it (nobody D-pads during a layout
        // pass) — so it is put back on the same episode. A user focused elsewhere (header,
        // seasons) trips the first check and nothing is touched: no focus-steal.
        created.addOnPagesUpdatedListener {
            val rv = binding.rvEpisodes
            if (!rv.isFocusInsideThis()) return@addOnPagesUpdatedListener
            if (lastFocusedEpisodeId == null) return@addOnPagesUpdatedListener
            OneShotPreDrawListener.add(rv) {
                if (page.isViewAlive() && !rv.isFocusInsideThis()) restoreFocus()
            }
        }

        pageOpenedAt = android.os.SystemClock.elapsedRealtime()
        // Re-evaluate once the grace window lapses so a genuinely-dead listing flips
        // from the skeleton to "No episodes" even without a new LoadState emission.
        binding.root.postDelayed({
            if (page.isViewAlive()) evaluateLoadingUi()
        }, EPISODES_GRACE_MS + 250)

        return created
    }

    /** Called for every paging load-state emission. */
    fun onLoadStates(states: CombinedLoadStates) {
        lastEpisodeLoadStates = states
        evaluateLoadingUi()
    }

    /**
     * Episodes strip loading/empty UI. Room paging reports "empty" instantly on a
     * cold open while get_series_info is still on the wire — so an empty strip only
     * counts as truly empty AFTER the grace window; until then the shimmer skeleton
     * shows so the user can see something is loading.
     */
    fun evaluateLoadingUi() {
        if (!page.isViewAlive()) return
        val b = binding
        val states = lastEpisodeLoadStates ?: return
        val count = adapter?.itemCount ?: 0
        val loading = states.refresh is LoadState.Loading ||
            states.mediator?.refresh is LoadState.Loading
        val error = states.refresh is LoadState.Error
        val withinGrace =
            android.os.SystemClock.elapsedRealtime() - pageOpenedAt < EPISODES_GRACE_MS
        val emptySettled =
            states.refresh is LoadState.NotLoading && count == 0 && !loading && !withinGrace
        val showSkeleton = count == 0 && !error && !emptySettled
        b.pbLoadingCentral.visibility = View.GONE
        b.llEpisodesLoading.visibility = if (showSkeleton) View.VISIBLE else View.GONE
        if (showSkeleton) startEpisodeShimmer() else stopEpisodeShimmer()
        b.tvEmptyEpisodes.visibility = if (emptySettled) View.VISIBLE else View.GONE
        if (emptySettled) b.tvEmptyEpisodes.text = b.tvEmptyEpisodes.context.getString(R.string.c_no_episodes)
    }

    private fun startEpisodeShimmer() {
        if (episodeShimmerRunning) return
        episodeShimmerRunning = true
        val container = binding.llEpisodesLoading
        for (i in 0 until container.childCount) {
            val shimmer = container.getChildAt(i)
                ?.findViewById<View>(com.tvonnet.debridxtreamiptv.R.id.v_shimmer) ?: continue
            val anim = android.view.animation.TranslateAnimation(
                android.view.animation.Animation.RELATIVE_TO_PARENT, -1.0f,
                android.view.animation.Animation.RELATIVE_TO_PARENT, 1.0f,
                android.view.animation.Animation.RELATIVE_TO_PARENT, 0f,
                android.view.animation.Animation.RELATIVE_TO_PARENT, 0f
            ).apply {
                duration = 1200L + (i * 80L)
                repeatCount = android.view.animation.Animation.INFINITE
                repeatMode = android.view.animation.Animation.RESTART
                interpolator = android.view.animation.LinearInterpolator()
            }
            shimmer.startAnimation(anim)
        }
    }

    private fun stopEpisodeShimmer() {
        if (!episodeShimmerRunning) return
        episodeShimmerRunning = false
        val container = binding.llEpisodesLoading
        for (i in 0 until container.childCount) {
            container.getChildAt(i)
                ?.findViewById<View>(com.tvonnet.debridxtreamiptv.R.id.v_shimmer)?.clearAnimation()
        }
    }

    // ── Cross-observer adapter updates ─────────────────────────────────────────

    fun applyWatchedKeys(keys: Set<String>) {
        if (adapter?.watchedKeys != keys) {
            adapter?.watchedKeys = keys
            adapter?.notifyItemRangeChanged(0, adapter?.itemCount ?: 0, Any())
        }
    }

    fun applyProgress(progress: Map<String, Long>) {
        if (adapter?.progressByKey != progress) {
            adapter?.progressByKey = progress
            adapter?.notifyItemRangeChanged(0, adapter?.itemCount ?: 0, Any())
        }
    }

    /**
     * [rawCover] is the provider's own value; the adapter is given the *resolved* URL.
     * The guard deliberately compares the raw value, exactly as before the split.
     */
    fun applyCoverUrl(rawCover: String) {
        if (adapter?.seriesCoverUrl != rawCover) {
            adapter?.seriesCoverUrl =
                com.tvonnet.debridxtreamiptv.util.GlobalConfig.resolveIconUrl(rawCover)
            adapter?.notifyItemRangeChanged(0, adapter?.itemCount ?: 0, Any())
        }
    }

    fun firstEpisode(): EpisodeEntityV2? = adapter?.snapshot()?.items?.firstOrNull()

    fun restoreFocus() {
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
}
