package com.tvonnet.debridxtreamiptv.player.stabilized

import com.tvonnet.debridxtreamiptv.debug.PlaybackDiagnosticsRecorder
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Freezes the diagnostics field builders extracted in P7. The privacy contract matters
 * most here: raw identities must never reach a diagnostics map, only fingerprints.
 */
class PlayerDiagnosticsFieldsTest {

    @Test
    fun `source fields never carry the raw binge group or stream id`() {
        val fields = diagnosticsSourceFields(
            provider = "realdebrid",
            sourceType = "STREMIO",
            sourceName = "Torrentio",
            languages = listOf("en", "hi"),
            quality = "2160p",
            bingeGroup = "secret-binge-group",
            fileIdx = 3,
            streamId = "secret-stream-id",
            infoHash = "aabbccdd"
        )

        assertEquals("realdebrid", fields["provider"])
        assertEquals("STREMIO", fields["sourceType"])
        assertEquals(listOf("en", "hi"), fields["languages"])
        assertEquals(3, fields["fileIdx"])
        assertEquals(true, fields["hasBingeGroup"])
        assertFalse(fields.values.any { it == "secret-binge-group" || it == "secret-stream-id" })
        assertEquals(
            PlaybackDiagnosticsRecorder.identityFingerprint("secret-binge-group"),
            fields["bingeGroupFingerprint"]
        )
        assertEquals(
            PlaybackDiagnosticsRecorder.identityFingerprint("secret-stream-id"),
            fields["streamIdFingerprint"]
        )
    }

    @Test
    fun `source fields fall back to the info hash when there is no stream id`() {
        val fields = diagnosticsSourceFields(
            provider = null,
            sourceType = null,
            sourceName = null,
            languages = null,
            quality = null,
            bingeGroup = null,
            fileIdx = null,
            streamId = null,
            infoHash = "aabbccdd"
        )

        assertEquals(false, fields["hasBingeGroup"])
        assertNull(fields["bingeGroupFingerprint"])
        assertEquals(
            PlaybackDiagnosticsRecorder.identityFingerprint("aabbccdd"),
            fields["streamIdFingerprint"]
        )
    }

    @Test
    fun `blank binge group does not count as having one`() {
        val fields = diagnosticsSourceFields(
            provider = "p", sourceType = null, sourceName = null, languages = null,
            quality = null, bingeGroup = "   ", fileIdx = null, streamId = "s", infoHash = null
        )

        assertEquals(false, fields["hasBingeGroup"])
    }

    @Test
    fun `source fields always report the same key set`() {
        val fields = diagnosticsSourceFields(
            provider = null, sourceType = null, sourceName = null, languages = null,
            quality = null, bingeGroup = null, fileIdx = null, streamId = null, infoHash = null
        )

        assertTrue(
            fields.keys.containsAll(
                listOf(
                    "provider", "sourceType", "sourceName", "languages", "quality",
                    "hasBingeGroup", "bingeGroupFingerprint", "fileIdx", "streamIdFingerprint"
                )
            )
        )
    }
}
