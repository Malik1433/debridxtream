package com.tvonnet.debridxtreamiptv.data.debrid.repository

import com.tvonnet.debridxtreamiptv.data.debrid.model.AddonSourceType
import com.tvonnet.debridxtreamiptv.data.debrid.model.AddonStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * H1 (SOURCE_LIST_QUALITY_PLAN P1): the addon flood collapses to one row per real file,
 * copies are RETAINED as alternates, and no two groups share an identity.
 */
class StreamGroupingTest {

    private val hashA = "a94a8fe5ccb19ba61c4c0873d391e987982fbbd3"
    private val hashB = "b0000000000000000000000000000000000000bb"
    private val hashC = "c1111111111111111111111111111111111111cc"

    private fun stream(
        source: AddonSourceType,
        infoHash: String? = null,
        magnet: String? = null,
        url: String? = null,
        title: String? = "Movie.2026.1080p.WEBRip.mkv",
        sizeBytes: Long? = null,
        seeders: Int? = null,
        quality: String? = null,
    ) = AddonStream(
        source = source,
        title = title,
        url = url,
        infoHash = infoHash,
        magnet = magnet,
        sizeBytes = sizeBytes,
        seeders = seeders,
        peers = null,
        quality = quality,
        languages = null,
        subtitles = null,
    )

    /** A captured-shape fixture: 3 real files offered as 8 rows across 4 addons. */
    private fun multiAddonFixture(): List<AddonStream> = listOf(
        // File A: 4 copies (field hash, magnet, url-hash, uppercase field)
        stream(AddonSourceType.STREMIO, infoHash = hashA, sizeBytes = 2_000_000_000, seeders = 40, quality = "1080p"),
        stream(AddonSourceType.MEDIA_FUSION, url = "https://proxy.example/$hashA/0/movie.mkv", sizeBytes = 2_000_000_000),
        stream(AddonSourceType.DYNAMIC, magnet = "magnet:?xt=urn:btih:$hashA&dn=x"),
        stream(AddonSourceType.ZILEAN, infoHash = hashA.uppercase()),
        // File B: 2 copies
        stream(AddonSourceType.STREMIO, infoHash = hashB, quality = "4K"),
        stream(AddonSourceType.DYNAMIC, magnet = "magnet:?xt=urn:btih:$hashB"),
        // File C: 2 rows but DIFFERENT files of one torrent (season pack indices)
        stream(AddonSourceType.STREMIO, url = "https://proxy.example/$hashC/1/S01E01.mkv"),
        stream(AddonSourceType.STREMIO, url = "https://proxy.example/$hashC/2/S01E02.mkv"),
    )

    @Test
    fun `row count drops and no two groups share an identity`() {
        val groups = groupStreamsByFile(multiAddonFixture())
        // A, B, C#1, C#2 = 4 real files from 8 rows.
        assertEquals(4, groups.size)
        val keys = groups.map { it.identity.key }
        assertEquals(keys.size, keys.distinct().size)
    }

    @Test
    fun `copies are retained as alternates with the count intact`() {
        val groups = groupStreamsByFile(multiAddonFixture())
        val groupA = groups.first { (it.identity as? FileIdentity.Strong)?.hash == hashA }
        assertEquals(3, groupA.alternates.size)
        // Nothing is lost: primary + alternates == all 4 copies.
        assertEquals(4, groupA.alternates.size + 1)
    }

    @Test
    fun `primary prefers richer metadata`() {
        val groups = groupStreamsByFile(multiAddonFixture())
        val groupA = groups.first { (it.identity as? FileIdentity.Strong)?.hash == hashA }
        // The STREMIO copy carries size+seeders+quality — highest metadataScore.
        assertEquals(AddonSourceType.STREMIO, groupA.primary.source)
    }

    @Test
    fun `metadata tie breaks toward a direct url over a magnet`() {
        val magnetCopy = stream(AddonSourceType.DYNAMIC, magnet = "magnet:?xt=urn:btih:$hashB")
        val directCopy = stream(AddonSourceType.STREMIO, url = "https://proxy.example/$hashB/0/movie.mkv")
        val groups = groupStreamsByFile(listOf(magnetCopy, directCopy))
        assertEquals(1, groups.size)
        // Same metadataScore would be false here (url adds +1) — force a tie by
        // giving the magnet copy one extra point via seeders.
        val tied = groupStreamsByFile(
            listOf(
                magnetCopy.copy(seeders = 10),
                directCopy,
            )
        )
        assertEquals(1, tied.size)
        assertTrue(!tied.first().primary.url.isNullOrBlank())
    }

    @Test
    fun `dedup wrapper returns primaries only`() {
        val deduped = deduplicateStreams(multiAddonFixture())
        assertEquals(4, deduped.size)
    }

    @Test
    fun `unique placeholders never collapse`() {
        val placeholders = listOf(
            stream(AddonSourceType.STREMIO, title = "Stremio Stream"),
            stream(AddonSourceType.STREMIO, title = "Stremio Stream"),
        )
        assertEquals(2, groupStreamsByFile(placeholders).size)
    }

    @Test
    fun `alternates map is keyed by primary`() {
        val groups = groupStreamsByFile(multiAddonFixture())
        val map = alternatesByPrimary(groups)
        val groupA = groups.first { (it.identity as? FileIdentity.Strong)?.hash == hashA }
        assertEquals(groupA.alternates, map[groupA.primary])
        // Groups without copies are absent.
        val soloGroups = groups.filter { it.alternates.isEmpty() }
        soloGroups.forEach { assertTrue(it.primary !in map) }
    }
}
