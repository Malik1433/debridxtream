package com.tvonnet.debridxtreamiptv.player.stabilized

import android.content.Context
import androidx.media3.exoplayer.DefaultLoadControl
import com.tvonnet.debridxtreamiptv.data.model.ContentType
import com.tvonnet.debridxtreamiptv.data.prefs.SettingsPreferences
import com.tvonnet.debridxtreamiptv.util.DeviceProfile

enum class NetworkQuality { FAST, MODERATE, SLOW, UNKNOWN }

data class BufferConfig(
    val minBufferMs: Int,
    val maxBufferMs: Int,
    val startPlaybackMs: Int,
    val rebufferPlaybackMs: Int
)

object PlayerBufferConfigFactory {
    private const val LOW_RAM_MAX_BUFFER_MS = 30000
    private const val LOW_RAM_TARGET_BUFFER_BYTES = 32 * 1024 * 1024

    fun buildConfig(
        context: Context,
        settingsPreferences: SettingsPreferences,
        contentType: ContentType?,
        streamUrl: String,
        isDebrid: Boolean
    ): BufferConfig {
        val isHls = isHlsUrl(streamUrl)
        return if (contentType == ContentType.LIVE_TV) {
            buildLiveBufferConfig(context, settingsPreferences, isHls, isDebrid)
        } else {
            buildVodBufferConfig(context, settingsPreferences, isHls, isDebrid)
        }
    }

    fun resolveTargetBufferBytes(context: Context, isDebrid: Boolean): Int {
        if (isDebrid) {
            // High-bitrate 4K Debrid streams need large buffers, but a flat 128MB
            // risks OOM on 1GB Fire TV sticks — scale with device RAM class.
            val snapshot = DeviceProfile.get(context)
            return when {
                snapshot.isLowRamDevice -> 48 * 1024 * 1024
                snapshot.totalRamGb <= 2.5 -> 96 * 1024 * 1024
                else -> 128 * 1024 * 1024
            }
        }

        return if (DeviceProfile.isLowRamDevice(context)) {
            LOW_RAM_TARGET_BUFFER_BYTES
        } else {
            DefaultLoadControl.DEFAULT_TARGET_BUFFER_BYTES
        }
    }

    private fun calculateSmartBuffer(context: Context): Int {
        val snapshot = DeviceProfile.get(context)
        return if (!snapshot.isLowRamDevice) 60000 else 25000
    }

    private fun getSavedNetworkQuality(settingsPreferences: SettingsPreferences): NetworkQuality {
        val rawQuality = settingsPreferences.getNetworkQuality()
        return runCatching { NetworkQuality.valueOf(rawQuality) }.getOrDefault(NetworkQuality.MODERATE)
    }

    private fun isHlsUrl(url: String): Boolean = url.contains(".m3u8", ignoreCase = true)

    /**
     * On a slow line, buffer continuously instead of in bursts.
     *
     * With min well below max, ExoPlayer fills to max, stops, drains to min, then fetches hard
     * again — and every one of those gaps is a chance for a slow line to fall behind. Setting the
     * two equal keeps the pipe always slightly hungry, which the ExoPlayer team measured as
     * "significantly" fewer rebuffers for a small battery cost (via Akamai's write-up of their
     * buffering strategy).
     *
     * VOD ONLY, and that limit is the point. A live stream is produced in real time: the server
     * cannot hand over sixty seconds of a channel that has not happened yet, so a bigger MINIMUM
     * there is aspiration, not policy — it would only delay the start. The lever that would
     * actually help live is how much to re-accumulate after a stall, and that number should be
     * changed against a measurement on a real stalling channel, not from a blog post.
     */
    private fun dripLoad(config: BufferConfig): BufferConfig =
        config.copy(minBufferMs = config.maxBufferMs)

    private fun capMaxBufferForDevice(context: Context, maxBufferMs: Int): Int {
        return if (DeviceProfile.isLowRamDevice(context)) {
            maxBufferMs.coerceAtMost(LOW_RAM_MAX_BUFFER_MS)
        } else {
            maxBufferMs
        }
    }

