package com.tvonnet.debridxtreamiptv.data.prefs

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.tvonnet.debridxtreamiptv.data.model.ContentType
import com.tvonnet.debridxtreamiptv.data.model.ContinueWatchingItem
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class WatchHistoryPreferencesTest {
    private lateinit var context: Context
    private lateinit var prefs: WatchHistoryPreferences

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        context.getSharedPreferences("watch_history", Context.MODE_PRIVATE)
            .edit()
            .clear()
            .commit()
        prefs = WatchHistoryPreferences(context)
    }

    @Test
    fun saveContinueWatching_dedupesByStableSeriesKey_notByVolatileContentId() {
        val first = continueItem(
            contentId = "old-episode-id",
            title = "Pilot",
            seriesId = "series-42",
            seriesTitle = "Sample Show"
        )
        val second = continueItem(
            contentId = "new-episode-id",
            title = "Episode 2",
            seriesId = "series-42",
            seriesTitle = "Sample Show"
        )

        prefs.saveContinueWatchingItem(first)
        prefs.saveContinueWatchingItem(second)

        val list = prefs.getContinueWatchingList()
        assertEquals(1, list.size)
        assertEquals("new-episode-id", list.first().contentId)
    }

    @Test
    fun clearStatusFlow_removeThenSuppress_preventsImmediateReAdd_forSameStableKey() {
        val original = continueItem(
            contentId = "content-a",
            title = "Episode 1",
            seriesId = "series-77",
            seriesTitle = "Guard Show"
        )
        val sameLogicalItemNewId = continueItem(
            contentId = "content-b",
            title = "Episode 1 Reloaded",
            seriesId = "series-77",
            seriesTitle = "Guard Show"
        )

        prefs.saveContinueWatchingItem(original)
        prefs.removeContinueWatchingItem(original)
        prefs.suppressContinueWatchingWrite(original)
        prefs.saveContinueWatchingItem(sameLogicalItemNewId)

        assertTrue(prefs.getContinueWatchingList().isEmpty())

        prefs.saveContinueWatchingItem(sameLogicalItemNewId)
        assertEquals(1, prefs.getContinueWatchingList().size)
        assertEquals("content-b", prefs.getContinueWatchingList().first().contentId)
    }

    private fun continueItem(
        contentId: String,
        title: String,
        seriesId: String? = null,
        seriesTitle: String? = null
    ): ContinueWatchingItem {
        return ContinueWatchingItem(
            contentId = contentId,
            contentType = ContentType.EPISODE,
            title = title,
            posterUrl = "https://example.com/poster.jpg",
            backdropUrl = "https://example.com/backdrop.jpg",
            currentPosition = 30_000L,
            totalDuration = 1_000_000L,
            lastWatchedTimestamp = 123456789L,
            streamUrl = "https://example.com/stream.m3u8",
            seriesId = seriesId,
            seriesTitle = seriesTitle,
            seasonNumber = 1,
            episodeNumber = 1
        )
    }
}
