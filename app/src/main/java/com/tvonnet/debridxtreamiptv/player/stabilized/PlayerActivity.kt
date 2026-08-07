package com.tvonnet.debridxtreamiptv.player.stabilized

import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.os.Bundle
import android.view.KeyEvent
import androidx.appcompat.app.AppCompatActivity
import androidx.media3.common.util.UnstableApi
import com.tvonnet.debridxtreamiptv.data.model.ContentType
import dagger.hilt.android.AndroidEntryPoint
import com.tvonnet.debridxtreamiptv.util.lockLandscapeOnTouchDevices

/**
 * Thin host for the player screen (P26 fragment split).
 *
 * All player logic — the ExoPlayer + PlayerView lifecycle, the 20+ Player* collaborators,
 * error/timeout/exit/history, live tuning, debrid coordination — lives in
 * a [BasePlayerFragment] subclass ([LivePlayerFragment] / [VodPlayerFragment]). This Activity remains ONLY:
 *  - the launch contract (`createIntent` + the `EXTRA_*` keys every caller / detail screen
 *    and the return-result builders reference), so nothing outside this package changed;
 *  - the manifest entry (singleTop, PiP-capable, taskAffinity) that owns the task; and
 *  - the Activity-level callbacks the platform only delivers to an Activity — which it
 *    forwards to the hosted fragment: finish() (set the return result on the way out),
 *    dispatchKeyEvent / onKeyLongPress (D-pad routing), onUserInteraction, PiP-mode
 *    changes, and onTrimMemory (the retained-player memory guard).
 */
@UnstableApi
@AndroidEntryPoint
class PlayerActivity : AppCompatActivity() {

    private val screen: BasePlayerFragment?
        get() = supportFragmentManager.findFragmentById(android.R.id.content) as? BasePlayerFragment

    override fun onCreate(savedInstanceState: Bundle?) {
        // M13: one orientation for the whole app on a phone — landscape, like the TV.
        lockLandscapeOnTouchDevices()
        super.onCreate(savedInstanceState)
        if (savedInstanceState == null) {
            // P27: pick the Live vs VOD player screen by content type. Both currently subclass
            // BasePlayerFragment (which still holds the shared + branch logic); the split moves
            // the mode-specific logic down into each subclass.
            val isLive = intent.getStringExtra(EXTRA_CONTENT_TYPE) == ContentType.LIVE_TV.name
            val fragment: BasePlayerFragment = if (isLive) LivePlayerFragment() else VodPlayerFragment()
            supportFragmentManager.beginTransaction()
                .replace(android.R.id.content, fragment)
                .commitNow()
        }
    }

    // The player screen's finish() forwards here (requireActivity().finish()); run the
    // fragment's exit-result setters BEFORE super.finish() so the return-to-sources /
    // live-return result still reaches the detail screen's startActivityForResult.
    override fun finish() {
        screen?.onHostFinishing()
        super.finish()
    }

    // D-pad / media-key routing: the fragment returns null to pass the event to super.
    override fun dispatchKeyEvent(event: KeyEvent): Boolean =
        screen?.hostDispatchKeyEvent(event) ?: super.dispatchKeyEvent(event)

    override fun onKeyLongPress(keyCode: Int, event: KeyEvent): Boolean =
        screen?.hostKeyLongPress(keyCode, event) ?: super.onKeyLongPress(keyCode, event)

    /**
     * M9b: touch gestures have to be seen HERE, not on the PlayerView.
     *
     * The first attempt put the detector on the PlayerView and the handset reported no change:
     * the Live OSD is a full-screen overlay sitting above it, so a finger never reaches the
     * video at all. `dispatchTouchEvent` is the one place every touch passes through regardless
     * of what is stacked on top.
     *
     * It only OBSERVES — `super` still runs, so the OSD's own buttons keep working; the fragment
     * turns a tap or a vertical fling into the same key the remote sends. On TV nothing attaches,
     * so this is a null check per event.
     */
    override fun dispatchTouchEvent(event: android.view.MotionEvent): Boolean {
        screen?.hostTouchEvent(event)
        return super.dispatchTouchEvent(event)
    }

    override fun onUserInteraction() {
        super.onUserInteraction()
        screen?.hostUserInteraction()
    }

