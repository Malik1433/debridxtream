package com.tvonnet.debridxtreamiptv.data.repository

import android.util.Base64
import com.tvonnet.debridxtreamiptv.data.model.XtreamEpgListing
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * Phase 7 freeze tests for the EPG leaf-mappers lifted out of XtreamRepository into EpgMapping.kt.
 * These were previously private and untestable; making them top-level lets us lock their behaviour.
 * Robolectric is required because decodeBase64IfPossible uses android.util.Base64.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class EpgMappingTest {

    private fun b64(value: String): String =
        Base64.encodeToString(value.toByteArray(Charsets.UTF_8), Base64.DEFAULT)

    // ---------- decodeBase64IfPossible ----------

    @Test
    fun `decodeBase64IfPossible returns null for null or blank`() {
        assertNull(decodeBase64IfPossible(null))
        assertNull(decodeBase64IfPossible(""))
        assertNull(decodeBase64IfPossible("   "))
    }

    @Test
    fun `decodeBase64IfPossible decodes valid base64`() {
        assertEquals("Evening News", decodeBase64IfPossible(b64("Evening News")))
    }

    // Note: the "plain text that isn't Base64" fall-through is intentionally NOT asserted here.
    // Its outcome depends on the android.util.Base64 implementation (Robolectric's lenient decoder
    // silently strips non-alphabet chars instead of throwing), so any expectation would freeze the
    // test double's behaviour, not this code's. The mapping logic below is what this file relocated.

    // ---------- toEpgEntityOrNull ----------

    @Test
    fun `toEpgEntityOrNull maps timestamps to millis and decodes fields`() {
        val listing = XtreamEpgListing(
            title = b64("News"),
            description = b64("Nightly bulletin"),
            startTimestamp = "1000",
            stopTimestamp = "2000"
        )
        val entity = listing.toEpgEntityOrNull("bbc.uk")
        requireNotNull(entity)
        assertEquals("bbc.uk", entity.channelId)
        assertEquals(1_000_000L, entity.start)
        assertEquals(2_000_000L, entity.stop)
        assertEquals("News", entity.title)
        assertEquals("Nightly bulletin", entity.description)
        assertNull(entity.category)
    }

    @Test
    fun `toEpgEntityOrNull returns null on non-numeric or missing timestamps`() {
        assertNull(
            XtreamEpgListing(title = "x", startTimestamp = null, stopTimestamp = "2000")
                .toEpgEntityOrNull("c")
        )
        assertNull(
            XtreamEpgListing(title = "x", startTimestamp = "abc", stopTimestamp = "2000")
                .toEpgEntityOrNull("c")
        )
    }

    @Test
    fun `toEpgEntityOrNull returns null when stop is not after start`() {
        assertNull(
            XtreamEpgListing(startTimestamp = "2000", stopTimestamp = "2000").toEpgEntityOrNull("c")
        )
        assertNull(
            XtreamEpgListing(startTimestamp = "3000", stopTimestamp = "2000").toEpgEntityOrNull("c")
        )
        assertNull(
            XtreamEpgListing(startTimestamp = "0", stopTimestamp = "0").toEpgEntityOrNull("c")
        )
    }
}
