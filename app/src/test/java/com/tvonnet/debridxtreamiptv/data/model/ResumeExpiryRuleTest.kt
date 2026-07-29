package com.tvonnet.debridxtreamiptv.data.model

import com.tvonnet.debridxtreamiptv.ui.debrid.DebridContentItem
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The resume rule at the two places that decide whether a row can go straight to the player.
 *
 * Both used to answer "might this link have aged out?" with yes, because neither ever has an
 * `expiresAt` to check — nothing on the resume path stores one. The player was fixed to play what it
 * holds and repair on a real 401/403/410, but these two kept assuming the worst, each with its own
 * cost:
 *
 * - [ContinueWatchingItem] feeds `canResumeDirectly`. Its "we have a fresh URL" clause could never
 *   fire, so a row holding a good URL but no infoHash or magnet to re-resolve from was sent to the
 *   detail screen instead of playing. Hidden in practice because most rows DO carry an infoHash and
 *   a different clause covered them.
 * - [DebridContentItem] is passed to `PlaybackResolver` as `isExpired`, which becomes `bypassCache`.
 *   Every resume from the Debrid and Stremio screens therefore skipped the 15-minute resolved-link
 *   cache and re-ran the full add-magnet → poll → unrestrict chain — the exact cache that exists to
 *   make a replay instant.
 *
 * Same rule for both: only a KNOWN-past expiry counts.
 */
class ResumeExpiryRuleTest {

    private val past get() = System.currentTimeMillis() - 60_000
    private val future get() = System.currentTimeMillis() + 60_000

    private fun continueWatching(source: String, expiresAt: Long?) = ContinueWatchingItem(
        contentId = "1",
        contentType = ContentType.MOVIE,
        title = "Test",
        posterUrl = null,
        backdropUrl = null,
        currentPosition = 0,
        totalDuration = 0,
        lastWatchedTimestamp = 0,
        streamUrl = "http://host/file.mkv",
        source = source,
        expiresAt = expiresAt
    )

    private fun debridRow(source: String, expiresAt: Long?) = DebridContentItem(
        id = "1",
        title = "Test",
        posterUrl = null,
        backdropUrl = null,
        type = "movie",
        year = null,
        rating = null,
        source = source,
        expiresAt = expiresAt
    )

    @Test
    fun `an unknown expiry means play it - Continue Watching`() {
        assertFalse(continueWatching(source = "debrid", expiresAt = null).isExpired())
    }

    @Test
    fun `an unknown expiry means use the cached link - Debrid row`() {
        // The one that was costing a full re-resolution on every replay.
        assertFalse(debridRow(source = "debrid", expiresAt = null).isExpired())
    }

    @Test
    fun `an expiry that has passed is the only case either treats as dead`() {
        assertTrue(continueWatching(source = "debrid", expiresAt = past).isExpired())
        assertTrue(debridRow(source = "debrid", expiresAt = past).isExpired())
    }

    @Test
    fun `an expiry still in the future is not expired`() {
        assertFalse(continueWatching(source = "debrid", expiresAt = future).isExpired())
        assertFalse(debridRow(source = "debrid", expiresAt = future).isExpired())
    }

    @Test
    fun `an Xtream row never expires - its URL is built from credentials, not minted`() {
        // DebridContentItem had no source guard at all, so an Xtream row there was "expired" too.
        assertFalse(continueWatching(source = "xtream", expiresAt = null).isExpired())
        assertFalse(continueWatching(source = "xtream", expiresAt = past).isExpired())
        assertFalse(debridRow(source = "xtream", expiresAt = null).isExpired())
        assertFalse(debridRow(source = "xtream", expiresAt = past).isExpired())
    }
}