    override fun onPictureInPictureModeChanged(isInPictureInPictureMode: Boolean, newConfig: Configuration) {
        super.onPictureInPictureModeChanged(isInPictureInPictureMode, newConfig)
        screen?.hostPipModeChanged(isInPictureInPictureMode)
    }

    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        screen?.onHostTrimMemory(level)
    }

    companion object {
        const val EXTRA_STREAM_URL = "STREAM_URL"
        const val EXTRA_STREAM_HEADERS = "STREAM_HEADERS"
        const val EXTRA_STREAM_TITLE = "STREAM_TITLE"
        const val EXTRA_CHANNEL_NAME = "CHANNEL_NAME"
        const val EXTRA_CHANNEL_LOGO = "CHANNEL_LOGO"
        const val EXTRA_EPG_CHANNEL_ID = "EPG_CHANNEL_ID"
        const val EXTRA_START_POSITION = "START_POSITION"
        const val EXTRA_CONTENT_ID = "CONTENT_ID"
        const val EXTRA_CONTENT_TYPE = "CONTENT_TYPE"
        const val EXTRA_POSTER_URL = "POSTER_URL"
        const val EXTRA_BACKDROP_URL = "BACKDROP_URL"
        const val EXTRA_PLAYBACK_SOURCE = "PLAYBACK_SOURCE"
        const val EXTRA_LIVE_CATEGORY_ID = "LIVE_CATEGORY_ID"
        const val EXTRA_LIVE_CHANNEL_IDS = "LIVE_CHANNEL_IDS"
        const val EXTRA_BASE_SERVER_URL = "BASE_SERVER_URL"
        const val EXTRA_LIVE_RETURN_CHANNEL_ID = "LIVE_RETURN_CHANNEL_ID"
        const val EXTRA_SHARED_LIVE_PLAYER = "SHARED_LIVE_PLAYER"
        const val EXTRA_LIVE_RETURN_CHANNEL_NAME = "LIVE_RETURN_CHANNEL_NAME"
        const val EXTRA_LIVE_RETURN_CHANNEL_LOGO = "LIVE_RETURN_CHANNEL_LOGO"
        const val EXTRA_LIVE_RETURN_EPG_CHANNEL_ID = "LIVE_RETURN_EPG_CHANNEL_ID"
        const val EXTRA_LIVE_RETURN_STREAM_URL = "LIVE_RETURN_STREAM_URL"
        const val EXTRA_LIVE_RETURN_CATEGORY_ID = "LIVE_RETURN_CATEGORY_ID"
        const val EXTRA_SERIES_ID = "EXTRA_SERIES_ID"
        const val EXTRA_SEASON_NUM = "EXTRA_SEASON_NUM"
        const val EXTRA_EPISODE_NUM = "EXTRA_EPISODE_NUM"
        const val EXTRA_TMDB_ID = "EXTRA_TMDB_ID"
        const val EXTRA_IMDB_ID = "EXTRA_IMDB_ID"
        const val EXTRA_SERIES_TITLE = "EXTRA_SERIES_TITLE"
        const val EXTRA_EPISODE_TITLE = "EXTRA_EPISODE_TITLE"
        const val EXTRA_DEBRID_INFOHASH = "EXTRA_DEBRID_INFOHASH"
        const val EXTRA_DEBRID_MAGNET = "EXTRA_DEBRID_MAGNET"
        const val EXTRA_DIRECT_DEBRID_PLAYBACK = "EXTRA_DIRECT_DEBRID_PLAYBACK"
        const val EXTRA_DEBRID_PROVIDER = "EXTRA_DEBRID_PROVIDER"
        const val EXTRA_DEBRID_SOURCE_TYPE = "EXTRA_DEBRID_SOURCE_TYPE"
        const val EXTRA_DEBRID_SOURCE_NAME = "EXTRA_DEBRID_SOURCE_NAME"
        const val EXTRA_DEBRID_LANGUAGES = "EXTRA_DEBRID_LANGUAGES"
        const val EXTRA_DEBRID_QUALITY = "EXTRA_DEBRID_QUALITY"
        const val EXTRA_DEBRID_STREAM_ID = "EXTRA_DEBRID_STREAM_ID"
        const val EXTRA_DEBRID_BINGE_GROUP = "EXTRA_DEBRID_BINGE_GROUP"
        const val EXTRA_DEBRID_FILE_IDX = "EXTRA_DEBRID_FILE_IDX"
        const val EXTRA_EXPIRES_AT = "EXTRA_EXPIRES_AT"
        const val EXTRA_RETURN_TO_SOURCES = "EXTRA_RETURN_TO_SOURCES"
        const val EXTRA_FAILED_STREAM_ID = "EXTRA_FAILED_STREAM_ID"
        const val EXTRA_FAIL_REASON = "EXTRA_FAIL_REASON"
        const val EXTRA_AUTO_PLAY_NEXT = "EXTRA_AUTO_PLAY_NEXT"
        const val EXTRA_SUBTITLE_ENTRIES = "EXTRA_SUBTITLE_ENTRIES"
        const val EXTRA_OPENED_FROM_PLAYBACK_FAILURE = "EXTRA_OPENED_FROM_PLAYBACK_FAILURE"

        fun createIntent(
            context: Context,
            streamUrl: String,
            title: String? = null,
            channelName: String? = null,
            channelLogo: String? = null,
            epgChannelId: String? = null,
            startPositionMs: Long? = null,
            contentId: String? = null,
            contentType: ContentType? = null,
            playbackSource: PlaybackSource? = null,
            posterUrl: String? = null,
            backdropUrl: String? = null,
            liveCategoryId: String? = null,
            liveChannelIds: ArrayList<String>? = null,
            baseServerUrl: String? = null,
            headers: Map<String, String>? = null,
            tmdbId: String? = null,
            imdbId: String? = null,
            seriesTitle: String? = null,
            episodeTitle: String? = null,
            seasonNumber: Int? = null,
            episodeNumber: Int? = null,
            debridInfoHash: String? = null,
            debridMagnet: String? = null,
            directDebridPlayback: Boolean = false,
            debridProvider: String? = null,
            debridSourceType: String? = null,
            debridSourceName: String? = null,
            debridLanguages: List<String>? = null,
            debridQuality: String? = null,
            debridStreamId: String? = null,
            debridBingeGroup: String? = null,
            debridFileIdx: Int? = null,
            subtitles: List<String>? = null,
            expiresAt: Long? = null,
            seriesId: String? = null,
            sharedLivePlayer: Boolean = false
        ): Intent {
            val extras = LaunchExtras(
                streamUrl = streamUrl,
                title = title,
                channelName = channelName,
                channelLogo = channelLogo,
                epgChannelId = epgChannelId,
                startPositionMs = startPositionMs,
                contentId = contentId,
                contentType = contentType,
                playbackSource = playbackSource,
                posterUrl = posterUrl,
                backdropUrl = backdropUrl,
                liveCategoryId = liveCategoryId,
                liveChannelIds = liveChannelIds,
                baseServerUrl = baseServerUrl,
                headers = headers,
                tmdbId = tmdbId,
                imdbId = imdbId,
                seriesTitle = seriesTitle,
                episodeTitle = episodeTitle,
                seasonNumber = seasonNumber,
                episodeNumber = episodeNumber,
                debridInfoHash = debridInfoHash,
                debridMagnet = debridMagnet,
                directDebridPlayback = directDebridPlayback,
                debridProvider = debridProvider,
                debridSourceType = debridSourceType,
                debridSourceName = debridSourceName,
                debridLanguages = debridLanguages,
                debridQuality = debridQuality,
                debridStreamId = debridStreamId,
                debridBingeGroup = debridBingeGroup,
                debridFileIdx = debridFileIdx,
                subtitles = subtitles,
                expiresAt = expiresAt,
                seriesId = seriesId,
                sharedLivePlayer = sharedLivePlayer
            )
            return Intent(context, PlayerActivity::class.java).apply {
                putCoreExtras(extras)
                putSessionExtras(extras)
                putIdentityExtras(extras)
                putDebridSourceExtras(extras)
                putDebridStreamExtras(extras)
            }
        }

        /** Everything createIntent accepts, bundled so the put* helpers stay parameter-light. */
        private data class LaunchExtras(
            val streamUrl: String,
            val title: String?,
            val channelName: String?,
            val channelLogo: String?,
            val epgChannelId: String?,
            val startPositionMs: Long?,
            val contentId: String?,
            val contentType: ContentType?,
            val playbackSource: PlaybackSource?,
            val posterUrl: String?,
            val backdropUrl: String?,
            val liveCategoryId: String?,
            val liveChannelIds: ArrayList<String>?,
            val baseServerUrl: String?,
            val headers: Map<String, String>?,
            val tmdbId: String?,
            val imdbId: String?,
            val seriesTitle: String?,
            val episodeTitle: String?,
            val seasonNumber: Int?,
            val episodeNumber: Int?,
            val debridInfoHash: String?,
            val debridMagnet: String?,
            val directDebridPlayback: Boolean,
            val debridProvider: String?,
            val debridSourceType: String?,
            val debridSourceName: String?,
            val debridLanguages: List<String>?,
            val debridQuality: String?,
            val debridStreamId: String?,
            val debridBingeGroup: String?,
            val debridFileIdx: Int?,
            val subtitles: List<String>?,
            val expiresAt: Long?,
            val seriesId: String?,
            val sharedLivePlayer: Boolean
        )

        // The always-set extras (nullable values are put as-is, same as before).
        private fun Intent.putCoreExtras(e: LaunchExtras) {
            putExtra(EXTRA_STREAM_URL, e.streamUrl)
            putExtra(EXTRA_STREAM_TITLE, e.title)
            putExtra(EXTRA_CHANNEL_NAME, e.channelName)
            putExtra(EXTRA_CHANNEL_LOGO, e.channelLogo)
            putExtra(EXTRA_EPG_CHANNEL_ID, e.epgChannelId)
            putExtra(EXTRA_CONTENT_ID, e.contentId)
            putExtra(EXTRA_CONTENT_TYPE, e.contentType?.name)
            putExtra(EXTRA_PLAYBACK_SOURCE, e.playbackSource?.name)
            putExtra(EXTRA_POSTER_URL, e.posterUrl)
            putExtra(EXTRA_BACKDROP_URL, e.backdropUrl)
            putExtra(EXTRA_SERIES_ID, e.seriesId)
            putExtra(EXTRA_SHARED_LIVE_PLAYER, e.sharedLivePlayer)
            putExtra(EXTRA_DIRECT_DEBRID_PLAYBACK, e.directDebridPlayback)
        }

        private fun Intent.putSessionExtras(e: LaunchExtras) {
            e.liveCategoryId?.let { putExtra(EXTRA_LIVE_CATEGORY_ID, it) }
            e.liveChannelIds?.let { putStringArrayListExtra(EXTRA_LIVE_CHANNEL_IDS, it) }
            e.baseServerUrl?.let { putExtra(EXTRA_BASE_SERVER_URL, it) }
            e.startPositionMs?.let { putExtra(EXTRA_START_POSITION, it) }
            e.headers?.let { putExtra(EXTRA_STREAM_HEADERS, HashMap(it)) }
        }

        private fun Intent.putIdentityExtras(e: LaunchExtras) {
            e.tmdbId?.let { putExtra(EXTRA_TMDB_ID, it) }
            e.imdbId?.let { putExtra(EXTRA_IMDB_ID, it) }
            e.seriesTitle?.let { putExtra(EXTRA_SERIES_TITLE, it) }
            e.episodeTitle?.let { putExtra(EXTRA_EPISODE_TITLE, it) }
            e.seasonNumber?.let { putExtra(EXTRA_SEASON_NUM, it) }
            e.episodeNumber?.let { putExtra(EXTRA_EPISODE_NUM, it) }
        }

        private fun Intent.putDebridSourceExtras(e: LaunchExtras) {
            e.debridProvider?.let { putExtra(EXTRA_DEBRID_PROVIDER, it) }
            e.debridSourceType?.let { putExtra(EXTRA_DEBRID_SOURCE_TYPE, it) }
            e.debridSourceName?.let { putExtra(EXTRA_DEBRID_SOURCE_NAME, it) }
            e.debridLanguages?.let { putStringArrayListExtra(EXTRA_DEBRID_LANGUAGES, ArrayList(it)) }
            e.debridQuality?.let { putExtra(EXTRA_DEBRID_QUALITY, it) }
            e.debridBingeGroup?.let { putExtra(EXTRA_DEBRID_BINGE_GROUP, it) }
        }

        private fun Intent.putDebridStreamExtras(e: LaunchExtras) {
            e.debridInfoHash?.let { putExtra(EXTRA_DEBRID_INFOHASH, it) }
            e.debridMagnet?.let { putExtra(EXTRA_DEBRID_MAGNET, it) }
            e.debridStreamId?.let { putExtra(EXTRA_DEBRID_STREAM_ID, it) }
            e.debridFileIdx?.let { putExtra(EXTRA_DEBRID_FILE_IDX, it) }
            e.expiresAt?.let { putExtra(EXTRA_EXPIRES_AT, it) }
            e.subtitles?.let { putStringArrayListExtra(EXTRA_SUBTITLE_ENTRIES, ArrayList(it)) }
        }
    }
}
