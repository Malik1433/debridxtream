package com.tvonnet.debridxtreamiptv.player.stabilized

import android.content.Context
import com.tvonnet.debridxtreamiptv.R
import com.tvonnet.debridxtreamiptv.data.Result
import com.tvonnet.debridxtreamiptv.data.local.entity.EpgEntity
import com.tvonnet.debridxtreamiptv.data.repository.XtreamRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext

/**
 * C10: every EPG the player shows — the now/next overlay, the per-channel strip behind the surf
 * drawer, and the windowed grid behind the in-player TV Guide.
 *
 * They are one collaborator because they share the same hard problem: **not every channel has
 * XMLTV**. Each of the three falls back to the provider's per-stream `get_short_epg` when the
 * synced guide has nothing, and each bounds that fallback, because a channel list is hundreds long
 * and an unbounded fan-out would storm the provider (the guide caps concurrency at
 * [SHORT_EPG_CONCURRENCY]).
 *
 * The guide also emits **twice on purpose**: the batched DB read publishes immediately so the grid
 * fills at once, and short-EPG results merge in as they arrive. Waiting for both would leave the
 * guide blank for as long as the slowest provider call.
 *
 * Bodies moved verbatim from `PlayerViewModel`; the one change is that the "build an overlay state
 * out of a programme list" block, which appeared three times, is now [overlayFrom].
 */
