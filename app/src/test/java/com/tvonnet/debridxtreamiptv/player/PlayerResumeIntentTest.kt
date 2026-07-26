package com.tvonnet.debridxtreamiptv.player

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.tvonnet.debridxtreamiptv.data.model.ContentType
import com.tvonnet.debridxtreamiptv.player.stabilized.PlayerActivity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Roadmap B2.3 — the `startPositionMs` trap.
 *
 * "Resume" replayed from zero more than once in this project, and every time the cause was the
 * same: a launch path built its `PlayerActivity.createIntent(...)` without passing
 * `startPositionMs`, so the player started at 0 with no error anywhere. The carrying itself is
 * what this pins — the intent must transport the position, and `BasePlayerFragment` reads it back
 * with `getLongExtra(EXTRA_START_POSITION, 0L)`, so an intent that never carried it is
 * indistinguishable from "start at the beginning".
 *
 * A test cannot see which *call site* forgot the argument, so the surviving rule is: when a caller
 * does pass a position it must arrive intact, together with the identity extras the resume path
 * needs.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class PlayerResumeIntentTest {

    private lateinit var context: Context

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
    }

    @Test
    fun `a resume position survives the intent`() {
        val intent = PlayerActivity.createIntent(
            context = context,
            streamUrl = "http://provider.test/movie/u/p/1.mp4",
            startPositionMs = 1_845_000L,
            contentId = "m1",
            contentType = ContentType.MOVIE
        )

        assertEquals(
            "this is the value BasePlayerFragment reads to seek — dropping it replays from 0",
            1_845_000L,
            intent.getLongExtra(PlayerActivity.EXTRA_START_POSITION, 0L)
        )
    }

    @Test
    fun `no resume position means the extra is absent, which reads back as start-from-zero`() {
        val intent = PlayerActivity.createIntent(
            context = context,
            streamUrl = "http://provider.test/movie/u/p/1.mp4",
            contentId = "m1",
            contentType = ContentType.MOVIE
        )

        assertFalse(intent.hasExtra(PlayerActivity.EXTRA_START_POSITION))
        assertEquals(0L, intent.getLongExtra(PlayerActivity.EXTRA_START_POSITION, 0L))
    }

    /** An episode resume also needs the identity that drives next-episode + history writes. */
    @Test
    fun `an episode resume carries its series identity alongside the position`() {
        val intent = PlayerActivity.createIntent(
            context = context,
            streamUrl = "http://provider.test/series/u/p/9.mkv",
            startPositionMs = 420_000L,
            contentId = "ep-9",
            contentType = ContentType.EPISODE,
            seriesId = "9911",
            seriesTitle = "Breaking Bad",
            seasonNumber = 1,
            episodeNumber = 2
        )

        assertEquals(420_000L, intent.getLongExtra(PlayerActivity.EXTRA_START_POSITION, 0L))
        assertEquals("9911", intent.getStringExtra(PlayerActivity.EXTRA_SERIES_ID))
        assertEquals("Breaking Bad", intent.getStringExtra(PlayerActivity.EXTRA_SERIES_TITLE))
        assertEquals(1, intent.getIntExtra(PlayerActivity.EXTRA_SEASON_NUM, -1))
        assertEquals(2, intent.getIntExtra(PlayerActivity.EXTRA_EPISODE_NUM, -1))
    }

    /**
     * A Debrid resume is the path that has to re-resolve: `BasePlayerFragment` treats
     * "debrid + startPositionMs > 0" as an expired link and resolves afresh, which only works if
     * the hash/magnet travelled with the position.
     */
    @Test
    fun `a debrid resume carries what a re-resolve needs`() {
        val intent = PlayerActivity.createIntent(
            context = context,
            streamUrl = "",
            startPositionMs = 600_000L,
            contentId = "tt1375666",
            contentType = ContentType.MOVIE,
            debridInfoHash = "abc123",
            debridMagnet = "magnet:?xt=urn:btih:abc123",
            debridFileIdx = 3
        )

        assertEquals(600_000L, intent.getLongExtra(PlayerActivity.EXTRA_START_POSITION, 0L))
        assertEquals("abc123", intent.getStringExtra(PlayerActivity.EXTRA_DEBRID_INFOHASH))
        assertTrue(intent.hasExtra(PlayerActivity.EXTRA_DEBRID_MAGNET))
        assertEquals(3, intent.getIntExtra(PlayerActivity.EXTRA_DEBRID_FILE_IDX, -1))
    }

    /** Live never resumes — but it must still carry the hand-off flag and its channel list. */
    @Test
    fun `a live launch carries the shared-player flag`() {
        val intent = PlayerActivity.createIntent(
            context = context,
            streamUrl = "http://provider.test/live/u/p/5.ts",
            contentId = "5",
            contentType = ContentType.LIVE_TV,
            liveCategoryId = "100",
            liveChannelIds = arrayListOf("4", "5", "6"),
            sharedLivePlayer = true
        )

        assertTrue(intent.getBooleanExtra(PlayerActivity.EXTRA_SHARED_LIVE_PLAYER, false))
        assertEquals("100", intent.getStringExtra(PlayerActivity.EXTRA_LIVE_CATEGORY_ID))
        assertEquals(
            listOf("4", "5", "6"),
            intent.getStringArrayListExtra(PlayerActivity.EXTRA_LIVE_CHANNEL_IDS)
        )
        assertFalse("live has no resume position", intent.hasExtra(PlayerActivity.EXTRA_START_POSITION))
    }
}
