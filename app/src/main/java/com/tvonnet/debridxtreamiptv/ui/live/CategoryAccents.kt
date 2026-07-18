package com.tvonnet.debridxtreamiptv.ui.live

import android.graphics.Color

/**
 * Per-category accent colours for the Live TV v2 chip strip. Mirrors the design's CAT_THEME
 * idea (Sports=green, News=blue, Movies=amber, …) but keyed off arbitrary provider category
 * names via keyword matching, so real categories like "SPORTS RAW UHD" or "FR| JEUNESSE" still
 * get a sensible colour. Anything unmatched falls back to a stable per-name palette pick, so a
 * category always shows the same colour across sessions.
 */
object CategoryAccents {

    // Keyword → accent. Order matters: first match wins, so put more specific words first.
    private val KEYWORDS: List<Pair<List<String>, Int>> = listOf(
        listOf("favorit", "favoris", "favourite") to 0xFFFFAA00.toInt(),                         // gold
        listOf("news", "welt", "cnn", "bbc", "aljazeera", "actu") to 0xFF3B82F6.toInt(),        // blue
        listOf("sport", "football", "soccer", "f1", "motogp", "nba", "nfl", "uefa", "dazn",
            "bein", "cup", "mondial", "rugby", "tennis", "golf") to 0xFF10B981.toInt(),          // green
        listOf("movie", "cinema", "film", "box office", "cine") to 0xFFF59E0B.toInt(),           // amber
        listOf("kids", "jeunesse", "cartoon", "disney", "nick", "baby", "enfant") to 0xFFF472B6.toInt(), // pink
        listOf("music", "mtv", "hits", "radio", "musik") to 0xFFA78BFA.toInt(),                  // purple
        listOf("doc", "documentary", "nat geo", "history", "discovery", "science") to 0xFF2DD4BF.toInt(), // teal
        listOf("4k", "uhd") to 0xFF22D3EE.toInt(),                                               // cyan
        listOf("relax", "nature", "chill") to 0xFF34D399.toInt(),                                // mint
    )

    // Stable fallback palette for anything the keywords miss.
    private val PALETTE = intArrayOf(
        0xFF60A5FA.toInt(), // blue
        0xFF34D399.toInt(), // green
        0xFFFBBF24.toInt(), // amber
        0xFFF472B6.toInt(), // pink
        0xFFA78BFA.toInt(), // purple
        0xFF22D3EE.toInt(), // cyan
        0xFFFB923C.toInt(), // orange
        0xFF818CF8.toInt(), // indigo
    )

    private const val CYAN = 0xFF00F0FF.toInt()

    fun colorFor(categoryName: String?): Int {
        val name = categoryName?.trim().orEmpty()
        if (name.isEmpty()) return CYAN
        val lower = name.lowercase()
        for ((words, color) in KEYWORDS) {
            if (words.any { lower.contains(it) }) return color
        }
        // Stable hash so the same name always maps to the same palette slot.
        val idx = (name.hashCode() and Int.MAX_VALUE) % PALETTE.size
        return PALETTE[idx]
    }

    /** A dimmed version of the accent, for subtle fills/borders behind the bright dot. */
    fun dim(color: Int, alpha: Int = 0x33): Int =
        Color.argb(alpha, Color.red(color), Color.green(color), Color.blue(color))
}
