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

/**
 * Thin host for the player screen (P26 fragment split).
 *
 * All player logic — the ExoPlayer + PlayerView lifecycle, the 20+ Player* collaborators,
 * error/timeout/exit/history, live tuning, debrid coordination — lives in
 * [PlayerScreenFragment]. This Activity remains ONLY:
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

    private val screen: PlayerScreenFragment?
        get() = supportFragmentManager.findFragmentById(android.R.id.content) as? PlayerScreenFragment

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (savedInstanceState == null) {
            supportFragmentManager.beginTransaction()
                .replace(android.R.id.content, PlayerScreenFragment())
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
            return Intent(context, PlayerActivity::class.java).apply {
                putExtra(EXTRA_STREAM_URL, streamUrl)
                putExtra(EXTRA_STREAM_TITLE, title)
                putExtra(EXTRA_CHANNEL_NAME, channelName)
                putExtra(EXTRA_CHANNEL_LOGO, channelLogo)
                putExtra(EXTRA_EPG_CHANNEL_ID, epgChannelId)
                putExtra(EXTRA_CONTENT_ID, contentId)
                putExtra(EXTRA_CONTENT_TYPE, contentType?.name)
                putExtra(EXTRA_PLAYBACK_SOURCE, playbackSource?.name)
                putExtra(EXTRA_POSTER_URL, posterUrl)
                putExtra(EXTRA_BACKDROP_URL, backdropUrl)
                liveCategoryId?.let { putExtra(EXTRA_LIVE_CATEGORY_ID, it) }
                liveChannelIds?.let { putStringArrayListExtra(EXTRA_LIVE_CHANNEL_IDS, it) }
                baseServerUrl?.let { putExtra(EXTRA_BASE_SERVER_URL, it) }
                putExtra(EXTRA_SERIES_ID, seriesId)
                putExtra(EXTRA_SHARED_LIVE_PLAYER, sharedLivePlayer)
                startPositionMs?.let { putExtra(EXTRA_START_POSITION, it) }
                headers?.let { putExtra(EXTRA_STREAM_HEADERS, HashMap(it)) }
                tmdbId?.let { putExtra(EXTRA_TMDB_ID, it) }
                imdbId?.let { putExtra(EXTRA_IMDB_ID, it) }
                seriesTitle?.let { putExtra(EXTRA_SERIES_TITLE, it) }
                episodeTitle?.let { putExtra(EXTRA_EPISODE_TITLE, it) }
                seasonNumber?.let { putExtra(EXTRA_SEASON_NUM, it) }
                episodeNumber?.let { putExtra(EXTRA_EPISODE_NUM, it) }
                debridInfoHash?.let { putExtra(EXTRA_DEBRID_INFOHASH, it) }
                debridMagnet?.let { putExtra(EXTRA_DEBRID_MAGNET, it) }
                putExtra(EXTRA_DIRECT_DEBRID_PLAYBACK, directDebridPlayback)
                debridProvider?.let { putExtra(EXTRA_DEBRID_PROVIDER, it) }
                debridSourceType?.let { putExtra(EXTRA_DEBRID_SOURCE_TYPE, it) }
                debridSourceName?.let { putExtra(EXTRA_DEBRID_SOURCE_NAME, it) }
                debridLanguages?.let { putStringArrayListExtra(EXTRA_DEBRID_LANGUAGES, ArrayList(it)) }
                debridQuality?.let { putExtra(EXTRA_DEBRID_QUALITY, it) }
                debridStreamId?.let { putExtra(EXTRA_DEBRID_STREAM_ID, it) }
                debridBingeGroup?.let { putExtra(EXTRA_DEBRID_BINGE_GROUP, it) }
                debridFileIdx?.let { putExtra(EXTRA_DEBRID_FILE_IDX, it) }
                expiresAt?.let { putExtra(EXTRA_EXPIRES_AT, it) }
                subtitles?.let { putStringArrayListExtra(EXTRA_SUBTITLE_ENTRIES, ArrayList(it)) }
            }
        }
    }
}
