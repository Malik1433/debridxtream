package com.tvonnet.debridxtreamiptv.player.stabilized

import com.tvonnet.debridxtreamiptv.data.model.ContentType

/**
 * The player screen's mutable state, in one place (Phase S1 of the route to ≤600).
 *
 * Before this, ~55 loose `var`s lived directly on [PlayerActivity], which is why
 * every extraction needed a fistful of callbacks. The Activity now declares each of
 * these as `by session::field`, so every existing call site — inside the Activity
 * and in collaborators reading `activity.contentId` etc. — compiles unchanged,
 * while new controllers can take the session directly.
 *
 * Grouped by lifecycle: identity (set once from the intent), playback (changes per
 * source), recovery (reset on READY / zap).
 */
internal class PlayerSessionState {

    // ── identity: what was launched (intent extras, may be rewritten on episode switch) ──
    var contentType: ContentType? = null
    var playbackSource: PlaybackSource = PlaybackSource.IPTV
    var directDebridPlayback: Boolean = false
    var contentId: String? = null
    var originalTitle: String? = null
    var posterUrlExtra: String? = null
    var backdropUrlExtra: String? = null
    var tmdbIdExtra: String? = null
    var imdbIdExtra: String? = null
    var seriesTitleExtra: String? = null
    var episodeTitleExtra: String? = null
    var seasonNumberExtra: Int? = null
    var episodeNumberExtra: Int? = null
    var expiresAtExtra: Long? = null
    var returnToSourcesOnExit: Boolean = false
    var subtitleEntries: List<String> = emptyList()

    // ── debrid source identity ──
    var debridInfoHashExtra: String? = null
    var debridMagnetExtra: String? = null
    var debridProviderExtra: String? = null
    var debridSourceTypeExtra: String? = null
    var debridSourceNameExtra: String? = null
    var debridLanguagesExtra: List<String>? = null
    var debridQualityExtra: String? = null
    var debridStreamIdExtra: String? = null
    var debridBingeGroupExtra: String? = null
    var debridFileIdxExtra: Int? = null

    // ── live identity ──
    var channelLogoUrl: String? = null
    var pendingChannelName: String? = null
    var currentEpgChannelId: String? = null
    var liveCategoryId: String? = null
    var baseServerUrl: String? = null

    // ── playback state (changes per source / per position save) ──
    var currentUrl: String? = null
    var streamHeaders: Map<String, String>? = null
    var startPositionMs: Long = 0L
    var lastPlaybackPositionMs: Long = 0L
    var timeoutMs: Long = 0L
    var didPlaybackComplete: Boolean = false
    var hasRecordedHistory: Boolean = false
    var isResolvingDebrid: Boolean = false
    var isSwitching: Boolean = false
    var switchCount: Int = 0
    var hasAppliedIndexOverride: Boolean = false
    var hasRenderedFirstFrameForCurrentSource: Boolean = false
    var frameRateMatchedForCurrentSource: Boolean = false
    var currentZapRequestId: Long = 0L

    // ── exit state ──
    var manualExit: Boolean = false
    var exitResultHandled: Boolean = false
    var sharedExitInProgress: Boolean = false
    var wasPlayingBeforePiP: Boolean = true
    var backHideArmed: Boolean = false

    // ── recovery state (reset on a clean READY or a user zap) ──
    var retryCount: Int = 0
    var audioSinkRecoveryCount: Int = 0
    var endedReconnects: Int = 0
    var liveFreezeReprepares: Int = 0
    var lastFreezeRecoveryUrl: String? = null
    var lastFreezeRecoveryAtMs: Long = 0L
    var disableTunnelingForSession: Boolean = false
    var blackVideoFallbackTried: Boolean = false
    var lastBufferingStartMs: Long = 0L
    var watchdogExtensions: Int = 0
    var watchdogBufferedPosAtArm: Long = -1L
    var networkAvailable: Boolean = true
}
