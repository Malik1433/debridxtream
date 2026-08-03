package com.tvonnet.debridxtreamiptv.ui.live

import android.view.KeyEvent
import android.widget.TextView
import android.widget.Toast
import androidx.fragment.app.Fragment
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.tvonnet.debridxtreamiptv.R
import com.tvonnet.debridxtreamiptv.data.model.XtreamStream
import com.tvonnet.debridxtreamiptv.data.prefs.WatchHistoryPreferences
import com.tvonnet.debridxtreamiptv.data.repository.XtreamRepository
import com.tvonnet.debridxtreamiptv.util.EpgCache
import com.tvonnet.debridxtreamiptv.utils.updatePreservingFocus
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * LF-3: the EPG **preview + Program-Guide-strip** concern lifted out of [LiveFragment]. It owns the
 * relationship between whichever channel is previewing and its now/next EPG: the preview panel's EPG
 * labels, the horizontal guide strip below the preview, the batch now/next cache warm for the visible
 * channel window, and the on-launch EPG prime. It also restores the preview player + its EPG on return
 * from fullscreen ([maybeRestorePreviewFromState]).
 *
 * Owns its own EPG jobs (updates/prime/retry/guide), the guide-strip adapter, and the preview-EPG keys.
 * Reaches back to the Fragment (which keeps the widely-shared preview/paging/focus state) through
 * getters/callbacks: [previewPanel] (the nullable, re-created preview panel), [onFocusChannel] (LF-6),
 * [onUpdateFavoriteButton] (LF-5) and the return-restore flag ([isPreviewRestored] / [markPreviewRestored],
 * shared with LF-7's `handleLivePlayerResult`). [updatePreviewEpg] is called by the Fragment's play/return
 * paths (LF-7). Behaviour byte-identical to the old private methods; only `this`/field refs were rebound.
 */
class LiveEpgPreviewController(
    private val fragment: Fragment,
    private val viewModel: LiveViewModel,
    private val repository: XtreamRepository,
    private val channelPagingAdapter: ChannelPagingAdapter,
    views: Views,
    callbacks: Callbacks,
) {
    /** The EPG strip + channel list views this controller renders into. */
    data class Views(
        val rvChannels: RecyclerView,
        val rvEpgStrip: RecyclerView?,
        val tvEpgStripSubtitle: TextView?,
    )

    /** Fragment-owned surfaces and the shared preview-restore latch. */
    data class Callbacks(
        val previewPanel: () -> PreviewPlayerPanel?,
        val onFocusChannel: (Int) -> Unit,
        val onUpdateFavoriteButton: (XtreamStream?) -> Unit,
        val isPreviewRestored: () -> Boolean,
        val markPreviewRestored: () -> Unit,
    )

    private val rvChannels = views.rvChannels
    private val rvEpgStrip = views.rvEpgStrip
    private val tvEpgStripSubtitle = views.tvEpgStripSubtitle
    private val previewPanel = callbacks.previewPanel
    private val onFocusChannel = callbacks.onFocusChannel
    private val onUpdateFavoriteButton = callbacks.onUpdateFavoriteButton
    private val isPreviewRestored = callbacks.isPreviewRestored
    private val markPreviewRestored = callbacks.markPreviewRestored

    private var guideEpgJob: Job? = null
    private var epgStripAdapter: LiveEpgStripAdapter? = null
    private var epgPrimeJob: Job? = null
    private var epgUpdatesJob: Job? = null
    private var previewEpgRetryJob: Job? = null

    private var currentPreviewEpgKey: String? = null
    private var currentPreviewStreamId: String? = null
    private var lastEpgWarmupSignature: String? = null

    private companion object {
        // EPG batch-warm window: rows above/below the viewport to prefetch, and the count to
        // warm before the list has been laid out (initial load).
        const val EPG_WARM_BUFFER = 6
        const val EPG_WARM_INITIAL_COUNT = 15
    }

    fun observeEpgUpdates() {
        epgUpdatesJob = fragment.viewLifecycleOwner.lifecycleScope.launch {
            try {
                fragment.viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                    EpgCache.updates.collect { channelKey ->
                        notifyEpgUpdated(channelKey)
                        if (channelKey == currentPreviewEpgKey) {
                            val (now, next) = EpgCache.getEpgData(channelKey, currentPreviewStreamId)
                            if (now != null || next != null) {
                                previewPanel()?.updateEpg(now, next)
                            }
                        }
                    }
                }
            } catch (e: CancellationException) {
                android.util.Log.d("LiveFragment", "EPG updates observation cancelled")
                throw e // cooperative cancellation — never swallow
            } catch (e: Exception) {
                android.util.Log.e("LiveFragment", "Error observing EPG updates", e)
            }
        }
    }

    fun updatePreviewEpg(stream: XtreamStream) {
        val epgKey = stream.epg_channel_id?.takeIf { it.isNotBlank() } ?: stream.stream_id?.toString()
        val streamId = stream.stream_id?.toString()
        currentPreviewEpgKey = epgKey
        currentPreviewStreamId = streamId
        previewEpgRetryJob?.cancel()

        // v2: the Program Guide strip follows whichever channel is previewing.
        updateGuideStripHeader(stream)
        viewModel.loadGuideEpg(epgKey)

        if (epgKey.isNullOrBlank()) {
            previewPanel()?.updateEpg(null, null)
            return
        }

        // Prime EPG fetch; if cache miss, EpgCache will refresh in background.
        val (now, next) = EpgCache.getEpgData(epgKey, streamId)
        if (now != null || next != null) {
            previewPanel()?.updateEpg(now, next)
            return
        }

        // Avoid leaving "Syncing TV guide" forever: retry briefly, then show "no guide" placeholder.
        previewEpgRetryJob = fragment.viewLifecycleOwner.lifecycleScope.launch {
            repeat(10) {
                delay(700)
                val (retryNow, retryNext) = EpgCache.getEpgData(epgKey, streamId)
                if (retryNow != null || retryNext != null) {
                    previewPanel()?.updateEpg(retryNow, retryNext)
                    return@launch
                }
            }
            // Still nothing: switch to "no EPG" state.
            previewPanel()?.updateEpg(null, null)
        }
    }

    private fun notifyEpgUpdated(channelKey: String) {
        // Update only items that match the updated key; no animations (rvChannels.itemAnimator = null).
        val items = channelPagingAdapter.snapshot().items
        if (items.isEmpty()) return
        items.forEachIndexed { index, stream ->
            val key = stream.epg_channel_id?.takeIf { it.isNotBlank() } ?: stream.stream_id?.toString()
            if (key == channelKey) {
                channelPagingAdapter.notifyItemChanged(index)
            }
        }
    }

    fun primeEpgData() {
        epgPrimeJob = fragment.viewLifecycleOwner.lifecycleScope.launch {
            try {
                // Avoid full XMLTV sync here (too heavy on many TV devices). We use short-EPG on-demand via EpgCache.
                withContext(Dispatchers.IO) {
                    runCatching { repository.clearOldEpg() }
                    runCatching { EpgCache.cleanupStaleEntries() }
                }
            } catch (e: CancellationException) {
                android.util.Log.d("LiveFragment", "EPG priming cancelled")
                throw e // cooperative cancellation — never swallow
            } catch (e: Exception) {
                android.util.Log.e("LiveFragment", "Error priming EPG data", e)
            }
        }
    }

    /**
     * Batch-warm now/next EPG for the currently visible window (+buffer) in one shot. Replaces
     * the old per-row fetch that fired a get_short_epg HTTP call per channel and only ever
     * covered the first 12. Re-runs as the list settles on a new window (see the scroll listener).
     */
    fun warmVisibleEpgCache() {
        if (!fragment.isAdded) return
        val items = channelPagingAdapter.snapshot().items
        if (items.isEmpty()) return

        val lm = rvChannels.layoutManager as? LinearLayoutManager
        val firstVisible = lm?.findFirstVisibleItemPosition() ?: 0
        val lastVisible = lm?.findLastVisibleItemPosition() ?: -1
        val start = (firstVisible - EPG_WARM_BUFFER).coerceAtLeast(0)
        val end = (if (lastVisible >= 0) lastVisible + EPG_WARM_BUFFER else EPG_WARM_INITIAL_COUNT)
            .coerceAtMost(items.size - 1)
        if (end < start) return

        val entries = (start..end).mapNotNull { i ->
            val stream = items.getOrNull(i) ?: return@mapNotNull null
            val epgId = stream.epg_channel_id?.takeIf { it.isNotBlank() }
            val streamId = stream.stream_id
            val cacheKey = epgId ?: streamId ?: return@mapNotNull null
            EpgCache.WarmEntry(cacheKey = cacheKey, epgChannelId = epgId, streamId = streamId)
        }
        if (entries.isEmpty()) return

        val signature = "${viewModel.uiState.value.selectedCategoryId.orEmpty()}:$start-$end"
        if (signature == lastEpgWarmupSignature) return
        lastEpgWarmupSignature = signature
        EpgCache.warmNowNext(entries)
    }

    fun maybeRestorePreviewFromState(state: LiveUiState) {
        if (isPreviewRestored()) return
        if (!state.restoreFromFullscreenPending) return
        // Shared-player hand-off return (LP-CLASSIC): the fullscreen session parked its running player
        // in LiveSharedPlayer and set RESULT_OK, so LivePlaybackLauncher.handleLivePlayerResult will
        // ADOPT it authoritatively. Do NOT reconnect a fresh preview here — this method racing the
        // result callback was the occasional "reload on return". Defer; the result path restores it.
        if (com.tvonnet.debridxtreamiptv.player.stabilized.LiveSharedPlayer.isParked()) {
            android.util.Log.i("LIVE_HANDOFF", "maybeRestore: deferring to result adopt (player parked)")
            return
        }

        val historyItem = runCatching {
            WatchHistoryPreferences(fragment.requireContext()).getRecentLiveChannelsList().firstOrNull()
        }.getOrNull()
        val historyStreamId = historyItem?.channelId?.takeIf { it.isNotBlank() }
        val streamId = historyStreamId ?: state.lastPlayedChannelId ?: return
        val restoredStream = buildRestoredStream(historyItem, state, streamId)

        val sameStream = previewPanel()?.getCurrentStream()?.stream_id == streamId
        val isPlaying = previewPanel()?.isPlaying() == true
        if (!sameStream || !isPlaying) {
            previewPanel()?.play(restoredStream)
        }
        updatePreviewEpg(restoredStream)
        onUpdateFavoriteButton(restoredStream)
        if (shouldSyncHistory(historyItem, historyStreamId, state)) {
            viewModel.onEvent(LiveEvent.RememberPreviewStream(restoredStream))
        }
        markPreviewRestored()
    }

    // History wins per field, state fills the gaps — same precedence as the old inline block.
    private fun buildRestoredStream(
        historyItem: com.tvonnet.debridxtreamiptv.data.model.RecentLiveChannelItem?,
        state: LiveUiState,
        streamId: String,
    ): XtreamStream {
        val resolvedName = historyItem?.channelName?.takeIf { it.isNotBlank() } ?: state.lastPlayedChannelName
        val resolvedLogo = historyItem?.channelLogo ?: state.lastPlayedChannelLogo
        val resolvedEpgId = historyItem?.epgChannelId ?: state.lastPlayedEpgChannelId
        return XtreamStream(
            num = null,
            name = resolvedName,
            stream_type = "live",
            stream_id = streamId,
            stream_icon = resolvedLogo,
            epg_channel_id = resolvedEpgId,
            added = null,
            category_id = state.selectedCategoryId,
            category_ids = null,
            container_extension = null,
            custom_sid = null,
            direct_source = null,
            tv_archive = null,
            tv_archive_duration = null
        )
    }

    // Only push the restored identity back into state when the history row genuinely differs.
    private fun shouldSyncHistory(
        historyItem: com.tvonnet.debridxtreamiptv.data.model.RecentLiveChannelItem?,
        historyStreamId: String?,
        state: LiveUiState,
    ): Boolean {
        return historyItem != null && historyStreamId != null && (
            historyStreamId != state.lastPlayedChannelId ||
                (historyItem.channelName.isNotBlank() && historyItem.channelName != state.lastPlayedChannelName) ||
                (historyItem.channelLogo != null && historyItem.channelLogo != state.lastPlayedChannelLogo) ||
                (!historyItem.epgChannelId.isNullOrBlank() && historyItem.epgChannelId != state.lastPlayedEpgChannelId)
            )
    }

    fun setupEpgStrip() {
        epgStripAdapter = LiveEpgStripAdapter(
            onProgramClick = { program ->
                // Tapping a guide card just surfaces its synopsis — the strip is informational.
                val details = listOfNotNull(
                    program.title?.takeIf { it.isNotBlank() },
                    program.description?.takeIf { it.isNotBlank() }
                ).joinToString("\n").ifBlank { fragment.getString(R.string.player_epg_no_guide) }
                Toast.makeText(fragment.requireContext(), details, Toast.LENGTH_LONG).show()
            },
            onKey = { position, keyCode, event ->
                if (event.action == KeyEvent.ACTION_DOWN &&
                    keyCode == KeyEvent.KEYCODE_DPAD_LEFT &&
                    position == 0
                ) {
                    onFocusChannel(viewModel.uiState.value.lastFocusedChannelPosition ?: 0)
                    true
                } else {
                    false
                }
            }
        )
        rvEpgStrip?.apply {
            layoutManager = LinearLayoutManager(context, LinearLayoutManager.HORIZONTAL, false)
            itemAnimator = null
            adapter = epgStripAdapter
            isFocusable = false
        }

        guideEpgJob = fragment.viewLifecycleOwner.lifecycleScope.launch {
            fragment.viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.guideEpg.collect { programs ->
                    // Refreshing a list under the D-pad is this app's classic focus thief.
                    rvEpgStrip?.updatePreservingFocus { epgStripAdapter?.submitList(programs) }
                }
            }
        }
    }

    private fun updateGuideStripHeader(stream: XtreamStream?) {
        val name = stream?.name?.takeIf { it.isNotBlank() }
            ?: fragment.getString(R.string.player_epg_channel_unknown)
        tvEpgStripSubtitle?.text = fragment.getString(R.string.livev2_guide_subtitle_format, name)
    }

    /** Cancel every EPG job and drop the guide-strip adapter (mirrors the Fragment's old onDestroyView). */
    fun cancel() {
        epgPrimeJob?.cancel()
        epgUpdatesJob?.cancel()
        previewEpgRetryJob?.cancel()
        guideEpgJob?.cancel()
        epgPrimeJob = null
        epgUpdatesJob = null
        previewEpgRetryJob = null
        guideEpgJob = null
        rvEpgStrip?.adapter = null
        epgStripAdapter = null
    }
}
