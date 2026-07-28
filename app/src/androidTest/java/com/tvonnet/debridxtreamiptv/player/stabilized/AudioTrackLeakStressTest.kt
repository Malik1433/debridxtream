package com.tvonnet.debridxtreamiptv.player.stabilized

import android.content.Context
import android.util.Log
import androidx.media3.common.MediaItem
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.analytics.AnalyticsListener
import androidx.media3.exoplayer.audio.AudioSink
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.atomic.AtomicInteger

/**
 * The "first frame freezes after three or four days" incident, measured instead of waited for.
 *
 * **What happens.** Eventually the picture stops on frame 1 — Live, resume, fresh content alike —
 * and only a device restart clears it. Measured on the Fire TV Cube while it was happening:
 * AudioFlinger's output thread held `64 Tracks of which 63 are active`, all TERMINATED. 64 is the
 * hard per-thread limit. With no slot free the audio renderer cannot allocate an AudioTrack, it
 * throws immediately after the video's first frame renders, and the picture stops there. Fifteen of
 * the dead tracks belonged to this app's process.
 *
 * **Why this file exists.** An accumulation that takes days cannot be debugged by reproducing it,
 * and must not be shipped on the hope that it is gone. So the invariant is asserted directly:
 * **a channel change must give back the AudioTrack of the channel it left.** The counts come from
 * ExoPlayer's own [AnalyticsListener.onAudioTrackInitialized] / [AnalyticsListener.onAudioTrackReleased]
 * — not from sampling `dumpsys`, which cannot attribute a track to the code path that made it.
 *
 * **What it deliberately does NOT assert.** Release accounting for a player being *torn down* is not
 * observable from inside the app: `ExoPlayer.release()` shuts the analytics collector down before the
 * sink's asynchronous AudioTrack release can be dispatched, so the callback never arrives even though
 * the track is freed. Measured, not assumed — twenty build/play/release cycles report zero releases
 * while `dumpsys media.audio_flinger` shows the process holding exactly one track throughout. Any
 * assertion on that path would fail for a reason that has nothing to do with the app, so teardown is
 * covered by [LiveSharedPlayerHandoffTest] (via `onPlayerReleased`) and by reading AudioFlinger from
 * the host, not here.
 *
 * The media is a silent WAV generated into the cache at run time: no network, no provider account, no
 * binary in the repo, and it still drives the real `DefaultAudioSink` → `AudioTrack` path.
 */
@UnstableApi
@RunWith(AndroidJUnit4::class)
class AudioTrackLeakStressTest {

    private val context: Context get() = ApplicationProvider.getApplicationContext()
    private lateinit var mediaFile: File
    private val players = mutableListOf<ExoPlayer>()

    @Before
    fun setUp() {
        mediaFile = File(context.cacheDir, "audio_track_stress.wav")
        writeSilentWav(mediaFile)
    }

    @After
    fun tearDown() {
        players.forEach { player -> onMain { runCatching { player.release() } } }
        players.clear()
        mediaFile.delete()
    }

    /**
     * The one that would have caught the incident.
     *
     * This mirrors `PlayerLiveTuner.performSeamlessSwitch` exactly — stop, clear, set, prepare — on
     * ONE player, because that is what changing channel does. Each pass makes the sink build a fresh
     * AudioTrack, so each pass must also hand the previous one back. A heavy zapper does hundreds of
     * these a day; at one leaked slot per zap the 64-slot pool is gone in an afternoon.
     *
     * The tolerance of one is the track currently playing.
     */
    @Test
    fun everyZapReleasesThePreviousAudioTrack() {
        val ledger = AudioTrackLedger()
        val player = newPlayer(ledger)

        playUntilAudioStarts(player, ledger, expectedInitialized = 1)
        repeat(ZAP_CYCLES) { index ->
            onMain {
                player.stop()
                player.clearMediaItems()
                player.setMediaItem(MediaItem.fromUri(mediaFile.toURI().toString()))
                player.prepare()
                player.playWhenReady = true
            }
            playUntilAudioStarts(player, ledger, expectedInitialized = index + 2)
        }

        ledger.awaitSettled(expected = ledger.initialized.get() - 1)
        assertEquals(
            "each zap must release the AudioTrack of the channel it left — of " +
                "${ledger.initialized.get()} created, ${ledger.leaked()} were still held",
            1,
            ledger.leaked()
        )
    }

