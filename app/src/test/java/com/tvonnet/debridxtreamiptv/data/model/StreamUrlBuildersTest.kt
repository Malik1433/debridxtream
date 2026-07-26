package com.tvonnet.debridxtreamiptv.data.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

/**
 * Roadmap B3 — the Xtream stream-URL builders.
 *
 * These four functions decide whether playback happens at all: get the shape wrong and every
 * channel or film 404s/405s. Two rules already exist because they *were* broken — a trailing slash
 * on the server URL used to produce `//live`, and a blank container extension used to produce
 * `.../<id>.` which Xtream servers answer with 405. Both are pinned here, along with the per-kind
 * defaults (live → ts, movie → mp4, series → mp4).
 */
class StreamUrlBuildersTest {

    private val server = "http://provider.test:8080"
    private val user = "malik"
    private val pass = "hunter22"

    @Test
    fun `a live url has the live shape and keeps its extension`() {
        assertEquals(
            "$server/live/$user/$pass/12345.m3u8",
            buildLiveStreamUrl(server, user, pass, "12345", "m3u8")
        )
    }

    @Test
    fun `live falls back to ts, movie and series to mp4`() {
        assertEquals("$server/live/$user/$pass/1.ts", buildLiveStreamUrl(server, user, pass, "1", null))
        assertEquals("$server/live/$user/$pass/1.ts", buildLiveStreamUrl(server, user, pass, "1", ""))
        assertEquals("$server/movie/$user/$pass/2.mp4", buildVodStreamUrl(server, user, pass, "2", null))
        assertEquals(
            "a blank extension used to build '.../2.' which servers answer with 405",
            "$server/movie/$user/$pass/2.mp4",
            buildVodStreamUrl(server, user, pass, "2", "  ")
        )
    }

    @Test
    fun `a trailing slash on the server url never doubles up`() {
        val withSlash = "$server/"

        assertEquals("$server/live/$user/$pass/1.ts", buildLiveStreamUrl(withSlash, user, pass, "1", "ts"))
        assertEquals("$server/movie/$user/$pass/2.mkv", buildVodStreamUrl(withSlash, user, pass, "2", "mkv"))
        assertFalse(buildLiveStreamUrl("$server///", user, pass, "1", "ts").contains("//live"))
    }

    @Test
    fun `the stream extension drives the live url built from a channel`() {
        val stream = liveStream(streamId = "77", containerExtension = "m3u8")

        assertEquals("$server/live/$user/$pass/77.m3u8", stream.toLiveStreamUrl(server, user, pass))
    }

    @Test
    fun `a channel with no extension still builds a playable url`() {
        val stream = liveStream(streamId = "77", containerExtension = null)

        assertEquals("$server/live/$user/$pass/77.ts", stream.toLiveStreamUrl(server, user, pass))
    }

    @Test
    fun `a channel with no id builds a url with an empty id rather than throwing`() {
        val stream = liveStream(streamId = null, containerExtension = "ts")

        assertEquals("$server/live/$user/$pass/.ts", stream.toLiveStreamUrl(server, user, pass))
    }

    private fun liveStream(streamId: String?, containerExtension: String?) = XtreamStream(
        num = 1,
        name = "Channel",
        stream_type = "live",
        stream_id = streamId,
        stream_icon = null,
        epg_channel_id = null,
        added = null,
        category_id = null,
        category_ids = null,
        container_extension = containerExtension,
        custom_sid = null,
        direct_source = null,
        tv_archive = null,
        tv_archive_duration = null
    )
}
