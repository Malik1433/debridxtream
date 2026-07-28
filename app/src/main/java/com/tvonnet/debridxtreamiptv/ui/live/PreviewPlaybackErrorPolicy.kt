package com.tvonnet.debridxtreamiptv.ui.live

import androidx.media3.common.PlaybackException
import com.tvonnet.debridxtreamiptv.player.stabilized.isAudioSinkInitFailure

/**
 * What the Live preview should do when its player fails.
 *
 * Until this existed the preview did nothing at all: no listener, no message, no retry — a failed
 * player stayed attached showing a frozen first frame, and kept its AudioTrack. That last part is
 * what made it more than cosmetic. AudioFlinger allows 64 tracks per output thread and once they are
 * gone nothing on the device can start audio until it is restarted, so a preview holding a slot it
 * has already failed on is spending one for nothing.
 *
 * Split out from [PreviewPlayerPanel] so the decision can be tested without inflating a view: the
 * panel is constructed from a live layout and its findViewById fields, which makes the branch that
 * matters here unreachable from a unit test.
 */
internal object PreviewPlaybackErrorPolicy {

    enum class Action {
        /** Re-prepare the same item — ExoPlayer's own recovery for a transient failure. */
        RETRY,

        /** Release, and tell the user to restart the device: nothing in the app can free the pool. */
        RELEASE_AUDIO_EXHAUSTED,

        /** Retries are spent. Release rather than sit on a slot showing a frozen frame. */
        RELEASE_FAILED,
    }

    /**
     * @param retriesSoFar how many retries this channel has already used.
     * @param maxRetries the ceiling; bounded on purpose, because a preview that retries forever
     *   holds its slot forever — the exact behaviour this is here to remove.
     */
    fun decide(error: PlaybackException, retriesSoFar: Int, maxRetries: Int): Action = when {
        // Never retry this one. The pool is empty, so another attempt allocates against nothing and
        // the only outcome is another burned slot — the fullscreen player learned this first, see
        // PlayerRecoveryController.
        isAudioSinkInitFailure(error) -> Action.RELEASE_AUDIO_EXHAUSTED
        retriesSoFar < maxRetries -> Action.RETRY
        else -> Action.RELEASE_FAILED
    }
}
