package com.tvonnet.debridxtreamiptv.ui.live

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Roadmap B3 — the zap list handed to fullscreen (LF-3).
 *
 * Channel up/down walks this list, so its order *is* the user-visible channel order. It must follow
 * the provider's category order (the cached list) rather than however far the paging adapter had
 * scrolled, and it must always contain the channel being launched — otherwise the very first
 * channel-up lands somewhere unrelated.
 */
class LiveZapListTest {

    @Test
    fun `the cached category order leads`() {
        val merged = LiveZapList.merge(
            cachedIds = listOf("1", "2", "3"),
            snapshotIds = listOf("3", "2", "1"),
            currentStreamId = "2"
        )

        assertEquals(
            "the provider's order is the channel order the user expects",
            listOf("1", "2", "3"),
            merged
        )
    }

    @Test
    fun `channels only the adapter has seen are appended, not dropped`() {
        val merged = LiveZapList.merge(
            cachedIds = listOf("1", "2"),
            snapshotIds = listOf("2", "9"),
            currentStreamId = "1"
        )

        assertEquals(listOf("1", "2", "9"), merged)
    }

    /** Favourites and search have no cached category list; the adapter is all there is. */
    @Test
    fun `with no cached list the adapter order is used`() {
        val merged = LiveZapList.merge(
            cachedIds = emptyList(),
            snapshotIds = listOf("7", "8", "9"),
            currentStreamId = "8"
        )

        assertEquals(listOf("7", "8", "9"), merged)
    }

    @Test
    fun `the launching channel is always present`() {
        val merged = LiveZapList.merge(
            cachedIds = listOf("1", "2"),
            snapshotIds = listOf("1", "2"),
            currentStreamId = "42"
        )

        assertTrue("zapping from a channel that is not in the list would jump elsewhere", "42" in merged)
        assertEquals(listOf("1", "2", "42"), merged)
    }

    @Test
    fun `duplicates never survive the merge`() {
        val merged = LiveZapList.merge(
            cachedIds = listOf("1", "1", "2"),
            snapshotIds = listOf("2", "2", "3"),
            currentStreamId = "3"
        )

        assertEquals(listOf("1", "2", "3"), merged)
        assertEquals(merged.size, merged.toSet().size)
    }

    @Test
    fun `an empty everything yields an empty list rather than a null`() {
        assertTrue(LiveZapList.merge(emptyList(), emptyList(), null).isEmpty())
    }
}
