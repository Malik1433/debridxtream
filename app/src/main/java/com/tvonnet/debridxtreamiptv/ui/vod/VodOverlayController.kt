package com.tvonnet.debridxtreamiptv.ui.vod

import android.widget.Toast
import androidx.fragment.app.Fragment
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.tvonnet.debridxtreamiptv.data.local.WatchedIdentityBuilder
import com.tvonnet.debridxtreamiptv.data.model.XtreamVodInfo
import com.tvonnet.debridxtreamiptv.data.repository.WatchedStateRepository
import com.tvonnet.debridxtreamiptv.data.repository.XtreamRepository
import kotlinx.coroutines.launch

/**
 * C2: the **card overlays** on the movie grid — the favourite heart, the watched tick and the
 * in-progress strip — plus the long-press menu that changes them.
 *
 * Three table-wide Flows feed this and every one of them repaints cards, so keeping them together
 * makes the repaint policy reviewable in one place. Two hard-won rules live here:
 *  - the collectors are bound to `viewLifecycleOwner` + `repeatOnLifecycle(STARTED)` (B-12), so
 *    they stop while the grid sits behind the player — otherwise every progress write during
 *    playback re-ran the query and repainted an off-screen grid;
 *  - repaints go through a **payload** rebind (B-7), never `notifyDataSetChanged`, because a full
 *    rebind restarts every Glide poster load — the one source of scroll jank on this grid.
 *
 * Bodies moved verbatim from `VodFragment`; only `this`/field references were rebound.
 */