    /**
     * Does dropping the "Atomic Reset" make a zap cheaper?
     *
     * `performSeamlessSwitch` opens with `stop()` + `clearMediaItems()`, and a stop resets the audio
     * sink, so every channel builds a brand-new AudioTrack. The obvious idea is to drop the stop and
     * let `DefaultAudioSink` keep the track when the audio format has not changed.
     *
     * **Measured on the Fire TV Cube: it does not help — 21 tracks either way over 20 zaps**, because
     * `setMediaItem` + `prepare()` resets the sink on its own. Pinned here so the idea is not tried
     * again as a fix, and so the day it *does* start helping, this test says so.
     */
    @Test
    fun droppingTheAtomicResetDoesNotReduceAudioTrackChurn() {
        val withReset = countAudioTracksOverZaps(atomicReset = true)
        val withoutReset = countAudioTracksOverZaps(atomicReset = false)
        Log.i(TAG, "AudioTracks over $ZAP_CYCLES zaps — with atomic reset: $withReset, without: $withoutReset")

        assertTrue(
            "dropping stop() must never cost MORE AudioTracks — " +
                "with reset: $withReset, without: $withoutReset",
            withoutReset <= withReset
        )
    }

    /**
     * The other candidate fix: keep both channels in the playlist and advance, instead of
     * re-preparing. Media3 only rebuilds the AudioTrack when the audio format changes, so an advance
     * between two same-format sources ought to cost nothing.
     *
     * **Measured: it does not help either — 21 tracks either way.** One AudioTrack per source
     * transition is simply how the sink behaves here, which also means this app's churn is ordinary
     * and not what sets it apart from other players. Pinned for the same reason as above.
     */
    @Test
    fun advancingThePlaylistDoesNotReduceAudioTrackChurn() {
        val ledger = AudioTrackLedger()
        val player = newPlayer(ledger)
        val uri = mediaFile.toURI().toString()

        onMain {
            repeat(ZAP_CYCLES + 1) { player.addMediaItem(MediaItem.fromUri(uri)) }
            player.prepare()
            player.playWhenReady = true
        }
        playUntilAudioStarts(player, ledger, expectedInitialized = 1)

        repeat(ZAP_CYCLES) {
            onMain { player.seekToNextMediaItem() }
            Thread.sleep(ZAP_SETTLE_MS)
        }
        val advancing = ledger.initialized.get()
        onMain { player.release() }
        players.remove(player)

        val rePreparing = countAudioTracksOverZaps(atomicReset = true)
        Log.i(TAG, "AudioTracks over $ZAP_CYCLES zaps — re-preparing: $rePreparing, advancing: $advancing")

        assertTrue(
            "advancing the playlist must never cost more than re-preparing — " +
                "re-preparing: $rePreparing, advancing: $advancing",
            advancing <= rePreparing
        )
    }

    // ------------------------------------------------------------------ helpers

    /** @return how many AudioTracks [ZAP_CYCLES] channel changes cost on one player. */
    private fun countAudioTracksOverZaps(atomicReset: Boolean): Int {
        val ledger = AudioTrackLedger()
        val player = newPlayer(ledger)
        playUntilAudioStarts(player, ledger, expectedInitialized = 1)

        repeat(ZAP_CYCLES) {
            onMain {
                if (atomicReset) {
                    player.stop()
                    player.clearMediaItems()
                }
                player.setMediaItem(MediaItem.fromUri(mediaFile.toURI().toString()))
                player.prepare()
                player.playWhenReady = true
            }
            Thread.sleep(ZAP_SETTLE_MS)
        }

        val created = ledger.initialized.get()
        onMain { player.release() }
        players.remove(player)
        return created
    }

    private fun newPlayer(ledger: AudioTrackLedger): ExoPlayer {
        val player = requireNotNull(
            onMainGet { ExoPlayer.Builder(context).build().also { it.addAnalyticsListener(ledger) } }
        )
        players += player
        return player
    }

