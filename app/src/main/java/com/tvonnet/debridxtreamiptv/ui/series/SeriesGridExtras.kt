package com.tvonnet.debridxtreamiptv.ui.series

import android.widget.ImageView
import android.widget.Toast
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import com.bumptech.glide.Glide
import com.bumptech.glide.load.resource.drawable.DrawableTransitionOptions
import com.tvonnet.debridxtreamiptv.data.model.XtreamSeriesInfo
import kotlinx.coroutines.launch

/**
 * C3: the small per-card concerns of the Series grid — the favourite toggle, the episode-count
 * cache the cards read, and the focus-follows backdrop.
 *
 * Two performance notes live here because both were regressions once:
 *  - the backdrop is **debounced**: surfing the grid must not repaint the whole background on
 *    every D-pad step, only once focus rests;
 *  - [loadEpisodeCounts] refreshes the cache and deliberately does **not** notify the adapter
 *    (B-5). The old `notifyDataSetChanged()` rebound every visible card — restarting each Glide
 *    poster load, i.e. a poster flash on view-create and every `onResume` — to update a count the
 *    series card never actually renders.
 *
 * Bodies moved verbatim from `SeriesFragment`; only `this`/field references were rebound.
 */
class SeriesGridExtras(
    private val fragment: Fragment,
    private val viewModel: SeriesViewModel,
    private val adapter: () -> SeriesPagingAdapter,
    private val backdropView: () -> ImageView?,
) {
    /** series_id -> (seasonCount, episodeCount) from the local episodes cache. */
    private var episodeCountsCache: Map<String, Pair<Int, Int>> = emptyMap()

    private val backdropHandler = android.os.Handler(android.os.Looper.getMainLooper())
    private var pendingBackdrop: Runnable? = null

    fun countsFor(seriesId: String): Pair<Int, Int>? = episodeCountsCache[seriesId]

    fun loadEpisodeCounts() {
        fragment.viewLifecycleOwner.lifecycleScope.launch {
            try {
                val counts = viewModel.getEpisodeCounts()
                // B-5: only refresh the cache. The old notifyDataSetChanged() here
                // rebound EVERY visible card (restarting each Glide poster load = a
                // poster-reload flash on view-create and every onResume) purely to
                // update episode counts that the series card does NOT display
                // (SeriesPagingViewHolder.bind never renders `counts`). No rebind needed.
                episodeCountsCache = counts
            } catch (_: Exception) {}
        }
    }

    fun handleFavoriteLongPress(series: XtreamSeriesInfo) {
        val streamId = series.series_id ?: return

        fragment.viewLifecycleOwner.lifecycleScope.launch {
            try {
                val repository = viewModel.getRepository()
                val isFavorite = viewModel.isFavorite(streamId)

                if (isFavorite) {
                    repository.removeFavorite(streamId)
                    toast("Removed from favorites")
                } else {
                    repository.addFavorite(
                        streamId = streamId,
                        type = "series",
                        name = series.name ?: "Unknown Series",
                        iconUrl = com.tvonnet.debridxtreamiptv.util.GlobalConfig.resolveIconUrl(series.cover)
                    )
                    toast("Added to favorites")
                }

                // Refresh adapter to show/hide heart icon
                adapter().refresh()
            } catch (e: Exception) {
                toast("Error: ${e.message}")
            }
        }
    }

    fun updateBackdrop(series: XtreamSeriesInfo) {
        // Debounce: swap the backdrop only once focus RESTS (~300ms) — surfing the grid
        // must not repaint the whole background on every D-pad step.
        pendingBackdrop?.let { backdropHandler.removeCallbacks(it) }
        val task = Runnable {
            if (!fragment.isAdded) return@Runnable
            val target = backdropView() ?: return@Runnable
            val imageUrl = com.tvonnet.debridxtreamiptv.util.GlobalConfig.resolveIconUrl(
                series.backdropPathList.firstOrNull() ?: series.cover
            )
            if (!imageUrl.isNullOrBlank()) {
                Glide.with(fragment)
                    .load(imageUrl)
                    .transition(DrawableTransitionOptions.withCrossFade(700))
                    .placeholder(android.R.color.transparent)
                    .error(android.R.color.transparent)
                    .into(target)
            }
        }
        pendingBackdrop = task
        backdropHandler.postDelayed(task, 300L)
    }

    /** onDestroyView teardown: drop the pending backdrop swap. */
    fun clear() {
        pendingBackdrop?.let { backdropHandler.removeCallbacks(it) }
        pendingBackdrop = null
    }

    private fun toast(message: String) {
        if (fragment.isAdded) {
            Toast.makeText(fragment.requireContext(), message, Toast.LENGTH_SHORT).show()
        }
    }
}
