package com.tvonnet.debridxtreamiptv.data.debrid.repository

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Roadmap C6 — the pure URL/magnet vocabulary the debrid playback path rests on.
 *
 * The error-target list is the reason this file exists. Addon proxies answer "the torrent is not
 * cached yet" with a 302 whose *target path* says so, and missing one of those spellings hands the
 * player an error page to buffer on forever — the exact "picked a source, got an endless spinner"
 * symptom from the 2026-07-16 empty-sources audit. Each marker below was observed in the wild, so
 * they are pinned as data rather than left to be "simplified" later.
 */
class DebridUrlRulesTest {

    // ── addon proxy identification ─────────────────────────────────────────────

    @Test
    fun `known proxy playback hosts are recognised from the url`() {
        assertTrue(DebridUrlRules.isAddonProxyPlaybackUrl("https://mediafusion.elfhosted.com/playback/abc/v.mkv"))
        assertTrue(DebridUrlRules.isAddonProxyPlaybackUrl("https://AIOStreams.elfhosted.com/x/v.mkv"))
        // MediaFusion only counts as a proxy on its /playback path — its other endpoints are not.
        assertFalse(DebridUrlRules.isAddonProxyPlaybackUrl("https://mediafusion.elfhosted.com/manifest.json"))
        assertFalse(DebridUrlRules.isAddonProxyPlaybackUrl("https://real-debrid.com/d/abc.mkv"))
    }

    @Test
    fun `proxies are also recognised by provider or source name`() {
        assertTrue(DebridUrlRules.isAddonProxySource("MediaFusion"))
        assertTrue(DebridUrlRules.isAddonProxySource("AIO Streams"))
        assertTrue(DebridUrlRules.isAddonProxySource("aiostreams"))
        assertTrue(DebridUrlRules.isAddonProxySource("AIO"))
        assertFalse(DebridUrlRules.isAddonProxySource("Torrentio"))
        assertFalse(DebridUrlRules.isAddonProxySource(null))
        assertFalse(DebridUrlRules.isAddonProxySource("  "))
    }

    // ── terminal vs retryable ──────────────────────────────────────────────────

    @Test
    fun `only never-going-to-work statuses are terminal`() {
        listOf(401, 403, 404, 410, 451).forEach {
            assertTrue("$it must be terminal", DebridUrlRules.isTerminalAddonProxyStatus(it))
        }
        // 5xx and rate limiting are transient: the source must stay in the list.
        listOf(200, 204, 429, 500, 502, 503).forEach {
            assertFalse("$it must NOT be terminal", DebridUrlRules.isTerminalAddonProxyStatus(it))
        }
    }

    @Test
    fun `a redirect target that admits the torrent is not cached is an error target`() {
        listOf(
            "/error?reason=torrent_not_downloaded",
            "/not-ready",
            "/notcached",
            "/x?reason=not_cached",
            "/error?reason=api_exception",
            "/rate-limit",
            "/limit reached",
            "/quota-exceeded",
            "/provider_error",
            "/invalid-token",
            "/static/error.mp4",
            "/error",
        ).forEach {
            assertTrue("'$it' must be treated as an error target", DebridUrlRules.isAddonProxyErrorTarget(it))
        }
    }

    @Test
    fun `a real stream redirect is not mistaken for an error target`() {
        assertFalse(DebridUrlRules.isAddonProxyErrorTarget("https://cdn.real-debrid.com/d/ABCD/Show.S01E01.mkv"))
        assertFalse(DebridUrlRules.isAddonProxyErrorTarget(null))
    }

    @Test
    fun `a document content type means the proxy answered with an error page`() {
        assertTrue(DebridUrlRules.isAddonProxyErrorContentType("application/json; charset=utf-8"))
        assertTrue(DebridUrlRules.isAddonProxyErrorContentType("text/html"))
        assertTrue(DebridUrlRules.isAddonProxyErrorContentType("text/plain"))
        assertFalse(DebridUrlRules.isAddonProxyErrorContentType("video/mp4"))
        assertFalse(DebridUrlRules.isAddonProxyErrorContentType("application/octet-stream"))
    }

    // ── direct stream URLs ─────────────────────────────────────────────────────

    @Test
    fun `addon stream paths and plain media urls are already playable`() {
        assertTrue(DebridUrlRules.isDirectStreamUrl("https://mediafusion.elfhosted.com/playback/a/b"))
        assertTrue(DebridUrlRules.isDirectStreamUrl("https://host/stream/abc"))
        assertTrue(DebridUrlRules.isDirectStreamUrl("https://host/video.mkv"))
        assertTrue(DebridUrlRules.isDirectStreamUrl("https://host/video.m3u8"))
        // The extension is read past the query string and fragment.
        assertTrue(DebridUrlRules.isDirectStreamUrl("https://host/video.mp4?token=x#t=10"))
        // .m4v must be accepted here AND by TorrentFileMatcher.isVideoFile — the two disagreeing
        // is what stalled a .m4v-only torrent in waiting_files_selection (A2).
        assertTrue(DebridUrlRules.isDirectStreamUrl("https://host/video.m4v"))
    }

    @Test
    fun `magnets, torrents and non-media urls are not direct streams`() {
        assertFalse(DebridUrlRules.isDirectStreamUrl("magnet:?xt=urn:btih:abcdef"))
        assertFalse(DebridUrlRules.isDirectStreamUrl("https://host/file.torrent"))
        assertFalse(DebridUrlRules.isDirectStreamUrl("https://host/api/status"))
    }

    // ── source identity ────────────────────────────────────────────────────────

    @Test
    fun `the source key is the info hash, lowercased`() {
        assertEquals("abcdef0123", DebridUrlRules.normalizeSourceKey("  ABCDEF0123 ", null))
    }

    @Test
    fun `without an info hash the key comes from the magnet's btih`() {
        val hash = "0123456789abcdef0123456789abcdef01234567"
        assertEquals(hash, DebridUrlRules.normalizeSourceKey(null, "magnet:?xt=urn:btih:${hash.uppercase()}&dn=x"))
    }

    @Test
    fun `two magnets sharing a long prefix never collapse onto one key`() {
        // The key must be the FULL string, never a truncated prefix — a shared prefix would put
        // two different sources on the same rate-limit and resolution cache entry.
        val a = "https://host/verylongidenticalprefix/aaaa"
        val b = "https://host/verylongidenticalprefix/bbbb"
        assertTrue(DebridUrlRules.normalizeSourceKey(null, a) != DebridUrlRules.normalizeSourceKey(null, b))
    }

    @Test
    fun `nothing identifiable yields no key rather than an empty one`() {
        assertNull(DebridUrlRules.normalizeSourceKey(null, null))
        assertNull(DebridUrlRules.normalizeSourceKey("  ", "  "))
    }

    @Test
    fun `hash extraction accepts both 32 and 40 character info hashes`() {
        assertEquals("0123456789abcdef0123456789abcdef", DebridUrlRules.extractHashFromMagnet("magnet:?xt=urn:btih:0123456789ABCDEF0123456789abcdef"))
        assertNull(DebridUrlRules.extractHashFromMagnet("magnet:?xt=urn:btih:tooshort"))
    }
}
