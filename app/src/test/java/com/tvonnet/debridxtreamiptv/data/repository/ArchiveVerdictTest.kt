package com.tvonnet.debridxtreamiptv.data.repository

import com.tvonnet.debridxtreamiptv.data.model.XtreamEpgListing
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ArchiveVerdictTest {

    private fun listing(hasArchive: Int?) = XtreamEpgListing(title = "t", hasArchive = hasArchive)

    @Test
    fun `absent listings are unknown, not no-archive`() {
        assertNull(archiveVerdict(null))
    }

    @Test
    fun `empty listings are unknown, not no-archive`() {
        assertNull(archiveVerdict(emptyList()))
    }

    @Test
    fun `one archived programme means the channel keeps recordings`() {
        assertEquals(true, archiveVerdict(listOf(listing(0), listing(1), listing(null))))
    }

    @Test
    fun `listings with no archived programme mean no archive`() {
        assertEquals(false, archiveVerdict(listOf(listing(0), listing(null))))
    }
}
