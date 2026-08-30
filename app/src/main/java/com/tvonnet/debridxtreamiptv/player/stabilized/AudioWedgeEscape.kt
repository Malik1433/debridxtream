package com.tvonnet.debridxtreamiptv.player.stabilized

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.os.SystemClock
import android.util.Log
import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.audio.BaseAudioProcessor
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.audio.AudioSink
import androidx.media3.exoplayer.audio.DefaultAudioSink
import java.nio.ByteBuffer

/**
 * Escape hatch for the wedged-HDMI-audio first-frame freeze (Fire TV Cube, Amlogic).
 *
 * Device-verified root cause (2026-08-27, live capture on .64 while frozen): the HAL's
 * PRIMARY stereo mixer thread (`AudioOut_1D`) goes into `write()` and never returns
 * ("Blocked in write: yes", last write >1h old). Every stereo PCM AudioTrack routes to
 * that thread, so its buffer fills once and never drains — the playback clock stops on
 * the first frame with NO exception raised. Meanwhile the SAME dump showed our own app
 * playing 5.1 PCM perfectly through a freshly-created DIRECT output
 * (`AudioOut_B5`, AUDIO_OUTPUT_FLAG_DIRECT|DEEP_BUFFER) on the same HDMI device:
 * the wedge is per-thread, NOT device-wide. That is why "some channels play"
 * (multichannel/passthrough audio) while stereo channels freeze, and why other player
 * apps appear immune.
 *
 * The escape: once a wedge is detected, upmix 1ch/2ch PCM to 6-channel (5.1) PCM.
 * The audio policy cannot put multichannel PCM on the stereo primary mixer, so it
 * opens a fresh DIRECT output — bypassing the wedged thread entirely. Content is
 * unchanged: L/R go to the front speakers, the other four channels carry silence
 * (the HAL/MS12 downmixes per the sink's EDID as usual).
 *
 * [engaged] is process-wide and sticky: a wedge outlives any single playback (only an
 * HDMI renegotiation — TV off/on — or reboot clears it), and staying upmixed after the
 * wedge clears is harmless, so nothing ever disengages it within a process lifetime.
 */
internal object AudioWedgeEscape {
    @Volatile
    var engaged: Boolean = false
        private set

    fun engage() {
        engaged = true
    }

    /** Test-only reset — production never disengages within a process lifetime. */
    fun resetForTest() {
        engaged = false
    }

    @Volatile
    private var probeRunning = false

    /**
     * Proactive wedge probe, so the FIRST playback after a wedge already takes the
     * escape route instead of freezing until a stall detector fires. Fire-and-forget:
     * a short-lived bounded thread creates a small silent stereo AudioTrack (which
     * routes to the primary mixer), fills it non-blocking, and watches whether the
     * mixer CONSUMES it. On a wedged primary the server position stays at 0 (the
     * thread never returns from write(), so it never drains any track — exactly what
     * the 2026-08-27 dump showed: Server=00000000 on all 53 stuck tracks). A track
     * that cannot even be created is the saturated flavor of the same failure.
     *
     * Cheap and safe on a healthy device: one track, released after <=600ms, silent,
     * no audio focus. Call whenever a playback surface spins up; it self-guards
     * against re-entry and does nothing once engaged.
     */
    fun maybeProbeAsync(context: Context) {
        if (engaged || probeRunning) return
        probeRunning = true
        val appContext = context.applicationContext
        Thread({
            try {
                if (isForcedByAdb(appContext)) {
                    engage()
                    Log.w("PlayerActivity", "Audio wedge escape FORCED via adb setting (QA override)")
                } else if (probeDetectsWedge()) {
                    engage()
                    Log.w("PlayerActivity", "Audio wedge probe: primary mixer not consuming — 5.1 upmix escape engaged pre-playback")
                }
            } finally {
                probeRunning = false
            }
        }, "AudioWedgeProbe").apply { isDaemon = true }.start()
    }

    /** QA-only: `adb shell settings put global dx_audio_wedge_escape 1` forces the escape on. */
    private fun isForcedByAdb(context: Context): Boolean = runCatching {
        android.provider.Settings.Global.getInt(context.contentResolver, "dx_audio_wedge_escape", 0) == 1
    }.getOrDefault(false)

