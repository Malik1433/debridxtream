package com.tvonnet.debridxtreamiptv.player.stabilized

/**
 * Stall / video-freeze DETECTION (Phase P15 — the scope §8 had blocked, reopened by
 * the owner on 2026-07-22).
 *
 * Only the detection state machines live here: position-progress strikes and
 * rendered-frame progress. Every REACTION stays in [PlayerActivity] — diagnostics,
 * handlePlaybackError, the live re-prepare and the bounded tunneling-off recovery are
 * landmine territory and were not touched.
 */

internal enum class StallVerdict {
    /** Position advanced since the last tick. */
    PROGRESSING,

    /** Nothing to say: not playing, or the threshold has not elapsed yet. */
    IDLE,

    /** Position is stuck, but the strike count has not reached the limit yet. */
    WARNING,

    /** Position is stuck and the strikes ran out — the caller should recover. */
    STALLED
}

/**
 * Tracks whether the playback position keeps advancing. A stuck position only counts
 * once [thresholdMs] has elapsed, and only [requiredStrikes] consecutive stuck windows
 * escalate to [StallVerdict.STALLED] — one slow window is not a stall.
 */
internal class PlayerStallDetector {

    private var lastBoundPosition = 0L
    private var lastProgressCheckMs = 0L

    /** Consecutive stuck windows; reset by progress, by a stall, and by re-arming. */
    var strikeCount = 0
        private set

    /** Re-arm on start / after a successful recovery. */
    fun reset(positionMs: Long, nowMs: Long) {
        lastBoundPosition = positionMs
        lastProgressCheckMs = nowMs
        strikeCount = 0
    }

    fun onTick(
        isActivelyPlaying: Boolean,
        positionMs: Long,
        nowMs: Long,
        thresholdMs: Long,
        requiredStrikes: Int
    ): StallVerdict {
        if (!isActivelyPlaying) {
            reset(positionMs, nowMs)
            return StallVerdict.IDLE
        }
        if (positionMs > lastBoundPosition) {
            reset(positionMs, nowMs)
            return StallVerdict.PROGRESSING
        }
        // Position 0 means playback never started — that is a load problem, not a stall.
        if (positionMs > 0 && nowMs - lastProgressCheckMs >= thresholdMs) {
            strikeCount++
            lastProgressCheckMs = nowMs
            if (strikeCount >= requiredStrikes) {
                strikeCount = 0
                return StallVerdict.STALLED
            }
            return StallVerdict.WARNING
        }
        return StallVerdict.IDLE
    }
}

/**
 * "Audio plays, video frozen": the position advances but the decoder stops producing
 * rendered frames (MTK Fire TV HEVC live channels — GuiExt alloc errors leave the
 * tunneled decoder outputting nothing).
 */
internal class VideoFreezeDetector {

    private var lastRenderedFrameCount = -1
    private var lastProgressMs = 0L

    /** No video format yet — forget the frame count so the next one is a fresh baseline. */
    fun onNoVideoFormat() {
        lastRenderedFrameCount = -1
    }

    /**
     * @return true when no new frame has been rendered for [freezeThresholdMs].
     * Returning true also re-arms, so the caller gets one verdict per freeze window.
     */
    fun onTick(renderedFrames: Int, nowMs: Long, freezeThresholdMs: Long): Boolean {
        if (renderedFrames != lastRenderedFrameCount) {
            lastRenderedFrameCount = renderedFrames
            lastProgressMs = nowMs
            return false
        }
        if (lastProgressMs == 0L) {
            lastProgressMs = nowMs
            return false
        }
        if (nowMs - lastProgressMs < freezeThresholdMs) return false

        lastProgressMs = nowMs
        lastRenderedFrameCount = -1
        return true
    }

    fun reset() {
        lastRenderedFrameCount = -1
        lastProgressMs = 0L
    }
}