    private fun buildLiveBufferConfig(
        context: Context,
        settingsPreferences: SettingsPreferences,
        isHls: Boolean,
        isDebrid: Boolean
    ): BufferConfig {
        val baseMaxBuffer = calculateSmartBuffer(context)
        val quality = getSavedNetworkQuality(settingsPreferences)
        val config = when (quality) {
            NetworkQuality.FAST -> BufferConfig(15000, baseMaxBuffer, 1000, 2000)
            NetworkQuality.MODERATE -> BufferConfig(15000, baseMaxBuffer, 2000, 3000)
            NetworkQuality.SLOW -> BufferConfig(20000, (baseMaxBuffer + 10000).coerceAtMost(60000), 3000, 5000)
            NetworkQuality.UNKNOWN -> BufferConfig(15000, baseMaxBuffer, 2000, 3000)
        }
        val debridAdjusted = if (isDebrid) {
            config.copy(
                startPlaybackMs = (config.startPlaybackMs + 1000).coerceAtLeast(3000),
                rebufferPlaybackMs = (config.rebufferPlaybackMs + 1000).coerceAtLeast(4000)
            )
        } else {
            config
        }
        val cappedForDevice = debridAdjusted.copy(
            maxBufferMs = capMaxBufferForDevice(context, debridAdjusted.maxBufferMs)
        )
        // LP-A-3: cap applies to raw TS too (the majority IPTV case), not just HLS —
        // an endless live stream gains nothing from a 60s window; it only adds drift
        // from the live edge and memory pressure on low-RAM boxes.
        return cappedForDevice.copy(
            maxBufferMs = cappedForDevice.maxBufferMs.coerceAtMost(
                if (DeviceProfile.isLowRamDevice(context)) 25000 else 30000
            )
        )
    }

    private fun buildVodBufferConfig(
        context: Context,
        settingsPreferences: SettingsPreferences,
        isHls: Boolean,
        isDebrid: Boolean
    ): BufferConfig {
        if (isDebrid) {
            // High-bitrate Debrid parameters. On low-RAM devices the 30-60s window
            // (600MB+ for a 4K remux) exhausts the heap — verified OOM on a 1.5GB
            // Fire TV Stick 4K — so cap the buffer window there.
            return if (DeviceProfile.isLowRamDevice(context)) {
                BufferConfig(
                    minBufferMs = 15000,
                    maxBufferMs = LOW_RAM_MAX_BUFFER_MS,
                    startPlaybackMs = 2500,
                    rebufferPlaybackMs = 5000
                )
            } else {
                BufferConfig(
                    minBufferMs = 30000,
                    maxBufferMs = 60000,
                    startPlaybackMs = 2500,
                    rebufferPlaybackMs = 5000
                )
            }
        }

        val baseMaxBuffer = calculateSmartBuffer(context)
        val quality = getSavedNetworkQuality(settingsPreferences)
        val config = when (quality) {
            NetworkQuality.FAST -> BufferConfig(12000, baseMaxBuffer, 1500, 3000)
            NetworkQuality.MODERATE -> BufferConfig(15000, baseMaxBuffer, 2000, 3500)
            NetworkQuality.SLOW -> BufferConfig(18000, (baseMaxBuffer + 15000).coerceAtMost(90000), 3000, 5000)
            NetworkQuality.UNKNOWN -> BufferConfig(15000, baseMaxBuffer, 2000, 3500)
        }
        val cappedForDevice = config.copy(
            maxBufferMs = capMaxBufferForDevice(context, config.maxBufferMs)
        ).let { if (quality == NetworkQuality.SLOW) dripLoad(it) else it }
        return if (isHls) {
            cappedForDevice.copy(
                maxBufferMs = cappedForDevice.maxBufferMs.coerceAtMost(
                    if (DeviceProfile.isLowRamDevice(context)) 25000 else 45000
                )
            )
        } else {
            cappedForDevice
        }
    }
}
