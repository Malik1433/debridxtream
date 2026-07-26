package com.tvonnet.debridxtreamiptv.util

import com.tvonnet.debridxtreamiptv.R
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Roadmap B3 — the category flag/badge mapping shared by the Live sidebar and the in-player surf
 * drawer. It is a long `when` over provider-supplied strings, which is exactly the kind of code
 * that quietly stops matching when a provider renames a category, and exactly the kind nobody
 * re-reads. Pinning the shapes (country word, `|CC|` code prefix, alias, accent, fallback badge)
 * means a future edit that reorders or breaks one branch shows up immediately.
 */
class CategoryFlagResolverTest {

    @Test
    fun `a country named in the category resolves to its flag`() {
        assertEquals(R.drawable.flag_fr, CategoryFlagResolver.resolveFlagRes("FRANCE | CANAL+"))
        assertEquals(R.drawable.flag_de, CategoryFlagResolver.resolveFlagRes("Germany Sport"))
        assertEquals(R.drawable.flag_de, CategoryFlagResolver.resolveFlagRes("DEUTSCHLAND HD"))
    }

    /** Accents must not defeat the match — providers write "ÖSTERREICH" and "OSTERREICH" alike. */
    @Test
    fun `accents are normalized away before matching`() {
        assertEquals(R.drawable.flag_at, CategoryFlagResolver.resolveFlagRes("ÖSTERREICH"))
        assertEquals(R.drawable.flag_es, CategoryFlagResolver.resolveFlagRes("ESPAÑA"))
    }

    @Test
    fun `a leading pipe code resolves to its flag`() {
        assertEquals(R.drawable.flag_fr, CategoryFlagResolver.resolveFlagRes("FR| CANAL+"))
        assertEquals(R.drawable.flag_it, CategoryFlagResolver.resolveFlagRes("IT| RAI"))
    }

    @Test
    fun `the common provider aliases map to real country codes`() {
        assertEquals(R.drawable.flag_us, CategoryFlagResolver.resolveFlagRes("USA| ENTERTAINMENT"))
        assertEquals(R.drawable.flag_gb, CategoryFlagResolver.resolveFlagRes("UK| SPORTS"))
        assertEquals(R.drawable.flag_se, CategoryFlagResolver.resolveFlagRes("SW| KANALER"))
    }

    @Test
    fun `a country spelled out but not in the word list still resolves through the tail matches`() {
        assertEquals(R.drawable.flag_us, CategoryFlagResolver.resolveFlagRes("UNITED STATES CHANNELS"))
        assertEquals(R.drawable.flag_gb, CategoryFlagResolver.resolveFlagRes("ENGLAND CHANNELS"))
    }

    @Test
    fun `a category with no country mapping has no flag`() {
        assertNull(CategoryFlagResolver.resolveFlagRes("Sports"))
        assertNull(CategoryFlagResolver.resolveFlagRes("24/7 Movies"))
        assertNull(CategoryFlagResolver.resolveFlagRes(""))
    }

    // ── the badge fallback ───────────────────────────────────────────────────

    @Test
    fun `themed categories get a themed badge`() {
        assertEquals("★", CategoryFlagResolver.resolveCategoryIcon("Favorites"))
        assertEquals("⚽", CategoryFlagResolver.resolveCategoryIcon("Sports HD"))
        assertEquals("📰", CategoryFlagResolver.resolveCategoryIcon("News 24"))
        assertEquals("🎬", CategoryFlagResolver.resolveCategoryIcon("Movies 2024"))
        assertEquals("🎵", CategoryFlagResolver.resolveCategoryIcon("Music Box"))
    }

    @Test
    fun `a bare two-letter category is shown as its own code`() {
        assertEquals("DE", CategoryFlagResolver.resolveCategoryIcon("DE"))
        assertEquals("UK", CategoryFlagResolver.resolveCategoryIcon("| UK |"))
    }

    /**
     * The caller renders this directly, so it must never come back blank. Writing this found that
     * a blank category name DID return "" — `firstOrNull` on a blank string yields "" rather than
     * null, so the `?: "#"` fallback never fired. Fixed alongside this test.
     */
    @Test
    fun `an unmapped category still gets something renderable`() {
        assertEquals("AB", CategoryFlagResolver.resolveCategoryIcon("Abcdef Channels"))
        assertFalse(CategoryFlagResolver.resolveCategoryIcon("###").isBlank())
        assertEquals("#", CategoryFlagResolver.resolveCategoryIcon(""))
        assertEquals("#", CategoryFlagResolver.resolveCategoryIcon("   "))
    }
}
