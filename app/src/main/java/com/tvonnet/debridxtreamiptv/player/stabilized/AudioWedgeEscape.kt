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
 * [engaged] is process-wide but NOT permanent any more. The 2026-08-27 note said the
 * flag never needs to come off because "staying upmixed after the wedge clears is
 * harmless". It is not (device capture 2026-09-15, .64, process 26h old): the same HAL
 * bug can hit the DIRECT thread too, and `hdmi_pcm_passthrough_direct` has
 * maxOpenCount 1 — once that single thread is blocked in write() it never closes, no
 * other direct output can open, and every upmixed track for the same config lands on
 * the dead thread. Meanwhile the primary mixer had been healthy again for a day (the
 * owner's TV off/on had cleared it) and the app kept avoiding it. So the route is
 * re-decided from the probe: escape while the primary is wedged, come back when it
 * consumes again. [release] is rate-limited so a flapping probe cannot ping-pong.
 */
internal object AudioWedgeEscape {
    @Volatile
    var engaged: Boolean = false
        private set

    fun engage() {
        engaged = true
    }

    @Volatile
    private var lastReleaseAtMs = -RELEASE_BACKOFF_MS

    /**
     * Come back off the escape route because the primary mixer is consuming again.
     * Returns false — and changes nothing — when a release happened less than
     * [RELEASE_BACKOFF_MS] ago: two flips in five minutes means the probe and the
     * playback disagree, and the caller must stop flipping and use the ordinary path.
     */
    fun release(nowMs: Long = SystemClock.elapsedRealtime()): Boolean {
        if (nowMs - lastReleaseAtMs < RELEASE_BACKOFF_MS) return false
        lastReleaseAtMs = nowMs
        engaged = false
        return true
    }

    /** Test-only reset. */
    fun resetForTest() {
        engaged = false
        lastReleaseAtMs = -RELEASE_BACKOFF_MS
        lastEngagedProbeAtMs = -ENGAGED_PROBE_INTERVAL_MS
    }

    enum class RouteDecision { ENGAGE, RELEASE, KEEP }

    /** The whole routing rule, kept pure so it can be tested without an AudioTrack. */
    fun decide(engaged: Boolean, forced: Boolean, primaryConsumes: Boolean): RouteDecision = when {
        forced -> if (engaged) RouteDecision.KEEP else RouteDecision.ENGAGE
        !engaged && !primaryConsumes -> RouteDecision.ENGAGE
        engaged && primaryConsumes -> RouteDecision.RELEASE
        else -> RouteDecision.KEEP
    }

    @Volatile
    private var probeRunning = false

    @Volatile
    private var lastEngagedProbeAtMs = -ENGAGED_PROBE_INTERVAL_MS

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
     * Cheap and safe on a healthy device: one track, silent, released as soon as it consumes (or after
     * PROBE_OBSERVE_MS when it never does - the verdict itself still lands at 500 ms),
     * no audio focus. Call whenever a playback surface spins up; it self-guards
     * against re-entry. While engaged it still probes — that is how the escape is
     * RELEASED once the primary consumes again — but only every
     * [ENGAGED_PROBE_INTERVAL_MS], because on a wedged primary each probe track is
     * one more terminated track the dead thread will never reap.
     */
    fun maybeProbeAsync(context: Context) {
        if (probeRunning) return
        if (engaged) {
            val now = SystemClock.elapsedRealtime()
            if (now - lastEngagedProbeAtMs < ENGAGED_PROBE_INTERVAL_MS) return
            lastEngagedProbeAtMs = now
        }
        probeRunning = true
        val appContext = context.applicationContext
        Thread({
            try {
                val forced = isForcedByAdb(appContext)
                if (forced) applyProbeVerdict(forced = true, primaryConsumes = false)
                else probeDetectsWedge { wedged ->
                    if (wedged) AudioCensus.logAsync(appContext, "probe_not_consuming")
                    applyProbeVerdict(forced = false, primaryConsumes = !wedged)
                }
            } finally {
                probeRunning = false
            }
        }, "AudioWedgeProbe").apply { isDaemon = true }.start()
    }

    private fun applyProbeVerdict(forced: Boolean, primaryConsumes: Boolean) {
        when (decide(engaged, forced, primaryConsumes)) {
            RouteDecision.ENGAGE -> {
                engage()
                if (forced) {
                    Log.w("PlayerActivity", "Audio wedge escape FORCED via adb setting (QA override)")
                } else {
                    Log.w("PlayerActivity", "Audio wedge probe: primary mixer not consuming — 5.1 upmix escape engaged pre-playback")
                }
            }
            RouteDecision.RELEASE -> if (release()) {
                Log.w("PlayerActivity", "Audio wedge probe: primary mixer consuming again — 5.1 upmix escape released (the DIRECT route can wedge too)")
            }
            RouteDecision.KEEP -> Unit
        }
    }

    /**
     * Second-level check for a player that is frozen WHILE on the escape route: asks
     * the primary mixer whether it consumes, and answers on the main thread. Unlike
     * [maybeProbeAsync] this never flips the flag itself — the caller decides, because
     * it also has to rebuild the player or give up.
     */
    fun probePrimaryAsync(context: Context, onResult: (primaryConsumes: Boolean) -> Unit) {
        val appContext = context.applicationContext
        val main = android.os.Handler(android.os.Looper.getMainLooper())
        Thread({
            if (isForcedByAdb(appContext)) main.post { onResult(false) }
            else probeDetectsWedge { wedged ->
                if (wedged) AudioCensus.logAsync(appContext, "primary_probe_not_consuming")
                main.post { onResult(!wedged) }
            }
        }, "AudioWedgeProbe").apply { isDaemon = true }.start()
    }

    /** QA-only: `adb shell settings put global dx_audio_wedge_escape 1` forces the escape on. */
    private fun isForcedByAdb(context: Context): Boolean = runCatching {
        android.provider.Settings.Global.getInt(context.contentResolver, "dx_audio_wedge_escape", 0) == 1
    }.getOrDefault(false)

    /**
     * Runs the probe and hands [onVerdict] (wedged = true) the SAME answer, at the SAME moment, as
     * before: consumed within [PROBE_WINDOW_MS] = healthy, otherwise wedged.
     *
     * F1/W0 (2026-09-27): on the Fire TV Cube the verdict "wedged" was reached by that 500 ms
     * timeout pre-playback, while the HDMI route was cold, and the 5.1 DIRECT escape it engages is
     * the route that later wedged. Whether a healthy cold route simply needs longer than 500 ms is
     * the open question. So after delivering the verdict the probe keeps watching the same track for
     * up to [PROBE_OBSERVE_MS] and logs WHEN it first consumed ([probeTimingLogLine]). Diagnostics
     * only: the decision, its threshold and its timing are unchanged.
     */
    private fun probeDetectsWedge(onVerdict: (wedged: Boolean) -> Unit) {
        var delivered = false
        fun deliver(wedged: Boolean) {
            if (!delivered) { delivered = true; onVerdict(wedged) }
        }
        var track: AudioTrack? = null
        try {
            val minBuf = AudioTrack.getMinBufferSize(
                PROBE_SAMPLE_RATE, AudioFormat.CHANNEL_OUT_STEREO, AudioFormat.ENCODING_PCM_16BIT
            )
            if (minBuf <= 0) { deliver(false); return }
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
            if (track.state != AudioTrack.STATE_INITIALIZED) { deliver(true); return }
            val silence = ByteArray(minBuf)
            track.play()
            // Non-blocking: on a wedged device a blocking write would hang this thread
            // exactly the way playback hangs.
            track.write(silence, 0, silence.size, AudioTrack.WRITE_NON_BLOCKING)
            val firstProgressMs = watchProbeTrack(track, ::deliver)
            Log.i("PlayerActivity", probeTimingLogLine(firstProgressMs, PROBE_WINDOW_MS, PROBE_OBSERVE_MS))
        } catch (e: UnsupportedOperationException) {
            // "Cannot create AudioTrack" — the saturated flavor of the wedge.
            Log.w("PlayerActivity", "Audio wedge probe: AudioTrack creation refused (saturated primary)", e)
            deliver(true)
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
            deliver(false)
        } catch (e: Exception) {
            Log.w("PlayerActivity", "Audio wedge probe failed", e)
            deliver(false)
        } finally {
            runCatching { track?.release() }
        }
    }

    /**
     * Polls [track] until it first consumes or [PROBE_OBSERVE_MS] passes. [deliver] gets the verdict
     * the moment it is known - consumed before [PROBE_WINDOW_MS], or that window elapsed without it -
     * and the loop then keeps watching only to time the first consumption. Returns that time, or null.
     */
    private fun watchProbeTrack(track: AudioTrack, deliver: (wedged: Boolean) -> Unit): Long? {
        val start = SystemClock.elapsedRealtime()
        var firstProgressMs: Long? = null
        while (true) {
            val elapsed = SystemClock.elapsedRealtime() - start
            if (firstProgressMs == null && track.playbackHeadPosition > 0) firstProgressMs = elapsed
            if (firstProgressMs != null && firstProgressMs < PROBE_WINDOW_MS) deliver(false)
            else if (elapsed >= PROBE_WINDOW_MS) deliver(true)
            if (firstProgressMs != null || elapsed >= PROBE_OBSERVE_MS) return firstProgressMs
            Thread.sleep(PROBE_POLL_MS)
        }
    }

    /**
     * Pure: the one line the W0 analysis greps for. `late` = the route DID consume, but only after
     * the window had already declared it wedged - a false positive, with the real cold-start time.
     */
    internal fun probeTimingLogLine(firstProgressMs: Long?, windowMs: Long, observeMs: Long): String = when {
        firstProgressMs == null ->
            "Audio wedge probe timing: result=no_progress firstProgressMs=none observedMs=$observeMs verdict=wedged"
        firstProgressMs < windowMs ->
            "Audio wedge probe timing: result=in_window firstProgressMs=$firstProgressMs windowMs=$windowMs verdict=consuming"
        else ->
            "Audio wedge probe timing: result=late firstProgressMs=$firstProgressMs windowMs=$windowMs verdict=wedged (false positive)"
    }

    private const val PROBE_SAMPLE_RATE = 48_000
    private const val PROBE_WINDOW_MS = 500L
    private const val PROBE_POLL_MS = 50L
    // How long the probe keeps watching AFTER its verdict, for the timing log only.
    private const val PROBE_OBSERVE_MS = 3000L
    private const val RELEASE_BACKOFF_MS = 5 * 60_000L
    private const val ENGAGED_PROBE_INTERVAL_MS = 30 * 60_000L
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
