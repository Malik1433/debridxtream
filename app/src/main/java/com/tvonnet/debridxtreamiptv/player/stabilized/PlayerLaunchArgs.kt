package com.tvonnet.debridxtreamiptv.player.stabilized

import android.util.Log
import com.tvonnet.debridxtreamiptv.data.model.ContentType
import com.tvonnet.debridxtreamiptv.util.SensitiveLogRedactor

/**
 * Parses the launch intent into [PlayerSessionState] (Phase P25, fragment-split prep).
 *
 * Moved verbatim out of the 272-line `onCreate`: the block of ~30 identity
 * assignments (contentType / playbackSource / debrid identity / TMDB-IMDB ids …)
 * that read intent extras into the session. Assignments are byte-identical — only
 * the receiver changed (`with(session)`); the extra KEYS stay on [PlayerActivity]'s
 * companion (they are the public `createIntent` contract).
 */
internal object PlayerLaunchArgs {

    /** Populate the session identity fields from the launch intent. */
    fun readInto(activity: PlayerScreenFragment, session: PlayerSessionState) = with(session) {
        val intent = activity.intent
        val contentTypeString = intent.getStringExtra(PlayerActivity.EXTRA_CONTENT_TYPE)
        contentType = contentTypeString?.let { runCatching { ContentType.valueOf(it) }.getOrNull() }
        Log.d("PlayerActivity", "onCreate: contentTypeString=$contentTypeString, contentType=$contentType, contentId=${SensitiveLogRedactor.describeHash(contentId)}")
        playbackSource = intent.getStringExtra(PlayerActivity.EXTRA_PLAYBACK_SOURCE)
            ?.let { runCatching { PlaybackSource.valueOf(it) }.getOrNull() }
            ?: PlaybackSource.IPTV
        directDebridPlayback = intent.getBooleanExtra(PlayerActivity.EXTRA_DIRECT_DEBRID_PLAYBACK, false)
        contentId = intent.getStringExtra(PlayerActivity.EXTRA_CONTENT_ID) ?: intent.getStringExtra(PlayerActivity.EXTRA_STREAM_URL)
        returnToSourcesOnExit = intent.getBooleanExtra(PlayerActivity.EXTRA_RETURN_TO_SOURCES, false)
        currentEpgChannelId = intent.getStringExtra(PlayerActivity.EXTRA_EPG_CHANNEL_ID)
            ?.takeIf { it.isNotBlank() }
            ?: contentId?.takeIf { it.isNotBlank() }
        posterUrlExtra = intent.getStringExtra(PlayerActivity.EXTRA_POSTER_URL)
        backdropUrlExtra = intent.getStringExtra(PlayerActivity.EXTRA_BACKDROP_URL)
        tmdbIdExtra = intent.getStringExtra(PlayerActivity.EXTRA_TMDB_ID)
        imdbIdExtra = intent.getStringExtra(PlayerActivity.EXTRA_IMDB_ID)
        seriesTitleExtra = intent.getStringExtra(PlayerActivity.EXTRA_SERIES_TITLE)
        episodeTitleExtra = intent.getStringExtra(PlayerActivity.EXTRA_EPISODE_TITLE)
        seasonNumberExtra = intent.getIntExtra(PlayerActivity.EXTRA_SEASON_NUM, -1).takeIf { it >= 0 }
        episodeNumberExtra = intent.getIntExtra(PlayerActivity.EXTRA_EPISODE_NUM, -1).takeIf { it >= 0 }
        debridInfoHashExtra = intent.getStringExtra(PlayerActivity.EXTRA_DEBRID_INFOHASH)
        debridMagnetExtra = intent.getStringExtra(PlayerActivity.EXTRA_DEBRID_MAGNET)
            ?: intent.getStringExtra("DEBRID_MAGNET")
        debridProviderExtra = intent.getStringExtra(PlayerActivity.EXTRA_DEBRID_PROVIDER)
        debridSourceTypeExtra = intent.getStringExtra(PlayerActivity.EXTRA_DEBRID_SOURCE_TYPE)
        debridSourceNameExtra = intent.getStringExtra(PlayerActivity.EXTRA_DEBRID_SOURCE_NAME)
        debridLanguagesExtra = intent.getStringArrayListExtra(PlayerActivity.EXTRA_DEBRID_LANGUAGES)
        debridQualityExtra = intent.getStringExtra(PlayerActivity.EXTRA_DEBRID_QUALITY)
        debridStreamIdExtra = intent.getStringExtra(PlayerActivity.EXTRA_DEBRID_STREAM_ID)
        debridBingeGroupExtra = intent.getStringExtra(PlayerActivity.EXTRA_DEBRID_BINGE_GROUP)
        debridFileIdxExtra = intent.getIntExtra(PlayerActivity.EXTRA_DEBRID_FILE_IDX, -1).takeIf { it >= 0 }
        expiresAtExtra = intent.getLongExtra(PlayerActivity.EXTRA_EXPIRES_AT, -1L).takeIf { it > 0 }
    }
}
