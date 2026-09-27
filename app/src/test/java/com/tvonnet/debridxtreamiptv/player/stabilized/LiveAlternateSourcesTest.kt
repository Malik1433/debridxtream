package com.tvonnet.debridxtreamiptv.player.stabilized

import com.tvonnet.debridxtreamiptv.data.model.XtreamStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LiveAlternateSourcesTest {

    private fun stream(id: String, name: String) = XtreamStream(
        num = null,
        name = name,
        stream_type = "live",
        stream_id = id,
        stream_icon = null,
        epg_channel_id = null,
        added = null,
        category_id = "104",
        category_ids = null,
        container_extension = null,
        custom_sid = null,
        direct_source = null,
        tv_archive = null,
        tv_archive_duration = null,
    )

    private val catalogue = listOf(
        stream("1", "PK: PTV SPORTS"),
        stream("2", "PK: PTV SPORTS HD"),
        stream("3", "PK | PTV Sports FHD"),
        stream("4", "PK: A SPORTS HD"),
        stream("5", "PK: TEN SPORTS"),
    )

    @Test
    fun `only the same channel is offered`() {
        val alternates = LiveAlternateSources.rank(
            currentName = "PK: PTV SPORTS",
            currentStreamId = "1",
            candidates = catalogue,
            alreadyTried = emptySet(),
        )
        assertEquals(listOf("3", "2"), alternates.map { it.stream_id })
    }

    @Test
    fun `the feed that just failed is never offered back`() {
        val alternates = LiveAlternateSources.rank(
            currentName = "PK: PTV SPORTS HD",
            currentStreamId = "2",
            candidates = catalogue,
            alreadyTried = emptySet(),
        )
        assertTrue(alternates.none { it.stream_id == "2" })
    }

    @Test
    fun `a feed already tried in this sitting does not come round again`() {
        // Without this the app would loop between two dead feeds for ever.
        val alternates = LiveAlternateSources.rank(
            currentName = "PK: PTV SPORTS",
            currentStreamId = "1",
            candidates = catalogue,
            alreadyTried = setOf("3"),
        )
        assertEquals(listOf("2"), alternates.map { it.stream_id })
    }

    @Test
    fun `when everything has been tried the list is empty, and the caller must give up honestly`() {
        val alternates = LiveAlternateSources.rank(
            currentName = "PK: PTV SPORTS",
            currentStreamId = "1",
            candidates = catalogue,
            alreadyTried = setOf("2", "3"),
        )
        assertTrue(alternates.isEmpty())
    }

    @Test
    fun `the search term is the name a search can actually match`() {
        assertEquals("PTV SPORTS", LiveAlternateSources.searchTerm("|EN| PTV SPORTS"))
    }

    // Match night: a feed that is too SLOW (not dead) must be replaced by a lighter one first.
    private val tiers = listOf(
        stream("10", "UK: SKY SPORTS MAIN EVENT"),
        stream("11", "UK: SKY SPORTS MAIN EVENT 4K"),
        stream("12", "UK: SKY SPORTS MAIN EVENT FHD"),
        stream("13", "UK: SKY SPORTS MAIN EVENT HD"),
        stream("14", "UK: SKY SPORTS MAIN EVENT SD"),
    )

    @Test
    fun `under load the lighter feeds go first and the bare name last`() {
        val ids = LiveAlternateSources.rank(
            currentName = "UK: SKY SPORTS MAIN EVENT FHD",
            currentStreamId = "12",
            candidates = tiers,
            alreadyTried = emptySet(),
            underLoad = true,
        ).map { it.stream_id }
        assertEquals(listOf("14", "13", "11", "10"), ids)
    }

    @Test
    fun `a dead feed keeps the heavier-first order`() {
        val ids = LiveAlternateSources.rank(
            currentName = "UK: SKY SPORTS MAIN EVENT FHD",
            currentStreamId = "12",
            candidates = tiers,
            alreadyTried = emptySet(),
        ).map { it.stream_id }
        assertEquals(listOf("11", "13", "10", "14"), ids)
    }
}
