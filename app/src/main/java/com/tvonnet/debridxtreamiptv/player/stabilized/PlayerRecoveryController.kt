package com.tvonnet.debridxtreamiptv.player.stabilized

import android.content.Context
import android.net.ConnectivityManager
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.datasource.HttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import com.tvonnet.debridxtreamiptv.data.model.ContentType
import com.tvonnet.debridxtreamiptv.debug.PlaybackDiagnosticsRecorder

/**
 * The player screen's error-and-recovery brain (Phase C1 of the route to ≤600).
 *
 * Moved verbatim out of [PlayerActivity]: the playback-error reactions
 * ([handlePlaybackError]), the buffering watchdog ([handleTimeout]), first-connect
 * network recovery ([attemptNetworkRecovery] + the connectivity callback) and the
 * init-error terminal path. This is the code that keeps a flapping network, a dead
 * listing, a rate-limited WAF and a wedged AudioTrack from turning into the app's
 * "loading forever" landmines — so the logic here is byte-for-byte the shipped,
 * device-verified behaviour; only the plumbing (state via [session], collaborators
 * via [host]) changed.
 *
 * The two [Handler]s and the [timeoutRunnable] stay OWNED by the Activity (they are
 * armed/cancelled from ~30 non-recovery call sites — initializePlayer, the lifecycle
 * callbacks, releasePlayer); this controller holds the same instances so both sides
 * post/cancel the same work.
 */
