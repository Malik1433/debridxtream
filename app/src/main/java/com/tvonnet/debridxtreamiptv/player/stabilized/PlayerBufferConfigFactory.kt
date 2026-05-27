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
    private const val LOW_RAM_TARGET_BUFFER_BYTES = 12 * 1024 * 1024

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

    fun resolveTargetBufferBytes(context: Context): Int {
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
        return if (isHls) {
            cappedForDevice.copy(
                maxBufferMs = cappedForDevice.maxBufferMs.coerceAtMost(
                    if (DeviceProfile.isLowRamDevice(context)) 25000 else 30000
                )
            )
        } else {
            cappedForDevice
        }
    }

    private fun buildVodBufferConfig(
        context: Context,
        settingsPreferences: SettingsPreferences,
        isHls: Boolean,
        isDebrid: Boolean
    ): BufferConfig {
        val baseMaxBuffer = calculateSmartBuffer(context)
        val quality = getSavedNetworkQuality(settingsPreferences)
        val config = when (quality) {
            NetworkQuality.FAST -> BufferConfig(12000, baseMaxBuffer, 1500, 3000)
            NetworkQuality.MODERATE -> BufferConfig(15000, baseMaxBuffer, 2000, 3500)
            NetworkQuality.SLOW -> BufferConfig(18000, (baseMaxBuffer + 15000).coerceAtMost(90000), 3000, 5000)
            NetworkQuality.UNKNOWN -> BufferConfig(15000, baseMaxBuffer, 2000, 3500)
        }
        val debridAdjusted = if (isDebrid) {
            config.copy(
                startPlaybackMs = (config.startPlaybackMs + 2000).coerceAtLeast(5000),
                rebufferPlaybackMs = (config.rebufferPlaybackMs + 2000).coerceAtLeast(6000)
            )
        } else {
            config
        }
        val cappedForDevice = debridAdjusted.copy(
            maxBufferMs = capMaxBufferForDevice(context, debridAdjusted.maxBufferMs)
        )
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
