package com.tvonnet.debridxtreamiptv.player.stabilized

/** How long after our own connection comes back a stall is still blamed on it, not on the feed. */
internal const val NETWORK_GRACE_MS = 60_000L

/**
 * Our own connection is down, or came back moments ago. A stall now says nothing about the
 * provider's feed, so it must not send the channel to another feed (QA 2026-09-28: a 9 s Wi-Fi cut
 * ended in a failover and 45 s of black instead of ~20 s). Pure given [nowMs].
 */
internal fun networkRecentlyLost(networkAvailable: Boolean, lastLossAtMs: Long, nowMs: Long): Boolean =
    !networkAvailable || (lastLossAtMs > 0L && nowMs - lastLossAtMs < NETWORK_GRACE_MS)

internal fun PlayerSessionState.networkRecentlyLost(): Boolean =
    networkRecentlyLost(networkAvailable, lastNetworkLossAtMs, android.os.SystemClock.elapsedRealtime())
