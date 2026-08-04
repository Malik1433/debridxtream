package com.tvonnet.debridxtreamiptv.ui.sources

import com.tvonnet.debridxtreamiptv.data.model.XtreamVodInfo
import com.tvonnet.debridxtreamiptv.data.repository.DebridCacheStatus
import com.tvonnet.debridxtreamiptv.data.repository.MovieSource
import org.junit.Assert.*
import org.junit.Test

/**
 * Phase 8: unit tests for the debrid source ranking the user actually sees. SourceSorter is a pure
 * object (score/sort/isCached), so no Room/Robolectric needed — this pins the scoring weights
 * (verified language +100, claimed +80, MULTI +60, cached +50, quality tier, size penalty)
 * and the stable descending sort.
 */
class SourceSorterTest {

    private fun emptyVod(): XtreamVodInfo = XtreamVodInfo(
        num = null, name = null, stream_type = null, stream_id = null, stream_icon = null,
        rating = null, rating_5based = null, added = null, category_id = null, category_ids = null,
        container_extension = null, custom_sid = null, direct_source = null, releaseDate = null,
        duration = null, plot = null, cast = null, director = null, genre = null,
        youtube_trailer = null, cover = null, rating_imdb = null
    )

    private fun source(
        label: String = "s",
        languages: List<String>? = null,
        quality: String? = null,
        sizeBytes: Long? = null,
        isCached: Boolean? = null,
        cacheStatus: DebridCacheStatus = DebridCacheStatus.UNKNOWN,
        languagesVerified: Boolean = false
    ) = MovieSource(
        stream = emptyVod(),
        category = null,
        label = label,
        isPrimary = false,
        languages = languages,
        quality = quality,
        sizeBytes = sizeBytes,
        isCached = isCached,
        cacheStatus = cacheStatus,
        languagesVerified = languagesVerified
    )

    // ── score ────────────────────────────────────────────────────────────────────────────────────

    @Test
    fun `claimed preferred-language match adds 80`() {
        val withLang = source(languages = listOf("en"))
        val withoutLang = source(languages = listOf("fr"))

        assertEquals(80.0, SourceSorter.score(withLang, "EN") - SourceSorter.score(withoutLang, "EN"), 0.001)
    }

    // H4 tiering: verified match 100 > claimed match 80 > MULTI-claimed 60 > nothing.
    @Test
    fun `verified match outranks claimed match`() {
        val verified = source(languages = listOf("hi"), languagesVerified = true)
        val claimed = source(languages = listOf("hi"))
        val none = source(languages = listOf("fr"))

        assertEquals(100.0, SourceSorter.score(verified, "HI") - SourceSorter.score(none, "HI"), 0.001)
        assertEquals(80.0, SourceSorter.score(claimed, "HI") - SourceSorter.score(none, "HI"), 0.001)
    }

    // H3 tiering: exact claimed match > MULTI-claimed 60 > nothing.
    @Test
    fun `MULTI-claimed row ranks between exact match and none`() {
        val exact = source(languages = listOf("hi"))
        val multi = source(languages = listOf("multi"))
        val none = source(languages = listOf("fr"))

        assertEquals(80.0, SourceSorter.score(exact, "HI") - SourceSorter.score(none, "HI"), 0.001)
        assertEquals(60.0, SourceSorter.score(multi, "HI") - SourceSorter.score(none, "HI"), 0.001)
    }

    // ── H9: the secondary priority ("Hindi na mile to German") ──

    @Test
    fun `secondary-language row ranks between primary and MULTI`() {
        val primary = source(languages = listOf("hi"))
        val secondary = source(languages = listOf("de"))
        val multi = source(languages = listOf("multi"))
        val none = source(languages = listOf("fr"))

        assertEquals(80.0, SourceSorter.score(primary, "HI", "DE") - SourceSorter.score(none, "HI", "DE"), 0.001)
        assertEquals(70.0, SourceSorter.score(secondary, "HI", "DE") - SourceSorter.score(none, "HI", "DE"), 0.001)
        assertEquals(60.0, SourceSorter.score(multi, "HI", "DE") - SourceSorter.score(none, "HI", "DE"), 0.001)
    }

    @Test
    fun `verified secondary still ranks below claimed primary`() {
        val claimedPrimary = source(languages = listOf("hi"))
        val verifiedSecondary = source(languages = listOf("de"), languagesVerified = true)

        assertTrue(
            SourceSorter.score(claimedPrimary, "HI", "DE") >
                SourceSorter.score(verifiedSecondary, "HI", "DE")
        )
        assertEquals(75.0, SourceSorter.score(verifiedSecondary, "HI", "DE") - SourceSorter.score(source(languages = listOf("fr")), "HI", "DE"), 0.001)
    }

