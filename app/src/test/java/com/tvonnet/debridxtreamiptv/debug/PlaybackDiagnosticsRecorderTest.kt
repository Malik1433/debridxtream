package com.tvonnet.debridxtreamiptv.debug

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import java.io.File
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * G1: the recorder is bounded and self-describing.
 *
 * - A session file rotates once it reaches the cap, the closing `session_rotated` event lands in
 *   the file it summarises, and the next event opens a fresh file.
 * - The directory is pruned oldest-first past the file-count cap when a session opens.
 * - `session_finished` carries the summary (counts by type, stall/retry/error tallies), so a
 *   reader never has to re-count 20 000 lines to know whether the session was healthy.
 * - Memory samples are throttled and never open a session by themselves.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class PlaybackDiagnosticsRecorderTest {

    private lateinit var context: Context
    private lateinit var dir: File

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        dir = context.getExternalFilesDir("playback-diagnostics")!!
        dir.mkdirs()
        dir.listFiles()?.forEach { it.delete() }
        File(dir, ".enabled").writeText("")
        // Fresh recorder state between tests: close whatever a previous test left open.
        PlaybackDiagnosticsRecorder.finishSession(context, "test_setup")
    }

    private fun sessionFiles(): List<File> =
        dir.listFiles { f -> f.name.startsWith("session-") && f.name.endsWith(".jsonl") }!!.sortedBy { it.lastModified() }

    private fun events(file: File): List<JSONObject> = file.readLines().filter { it.isNotBlank() }.map { JSONObject(it) }

    @Test
    fun `finishSession writes a summary the reader can trust`() {
        PlaybackDiagnosticsRecorder.record(context, "playback_launch")
        PlaybackDiagnosticsRecorder.record(context, "stall_warning")
        PlaybackDiagnosticsRecorder.record(context, "stall_warning")
        PlaybackDiagnosticsRecorder.record(context, "retry_triggered")
        PlaybackDiagnosticsRecorder.record(context, "first_frame_rendered")
        PlaybackDiagnosticsRecorder.finishSession(context, "activity_destroyed")

        val file = sessionFiles().single()
        val last = events(file).last()
        assertEquals("session_finished", last.getString("eventType"))
        val f = last.getJSONObject("fields")
        assertEquals(2, f.getInt("stallWarnings"))
        assertEquals(1, f.getInt("retries"))
        assertEquals(1, f.getInt("firstFrames"))
        assertEquals(0, f.getInt("stalls"))
        assertEquals("activity_destroyed", f.getString("reasonCode"))
        // session_started + 5 events = 6 before the summary line itself
        assertEquals(6, f.getLong("eventCount"))
        assertEquals(2, f.getJSONObject("countsByType").getInt("stall_warning"))
    }

    @Test
    fun `a session file rotates at the cap and the summary lands in the file it closes`() {
        // sanitizeValue truncates long strings, so bulk comes from MANY short fields per event.
        val bulk = (1..60).associate { "f$it" to "v".repeat(40) }
        var writes = 0
        while (sessionFiles().size < 2 && writes < 5000) {
            PlaybackDiagnosticsRecorder.record(context, "player_state_changed", bulk)
            writes++
        }
        val files = sessionFiles()
        assertEquals("rotated into a second file", 2, files.size)
        val first = events(files[0])
        assertEquals("session_rotated", first.last().getString("eventType"))
        assertTrue(files[0].length() >= PlaybackDiagnosticsRecorder.MAX_SESSION_FILE_BYTES)
        assertTrue("rotated file is bounded", files[0].length() < PlaybackDiagnosticsRecorder.MAX_SESSION_FILE_BYTES + 16 * 1024)
        assertEquals("session_started", events(files[1]).first().getString("eventType"))
        // The rotated half reports how many events it held.
        assertTrue(first.last().getJSONObject("fields").getLong("eventCount") > 100)
        PlaybackDiagnosticsRecorder.finishSession(context, "test_end")
    }

    @Test
    fun `opening a session prunes the directory oldest-first past the file cap`() {
        repeat(PlaybackDiagnosticsRecorder.MAX_FILES + 5) { i ->
            val f = File(dir, "session-20260101-%06d-old$i.jsonl".format(i))
            f.writeText("{}\n")
            f.setLastModified(1_000_000L + i * 1000L)
        }
        assertEquals(PlaybackDiagnosticsRecorder.MAX_FILES + 5, sessionFiles().size)
        PlaybackDiagnosticsRecorder.record(context, "playback_launch")
        val after = sessionFiles()
        assertEquals("cap + the new session file", PlaybackDiagnosticsRecorder.MAX_FILES, after.size)
        assertTrue("the oldest were the ones deleted", after.none { it.name.contains("-old0.") || it.name.contains("-old4.") })
        PlaybackDiagnosticsRecorder.finishSession(context, "test_end")
    }

    @Test
    fun `memory samples never open a session and are throttled inside one`() {
        PlaybackDiagnosticsRecorder.maybeRecordMemorySample(context)
        assertEquals("no session opened by a sample", 0, sessionFiles().size)
        PlaybackDiagnosticsRecorder.record(context, "playback_launch")
        PlaybackDiagnosticsRecorder.maybeRecordMemorySample(context)
        PlaybackDiagnosticsRecorder.maybeRecordMemorySample(context) // inside the 30s window
        PlaybackDiagnosticsRecorder.finishSession(context, "test_end")
        val evs = events(sessionFiles().single())
        assertEquals(1, evs.count { it.getString("eventType") == "memory_sample" })
        val sample = evs.first { it.getString("eventType") == "memory_sample" }.getJSONObject("fields")
        assertNotNull(sample.get("javaHeapUsedMb"))
        assertEquals(1, evs.last().getJSONObject("fields").getInt("memorySamples"))
    }
}
