package com.tvonnet.debridxtreamiptv.player.stabilized

import android.media.AudioAttributes
import android.media.AudioDeviceInfo
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * W2: the census line is what the field analysis greps next to "no more tracks available". Its
 * shape is frozen here so a refactor cannot quietly change what the harvest scripts read.
 */
class AudioCensusTest {

    @Test
    fun `census line counts players by kind and lists outputs once`() {
        val line = AudioCensus.censusLine(
            reason = "probe_not_consuming",
            usages = listOf(
                AudioAttributes.USAGE_MEDIA,
                AudioAttributes.USAGE_ASSISTANCE_SONIFICATION,
                AudioAttributes.USAGE_MEDIA,
            ),
            outputTypes = listOf(AudioDeviceInfo.TYPE_HDMI_ARC, AudioDeviceInfo.TYPE_HDMI, AudioDeviceInfo.TYPE_HDMI),
        )
        assertEquals(
            "audio census reason=probe_not_consuming activePlayers=3 " +
                "usages=[MEDIA x2, SONIFICATION x1] outputs=[HDMI, HDMI_ARC]",
            line
        )
    }

    @Test
    fun `an empty device is still a readable line`() {
        assertEquals(
            "audio census reason=both_routes_wedged activePlayers=0 usages=[] outputs=[]",
            AudioCensus.censusLine("both_routes_wedged", emptyList(), emptyList())
        )
    }

    @Test
    fun `unknown usages and devices keep their number`() {
        assertEquals("USAGE_999", AudioCensus.usageName(999))
        assertEquals("ASSISTANT", AudioCensus.usageName(AudioAttributes.USAGE_ASSISTANT))
        assertEquals("TYPE_999", AudioCensus.deviceName(999))
        assertEquals("HDMI_EARC", AudioCensus.deviceName(29))
    }
}
