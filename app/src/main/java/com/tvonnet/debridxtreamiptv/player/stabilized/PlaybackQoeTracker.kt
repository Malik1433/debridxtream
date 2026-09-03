package com.tvonnet.debridxtreamiptv.player.stabilized

import android.content.Context
import android.os.Bundle
import android.os.SystemClock
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.analytics.AnalyticsListener
import com.google.firebase.analytics.FirebaseAnalytics
import com.google.firebase.crashlytics.FirebaseCrashlytics
import com.tvonnet.debridxtreamiptv.util.DiagnosticsConsent

/**
 * LP-D-2: QoE / playback analytics. One instance per ExoPlayer build, attached via
 * `player.addAnalyticsListener(...)`. Emits the three signals a TiviMate-level bar
 * needs and that were previously invisible:
 *
 *  - `playback_ttff`   — time-to-first-frame per prepare (zap latency for live)
 *  - `playback_error`  — media3 error-code name (also recorded to Crashlytics as a
 *                        non-fatal so device/OS-specific regressions show remotely)
 *  - `playback_session`— summary on release: watch seconds, rebuffer count,
 *                        dropped frames
 *
 * No URLs, credentials, titles, or channel names are ever attached — only mode
 * (live/vod/debrid), error codes, and numeric QoE values.
 */
@UnstableApi
class PlaybackQoeTracker(
    context: Context,
    /** G3: nothing leaves the device unless the Settings switch says so - asked on every emit. */
    private val consent: () -> Boolean = { DiagnosticsConsent.isEnabled(context) },
    private val modeProvider: () -> String,
) : AnalyticsListener {

    private val analytics: FirebaseAnalytics = FirebaseAnalytics.getInstance(context.applicationContext)
    private val crashlytics: FirebaseCrashlytics = FirebaseCrashlytics.getInstance()

    private var prepareStartedAtMs = 0L
    private var ttffReported = false
    private var reachedReady = false
    private var lastSeekAtMs = 0L
    private var rebufferCount = 0
    private var droppedFrames = 0
    private var sessionStartedAtMs = SystemClock.elapsedRealtime()
    private var summaryFlushed = false
    private var errorCount = 0
    private var firstFrameSeen = false
    private var firstTtffMs: Long? = null

    /** Call right before prepare()/seamless-switch so TTFF measures this source. */
    fun markPrepareStart() {
        prepareStartedAtMs = SystemClock.elapsedRealtime()
        ttffReported = false
        reachedReady = false
    }

    override fun onRenderedFirstFrame(
        eventTime: AnalyticsListener.EventTime,
        output: Any,
        renderTimeMs: Long
    ) {
        if (ttffReported || prepareStartedAtMs == 0L) return
        ttffReported = true
        val ttffMs = SystemClock.elapsedRealtime() - prepareStartedAtMs
        firstFrameSeen = true
        if (firstTtffMs == null) firstTtffMs = ttffMs
        if (!consent()) return
        analytics.logEvent("playback_ttff", Bundle().apply {
            putLong("ttff_ms", ttffMs)
            putString("mode", modeProvider())
        })
        crashlytics.log("qoe ttff=${ttffMs}ms mode=${modeProvider()}")
    }

    override fun onPlaybackStateChanged(eventTime: AnalyticsListener.EventTime, state: Int) {
        when (state) {
            Player.STATE_READY -> reachedReady = true
            Player.STATE_BUFFERING -> {
                // Rebuffer = buffering AFTER first READY that is not seek-induced.
                if (reachedReady && SystemClock.elapsedRealtime() - lastSeekAtMs > 1000L) {
                    rebufferCount++
                }
            }
        }
    }

    override fun onPositionDiscontinuity(
        eventTime: AnalyticsListener.EventTime,
        oldPosition: Player.PositionInfo,
        newPosition: Player.PositionInfo,
        reason: Int
    ) {
        if (reason == Player.DISCONTINUITY_REASON_SEEK) lastSeekAtMs = SystemClock.elapsedRealtime()
    }

    override fun onDroppedVideoFrames(eventTime: AnalyticsListener.EventTime, droppedFrames: Int, elapsedMs: Long) {
        this.droppedFrames += droppedFrames
    }

    override fun onPlayerError(eventTime: AnalyticsListener.EventTime, error: PlaybackException) {
        errorCount++
        if (!consent()) return
        analytics.logEvent("playback_error", Bundle().apply {
            putString("error_code", error.errorCodeName)
            putString("mode", modeProvider())
        })
        // Non-fatal to Crashlytics: exposes device/OS-specific decoder & network
        // failure clusters remotely. PlaybackException messages from media3 can
        // embed the stream URL — record a sanitized copy, never the original.
        crashlytics.recordException(
            PlaybackQoeException("playback_error ${error.errorCodeName} mode=${modeProvider()}")
        )
    }

    /** Call from releasePlayer — idempotent; emits the per-session QoE summary. */
    fun flushSessionSummary() {
        if (summaryFlushed) return
        summaryFlushed = true
        if (!consent()) return
        val sessionSec = (SystemClock.elapsedRealtime() - sessionStartedAtMs) / 1000L
        analytics.logEvent("playback_session", Bundle().apply {
            putLong("session_sec", sessionSec)
            putInt("rebuffer_count", rebufferCount)
            putInt("dropped_frames", droppedFrames)
            putString("mode", modeProvider())
        })
        // G2: the summary rides along with the NEXT crash report as custom keys; only an
        // unhealthy session files a non-fatal of its own (see SessionSummaryPolicy).
        val summary = SessionSummaryPolicy.Summary(
            mode = modeProvider(), sessionSec = sessionSec, rebufferCount = rebufferCount,
            droppedFrames = droppedFrames, errorCount = errorCount, firstFrameSeen = firstFrameSeen, ttffMs = firstTtffMs
        )
        SessionSummaryPolicy.keys(summary).forEach { (k, v) ->
            when (v) {
                is Boolean -> crashlytics.setCustomKey(k, v)
                is Int -> crashlytics.setCustomKey(k, v)
                is Long -> crashlytics.setCustomKey(k, v)
                else -> crashlytics.setCustomKey(k, v.toString())
            }
        }
        if (SessionSummaryPolicy.isUnhealthy(summary)) {
            crashlytics.recordException(PlaybackSessionSummary(SessionSummaryPolicy.message(summary)))
        }
    }

    /** Named so Crashlytics groups unhealthy-session reports apart from playback errors. */
    private class PlaybackSessionSummary(message: String) : Exception(message)

    /** Named exception type so Crashlytics groups playback non-fatals together. */
    private class PlaybackQoeException(message: String) : Exception(message)
}
