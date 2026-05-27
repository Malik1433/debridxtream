package com.tvonnet.debridxtreamiptv.player.stabilized

import android.content.Intent
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.media3.common.C
import com.tvonnet.debridxtreamiptv.R
import com.tvonnet.debridxtreamiptv.data.model.ContinueWatchingItem
import com.tvonnet.debridxtreamiptv.data.model.RecentLiveChannelItem
import com.tvonnet.debridxtreamiptv.data.model.ContentType
import com.tvonnet.debridxtreamiptv.data.model.toAbsoluteUrl
import com.tvonnet.debridxtreamiptv.data.prefs.WatchHistoryPreferences
import com.tvonnet.debridxtreamiptv.player.stabilized.PlaybackSource
import com.tvonnet.debridxtreamiptv.player.stabilized.PlayerViewModel
import com.tvonnet.debridxtreamiptv.util.SensitiveLogRedactor

class PlayerHistoryManager(
    private val activity: PlayerActivity,
    private val watchHistoryPrefs: WatchHistoryPreferences,
    private val viewModel: PlayerViewModel
) : DefaultLifecycleObserver {

    companion object {
        private const val MIN_DURATION_TO_TRACK_MS = 60_000L
        private const val MIN_PROGRESS_TO_TRACK_MS = 15_000L
        private const val COMPLETION_THRESHOLD_RATIO = 0.90f
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
                recordContinueWatchingHistory(id, type)
            }
        }
        if (type != ContentType.LIVE_TV) {
            activity.hasRecordedHistory = true
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

    private fun recordContinueWatchingHistory(contentId: String, type: ContentType) {
        val p = activity.player ?: return
        val dur = p.duration
        if (dur <= 0 || dur == C.TIME_UNSET || dur < MIN_DURATION_TO_TRACK_MS) return
        val pos = p.currentPosition
        if (pos < MIN_PROGRESS_TO_TRACK_MS) return
        val isWatched = (pos / dur.toFloat()) >= COMPLETION_THRESHOLD_RATIO
        if (type == ContentType.EPISODE) viewModel.updatePlaybackStatus(contentId, isWatched, if (isWatched) 0 else pos, dur)
        if (isWatched) {
            watchHistoryPrefs.removeContinueWatchingItem(resolveContinueWatchingId(type, contentId))
            return
        }
        val item = ContinueWatchingItem(
            contentId = resolveContinueWatchingId(type, contentId),
            contentType = type,
            title = if (type == ContentType.EPISODE) activity.seriesTitleExtra ?: activity.originalTitle ?: activity.pendingChannelName ?: activity.getString(R.string.player_epg_channel_unknown) else activity.originalTitle ?: activity.pendingChannelName ?: activity.getString(R.string.player_epg_channel_unknown),
            posterUrl = (activity.posterUrlExtra ?: activity.channelLogoUrl).toAbsoluteUrl(type, activity.baseServerUrl),
            backdropUrl = activity.backdropUrlExtra.toAbsoluteUrl(type, activity.baseServerUrl),
            currentPosition = pos,
            totalDuration = dur,
            lastWatchedTimestamp = System.currentTimeMillis(),
            streamUrl = activity.currentUrl,
            tmdbId = activity.tmdbIdExtra,
            imdbId = activity.imdbIdExtra,
            seriesId = activity.intent.getStringExtra("extra_series_id"),
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
        android.util.Log.d(
            "HISTORY_DEBUG",
            "Saving Continue Watching [${item.source}]: ${item.title} | type=${item.contentType} | contentId=${SensitiveLogRedactor.describeHash(item.contentId)} | seriesId=${SensitiveLogRedactor.describeHash(item.seriesId)} | season=${item.seasonNumber} | episode=${item.episodeNumber} | pos=$pos dur=$dur | stream=${if (item.streamUrl.isNullOrBlank()) "missing" else "present"} | poster=${if (item.posterUrl.isNullOrBlank()) "missing" else "present"} | expires=${item.expiresAt}"
        )
        watchHistoryPrefs.saveContinueWatchingItem(item)
    }

    private fun resolveContinueWatchingId(type: ContentType, fallbackId: String): String = if (activity.playbackSource != PlaybackSource.DEBRID) fallbackId else activity.tmdbIdExtra?.let { if (type == ContentType.EPISODE && activity.seasonNumberExtra != null && activity.episodeNumberExtra != null) "$it:S${activity.seasonNumberExtra}E${activity.episodeNumberExtra}" else it } ?: activity.debridInfoHashExtra ?: fallbackId
}