class VodOverlayController(
    private val fragment: Fragment,
    private val repository: XtreamRepository,
    private val watchedStateRepository: WatchedStateRepository,
    private val adapter: () -> VodAdapter,
) {
    private val favoritesCache by lazy {
        com.tvonnet.debridxtreamiptv.data.cache.FavoritesCache()
    }

    private var watchedMovieKeysCache: Set<String> = emptySet()

    /** streamId -> watch-progress fraction (0..1) for in-progress movies (card strip). */
    private var movieProgressByStreamId: Map<String, Float> = emptyMap()

    // ── what the adapter asks per card ───────────────────────────────────────

    fun isFavorite(streamId: String): Boolean = favoritesCache.isFavorite(streamId)

    fun isWatched(streamId: String): Boolean =
        watchedMovieKeysCache.contains(WatchedIdentityBuilder.iptvMovie(streamId))

    fun progressFor(streamId: String): Float? = movieProgressByStreamId[streamId]

    // ── the three sources ────────────────────────────────────────────────────

    fun observeFavorites() {
        fragment.viewLifecycleOwner.lifecycleScope.launch {
            fragment.viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                try {
                    repository.getAllFavorites().collect { favorites ->
                        favoritesCache.updateCache(favorites)
                        // B-7: repaint only the favorite badge (payload rebind) when the
                        // favorites Flow changes — the toggle no longer re-runs the whole
                        // category pipeline (network fetch + delete/insert + paging invalidate).
                        refreshCardOverlays()
                    }
                } catch (_: Exception) {}
            }
        }
    }

    fun observeWatchedState() {
        // B-12: bind to viewLifecycleOwner + repeatOnLifecycle(STARTED) so these
        // table-wide watched-state observers STOP collecting while the grid is
        // backgrounded behind the player — otherwise every progress write (every few
        // seconds during playback) re-ran the query + refreshCardOverlays off-screen.
        fragment.viewLifecycleOwner.lifecycleScope.launch {
            fragment.viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                try {
                    watchedStateRepository.observeAllWatchedMovieKeys().collect { keys ->
                        watchedMovieKeysCache = keys.toSet()
                        refreshCardOverlays()
                    }
                } catch (_: Exception) {}
            }
        }
        // In-progress movie positions → card progress strip.
        fragment.viewLifecycleOwner.lifecycleScope.launch {
            fragment.viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                try {
                    watchedStateRepository.observeInProgressMovieProgress().collect { rows ->
                        movieProgressByStreamId = rows.mapNotNull { row ->
                            val sid = row.iptvStreamId ?: return@mapNotNull null
                            val frac = if (row.durationMs > 0L) {
                                (row.progressMs.toFloat() / row.durationMs.toFloat()).coerceIn(0.02f, 1f)
                            } else 0.35f
                            sid to frac
                        }.toMap()
                        refreshCardOverlays()
                    }
                } catch (_: Exception) {}
            }
        }
    }

    /**
     * Refresh only the badge/progress overlays on visible cards — a payload rebind that
     * does NOT reload the poster (full notifyDataSetChanged restarted every Glide load,
     * the one scroll-jank source on the grid).
     */
    private fun refreshCardOverlays() {
        val a = adapter()
        if (a.snapshot().items.isEmpty()) return
        a.notifyItemRangeChanged(0, a.itemCount, VodAdapter.PAYLOAD_OVERLAYS)
    }

    // ── the long-press menu ──────────────────────────────────────────────────

    fun handleFavoriteLongPress(movie: XtreamVodInfo) {
        val streamId = movie.stream_id?.toString() ?: return
        val identityKey = WatchedIdentityBuilder.iptvMovie(streamId)
        val isFavorite = favoritesCache.isFavorite(streamId)
        val isWatched = watchedMovieKeysCache.contains(identityKey)

        val favoriteText = if (isFavorite) "Remove from Favorites" else "Add to Favorites"
        val watchedText = if (isWatched) "Mark as Unwatched" else "Mark as Watched"

        androidx.appcompat.app.AlertDialog.Builder(fragment.requireContext())
            .setTitle(movie.name)
            .setItems(arrayOf(favoriteText, watchedText)) { _, which ->
                if (which == 0) {
                    toggleFavorite(movie, streamId, isFavorite)
                } else if (which == 1) {
                    toggleWatched(movie, streamId, identityKey, isWatched)
                }
            }
            .show()
    }

    private fun toggleFavorite(movie: XtreamVodInfo, streamId: String, isFavorite: Boolean) {
        fragment.lifecycleScope.launch {
            try {
                if (isFavorite) {
                    repository.removeFavorite(streamId)
                    toast("Removed from favorites")
                } else {
                    repository.addFavorite(
                        streamId = streamId,
                        type = "vod",
                        name = movie.name ?: "Unknown Movie",
                        iconUrl = com.tvonnet.debridxtreamiptv.util.GlobalConfig.resolveIconUrl(movie.stream_icon)
                    )
                    toast("Added to favorites")
                }
                // B-7: no SelectCategory reload — the favorites Flow collector
                // (observeFavorites) repaints the badge via refreshCardOverlays().
            } catch (e: Exception) {
                toast("Error: ${e.message}")
            }
        }
    }

    private fun toggleWatched(
        movie: XtreamVodInfo,
        streamId: String,
        identityKey: String,
        isWatched: Boolean
    ) {
        fragment.lifecycleScope.launch {
            try {
                val historyPrefs =
                    com.tvonnet.debridxtreamiptv.data.prefs.WatchHistoryPreferences(fragment.requireContext())
                val newState = !isWatched
                val stateEntity = watchedStateRepository.getState(identityKey)
                    ?: com.tvonnet.debridxtreamiptv.data.local.entity.WatchedStateEntity(
                        identityKey = identityKey,
                        contentType = com.tvonnet.debridxtreamiptv.data.local.entity.WatchedStateEntity.CONTENT_TYPE_MOVIE,
                        source = com.tvonnet.debridxtreamiptv.data.local.entity.WatchedStateEntity.SOURCE_XTREAM,
                        iptvStreamId = streamId,
                        title = movie.name,
                        durationMs = 0L,
                        updatedAt = System.currentTimeMillis()
                    )
                watchedStateRepository.setManualWatched(stateEntity, newState, System.currentTimeMillis())
                historyPrefs.removeContinueWatchingItem(streamId)
            } catch (e: Exception) {
                toast("Error: ${e.message}")
            }
        }
    }

    private fun toast(message: String) {
        if (fragment.isAdded) {
            Toast.makeText(fragment.requireContext(), message, Toast.LENGTH_SHORT).show()
        }
    }
}