    @Test
    fun `a row carrying BOTH priorities scores as a primary match`() {
        val both = source(languages = listOf("hi", "de"))
        val primaryOnly = source(languages = listOf("hi"))
        assertEquals(SourceSorter.score(primaryOnly, "HI", "DE"), SourceSorter.score(both, "HI", "DE"), 0.001)
    }

    @Test
    fun `NONE secondary changes nothing`() {
        val german = source(languages = listOf("de"))
        assertEquals(SourceSorter.score(german, "HI"), SourceSorter.score(german, "HI", "NONE"), 0.001)
    }

    // H3: honestly-unknown languages (empty list) get no boost — and no penalty.
    @Test
    fun `unknown languages score like no match`() {
        val unknown = source(languages = emptyList())
        val none = source(languages = listOf("fr"))
        assertEquals(SourceSorter.score(none, "EN"), SourceSorter.score(unknown, "EN"), 0.001)
    }

    @Test
    fun `ALL preferred language disables the language boost`() {
        val english = source(languages = listOf("en"))
        val french = source(languages = listOf("fr"))

        assertEquals(SourceSorter.score(french, "ALL"), SourceSorter.score(english, "ALL"), 0.001)
    }

    @Test
    fun `cached adds 50`() {
        val cached = source(cacheStatus = DebridCacheStatus.VERIFIED_CACHED)
        val notCached = source(cacheStatus = DebridCacheStatus.NOT_CACHED)

        assertEquals(50.0, SourceSorter.score(cached, "ALL") - SourceSorter.score(notCached, "ALL"), 0.001)
    }

    @Test
    fun `quality tiers rank 4K over 1080p over other`() {
        val q4k = SourceSorter.score(source(quality = "4K"), "ALL")
        val q1080 = SourceSorter.score(source(quality = "1080p"), "ALL")
        val q720 = SourceSorter.score(source(quality = "720p"), "ALL")

        assertTrue(q4k > q1080)
        assertTrue(q1080 > q720)
    }

    @Test
    fun `larger file scores lower within the same tier`() {
        val small = source(quality = "1080p", sizeBytes = 1L * 1024 * 1024 * 1024)   // 1 GB
        val large = source(quality = "1080p", sizeBytes = 20L * 1024 * 1024 * 1024)  // 20 GB

        assertTrue(SourceSorter.score(small, "ALL") > SourceSorter.score(large, "ALL"))
    }

    // ── isCached ───────────────────────────────────────────────────────────────────────────────────

    @Test
    fun `isCached is true for VERIFIED_CACHED, DIRECT_STREAM, and the isCached flag`() {
        assertTrue(SourceSorter.isCached(source(cacheStatus = DebridCacheStatus.VERIFIED_CACHED)))
        assertTrue(SourceSorter.isCached(source(cacheStatus = DebridCacheStatus.DIRECT_STREAM)))
        assertTrue(SourceSorter.isCached(source(isCached = true)))
    }

    @Test
    fun `isCached is false for NOT_CACHED and UNKNOWN without the flag`() {
        assertFalse(SourceSorter.isCached(source(cacheStatus = DebridCacheStatus.NOT_CACHED)))
        assertFalse(SourceSorter.isCached(source(cacheStatus = DebridCacheStatus.UNKNOWN)))
    }

    // ── sort ─────────────────────────────────────────────────────────────────────────────────────

    @Test
    fun `sort puts preferred-language cached 4K first`() {
        val best = source(label = "best", languages = listOf("en"), quality = "4K", cacheStatus = DebridCacheStatus.VERIFIED_CACHED)
        val mid = source(label = "mid", quality = "1080p", cacheStatus = DebridCacheStatus.VERIFIED_CACHED)
        val worst = source(label = "worst", quality = "720p", cacheStatus = DebridCacheStatus.NOT_CACHED)

        val sorted = SourceSorter.sort(listOf(worst, mid, best), "EN")

        assertEquals(listOf("best", "mid", "worst"), sorted.map { it.label })
    }

    @Test
    fun `sort is stable for equal scores`() {
        val a = source(label = "a", quality = "1080p")
        val b = source(label = "b", quality = "1080p")
        val c = source(label = "c", quality = "1080p")

        val sorted = SourceSorter.sort(listOf(a, b, c), "ALL")

        assertEquals(listOf("a", "b", "c"), sorted.map { it.label })
    }

    @Test
    fun `sort returns the input untouched for zero or one element`() {
        assertTrue(SourceSorter.sort(emptyList(), "EN").isEmpty())
        val single = listOf(source(label = "only"))
        assertEquals(single, SourceSorter.sort(single, "EN"))
    }
}