internal class PlayerRecoveryController(
    private val activity: Context,
    private val session: PlayerSessionState,
    private val host: RecoveryHost,
    private val timeoutHandler: Handler,
    private val retryHandler: Handler,
    private val timeoutRunnable: Runnable,
) {

    /** Everything the recovery code needs from the Activity that is not screen state. */
    interface RecoveryHost {
        fun peekPlayer(): ExoPlayer?
        fun assignPlayer(player: ExoPlayer?)
        fun initializePlayer(streamUrl: String)
        fun requestSeamlessSwitch(newUrl: String)
        fun canAttemptReconnect(): Boolean
        fun handleTerminalPlaybackFailure(reason: String, preferReturnToSources: Boolean = false)
        fun finishWithReturnToSources(autoPlayNext: Boolean, reason: String?): Boolean
        fun refreshDirectDebridSourceFromMetadata(reason: String): Boolean
        fun canUseDebridResolver(): Boolean

        /**
         * Can a direct source mint a fresh link from its own metadata? Asked BEFORE deciding an
         * expired link is repairable — [refreshDirectDebridSourceFromMetadata] answers the same
         * question but by doing the work, and a classification must not have side effects.
         */
        fun canFreshResolveDirectDebrid(): Boolean
        fun reResolveDebridUrl(
            infoHash: String?,
            magnet: String?,
            season: Int?,
            episode: Int?,
            title: String?,
            allowDirectHttpPassthrough: Boolean = true
        )
        fun recordDirectAddonProxyFailure()
        fun hasRepeatedDirectAddonProxyTimeout(url: String): Boolean
        fun hasRepeatedDirectAddonProxyServerError(error: PlaybackException): Boolean
        fun requiresAddonProxyPlaybackContext(url: String?): Boolean
        fun diagnosticsFields(url: String?, retry: Int?): Map<String, Any?>
        fun isAutoReconnectEnabled(): Boolean
        fun maxRetriesConfigured(): Int
    }

    // ── connectivity: owned wholly by the recovery subsystem ──
    private val connectivityManager =
        activity.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
    private var networkCallback: ConnectivityManager.NetworkCallback? = null

    // ── screen state (S1): the same session the Activity owns ──
    private var isSwitching: Boolean by session::isSwitching
    private var currentUrl: String? by session::currentUrl
    private var retryCount: Int by session::retryCount
    private var audioSinkRecoveryCount: Int by session::audioSinkRecoveryCount
    private var disableTunnelingForSession: Boolean by session::disableTunnelingForSession
    private var startPositionMs: Long by session::startPositionMs
    private val contentType: ContentType? by session::contentType
    private var isResolvingDebrid: Boolean by session::isResolvingDebrid
    private val directDebridPlayback: Boolean by session::directDebridPlayback
    private val playbackSource: PlaybackSource by session::playbackSource
    private val debridInfoHashExtra: String? by session::debridInfoHashExtra
    private val debridMagnetExtra: String? by session::debridMagnetExtra
    private val seasonNumberExtra: Int? by session::seasonNumberExtra
    private val episodeNumberExtra: Int? by session::episodeNumberExtra
    private val episodeTitleExtra: String? by session::episodeTitleExtra
    private val timeoutMs: Long by session::timeoutMs
    private var watchdogExtensions: Int by session::watchdogExtensions
    private var watchdogBufferedPosAtArm: Long by session::watchdogBufferedPosAtArm
    private val hasRenderedFirstFrameForCurrentSource: Boolean by session::hasRenderedFirstFrameForCurrentSource
    private val lastBufferingStartMs: Long by session::lastBufferingStartMs
    private var networkAvailable: Boolean by session::networkAvailable

    // ── bridges so the moved bodies read/write the Activity's live player verbatim ──
    private var player: ExoPlayer?
        get() = host.peekPlayer()
        set(value) { host.assignPlayer(value) }

    private val maxRetries: Int get() = host.maxRetriesConfigured()

    // ── thin forwarders: the recovery code calls the same collaborators it always did ──
    private fun canAttemptReconnect(): Boolean = host.canAttemptReconnect()
    private fun handleTerminalPlaybackFailure(reason: String, preferReturnToSources: Boolean = false) =
        host.handleTerminalPlaybackFailure(reason, preferReturnToSources)
    private fun finishWithReturnToSources(autoPlayNext: Boolean, reason: String?): Boolean =
        host.finishWithReturnToSources(autoPlayNext, reason)
    private fun refreshDirectDebridSourceFromMetadata(reason: String): Boolean =
        host.refreshDirectDebridSourceFromMetadata(reason)
    private fun canUseDebridResolver(): Boolean = host.canUseDebridResolver()
    private fun canFreshResolveDirectDebrid(): Boolean = host.canFreshResolveDirectDebrid()
    private fun recordDirectAddonProxyFailure() = host.recordDirectAddonProxyFailure()
    private fun hasRepeatedDirectAddonProxyTimeout(url: String): Boolean =
        host.hasRepeatedDirectAddonProxyTimeout(url)
    private fun hasRepeatedDirectAddonProxyServerError(error: PlaybackException): Boolean =
        host.hasRepeatedDirectAddonProxyServerError(error)
    private fun requiresAddonProxyPlaybackContext(url: String?): Boolean =
        host.requiresAddonProxyPlaybackContext(url)
    private fun performSeamlessSwitch(newUrl: String) = host.requestSeamlessSwitch(newUrl)
    private fun initializePlayer(streamUrl: String) = host.initializePlayer(streamUrl)
    private fun isAutoReconnectEnabled(): Boolean = host.isAutoReconnectEnabled()
    private fun reResolveDebridUrl(
        infoHash: String?,
        magnet: String?,
        season: Int?,
        episode: Int?,
        title: String?,
        allowDirectHttpPassthrough: Boolean = true
    ) = host.reResolveDebridUrl(infoHash, magnet, season, episode, title, allowDirectHttpPassthrough)
    private fun diagnosticsPlaybackFields(
        url: String? = currentUrl,
        retry: Int? = retryCount
    ): Map<String, Any?> = host.diagnosticsFields(url, retry)

    private fun retryBackoffDelayMs(): Long =
        (1000L shl (retryCount - 1).coerceIn(0, 3)) + (0..400).random()

    fun attemptNetworkRecovery(reason: String) {
        if (Looper.myLooper() != Looper.getMainLooper()) { timeoutHandler.post { attemptNetworkRecovery(reason) }; return }
        if (!isAutoReconnectEnabled()) return
        val url = currentUrl ?: return
        val playerSnapshot = player ?: return
        if (!playerSnapshot.playWhenReady) return
        val now = SystemClock.elapsedRealtime()
        val bufferingMs = if (lastBufferingStartMs > 0L) now - lastBufferingStartMs else 0L
        if (playerSnapshot.playbackState == Player.STATE_IDLE || (playerSnapshot.playbackState == Player.STATE_BUFFERING && bufferingMs >= NETWORK_RECOVERY_BUFFER_MS)) {
            // Consult the unified budget (fix 2) so a flapping network can't chain
            // re-inits past the aggregate ceiling; shows the banner (fix 3) too.
            if (!canAttemptReconnect()) {
                handleTerminalPlaybackFailure("Network unstable")
                return
            }
            if (contentType != ContentType.LIVE_TV && playerSnapshot.currentPosition > 1000L) startPositionMs = playerSnapshot.currentPosition
            playerSnapshot.release()
            player = null
            initializePlayer(url)
        }
    }

    fun registerNetworkCallback() {
        if (networkCallback != null) return
        val manager = connectivityManager ?: return
        networkCallback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: android.net.Network) { networkAvailable = true; attemptNetworkRecovery("available") }
            override fun onLost(network: android.net.Network) { networkAvailable = false }
        }
        manager.registerDefaultNetworkCallback(networkCallback!!)
    }

    fun unregisterNetworkCallback() {
        val callback = networkCallback ?: return
        try {
            connectivityManager?.unregisterNetworkCallback(callback)
        } catch (e: Exception) {
            // Already unregistered (framework throws IllegalArgumentException) — harmless, but logged.
            Log.w("PlayerActivity", "unregisterNetworkCallback failed", e)
        } finally {
            networkCallback = null
        }
    }

    fun handlePlaybackError(error: PlaybackException) {
        isSwitching = false
        if (error.errorCode == PlaybackException.ERROR_CODE_BEHIND_LIVE_WINDOW) {
            player?.seekToDefaultPosition(); player?.prepare(); player?.playWhenReady = true; return
        }
        // ROOT CAUSE (device-verified via logcat 2026-07-18, both .35/.64 Amlogic):
        // the OS refuses to allocate even a plain 48kHz/stereo/16-bit AudioTrack
        // ("AudioTrack init failed 0 Config(48000,12,2)" → UnsupportedOperationException
        // "Cannot create AudioTrack"). The format is fully decoded (FFmpeg → raw PCM,
        // format_supported), so this is NOT a codec/format problem — the audio HAL's
        // output slot is still held by a prior session (tunneled AV-sync AudioTrack from
        // zap/retry churn). The FfmpegAudioRenderer then throws a FATAL renderer error
        // right AFTER the video's first frame renders → the picture freezes on frame 1.
        //
        // Recovery: (1) drop tunneling for the session — it removes the HW-AV-sync
        // AudioTrack requirement that most often wedges the slot; (2) fully release the
        // player so its AudioTrack is freed; (3) wait AUDIO_SINK_COOLOFF_MS (the old
        // 250ms was too soon — the HAL hadn't reclaimed the handle) before re-init.
        // Bounded by AUDIO_SINK_MAX_RECOVERIES so a genuinely dead audio device fails
        // cleanly instead of looping a frozen frame.
        if (isAudioSinkInitFailure(error)) {
            if (audioSinkRecoveryCount < AUDIO_SINK_MAX_RECOVERIES && canAttemptReconnect()) {
                audioSinkRecoveryCount++
                val tunnelingWasOn = !disableTunnelingForSession
                disableTunnelingForSession = true
                Log.w(
                    "PlayerActivity",
                    "AudioTrack init failed — audio-device recovery $audioSinkRecoveryCount/${AUDIO_SINK_MAX_RECOVERIES} (tunnelingWasOn=$tunnelingWasOn), cool-off ${AUDIO_SINK_COOLOFF_MS}ms"
                )
                PlaybackDiagnosticsRecorder.record(
                    activity,
                    "audio_sink_recovery",
                    diagnosticsPlaybackFields() + mapOf(
                        "errorCode" to error.errorCode,
                        "recovery" to audioSinkRecoveryCount,
                        "tunnelingWasOn" to tunnelingWasOn
                    )
                )
                if (contentType != ContentType.LIVE_TV && (player?.currentPosition ?: 0L) > 1000L) {
                    startPositionMs = player!!.currentPosition
                }
                player?.release(); player = null
                retryHandler.postDelayed({ currentUrl?.let { initializePlayer(it) } }, AUDIO_SINK_COOLOFF_MS)
                return
            }
            // The single recovery didn't help → the audio output is saturated (leaked
            // AudioTracks the firmware won't reclaim). Release OUR track so we stop
            // holding a slot, and stop retrying — more re-inits only leak more. Tell the
            // user the one thing that actually clears it.
            player?.release(); player = null
            PlaybackDiagnosticsRecorder.record(
                activity,
                "audio_sink_exhausted",
                diagnosticsPlaybackFields() + mapOf("errorCode" to error.errorCode)
            )
            handleTerminalPlaybackFailure("Audio unavailable — please restart your Fire TV (the device ran out of audio channels)")
            return
        }
        val cause = error.cause
        val errorMessage = when (cause) {
            is HttpDataSource.InvalidResponseCodeException -> "HTTP ${cause.responseCode}"
            else -> error.message ?: "Playback error"
        }
        PlaybackDiagnosticsRecorder.record(
            activity,
            "player_error",
            diagnosticsPlaybackFields() + mapOf(
                "reasonCode" to PlaybackDiagnosticsRecorder.sanitizeReason(errorMessage),
                "httpStatusCode" to PlaybackDiagnosticsRecorder.httpStatusCode(errorMessage),
                "errorCode" to error.errorCode
            )
        )
        // A link that aged out is REPAIRABLE, not terminal — see isRepairableExpiredDebridLink.
        // The retry block below already knows how to mint a fresh one and resume at the saved
        // position, for both debrid modes; it was simply unreachable for these codes, because both
        // terminal branches return before it. That is what made a dead link unrecoverable, and a
        // dead link being unrecoverable is why every resume re-resolves before the first frame.
        val hasDurableDebridIdentity = !debridInfoHashExtra.isNullOrBlank() || !debridMagnetExtra.isNullOrBlank()
        val repairableExpiredLink = isRepairableExpiredDebridLink(
            responseCode = (error.cause as? HttpDataSource.InvalidResponseCodeException)?.responseCode,
            playbackSource = playbackSource,
            hasDurableDebridIdentity = hasDurableDebridIdentity,
            canFreshResolveDirect = canFreshResolveDirectDebrid()
        )

        if (!repairableExpiredLink && isTerminalDirectHttpPlaybackError(error, directDebridPlayback)) {
            if ((error.cause as? HttpDataSource.InvalidResponseCodeException)?.responseCode == 404) {
                recordDirectAddonProxyFailure()
            }
            handleTerminalPlaybackFailure(errorMessage)
            return
        }
        if (hasRepeatedDirectAddonProxyServerError(error)) {
            recordDirectAddonProxyFailure()
            handleTerminalPlaybackFailure(errorMessage, preferReturnToSources = true)
            return
        }
        // Definitive client errors (removed/nonexistent listing, wrong method) never
        // recover by retrying. Plain IPTV/Xtream playback had NO terminal-HTTP path —
        // isTerminalDirectHttpPlaybackError only fires for directDebridPlayback — so a
        // dead listing (e.g. a provider that answers 405/500 for an unavailable movie id)
        // looped the "Reconnecting…" banner until the whole retry budget drained.
        // Fail fast for non-live sources instead. 429 (rate-limit) is excluded — handled
        // below with a cool-off.
        val terminalClientCode =
            (error.cause as? HttpDataSource.InvalidResponseCodeException)?.responseCode
        if (!repairableExpiredLink && isTerminalHttpForNonLive(terminalClientCode, contentType, playbackSource)) {
            handleTerminalPlaybackFailure(errorMessage, preferReturnToSources = true)
            return
        }
        // HTTP 429 for plain IPTV/Xtream (QA fix 4): the provider WAF is rate-limiting
        // this IP. Fast-backoff retries only deepen the ban, so cool off — honor a
        // Retry-After header if the server sent one, else a fixed 20s. The direct-
        // debrid path treats 429 as terminal separately (isTerminalDirectHttpPlaybackError).
        val responseCode = (error.cause as? HttpDataSource.InvalidResponseCodeException)?.responseCode
        if (isRateLimitCoolOff(responseCode, directDebridPlayback)) {
            if (retryCount < maxRetriesFor(contentType, LIVE_MAX_RETRIES, maxRetries) &&
                canAttemptReconnect()
            ) {
                retryCount++
                val coolOffMs = parseRetryAfterMs(error) ?: HTTP_429_COOLOFF_MS
                Log.i("PlayerActivity", "HTTP 429 (rate limited) — cooling off ${coolOffMs}ms before reconnect")
                PlaybackDiagnosticsRecorder.record(
                    activity,
                    "retry_triggered",
                    diagnosticsPlaybackFields(retry = retryCount) + mapOf(
                        "reasonCode" to "HTTP_429",
                        "retrySource" to "rate_limit_cooloff",
                        "coolOffMs" to coolOffMs
                    )
                )
                if (contentType != ContentType.LIVE_TV && player != null && player!!.currentPosition > 1000L) {
                    startPositionMs = player!!.currentPosition
                }
                player?.release(); player = null
                retryHandler.postDelayed({ currentUrl?.let { initializePlayer(it) } }, coolOffMs)
            } else {
                handleTerminalPlaybackFailure(errorMessage)
            }
            return
        }
        if (retryCount < maxRetriesFor(contentType, LIVE_MAX_RETRIES, maxRetries)) {
            // Aggregate budget gate (fix 2): even under the per-source retry cap, refuse
            // to reconnect once the rolling window is spent. Records the attempt + shows
            // the banner when allowed.
            if (!canAttemptReconnect()) {
                handleTerminalPlaybackFailure(errorMessage)
                return
            }
            retryCount++
            PlaybackDiagnosticsRecorder.record(
                activity,
                "retry_triggered",
                diagnosticsPlaybackFields(retry = retryCount) + mapOf(
                    "reasonCode" to PlaybackDiagnosticsRecorder.sanitizeReason(errorMessage),
                    "retrySource" to "player_error"
                )
            )
            if (directDebridPlayback && refreshDirectDebridSourceFromMetadata("player_error_direct_refresh")) {
                return
            }
            if (canUseDebridResolver() && !isResolvingDebrid && hasDurableDebridIdentity) {
                  isResolvingDebrid = true
                  if (player != null && player!!.currentPosition > 1000L) startPositionMs = player!!.currentPosition
                  reResolveDebridUrl(
                      debridInfoHashExtra,
                      debridMagnetExtra,
                      seasonNumberExtra,
                      episodeNumberExtra,
                      episodeTitleExtra,
                      allowDirectHttpPassthrough = false
                  )
                  return
            }
            // No "Retrying…" toast here: canAttemptReconnect() already drives the single
            // unified "RECONNECTING… (n/N)" banner. A second toast with a DIFFERENT counter
            // (retryCount/maxRetries) rendered two overlapping indicators — the bug in the
            // user's photo (top "RECONNECTING… 1/4" + centre "Retrying… 2/5").
            if (contentType != ContentType.LIVE_TV && player != null && player!!.currentPosition > 1000L) startPositionMs = player!!.currentPosition
            PlaybackDiagnosticsRecorder.record(
                activity,
                "release_player",
                diagnosticsPlaybackFields() + mapOf("releaseReason" to "player_error_retry")
            )
            player?.release(); player = null
            retryHandler.postDelayed({ currentUrl?.let { initializePlayer(it) } }, retryBackoffDelayMs())
        } else {
            handleTerminalPlaybackFailure(errorMessage)
        }
    }

    fun handleInitializationError(error: Exception) {
        handleTerminalPlaybackFailure("Init failed: ${error.message}")
    }

    fun handleTimeout() {
        if (player?.playbackState == Player.STATE_BUFFERING) {
            // Progress-aware watchdog (live): if the buffer has GROWN since the
            // watchdog was armed, data is still flowing — the connection is slow,
            // not dead. Tearing the whole player down here (release + 2s wait +
            // full re-init) turns a recovering rebuffer into a much longer visible
            // reconnect on weak channels. Extend the window instead (bounded), and
            // only tear down when the buffer is genuinely not filling.
            if (contentType == ContentType.LIVE_TV && maybeExtendWatchdogWindow()) {
                return
            }
            val timedOutUrl = currentUrl
            PlaybackDiagnosticsRecorder.record(
                activity,
                "buffer_timeout",
                diagnosticsPlaybackFields(timedOutUrl) + mapOf("reasonCode" to "TIMEOUT")
            )
            if (!timedOutUrl.isNullOrBlank() &&
                requiresAddonProxyPlaybackContext(timedOutUrl) &&
                !hasRenderedFirstFrameForCurrentSource
            ) {
                recordDirectAddonProxyFailure()
                finishWithReturnToSources(autoPlayNext = true, reason = "Connection timeout")
                return
            }
            if (!timedOutUrl.isNullOrBlank() && hasRepeatedDirectAddonProxyTimeout(timedOutUrl)) {
                recordDirectAddonProxyFailure()
                handleTerminalPlaybackFailure("Connection timeout")
                return
            }
            if (retryCount < (if (contentType == ContentType.LIVE_TV) LIVE_MAX_RETRIES else maxRetries)) {
                retryAfterBufferTimeout(timedOutUrl)
            } else {
                handleTerminalPlaybackFailure("Connection timeout")
            }
        }
    }

    // The buffer has genuinely stalled past the timeout window: one retry step, verbatim from
    // handleTimeout. Every early return here ends the timeout handling exactly as it did inline.
    private fun retryAfterBufferTimeout(timedOutUrl: String?) {
        // Aggregate budget gate (fix 2): a stuck first-connect timeout is part
        // of the same reconnect family; refuse once the window is spent.
        if (!canAttemptReconnect()) {
            handleTerminalPlaybackFailure("Connection timeout")
            return
        }
        retryCount++
        PlaybackDiagnosticsRecorder.record(
            activity,
            "retry_triggered",
            diagnosticsPlaybackFields(timedOutUrl, retry = retryCount) + mapOf(
                "reasonCode" to "TIMEOUT",
                "retrySource" to "buffer_timeout"
            )
        )
        if (directDebridPlayback && refreshDirectDebridSourceFromMetadata("buffer_timeout_direct_refresh")) {
            return
        }
        if (maybeReResolveDebridAfterTimeout()) return
        // No toast here either — the unified "RECONNECTING…" banner (shown by
        // canAttemptReconnect() above) is the single reconnect indicator.
        // Live first retry: light in-place re-prepare on the SAME engine
        // (the proven ENDED/freeze recovery path) instead of the heavy
        // release → 2s wait → full re-init. The player instance is healthy —
        // only the source starved — so this cuts each visible reconnect by
        // ~4-5s and avoids the audio-focus abandon/request churn. The full
        // engine rebuild is kept as the SECOND retry for wedged pipelines.
        if (contentType == ContentType.LIVE_TV && retryCount < LIVE_MAX_RETRIES && player != null) {
            Log.i("PlayerActivity", "Buffer timeout — in-place re-prepare (retry $retryCount)")
            currentUrl?.let { performSeamlessSwitch(it) }
            return
        }
        rebuildPlayerAfterTimeout(timedOutUrl)
    }

    // Second-retry escalation, verbatim from retryAfterBufferTimeout: mint a fresh debrid link
    // instead of re-connecting the stale one. True when the re-resolve was started (timeout
    // handling is done); false leaves the caller on the reconnect path.
    private fun maybeReResolveDebridAfterTimeout(): Boolean {
        val hasResolveInfo =
            !debridInfoHashExtra.isNullOrBlank() || !debridMagnetExtra.isNullOrBlank()
        val resolverIdle = canUseDebridResolver() && !isResolvingDebrid
        if (!resolverIdle || retryCount <= 1 || !hasResolveInfo) return false
        isResolvingDebrid = true
        if (player != null && player!!.currentPosition > 1000L) startPositionMs = player!!.currentPosition
        reResolveDebridUrl(debridInfoHashExtra, debridMagnetExtra, seasonNumberExtra, episodeNumberExtra, episodeTitleExtra)
        return true
    }

    // The heavy full-engine rebuild, verbatim: save the resume position, release, re-init after 2s.
    private fun rebuildPlayerAfterTimeout(timedOutUrl: String?) {
        if (contentType != ContentType.LIVE_TV && player != null && player!!.currentPosition > 1000L) startPositionMs = player!!.currentPosition
        PlaybackDiagnosticsRecorder.record(
            activity,
            "release_player",
            diagnosticsPlaybackFields(timedOutUrl) + mapOf("releaseReason" to "buffer_timeout_retry")
        )
        player?.release(); player = null
        retryHandler.postDelayed({ currentUrl?.let { initializePlayer(it) } }, 2000)
    }

    // Progress-aware watchdog extension (live): true when the buffer grew since arming and an
    // extension window was granted — the caller must return without tearing the player down.
    private fun maybeExtendWatchdogWindow(): Boolean {
        val buffered = player?.bufferedPosition ?: 0L
        if (buffered > watchdogBufferedPosAtArm + 500L &&
            watchdogExtensions < MAX_WATCHDOG_EXTENSIONS
        ) {
            watchdogExtensions++
            watchdogBufferedPosAtArm = buffered
            Log.i(
                "PlayerActivity",
                "Buffering slow but progressing (buffered=${buffered}ms) — extending watchdog $watchdogExtensions/${MAX_WATCHDOG_EXTENSIONS}"
            )
            timeoutHandler.postDelayed(timeoutRunnable, timeoutMs)
            return true
        }
        return false
    }

    private companion object {
        // Dead/looping provider restreams reconnect forever without this cap:
        // the server replays the same GOP on every connection, so retry #3+
        // can never succeed. Fail fast with a clear message instead.
        const val LIVE_MAX_RETRIES = 2
        // Progress-aware watchdog: extra 12s windows granted while the buffer is
        // still growing (slow-but-alive connection), before tearing the player down.
        const val MAX_WATCHDOG_EXTENSIONS = 2
        // AudioTrack-allocation recovery (device-verified 2026-07-18, dumpsys
        // media.audio_flinger). Two distinct failures share the "AudioTrack init failed /
        // Cannot create AudioTrack" symptom:
        //   (a) tunneled HW-AV-sync slot briefly held by a prior session — ONE re-init
        //       without tunneling recovers it (the rare, genuinely transient case);
        //   (b) AudioFlinger output SATURATED ("no more tracks available", errno -12) —
        //       ~80 leaked tracks accumulated across app restarts because this Amlogic
        //       firmware never reclaims released/dead-process AudioTracks. NOTHING an
        //       in-app re-init can do here; each attempt only LEAKS ~5 more tracks (media3
        //       retries the AudioTrack build 5× per init). Needs a device reboot.
        // So: exactly ONE recovery attempt (covers case a), then fail fast with an
        // actionable message (case b) instead of a leak-amplifying retry spiral.
        const val AUDIO_SINK_MAX_RECOVERIES = 1
        const val AUDIO_SINK_COOLOFF_MS = 900L
        const val NETWORK_RECOVERY_BUFFER_MS = 5000L
        // Fixed cool-off for HTTP 429 when the server sends no Retry-After (QA fix 4).
        // The media3 playbackLoadErrorPolicy (in BasePlayerFragment) applies the same
        // value at the load-error layer; this is the app-level reconnect cool-off.
        const val HTTP_429_COOLOFF_MS = 20_000L
    }
}
