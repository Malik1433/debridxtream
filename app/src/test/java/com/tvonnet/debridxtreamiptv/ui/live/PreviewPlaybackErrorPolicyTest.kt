package com.tvonnet.debridxtreamiptv.ui.live

import androidx.media3.common.Format
import androidx.media3.common.PlaybackException
import androidx.media3.exoplayer.audio.AudioSink
import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.IOException

/**
 * What the Live preview does when its player fails.
 *
 * Before this policy existed the preview did nothing at all — no listener, no message, no retry — so
 * a failed player sat there showing a frozen first frame while still holding its AudioTrack. With
 * only 64 track slots on the device and no way for the app to reclaim them, a preview that keeps one
 * after failing is the difference between a hiccup and a TV that has to be restarted.
 *
 * The two rules that matter are opposites, which is why they are pinned separately: an ordinary
 * failure should be retried, and an exhausted audio pool must NOT be.
 */
class PreviewPlaybackErrorPolicyTest {

    private val maxRetries = 2

    private fun audioSinkException() = AudioSink.InitializationException(
        /* audioTrackState = */ 0,
        /* sampleRate = */ 48_000,
        /* channelConfig = */ 12,
        /* bufferSize = */ 4096,
        /* format = */ Format.Builder().build(),
        /* isRecoverable = */ false,
        /* audioTrackException = */ IllegalStateException("Cannot create AudioTrack")
    )

    private fun audioSinkFailure() = PlaybackException(
        "audio track init failed",
        audioSinkException(),
        PlaybackException.ERROR_CODE_AUDIO_TRACK_INIT_FAILED
    )

    private fun networkFailure() = PlaybackException(
        "connection lost",
        IOException("boom"),
        PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED
    )

    @Test
    fun `an exhausted audio pool is never retried, at any point in the retry budget`() {
        // The pool is empty, so another attempt allocates against nothing and the only outcome is
        // one more burned slot. Checked with the budget untouched AND spent: neither makes it valid.
        assertEquals(
            PreviewPlaybackErrorPolicy.Action.RELEASE_AUDIO_EXHAUSTED,
            PreviewPlaybackErrorPolicy.decide(audioSinkFailure(), retriesSoFar = 0, maxRetries = maxRetries)
        )
        assertEquals(
            PreviewPlaybackErrorPolicy.Action.RELEASE_AUDIO_EXHAUSTED,
            PreviewPlaybackErrorPolicy.decide(audioSinkFailure(), retriesSoFar = maxRetries, maxRetries = maxRetries)
        )
    }

    @Test
    fun `an ordinary failure is retried while there is budget left`() {
        assertEquals(
            PreviewPlaybackErrorPolicy.Action.RETRY,
            PreviewPlaybackErrorPolicy.decide(networkFailure(), retriesSoFar = 0, maxRetries = maxRetries)
        )
        assertEquals(
            PreviewPlaybackErrorPolicy.Action.RETRY,
            PreviewPlaybackErrorPolicy.decide(networkFailure(), retriesSoFar = maxRetries - 1, maxRetries = maxRetries)
        )
    }

    @Test
    fun `once the retries are spent the player is released rather than left frozen`() {
        // The old behaviour was to leave it attached forever. Releasing is the whole point: a dead
        // preview must not keep an audio slot.
        assertEquals(
            PreviewPlaybackErrorPolicy.Action.RELEASE_FAILED,
            PreviewPlaybackErrorPolicy.decide(networkFailure(), retriesSoFar = maxRetries, maxRetries = maxRetries)
        )
    }

    @Test
    fun `a budget of zero means the first failure releases immediately`() {
        assertEquals(
            PreviewPlaybackErrorPolicy.Action.RELEASE_FAILED,
            PreviewPlaybackErrorPolicy.decide(networkFailure(), retriesSoFar = 0, maxRetries = 0)
        )
    }

    @Test
    fun `an audio failure is recognised through a wrapped cause, not just the error code`() {
        // Media3 does not always surface ERROR_CODE_AUDIO_TRACK_INIT_FAILED at the top level — the
        // renderer can wrap it. Matching on the code alone would send this down the retry path and
        // burn slots on a pool that has none left.
        val wrapped = PlaybackException(
            "renderer error",
            RuntimeException("wrapper", audioSinkException()),
            PlaybackException.ERROR_CODE_UNSPECIFIED
        )
        assertEquals(
            PreviewPlaybackErrorPolicy.Action.RELEASE_AUDIO_EXHAUSTED,
            PreviewPlaybackErrorPolicy.decide(wrapped, retriesSoFar = 0, maxRetries = maxRetries)
        )
    }
}
