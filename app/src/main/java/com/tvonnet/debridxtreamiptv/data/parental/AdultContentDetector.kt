package com.tvonnet.debridxtreamiptv.data.parental

import java.util.Locale

/**
 * Is this provider category adult content? (2026-09-03)
 *
 * Xtream carries no adult flag on a category, so the only signal is the name the provider
 * chose — and providers are consistent about it because their own apps filter the same way:
 * `XXX`, `ADULT`, `18+`, `+18`, `🔞`, `PORN`, `EROTIC`, and the same words in the languages
 * this app ships. Matching is on whole tokens (`ADULT` yes, `ADULTS ONLY` yes, `MADULTA` no)
 * after the decorative separators providers love (`|XXX|`, `[18+]`, `★ ADULT ★`) are stripped.
 *
 * Pure: no Android, no state — see [AdultContentDetectorTest].
 */
object AdultContentDetector {

    private val ADULT_TOKENS = setOf(
        "xxx", "adult", "adults", "porn", "porno", "erotic", "erotik", "erotica", "erotico",
        "erotique", "18+", "+18", "18", "hentai", "sex", "sexy", "nsfw",
        // DE / ES / FR / IT / PT
        "erwachsene", "adultos", "adulto", "adulte", "adultes", "adulti",
    )

    /** Tokens that only count next to a number/age marker, so "SEX AND THE CITY" stays visible. */
    private val WEAK_TOKENS = setOf("sex", "sexy", "18")

    private val SPLIT = Regex("[^\\p{L}\\p{N}+]+")

    fun isAdultCategoryName(name: String?): Boolean {
        val raw = name?.trim().orEmpty()
        if (raw.isEmpty()) return false
        if (raw.contains("🔞")) return true // 🔞
        val tokens = SPLIT.split(raw.lowercase(Locale.ROOT)).filter { it.isNotBlank() }
        if (tokens.isEmpty()) return false
        val strong = tokens.any { it in ADULT_TOKENS && it !in WEAK_TOKENS }
        if (strong) return true
        // "18" alone is a channel number; "18+" / "+18" / "18 plus" is an age gate.
        val weakAge = tokens.any { it == "18+" || it == "+18" } ||
            tokens.zipWithNext().any { (a, b) -> a == "18" && (b == "plus" || b == "only") }
        return weakAge
    }

    /** The ids of the adult categories in [categories], for filtering the streams that carry them. */
    fun <T> adultIds(categories: List<T>, id: (T) -> String?, name: (T) -> String?): Set<String> =
        categories.asSequence()
            .filter { isAdultCategoryName(name(it)) }
            .mapNotNull { id(it) }
            .toSet()
}
