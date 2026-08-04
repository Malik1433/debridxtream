package com.tvonnet.debridxtreamiptv.data.debrid.repository

import com.tvonnet.debridxtreamiptv.data.debrid.model.AddonSourceType
import com.tvonnet.debridxtreamiptv.data.debrid.model.AddonStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * H0 (SOURCE_LIST_QUALITY_PLAN P0): identity must collapse the same real file across
 * addons and must NEVER collapse two different files.
 */
class StreamIdentityTest {

    private val hash = "a94a8fe5ccb19ba61c4c0873d391e987982fbbd3"

    private fun stream(
        source: AddonSourceType = AddonSourceType.STREMIO,
        title: String? = "Movie.2026.1080p.WEBRip.x264.mkv",
        url: String? = null,
        infoHash: String? = null,
        magnet: String? = null,
        sizeBytes: Long? = null,
        extras: Map<String, Any?> = emptyMap(),
    ) = AddonStream(
        source = source,
        title = title,
        url = url,
        infoHash = infoHash,
        magnet = magnet,
        sizeBytes = sizeBytes,
        seeders = null,
        peers = null,
        quality = null,
        languages = null,
        subtitles = null,
        extras = extras,
    )

    // ── Strong: the same torrent from four addons is ONE identity ──

    @Test
    fun `same torrent from four addons collapses to one identity`() {
        val byField = stream(source = AddonSourceType.STREMIO, infoHash = hash)
        val byUpperField = stream(source = AddonSourceType.MEDIA_FUSION, infoHash = hash.uppercase())
        val byMagnet = stream(source = AddonSourceType.DYNAMIC, magnet = "magnet:?xt=urn:btih:$hash&dn=x")
        val byUrl = stream(source = AddonSourceType.ZILEAN, url = "https://proxy.example/stream/$hash/0/file.mkv")

        val keys = listOf(byField, byUpperField, byMagnet, byUrl)
            .map { StreamIdentity.fileIdentity(it) }
            .map { (it as FileIdentity.Strong).hash }
            .distinct()
        assertEquals(listOf(hash), keys)
    }

    @Test
    fun `magnet and direct url of one hash are the same identity`() {
        val magnet = StreamIdentity.fileIdentity(stream(magnet = "magnet:?xt=urn:btih:$hash"))
        val direct = StreamIdentity.fileIdentity(stream(url = "https://proxy.example/$hash/0/movie.mkv"))
        assertEquals((magnet as FileIdentity.Strong).hash, (direct as FileIdentity.Strong).hash)
    }

    @Test
    fun `different file indices in one torrent are different identities`() {
        val ep1 = StreamIdentity.fileIdentity(stream(infoHash = hash, extras = mapOf("fileIdx" to 0)))
        val ep2 = StreamIdentity.fileIdentity(stream(infoHash = hash, extras = mapOf("fileIdx" to 1)))
        assertNotEquals(ep1.key, ep2.key)
    }

    @Test
    fun `url path yields hash and file index`() {
        val parsed = StreamIdentity.urlHashAndIdx("https://p.example/api/v1/$hash/3/Some.File.mkv")
        assertEquals(hash, parsed?.first)
        assertEquals(3, parsed?.second)
    }

    @Test
    fun `url with -1 index keeps the hash but drops the index`() {
        val parsed = StreamIdentity.urlHashAndIdx("https://p.example/$hash/-1")
        assertEquals(hash, parsed?.first)
        assertNull(parsed?.second)
    }

    @Test
    fun `plain http url without a hash is not strong`() {
        val id = StreamIdentity.fileIdentity(
            stream(url = "https://cdn.example/movies/12345/master.m3u8", sizeBytes = 700L * 1024 * 1024)
        )
        assertTrue(id is FileIdentity.Weak)
    }

    // ── Weak: name AND size must both agree ──

    @Test
    fun `two different-sized 1080p releases stay two identities`() {
        val a = StreamIdentity.fileIdentity(stream(title = "Movie 2026 1080p", sizeBytes = 2_000L * 1024 * 1024))
        val b = StreamIdentity.fileIdentity(stream(title = "Movie 2026 1080p", sizeBytes = 5_000L * 1024 * 1024))
        assertNotEquals(a.key, b.key)
    }

    @Test
    fun `same name and same size bucket collapse`() {
        val a = StreamIdentity.fileIdentity(stream(title = "Movie.2026.1080p.mkv", sizeBytes = 2_000L * 1024 * 1024))
        val b = StreamIdentity.fileIdentity(stream(title = "movie 2026 1080p MKV", sizeBytes = 2_000L * 1024 * 1024 + 1))
        assertEquals(a.key, b.key)
    }

    // ── Unique: placeholders never collapse ──

    @Test
    fun `placeholder-titled sizeless streams are all distinct`() {
        val streams = listOf(
            stream(title = "Stremio Stream", sizeBytes = null),
            stream(title = "Stremio Stream", sizeBytes = null),
            stream(title = "Unknown", sizeBytes = null),
            stream(title = null, sizeBytes = 100L),
        )
        val keys = streams.map { StreamIdentity.fileIdentity(it).key }
        assertEquals(keys.size, keys.distinct().size)
        assertTrue(streams.map { StreamIdentity.fileIdentity(it) }.all { it is FileIdentity.Unique })
    }

    @Test
    fun `sized but placeholder-titled stream is unique not weak`() {
        val id = StreamIdentity.fileIdentity(stream(title = "Unknown", sizeBytes = 900L * 1024 * 1024))
        assertTrue(id is FileIdentity.Unique)
    }

    // ── H5b: scoped-resume matching for URL-hash rows ──

    @Test
    fun `stream with the hash only in its url matches that hash as stable identity`() {
        val byUrl = stream(url = "https://proxy.example/stream/$hash/0/file.mkv")
        assertTrue(streamMatchesStableIdentity(byUrl, hash))
    }

    @Test
    fun `url-hash stream still matches a pre-H5b title-hash id`() {
        val byUrl = stream(url = "https://proxy.example/stream/$hash/0/file.mkv")
        val legacyId = byUrl.title.hashCode().toString()
        assertTrue(streamMatchesStableIdentity(byUrl, legacyId))
    }

    @Test
    fun `url-hash stream does not match a different hash`() {
        val byUrl = stream(url = "https://proxy.example/stream/$hash/0/file.mkv")
        assertTrue(!streamMatchesStableIdentity(byUrl, "b".repeat(40)))
    }
}
