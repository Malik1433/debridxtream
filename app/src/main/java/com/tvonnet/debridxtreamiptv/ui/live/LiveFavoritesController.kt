package com.tvonnet.debridxtreamiptv.ui.live

import android.widget.Toast
import androidx.fragment.app.Fragment
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.tvonnet.debridxtreamiptv.R
import com.tvonnet.debridxtreamiptv.data.model.XtreamStream
import com.tvonnet.debridxtreamiptv.data.repository.XtreamRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * LF-5: the **favourites** concern lifted out of [LiveFragment] — the live-favourites bus
 * ([observeFavorites] over `repository.getFavoritesByType("live")`), the two toggle entry points
 * (long-press on a channel card via [handleFavoriteLongPress]; the preview panel's heart via
 * [togglePreviewFavorite]), and the preview favourite-button state ([updateFavoriteButtonState],
 * called by LF-3's play/return paths through the Fragment's `onUpdateFavoriteButton`).
 *
 * Owns the [favoritesCount] and the thread-safe `favoriteStreamIds` set (read O(1) by the paging
 * adapter's `favoriteChecker` via [isFavorite]). It does NOT hold the sibling header/category
 * controllers; when the favourites set changes it emits [onFavoritesChanged] and the Fragment
 * re-pokes the header + category-chip counts (the orchestration glue stays in the Fragment).
 * Behaviour byte-identical to the old private methods; only `this`/field refs were rebound.
 */
class LiveFavoritesController(
    private val fragment: Fragment,
    private val repository: XtreamRepository,
    private val channelPagingAdapter: ChannelPagingAdapter,
    private val previewPanel: () -> PreviewPlayerPanel?,
    private val onFavoritesChanged: (Int) -> Unit,
) {
    private val favoriteStreamIds = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()
    private var favoritesJob: Job? = null

    var favoritesCount: Int = 0
        private set

    /** O(1) fast lookup for the paging adapter's `favoriteChecker`; avoids main-thread blocking in bind(). */
    fun isFavorite(streamId: String): Boolean = favoriteStreamIds.contains(streamId)

    fun observeFavorites() {
        favoritesJob = fragment.viewLifecycleOwner.lifecycleScope.launch {
            try {
                fragment.viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                    repository.getFavoritesByType("live").collect { favorites ->
                        favoriteStreamIds.clear()
                        favorites.forEach { favoriteStreamIds.add(it.streamId) }
                        favoritesCount = favorites.size
                        // The Fragment re-pokes the chip-strip counts + header (it owns those controllers).
                        onFavoritesChanged(favoritesCount)
                    }
                }
            } catch (e: CancellationException) {
                android.util.Log.d("LiveFragment", "Favorites observation cancelled")
                throw e // cooperative cancellation — never swallow
            } catch (e: Exception) {
                android.util.Log.e("LiveFragment", "Error observing favorites", e)
            }
        }
    }

    /**
     * Handle long press on channel for favorites.
     * Week 10: Add/Remove favorites functionality
     */
    fun handleFavoriteLongPress(channel: XtreamStream) {
        fragment.viewLifecycleOwner.lifecycleScope.launch {
            try {
                val nowFavorite = toggleFavorite(channel)
                channel.stream_id?.let { streamId ->
                    if (nowFavorite) favoriteStreamIds.add(streamId) else favoriteStreamIds.remove(streamId)
                    notifyFavoriteChanged(streamId)
                }
                Toast.makeText(
                    fragment.requireContext(),
                    fragment.getString(if (nowFavorite) R.string.favorite_added else R.string.favorite_removed),
                    Toast.LENGTH_SHORT
                ).show()
                if (previewPanel()?.getCurrentStream()?.stream_id == channel.stream_id) {
                     previewPanel()?.setFavoriteState(nowFavorite)
                }
            } catch (e: Exception) {
                Toast.makeText(
                    fragment.requireContext(),
                    "Error: ${e.message}",
                    Toast.LENGTH_SHORT
                ).show()
            }
        }
    }

    /** The preview panel's favourite heart: toggle + reflect the new state on the panel. */
    fun togglePreviewFavorite(stream: XtreamStream) {
        fragment.viewLifecycleOwner.lifecycleScope.launch {
            try {
                val nowFavorite = toggleFavorite(stream)
                previewPanel()?.setFavoriteState(nowFavorite)
                Toast.makeText(
                    fragment.requireContext(),
                    fragment.getString(if (nowFavorite) R.string.favorite_added else R.string.favorite_removed),
                    Toast.LENGTH_SHORT
                ).show()
            } catch (e: Exception) {
                Toast.makeText(
                    fragment.requireContext(),
                    fragment.getString(R.string.f_error_detail, e.message.orEmpty()),
                    Toast.LENGTH_SHORT
                ).show()
            }
        }
    }

    private suspend fun toggleFavorite(channel: XtreamStream): Boolean {
        val streamId = channel.stream_id ?: return false
        val displayName = channel.name ?: fragment.getString(R.string.player_epg_channel_unknown)
        return withContext(Dispatchers.IO) {
            val isFavorite = repository.isFavorite(streamId)
            if (isFavorite) {
                repository.removeFavorite(streamId)
                false
            } else {
                repository.addFavorite(streamId, "live", displayName, channel.stream_icon)
                true
            }
        }
    }

    private fun notifyFavoriteChanged(streamId: String) {
        val index = channelPagingAdapter.snapshot().items.indexOfFirst { it.stream_id == streamId }
        if (index >= 0) {
            channelPagingAdapter.notifyItemChanged(index)
        }
    }

    fun updateFavoriteButtonState(stream: XtreamStream?) {
        if (stream?.stream_id.isNullOrBlank()) {
             previewPanel()?.setFavoriteState(false) // Or disable?
             return
        }
        val streamId = stream!!.stream_id!!
        fragment.viewLifecycleOwner.lifecycleScope.launch {
            val isFavorite = withContext(Dispatchers.IO) {
                repository.isFavorite(streamId)
            }
            previewPanel()?.setFavoriteState(isFavorite)
        }
    }

    /** onDestroyView teardown: cancel the bus + drop the favourite set. */
    fun clear() {
        favoritesJob?.cancel()
        favoritesJob = null
        favoriteStreamIds.clear()
        favoritesCount = 0
    }
}
