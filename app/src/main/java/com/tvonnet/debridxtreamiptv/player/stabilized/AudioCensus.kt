package com.tvonnet.debridxtreamiptv.player.stabilized

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.util.Log

/**
 * W2 diagnostics (2026-09-27): what the device's audio looked like at the moment the wedge escape
 * decided something was wrong.
 *
 * The saved Fire TV Cube logs showed both escape engagements right after AudioFlinger's
 * `no more tracks available` - the track slots were full while the HDMI sink was present - but not
 * WHO filled them. This logs, at each such moment, how many players are active device-wide and of
 * which kind (media, system UI sounds, assistant...), plus the output devices present.
 *
 * Deliberate limit: Android anonymises other apps' playback configurations for a normal app, so
 * this cannot say which ones are OURS - the `dumpsys media.audio_flinger` captured by the PC-side
 * watcher answers that. Diagnostics only; nothing here changes a route or a player.
 */
internal object AudioCensus {

    /** Logs one census line on a short background thread: the calls are binder IPC. */
    fun logAsync(context: Context, reason: String) {
        val appContext = context.applicationContext
        Thread({
            runCatching { Log.w("PlayerActivity", snapshot(appContext, reason)) }
                .onFailure { Log.w("PlayerActivity", "audio census failed ($reason)", it) }
        }, "AudioCensus").apply { isDaemon = true }.start()
    }

    private fun snapshot(context: Context, reason: String): String {
        val am = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        val usages = am.activePlaybackConfigurations.map { it.audioAttributes.usage }
        val outputs = am.getDevices(AudioManager.GET_DEVICES_OUTPUTS).map { it.type }
        return censusLine(reason, usages, outputs)
    }

    /** Pure: `audio census reason=… activePlayers=3 usages=[MEDIA x2, SONIFICATION x1] outputs=[HDMI, HDMI_ARC]`. */
    internal fun censusLine(reason: String, usages: List<Int>, outputTypes: List<Int>): String {
        val usageSummary = usages.groupingBy { usageName(it) }.eachCount()
            .entries.sortedByDescending { it.value }
            .joinToString(prefix = "[", postfix = "]") { "${it.key} x${it.value}" }
        val outputSummary = outputTypes.map { deviceName(it) }.distinct().sorted()
            .joinToString(prefix = "[", postfix = "]")
        return "audio census reason=$reason activePlayers=${usages.size} usages=$usageSummary outputs=$outputSummary"
    }

    internal fun usageName(usage: Int): String = when (usage) {
        AudioAttributes.USAGE_MEDIA -> "MEDIA"
        AudioAttributes.USAGE_VOICE_COMMUNICATION -> "VOICE_CALL"
        AudioAttributes.USAGE_ALARM -> "ALARM"
        AudioAttributes.USAGE_NOTIFICATION -> "NOTIFICATION"
        AudioAttributes.USAGE_ASSISTANCE_ACCESSIBILITY -> "ACCESSIBILITY"
        AudioAttributes.USAGE_ASSISTANCE_NAVIGATION_GUIDANCE -> "NAVIGATION"
        AudioAttributes.USAGE_ASSISTANCE_SONIFICATION -> "SONIFICATION"
        AudioAttributes.USAGE_GAME -> "GAME"
        AudioAttributes.USAGE_ASSISTANT -> "ASSISTANT"
        AudioAttributes.USAGE_UNKNOWN -> "UNKNOWN"
        else -> "USAGE_$usage"
    }

    internal fun deviceName(type: Int): String = when (type) {
        AudioDeviceInfo.TYPE_HDMI -> "HDMI"
        AudioDeviceInfo.TYPE_HDMI_ARC -> "HDMI_ARC"
        HDMI_EARC -> "HDMI_EARC"
        AudioDeviceInfo.TYPE_BUILTIN_SPEAKER -> "SPEAKER"
        AudioDeviceInfo.TYPE_BLUETOOTH_A2DP -> "BT_A2DP"
        AudioDeviceInfo.TYPE_USB_DEVICE -> "USB"
        AudioDeviceInfo.TYPE_WIRED_HEADPHONES -> "HEADPHONES"
        AudioDeviceInfo.TYPE_TELEPHONY -> "TELEPHONY"
        else -> "TYPE_$type"
    }

    /** AudioDeviceInfo.TYPE_HDMI_EARC is API 31; the Cube runs 28, so the literal keeps it readable. */
    private const val HDMI_EARC = 29
}
