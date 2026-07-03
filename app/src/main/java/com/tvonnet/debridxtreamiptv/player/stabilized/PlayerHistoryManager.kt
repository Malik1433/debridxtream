package com.tvonnet.debridxtreamiptv.player.stabilized

import android.content.Intent
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.lifecycleScope
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
import kotlinx.coroutines.Dispatchers
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
    }

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

    private fun recordLiveHistory(channelId: String) {
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
            removeExactContinueWatching(type, item)
            return true
        }
        if (isWatched) {
            removeExactContinueWatching(type, item)
            return true
        }

        saveContinueWatchingIfAllowed(item, pos, dur, type)
        return true
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
        activity.lifecycleScope.launch(Dispatchers.IO) {
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