    /** Drive playback until the sink has actually built the AudioTrack we are counting. */
    private fun playUntilAudioStarts(player: ExoPlayer, ledger: AudioTrackLedger, expectedInitialized: Int) {
        onMain {
            if (player.mediaItemCount == 0) {
                player.setMediaItem(MediaItem.fromUri(mediaFile.toURI().toString()))
                player.prepare()
            }
            player.playWhenReady = true
        }
        assertTrue(
            "the audio sink never opened an AudioTrack — the test cannot measure anything",
            ledger.awaitInitialized(expectedInitialized, PLAYBACK_TIMEOUT_MS)
        )
    }

    private fun onMain(block: () -> Unit) =
        InstrumentationRegistry.getInstrumentation().runOnMainSync(block)

    private fun <T> onMainGet(block: () -> T): T? {
        var result: T? = null
        onMain { result = block() }
        return result
    }

    /**
     * ExoPlayer's own AudioTrack bookkeeping.
     *
     * Releases are asynchronous — `DefaultAudioSink` hands the old track to a background executor —
     * so assertions wait for the count to settle rather than reading it straight after the call that
     * should have triggered it.
     */
    private class AudioTrackLedger : AnalyticsListener {
        val initialized = AtomicInteger()
        val released = AtomicInteger()

        override fun onAudioTrackInitialized(
            eventTime: AnalyticsListener.EventTime,
            audioTrackConfig: AudioSink.AudioTrackConfig,
        ) {
            initialized.incrementAndGet()
        }

        override fun onAudioTrackReleased(
            eventTime: AnalyticsListener.EventTime,
            audioTrackConfig: AudioSink.AudioTrackConfig,
        ) {
            released.incrementAndGet()
        }

        fun leaked(): Int = initialized.get() - released.get()

        fun awaitInitialized(count: Int, timeoutMs: Long): Boolean =
            awaitCondition(timeoutMs) { initialized.get() >= count }

        /**
         * Wait for [expected] releases, then pause once more: a leak assertion has to be sure a late
         * release did NOT arrive, and there is no signal for "nothing else is coming".
         */
        fun awaitSettled(expected: Int, timeoutMs: Long = SETTLE_TIMEOUT_MS) {
            awaitCondition(timeoutMs) { released.get() >= expected }
            Thread.sleep(STRAGGLER_GRACE_MS)
        }

        private fun awaitCondition(timeoutMs: Long, condition: () -> Boolean): Boolean {
            val deadline = System.currentTimeMillis() + timeoutMs
            while (System.currentTimeMillis() < deadline) {
                if (condition()) return true
                Thread.sleep(POLL_INTERVAL_MS)
            }
            return condition()
        }
    }

    /** 48kHz stereo 16-bit silence — the format the incident logs show the sink asking for. */
    private fun writeSilentWav(
        file: File,
        seconds: Int = MEDIA_SECONDS,
        sampleRate: Int = 48_000,
        channels: Int = 2,
    ) {
        val bitsPerSample = 16
        val byteRate = sampleRate * channels * bitsPerSample / 8
        val dataSize = byteRate * seconds
        val header = ByteBuffer.allocate(WAV_HEADER_BYTES).order(ByteOrder.LITTLE_ENDIAN).apply {
            put("RIFF".toByteArray())
            putInt(WAV_HEADER_BYTES - 8 + dataSize)
            put("WAVE".toByteArray())
            put("fmt ".toByteArray())
            putInt(16)                                       // PCM chunk size
            putShort(1)                                      // PCM
            putShort(channels.toShort())
            putInt(sampleRate)
            putInt(byteRate)
            putShort((channels * bitsPerSample / 8).toShort())
            putShort(bitsPerSample.toShort())
            put("data".toByteArray())
            putInt(dataSize)
        }
        file.outputStream().use { out ->
            out.write(header.array())
            out.write(ByteArray(dataSize))
        }
    }

    private companion object {
        const val TAG = "AudioTrackStress"
        const val ZAP_CYCLES = 20
        const val MEDIA_SECONDS = 2
        const val WAV_HEADER_BYTES = 44

        const val PLAYBACK_TIMEOUT_MS = 10_000L
        /** Long enough for a zap to actually reach the sink before the next one starts. */
        const val ZAP_SETTLE_MS = 400L
        const val SETTLE_TIMEOUT_MS = 5_000L
        /** A release that arrives after the count is already satisfied still has to be counted. */
        const val STRAGGLER_GRACE_MS = 750L
        const val POLL_INTERVAL_MS = 25L
    }
}
