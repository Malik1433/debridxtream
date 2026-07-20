package com.tvonnet.debridxtreamiptv.player.stabilized

import android.content.Intent
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.media3.common.C
import com.tvonnet.debridxtreamiptv.data.local.WatchedIdentityBuilder
import com.tvonnet.debridxtreamiptv.data.local.entity.WatchedStateEntity
import com.tvonnet.debridxtreamiptv.R
import com.tvonnet.debridxtreamiptv.data.model.ContinueWatchingItem
import com.tvonnet.debridxtreamiptv.data.model.RecentLiveChannelItem
import com.tvonnet.debridxtreamiptv.data.model.ContentType
import com.tvonnet.debridxtreamiptv.data.model.toAbsoluteUrl
import com.tvonnet.debridxtreamiptv.data.prefs.WatchHistoryPreferences
import com.tvonnet.debridxtreamiptv.data.repository.WatchedStateRepository
import com.tvonnet.debridxtreamiptv.player.stabilized.PlaybackSource
import com.tvonnet.debridxtreamiptv.player.stabilized.PlayerViewModel
import com.tvonnet.debridxtreamiptv.util.SensitiveLogRedactor
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

class PlayerHistoryManager(
    private val activity: PlayerActivity,
    private val watchHistoryPrefs: WatchHistoryPreferences,
    private val viewModel: PlayerViewModel,
    private val watchedStateRepository: WatchedStateRepository
) : DefaultLifecycleObserver {

    companion object {
        private const val MIN_DURATION_TO_TRACK_MS = 60_000L
        private const val MIN_PROGRESS_RATIO = 0.05f
        private const val MIN_PROGRESS_ABSOLUTE_MS = 90_000L
        private const val COMPLETION_THRESHOLD_RATIO = 0.90f
        private const val MOVIE_REMAINING_COMPLETION_MS = 5 * 60 * 1000L
        private const val EPISODE_REMAINING_COMPLETION_MS = 3 * 60 * 1000L
        private val RELEASE_YEAR_PATTERN = Regex("\\b(19\\d{2}|20\\d{2})\\b")
        // M5: teardown-time watched-state writes must survive activity-scope
        // cancellation — a launch on lifecycleScope from onDestroy is cancelled
        // mid-write and the upsert is silently lost. Fire-and-forget IO scope.
        private val persistScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    }

    // LP-C-2: onStop + onDestroy + back-press each call recordPlaybackHistoryIfNeeded;
    // for LIVE_TV that meant 3-4 redundant Gson encode/decode prefs writes on the main
    // thread per exit. Dedupe same-channel saves within a short teardown window.
    private var lastLiveHistoryChannelId: String? = null
    private var lastLiveHistorySavedAtMs = 0L

    override fun onDestroy(owner: LifecycleOwner) {
        recordPlaybackHistoryIfNeeded()
        super.onDestroy(owner)
    }

    fun recordPlaybackHistoryIfNeeded() {
        val type = activity.contentType ?: return
        val id = activity.contentId ?: activity.currentUrl ?: return
        when (type) {
            ContentType.LIVE_TV -> recordLiveHistory(id)
            ContentType.MOVIE, ContentType.SERIES, ContentType.EPISODE -> {
                if (activity.hasRecordedHistory) return
                // Only latch the flag when a real save/remove decision ran with a
                // valid duration. A call that lands while the player is still
                // resolving or erroring must stay retryable, otherwise the final
                // onStop/onDestroy save is silently skipped and the item never
                // reaches Continue Watching.
                if (recordContinueWatchingHistory(id, type)) {
                    activity.hasRecordedHistory = true
                }
            }
        }
    }

    /**
     * Crash-safe periodic progress snapshot (world-standard behaviour: streaming apps
     * persist position every few seconds so a crash/power-cut loses almost nothing).
     *
     * Unlike [recordPlaybackHistoryIfNeeded] this NEVER latches [PlayerActivity.hasRecordedHistory]
     * and has no removal side effects — it only refreshes the Continue Watching entry and
     * the watched-state progress row. Tiny progress and completed content are left for the
     * exit-time decision logic.
     */
    fun saveProgressSnapshot() {
        if (activity.hasRecordedHistory) return
        val type = activity.contentType ?: return
        if (type == ContentType.LIVE_TV) return
        val id = activity.contentId ?: activity.currentUrl ?: return
        val p = activity.player ?: return
        val dur = p.duration
        if (dur <= 0 || dur == C.TIME_UNSET || dur < MIN_DURATION_TO_TRACK_MS) return
        val pos = p.currentPosition
        if (shouldIgnoreTinyProgress(pos, dur)) return
        val isWatched = when (type) {
            ContentType.MOVIE -> isMovieWatchedThreshold(pos, dur)
            ContentType.EPISODE, ContentType.SERIES -> isEpisodeWatchedThreshold(pos, dur)
            else -> false
        }
        if (isWatched) return // completion handling belongs to the exit path
        val now = System.currentTimeMillis()
        val item = buildContinueWatchingItem(id, type, pos, dur, now)
        // Phase 1: the Continue-Watching prefs write is a full Gson decode+encode. On the
        // periodic snapshot it was running on the caller (main) thread every ~30s of playback.
        // Offload it to the existing IO persistScope. (The exit-time save in
        // recordContinueWatchingHistory stays synchronous for crash/kill durability.)
        persistScope.launch { watchHistoryPrefs.saveContinueWatchingItem(item) }
        recordAutomaticWatchedState(type, id, item, pos, dur, isWatched = false, timestamp = now)
    }

    private fun recordLiveHistory(channelId: String) {
        val nowMs = System.currentTimeMillis()
        if (channelId == lastLiveHistoryChannelId && nowMs - lastLiveHistorySavedAtMs < 5_000L) return
        lastLiveHistoryChannelId = channelId
        lastLiveHistorySavedAtMs = nowMs
        val logoToSave = activity.channelLogoUrl ?: activity.posterUrlExtra
        val absoluteLogo = logoToSave.toAbsoluteUrl(ContentType.LIVE_TV, activity.baseServerUrl)

        val item = RecentLiveChannelItem(
            channelId = channelId,
            channelName = activity.tvChannelName?.text?.toString() ?: activity.pendingChannelName ?: activity.originalTitle ?: activity.getString(R.string.player_epg_channel_unknown),
            channelLogo = absoluteLogo,
            lastWatchedTimestamp = System.currentTimeMillis(),
            streamUrl = activity.currentUrl ?: "",
            epgChannelId = activity.currentEpgChannelId,
            categoryId = activity.liveCategoryId
        )
        android.util.Log.d(
            "HISTORY_DEBUG",
            "Saving Recent Live: channelId=${item.channelId}, name=${item.channelName}, stream=${SensitiveLogRedactor.describeUrl(item.streamUrl)}"
        )
        watchHistoryPrefs.addRecentLiveChannel(item)
    }

    private fun recordContinueWatchingHistory(contentId: String, type: ContentType): Boolean {
        val p = activity.player ?: return false
        val dur = p.duration
        if (dur <= 0 || dur == C.TIME_UNSET || dur < MIN_DURATION_TO_TRACK_MS) return false
        val pos = p.currentPosition
        val now = System.currentTimeMillis()
        val item = buildContinueWatchingItem(contentId, type, pos, dur, now)
        val isTinyProgress = shouldIgnoreTinyProgress(pos, dur)
        val isWatched = when (type) {
            ContentType.MOVIE -> isMovieWatchedThreshold(pos, dur)
            ContentType.EPISODE, ContentType.SERIES -> isEpisodeWatchedThreshold(pos, dur)
            else -> false
        }

        if (!isTinyProgress) {
            recordAutomaticWatchedState(type, contentId, item, pos, dur, isWatched, now)
        }

        if (type == ContentType.EPISODE) viewModel.updatePlaybackStatus(contentId, isWatched, if (isWatched) 0 else pos, dur)
        if (isTinyProgress) {
            handleTinyProgressExit(type, item)
            return true
        }
        if (isWatched) {
            removeCompletedContinueWatching(type, item)
            return true
        }

        saveContinueWatchingIfAllowed(item, pos, dur, type)
        return true
    }

    /** Finds the Continue Watching entry that maps to the same logical content (any source). */
    private fun findLogicalContinueWatchingMatch(item: ContinueWatchingItem): ContinueWatchingItem? {
        val key = WatchHistoryPreferences.continueWatchingKey(item)
        return watchHistoryPrefs.getContinueWatchingList().firstOrNull {
            WatchHistoryPreferences.continueWatchingKey(it) == key
        }
    }

    private fun isSameLogicalPlayback(a: ContinueWatchingItem, b: ContinueWatchingItem): Boolean {
        if (a.contentType != ContentType.EPISODE && a.contentType != ContentType.SERIES) return true
        return a.seasonNumber != null && a.episodeNumber != null &&
            a.seasonNumber == b.seasonNumber && a.episodeNumber == b.episodeNumber
    }

    /**
     * Tiny watch (<90s and <5%). If the same content is already in Continue Watching
     * via the OTHER source (IPTV vs Debrid), re-home that entry to the source the user
     * just played from — keeping its progress — so resume always follows the LAST used
     * source. Without this, a short IPTV play leaves the stale Debrid entry in place
     * and the tile silently resumes via Debrid (and vice versa).
     */
    private fun handleTinyProgressExit(type: ContentType, item: ContinueWatchingItem) {
        val existing = findLogicalContinueWatchingMatch(item)
        if (existing != null &&
            existing.source != item.source &&
            existing.currentPosition > 0 &&
            existing.totalDuration > 0
        ) {
            if (isSameLogicalPlayback(item, existing)) {
                val rehomed = item.copy(
                    currentPosition = existing.currentPosition,
                    totalDuration = existing.totalDuration
                )
                logContinueWatchingSave(rehomed, rehomed.currentPosition, rehomed.totalDuration)
                watchHistoryPrefs.saveContinueWatchingItem(rehomed)
            }
            // Different episode of the same show via the other source: leave it alone.
            return
        }
        removeExactContinueWatching(type, item)
    }

    /**
     * Content finished. Besides the exact entry, also clear a stale same-content entry
     * left by the OTHER source for the same episode/movie so the finished title doesn't
     * resurrect in Continue Watching via the old source.
     */
    private fun removeCompletedContinueWatching(type: ContentType, item: ContinueWatchingItem) {
        removeExactContinueWatching(type, item)
        val existing = findLogicalContinueWatchingMatch(item) ?: return
        if (existing.source != item.source && isSameLogicalPlayback(item, existing)) {
            watchHistoryPrefs.removeContinueWatchingItem(existing)
        }
    }

    private fun buildContinueWatchingItem(
        contentId: String,
        type: ContentType,
        pos: Long,
        dur: Long,
        timestamp: Long
    ): ContinueWatchingItem {
        return ContinueWatchingItem(
            contentId = resolveContinueWatchingId(type, contentId),
            contentType = type,
            title = if (type == ContentType.EPISODE) activity.seriesTitleExtra ?: activity.originalTitle ?: activity.pendingChannelName ?: activity.getString(R.string.player_epg_channel_unknown) else activity.originalTitle ?: activity.pendingChannelName ?: activity.getString(R.string.player_epg_channel_unknown),
            posterUrl = (activity.posterUrlExtra ?: activity.channelLogoUrl).toAbsoluteUrl(type, activity.baseServerUrl),
            backdropUrl = activity.backdropUrlExtra.toAbsoluteUrl(type, activity.baseServerUrl),
            currentPosition = pos,
            totalDuration = dur,
            lastWatchedTimestamp = timestamp,
            streamUrl = activity.currentUrl,
            tmdbId = activity.tmdbIdExtra,
            imdbId = activity.imdbIdExtra,
            seriesId = activity.intent.getStringExtra(PlayerActivity.EXTRA_SERIES_ID),
            seriesTitle = activity.seriesTitleExtra,
            episodeTitle = activity.episodeTitleExtra,
            seasonNumber = activity.seasonNumberExtra,
            episodeNumber = activity.episodeNumberExtra,
            debridInfoHash = activity.debridInfoHashExtra,
            debridMagnet = activity.debridMagnetExtra,
            directDebridPlayback = activity.directDebridPlayback,
            debridProvider = activity.debridProviderExtra,
            debridSourceType = activity.debridSourceTypeExtra,
            debridSourceName = activity.debridSourceNameExtra,
            debridLanguages = activity.debridLanguagesExtra,
            debridQuality = activity.debridQualityExtra,
            debridStreamId = activity.debridStreamIdExtra ?: activity.debridInfoHashExtra,
            debridBingeGroup = activity.debridBingeGroupExtra,
            debridFileIdx = activity.debridFileIdxExtra,
            source = if (activity.playbackSource == PlaybackSource.DEBRID) "debrid" else "xtream",
            expiresAt = activity.expiresAtExtra
        )
    }

    private fun recordAutomaticWatchedState(
        type: ContentType,
        contentId: String,
        item: ContinueWatchingItem,
        pos: Long,
        dur: Long,
        isWatched: Boolean,
        timestamp: Long
    ) {
        val identityKey = watchedIdentityKey(type, contentId) ?: return
        // M5: persistScope (not lifecycleScope) — the onDestroy-time write must
        // outlive the activity's cancelling scope.
        persistScope.launch {
            val existing = watchedStateRepository.getState(identityKey)
            if (existing?.manualState == WatchedStateEntity.MANUAL_WATCHED ||
                existing?.manualState == WatchedStateEntity.MANUAL_UNWATCHED
            ) {
                return@launch
            }

            val state = buildWatchedState(
                identityKey = identityKey,
                type = type,
                item = item,
                progressMs = pos,
                durationMs = dur,
                isWatched = isWatched || existing?.isWatched == true,
                watchedAt = when {
                    isWatched -> timestamp
                    existing?.isWatched == true -> existing.watchedAt
                    else -> null
                },
                updatedAt = timestamp,
                existing = existing
            )
            watchedStateRepository.upsertAutomaticProgress(state, timestamp)
        }
    }

    private fun buildWatchedState(
        identityKey: String,
        type: ContentType,
        item: ContinueWatchingItem,
        progressMs: Long,
        durationMs: Long,
        isWatched: Boolean,
        watchedAt: Long?,
        updatedAt: Long,
        existing: WatchedStateEntity?
    ): WatchedStateEntity {
        val source = watchedSource()
        val stableSeriesId = stableSeriesIdentity()
            ?: stableContentIdentity(item.seriesId)
            ?: item.seriesTitle?.let { WatchedIdentityBuilder.normalizeKeyPart(it) }
        return WatchedStateEntity(
            identityKey = identityKey,
            contentType = if (type == ContentType.MOVIE) {
                WatchedStateEntity.CONTENT_TYPE_MOVIE
            } else {
                WatchedStateEntity.CONTENT_TYPE_EPISODE
            },
            source = source,
            isWatched = isWatched,
            progressMs = progressMs,
            durationMs = durationMs,
            watchedAt = watchedAt,
            updatedAt = updatedAt,
            manualState = existing?.manualState,
            manualUpdatedAt = existing?.manualUpdatedAt,
            title = if (type == ContentType.MOVIE) item.title else item.episodeTitle ?: activity.episodeTitleExtra,
            year = existing?.year,
            tmdbId = activity.tmdbIdExtra,
            imdbId = activity.imdbIdExtra,
            iptvStreamId = if (source == WatchedStateEntity.SOURCE_XTREAM && type == ContentType.MOVIE) {
                stableContentIdentity(activity.contentId)
            } else {
                existing?.iptvStreamId
            },
            seriesId = stableSeriesId,
            seasonNumber = activity.seasonNumberExtra,
            episodeNumber = activity.episodeNumberExtra,
            episodeId = if (source == WatchedStateEntity.SOURCE_XTREAM && type != ContentType.MOVIE) {
                stableContentIdentity(contentId = activity.contentId)
            } else {
                existing?.episodeId
            }
        )
    }

    private fun watchedIdentityKey(type: ContentType, contentId: String): String? {
        val source = watchedSource()
        return when {
            type == ContentType.MOVIE && source == WatchedStateEntity.SOURCE_XTREAM ->
                WatchedIdentityBuilder.iptvMovie(
                    streamId = stableContentIdentity(contentId),
                    title = activity.originalTitle,
                    year = null
                )
            type == ContentType.MOVIE ->
                WatchedIdentityBuilder.debridMovie(
                    tmdbId = activity.tmdbIdExtra,
                    imdbId = activity.imdbIdExtra,
                    title = activity.originalTitle,
                    year = releaseYearDiscriminator()
                )
            type == ContentType.EPISODE || type == ContentType.SERIES -> {
                if (source == WatchedStateEntity.SOURCE_XTREAM) {
                    WatchedIdentityBuilder.iptvEpisode(
                        episodeId = stableContentIdentity(contentId),
                        seriesId = stableSeriesIdentity(),
                        seasonNumber = activity.seasonNumberExtra,
                        episodeNumber = activity.episodeNumberExtra,
                        fallbackDiscriminator = episodeFallbackDiscriminator(contentId)
                    )
                } else {
                    WatchedIdentityBuilder.debridEpisode(
                        seriesIdentity = stableSeriesIdentity(),
                        seasonNumber = activity.seasonNumberExtra,
                        episodeNumber = activity.episodeNumberExtra,
                        seriesTitle = activity.seriesTitleExtra ?: activity.originalTitle,
                        seriesYear = releaseYearDiscriminator(),
                        fallbackDiscriminator = episodeFallbackDiscriminator(contentId)
                    )
                }
            }
            else -> null
        }
    }

    private fun stableSeriesIdentity(): String? {
        return stableContentIdentity(activity.intent.getStringExtra(PlayerActivity.EXTRA_SERIES_ID))
            ?: stableContentIdentity(activity.tmdbIdExtra)
            ?: stableContentIdentity(activity.imdbIdExtra)
    }

    private fun stableContentIdentity(contentId: String?): String? {
        return WatchedIdentityBuilder.stableIdPart(contentId)
    }

    private fun episodeFallbackDiscriminator(contentId: String): String? {
        return stableContentIdentity(contentId)
            ?: stableContentIdentity(activity.debridStreamIdExtra)
            ?: stableContentIdentity(activity.episodeTitleExtra)
            ?: stableContentIdentity(activity.originalTitle)
    }

    private fun releaseYearDiscriminator(): String? {
        return listOf(
            activity.seriesTitleExtra,
            activity.originalTitle,
            activity.episodeTitleExtra
        ).firstNotNullOfOrNull { value ->
            RELEASE_YEAR_PATTERN.find(value.orEmpty())?.value
        }
    }

    private fun watchedSource(): String {
        return if (activity.playbackSource == PlaybackSource.DEBRID) {
            WatchedStateEntity.SOURCE_DEBRID
        } else {
            WatchedStateEntity.SOURCE_XTREAM
        }
    }

    private fun removeExactContinueWatching(type: ContentType, item: ContinueWatchingItem) {
        if (type == ContentType.EPISODE || type == ContentType.SERIES) {
            watchHistoryPrefs.removeContinueWatchingItem(item.contentId)
        } else {
            watchHistoryPrefs.removeContinueWatchingItem(item)
        }
    }

    private fun progressRatio(pos: Long, dur: Long): Float = pos / dur.toFloat()

    private fun remainingMs(pos: Long, dur: Long): Long = (dur - pos).coerceAtLeast(0L)

    private fun shouldIgnoreTinyProgress(pos: Long, dur: Long): Boolean {
        // 5% of a long movie is several minutes — too strict on its own. Treat
        // 90s of absolute watch time as enough to remember the item.
        if (pos >= MIN_PROGRESS_ABSOLUTE_MS) return false
        return progressRatio(pos, dur) < MIN_PROGRESS_RATIO
    }

    private fun isMovieWatchedThreshold(pos: Long, dur: Long): Boolean {
        return progressRatio(pos, dur) >= COMPLETION_THRESHOLD_RATIO ||
            remainingMs(pos, dur) <= MOVIE_REMAINING_COMPLETION_MS
    }

    private fun isEpisodeWatchedThreshold(pos: Long, dur: Long): Boolean {
        return progressRatio(pos, dur) >= COMPLETION_THRESHOLD_RATIO ||
            remainingMs(pos, dur) <= EPISODE_REMAINING_COMPLETION_MS
    }

    private fun shouldSaveContinueWatching(pos: Long, dur: Long, type: ContentType): Boolean {
        if (shouldIgnoreTinyProgress(pos, dur)) return false
        return when (type) {
            ContentType.MOVIE -> !isMovieWatchedThreshold(pos, dur)
            ContentType.EPISODE, ContentType.SERIES -> !isEpisodeWatchedThreshold(pos, dur)
            else -> false
        }
    }

    private fun logContinueWatchingSave(item: ContinueWatchingItem, pos: Long, dur: Long) {
        android.util.Log.d(
            "HISTORY_DEBUG",
            "Saving Continue Watching [${item.source}]: ${item.title} | type=${item.contentType} | contentId=${SensitiveLogRedactor.describeHash(item.contentId)} | seriesId=${SensitiveLogRedactor.describeHash(item.seriesId)} | season=${item.seasonNumber} | episode=${item.episodeNumber} | pos=$pos dur=$dur | stream=${if (item.streamUrl.isNullOrBlank()) "missing" else "present"} | poster=${if (item.posterUrl.isNullOrBlank()) "missing" else "present"} | expires=${item.expiresAt}"
        )
    }

    private fun saveContinueWatchingIfAllowed(item: ContinueWatchingItem, pos: Long, dur: Long, type: ContentType) {
        if (!shouldSaveContinueWatching(pos, dur, type)) return
        logContinueWatchingSave(item, pos, dur)
        watchHistoryPrefs.saveContinueWatchingItem(item)
    }

    private fun resolveContinueWatchingId(type: ContentType, fallbackId: String): String {
        if (activity.playbackSource != PlaybackSource.DEBRID) return fallbackId
        return watchedIdentityKey(type, fallbackId)
            ?: WatchedIdentityBuilder.normalizeKeyPart(activity.originalTitle ?: fallbackId)
    }
}
