package com.tvonnet.debridxtreamiptv.player.stabilized

import android.content.Intent

/**
 * Builders for the result Intents the player hands back to its caller (Phase P12).
 *
 * Pure: they take the values and return the Intent. The Activity keeps the guards
 * (exitResultHandled / playbackSource / thresholds), the setResult call and the
 * finish, because those are lifecycle decisions.
 */

/** What the guide needs to restore the channel the user was just watching. */
internal data class LiveReturnChannel(
    val channelId: String,
    val channelName: String?,
    val channelLogoUrl: String?,
    val epgChannelId: String?,
    val streamUrl: String?,
    val categoryId: String?
)

/** Live TV: tell the guide which channel we were on so it can restore it. */
internal fun buildLiveReturnIntent(channel: LiveReturnChannel): Intent = Intent()
    .putExtra(PlayerActivity.EXTRA_LIVE_RETURN_CHANNEL_ID, channel.channelId)
    .putExtra(PlayerActivity.EXTRA_LIVE_RETURN_CHANNEL_NAME, channel.channelName)
    .putExtra(PlayerActivity.EXTRA_LIVE_RETURN_CHANNEL_LOGO, channel.channelLogoUrl)
    .putExtra(PlayerActivity.EXTRA_LIVE_RETURN_EPG_CHANNEL_ID, channel.epgChannelId)
    .putExtra(PlayerActivity.EXTRA_LIVE_RETURN_STREAM_URL, channel.streamUrl)
    .putExtra(PlayerActivity.EXTRA_LIVE_RETURN_CATEGORY_ID, channel.categoryId)

/** Debrid: plain "go back to the source picker" with no failure context. */
internal fun buildReturnToSourcesIntent(): Intent =
    Intent().putExtra(PlayerActivity.EXTRA_RETURN_TO_SOURCES, true)

/**
 * Debrid: return to the source picker *because this source failed* — carries the
 * failed stream id so the picker can skip it, plus whether to auto-play the next one.
 */
internal fun buildFailedSourceReturnIntent(
    failedStreamId: String?,
    reason: String?,
    autoPlayNext: Boolean
): Intent = Intent()
    .putExtra(PlayerActivity.EXTRA_RETURN_TO_SOURCES, true)
    .putExtra(PlayerActivity.EXTRA_FAILED_STREAM_ID, failedStreamId)
    .putExtra(PlayerActivity.EXTRA_FAIL_REASON, reason)
    .putExtra(PlayerActivity.EXTRA_AUTO_PLAY_NEXT, autoPlayNext)