    private fun probeDetectsWedge(): Boolean {
        var track: AudioTrack? = null
        try {
            val minBuf = AudioTrack.getMinBufferSize(
                PROBE_SAMPLE_RATE, AudioFormat.CHANNEL_OUT_STEREO, AudioFormat.ENCODING_PCM_16BIT
            )
            if (minBuf <= 0) return false
            track = AudioTrack(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_MOVIE)
                    .build(),
                AudioFormat.Builder()
                    .setSampleRate(PROBE_SAMPLE_RATE)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_STEREO)
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .build(),
                minBuf,
                AudioTrack.MODE_STREAM,
                AudioManager.AUDIO_SESSION_ID_GENERATE
            )
            if (track.state != AudioTrack.STATE_INITIALIZED) return true
            val silence = ByteArray(minBuf)
            track.play()
            // Non-blocking: on a wedged device a blocking write would hang this thread
            // exactly the way playback hangs.
            track.write(silence, 0, silence.size, AudioTrack.WRITE_NON_BLOCKING)
            val deadline = SystemClock.elapsedRealtime() + PROBE_WINDOW_MS
            while (SystemClock.elapsedRealtime() < deadline) {
                if (track.playbackHeadPosition > 0) return false
                Thread.sleep(PROBE_POLL_MS)
            }
            return true
        } catch (e: UnsupportedOperationException) {
            // "Cannot create AudioTrack" — the saturated flavor of the wedge.
            Log.w("PlayerActivity", "Audio wedge probe: AudioTrack creation refused (saturated primary)", e)
            return true
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
            return false
        } catch (e: Exception) {
            Log.w("PlayerActivity", "Audio wedge probe failed", e)
            return false
        } finally {
            runCatching { track?.release() }
        }
    }

    private const val PROBE_SAMPLE_RATE = 48_000
    private const val PROBE_WINDOW_MS = 500L
    private const val PROBE_POLL_MS = 50L
}

/**
 * Upmixes mono/stereo 16-bit PCM to 5.1 (FL FR FC LFE BL BR) with silent surrounds.
 * Inert (passthrough) until [AudioWedgeEscape.engaged]; the flag is sampled on every
 * sink configure, so the first player (re)build after engaging activates it — no app
 * restart needed. Byte-level copying keeps it endianness-agnostic.
 */
internal class StereoUpmixAudioProcessor : BaseAudioProcessor() {

    override fun onConfigure(inputAudioFormat: AudioProcessor.AudioFormat): AudioProcessor.AudioFormat {
        if (!AudioWedgeEscape.engaged) return AudioProcessor.AudioFormat.NOT_SET
        if (inputAudioFormat.encoding != C.ENCODING_PCM_16BIT) return AudioProcessor.AudioFormat.NOT_SET
        if (inputAudioFormat.channelCount != 1 && inputAudioFormat.channelCount != 2) {
            return AudioProcessor.AudioFormat.NOT_SET
        }
        return AudioProcessor.AudioFormat(inputAudioFormat.sampleRate, OUTPUT_CHANNEL_COUNT, C.ENCODING_PCM_16BIT)
    }

    override fun queueInput(inputBuffer: ByteBuffer) {
        val mono = inputAudioFormat.channelCount == 1
        val inputFrameBytes = if (mono) BYTES_PER_SAMPLE else 2 * BYTES_PER_SAMPLE
        val frames = inputBuffer.remaining() / inputFrameBytes
        if (frames == 0) return
        val output = replaceOutputBuffer(frames * OUTPUT_FRAME_BYTES)
        repeat(frames) {
            val left0 = inputBuffer.get()
            val left1 = inputBuffer.get()
            output.put(left0).put(left1)
            if (mono) {
                output.put(left0).put(left1)
            } else {
                output.put(inputBuffer.get()).put(inputBuffer.get())
            }
            output.put(SILENT_REAR_CHANNELS)
        }
        output.flip()
    }

    private companion object {
        const val OUTPUT_CHANNEL_COUNT = 6
        const val BYTES_PER_SAMPLE = 2
        const val OUTPUT_FRAME_BYTES = OUTPUT_CHANNEL_COUNT * BYTES_PER_SAMPLE
        /** FC, LFE, BL, BR — four silent 16-bit samples. */
        val SILENT_REAR_CHANNELS = ByteArray(4 * BYTES_PER_SAMPLE)
    }
}

/**
 * [DefaultRenderersFactory] whose audio sink carries the [StereoUpmixAudioProcessor].
 * The processor is always installed and self-gates on [AudioWedgeEscape.engaged], so
 * this factory is a drop-in replacement everywhere a player is built.
 */
internal open class WedgeEscapeRenderersFactory(context: Context) : DefaultRenderersFactory(context) {
    override fun buildAudioSink(
        context: Context,
        enableFloatOutput: Boolean,
        enableAudioTrackPlaybackParams: Boolean
    ): AudioSink = DefaultAudioSink.Builder(context)
        .setEnableFloatOutput(enableFloatOutput)
        .setEnableAudioTrackPlaybackParams(enableAudioTrackPlaybackParams)
        .setAudioProcessorChain(DefaultAudioSink.DefaultAudioProcessorChain(StereoUpmixAudioProcessor()))
        .build()
}