internal class PlayerEpgController(
    private val repository: XtreamRepository,
    private val context: Context,
    private val scope: CoroutineScope,
    private val zapChannels: () -> List<ZapChannel>?,
) {
    private companion object {
        const val SHORT_EPG_LIMIT = 12
        const val GUIDE_SHORT_EPG_LIMIT = 24
        const val SHORT_EPG_CONCURRENCY = 4
        const val UPCOMING_COUNT = 6
    }

    private val _overlayState = MutableStateFlow(PlayerOverlayUiState())
    val overlayState: StateFlow<PlayerOverlayUiState> = _overlayState.asStateFlow()

    /** streamId → the programme on air now, for the surf rows and ON AIR NOW panel. */
    private val _surfEpg = MutableStateFlow<Map<String, EpgEntity>>(emptyMap())
    val surfEpg: StateFlow<Map<String, EpgEntity>> = _surfEpg.asStateFlow()

    /** streamId → the programmes overlapping the visible guide window. */
    private val _guideEpg = MutableStateFlow<Map<String, List<EpgEntity>>>(emptyMap())
    val guideEpg: StateFlow<Map<String, List<EpgEntity>>> = _guideEpg.asStateFlow()

    private var epgJob: Job? = null
    private var epgRefreshJob: Job? = null

    // ── now / next overlay ─────────────────────────────────────────────────────

    fun observeEpg(epgChannelId: String?, streamId: String? = null) {
        epgJob?.cancel()
        epgRefreshJob?.cancel()

        if (epgChannelId.isNullOrBlank()) {
            _overlayState.value = PlayerOverlayUiState(
                epgAvailable = false,
                errorMessage = context.getString(R.string.player_epg_channel_unknown)
            )
            return
        }

        _overlayState.value = PlayerOverlayUiState(
            epgAvailable = false,
            lastSyncTime = readLastSyncTime(),
            isSyncing = true,
            errorMessage = null,
            streamId = streamId
        )

        epgJob = scope.launch {
            val flow = repository.getEpgByChannel(epgChannelId)
            if (flow == null) {
                _overlayState.value = shortEpgOverlay(
                    epgChannelId, streamId,
                    emptyMessage = context.getString(R.string.player_epg_unavailable_generic)
                )
                return@launch
            }
            flow.collect { programs ->
                if (programs.isEmpty()) {
                    // Synced guide has nothing for this channel yet — say so, then try the provider.
                    _overlayState.value = _overlayState.value.copy(
                        epgAvailable = false,
                        isSyncing = true,
                        errorMessage = null,
                        streamId = streamId
                    )
                    _overlayState.value = shortEpgOverlay(
                        epgChannelId, streamId,
                        emptyMessage = context.getString(R.string.player_epg_no_guide)
                    )
                    return@collect
                }
                _overlayState.value = overlayFrom(programs, streamId)
            }
        }
    }

    /** Ask the provider directly for this one channel, and build the overlay out of the answer. */
    private suspend fun shortEpgOverlay(
        epgChannelId: String,
        streamId: String?,
        emptyMessage: String,
    ): PlayerOverlayUiState {
        val fallback = streamId
            ?.takeIf { it.isNotBlank() }
            ?.let { id ->
                withContext(Dispatchers.IO) {
                    repository.fetchShortEpgPrograms(streamId = id, channelKey = epgChannelId, limit = SHORT_EPG_LIMIT)
                }
            }
        if (fallback is Result.Success && fallback.data.isNotEmpty()) {
            return overlayFrom(fallback.data, streamId)
        }
        return PlayerOverlayUiState(
            epgAvailable = false,
            lastSyncTime = readLastSyncTime(),
            isSyncing = false,
            errorMessage = emptyMessage,
            streamId = streamId
        )
    }

    /**
     * Now / next / upcoming out of one programme list.
     *
     * "Now" falls back to the first entry when nothing covers the current time — a provider whose
     * clock is off would otherwise show an empty overlay on a channel that has a guide.
     */
    private fun overlayFrom(programs: List<EpgEntity>, streamId: String?): PlayerOverlayUiState {
        val nowMs = System.currentTimeMillis()
        return PlayerOverlayUiState(
            now = programs.firstOrNull { nowMs in it.start..it.stop } ?: programs.firstOrNull(),
            next = programs.firstOrNull { it.start > nowMs },
            upcoming = programs.filter { it.start > nowMs }.take(UPCOMING_COUNT),
            epgAvailable = true,
            lastSyncTime = readLastSyncTime(),
            isSyncing = false,
            errorMessage = null,
            streamId = streamId
        )
    }

    private fun readLastSyncTime(): Long? {
        return context
            .getSharedPreferences("epg_prefs", Context.MODE_PRIVATE)
            .getLong("last_sync_time", 0L)
            .takeIf { it > 0 }
    }

    // ── per-channel current EPG for the v2 rich surf rows + ON AIR NOW panel ──

    fun refreshSurfEpg() {
        val channels = zapChannels() ?: return
        scope.launch(Dispatchers.IO) {
            val epgIds = channels.mapNotNull { it.epgChannelId?.takeIf { id -> id.isNotBlank() } }
            val byEpgId = repository.getCurrentProgramsByEpgId(epgIds)
            if (byEpgId.isEmpty()) {
                _surfEpg.value = emptyMap()
                return@launch
            }
            _surfEpg.value = channels.mapNotNull { ch ->
                val prog = ch.epgChannelId?.let { byEpgId[it] } ?: return@mapNotNull null
                ch.streamId to prog
            }.toMap()
        }
    }

    // ── windowed EPG for the in-player TV Guide grid (Live Player v2) ─────────

    /**
     * Batch-load windowed EPG for every channel in the active zap list, keyed by streamId.
     *
     * Two sources, same as the browse list: (1) one DB read for channels with synced XMLTV,
     * then (2) a bounded per-stream `get_short_epg` fallback for the rest — without which
     * channels that only expose provider short-EPG (e.g. many UK sports feeds) render as empty
     * lanes. DB results emit first; short-EPG results merge in as they arrive.
     */
    fun loadGuideEpg(windowStart: Long, windowEnd: Long) {
        val channels = zapChannels() ?: return
        scope.launch(Dispatchers.IO) {
            val result = java.util.concurrent.ConcurrentHashMap<String, List<EpgEntity>>()
            val needShortEpg = fillFromDatabase(channels, windowStart, windowEnd, result)
            if (result.isNotEmpty()) _guideEpg.value = HashMap(result)
            if (needShortEpg.isEmpty()) return@launch
            fillFromShortEpg(needShortEpg, windowStart, windowEnd, result)
            _guideEpg.value = HashMap(result)
        }
    }

    /** @return the channels the synced guide had nothing for. */
    private suspend fun fillFromDatabase(
        channels: List<ZapChannel>,
        windowStart: Long,
        windowEnd: Long,
        into: MutableMap<String, List<EpgEntity>>,
    ): List<ZapChannel> {
        val epgIds = channels.mapNotNull { it.epgChannelId?.takeIf { id -> id.isNotBlank() } }.distinct()
        val byEpgId = if (epgIds.isNotEmpty()) {
            runCatching { repository.getProgramsByEpgIdInRange(epgIds, windowStart, windowEnd) }
                .getOrNull().orEmpty()
        } else {
            emptyMap()
        }
        val missing = mutableListOf<ZapChannel>()
        for (ch in channels) {
            val dbProgs = ch.epgChannelId?.let { byEpgId[it] }
            if (!dbProgs.isNullOrEmpty()) into[ch.streamId] = dbProgs else missing.add(ch)
        }
        return missing
    }

    /** Provider short-EPG for the leftovers, capped so a full channel list can't storm it. */
    private suspend fun fillFromShortEpg(
        channels: List<ZapChannel>,
        windowStart: Long,
        windowEnd: Long,
        into: MutableMap<String, List<EpgEntity>>,
    ) {
        val gate = Semaphore(SHORT_EPG_CONCURRENCY)
        coroutineScope {
            channels.map { ch ->
                async {
                    gate.withPermit {
                        val key = ch.epgChannelId?.takeIf { it.isNotBlank() } ?: ch.streamId
                        val r = runCatching {
                            repository.fetchShortEpgPrograms(ch.streamId, key, limit = GUIDE_SHORT_EPG_LIMIT)
                        }.getOrNull()
                        if (r is Result.Success) {
                            val inWindow = r.data.filter { it.stop > windowStart && it.start < windowEnd }
                            if (inWindow.isNotEmpty()) into[ch.streamId] = inWindow
                        }
                    }
                }
            }.awaitAll()
        }
    }
}
