package com.tvonnet.debridxtreamiptv.util

import androidx.annotation.DrawableRes
import com.tvonnet.debridxtreamiptv.R
import java.text.Normalizer
import java.util.Locale

/**
 * Resolves a country flag drawable (or a generic emoji/text fallback) for an
 * Xtream category name, e.g. "|FR| CANAL+" -> flag_fr, "Sports" -> ⚽.
 *
 * Extracted from the original SidebarCategoryViewHolder implementation (Live TV
 * 3-column sidebar) so every category list in the app — the sidebar and the
 * fullscreen live-player surf drawer — shares one flag/icon mapping instead of
 * each maintaining its own copy.
 */
object CategoryFlagResolver {

    /** @return a flag drawable res id, or null if the category has no country mapping. */
    @DrawableRes
    fun resolveFlagRes(categoryName: String): Int? {
        val upper = categoryName.trim().uppercase(Locale.getDefault())
        val normalizedUpper = normalizeLabel(upper)
        FLAG_RES_BY_NAME_MARKER.firstOrNull { (marker, _) -> marker in normalizedUpper }
            ?.let { return it.second }

        FLAG_RES_BY_CODE[prefixCountryCode(normalizedUpper)]?.let { return it }

        return when {
            "UNITED STATES" in normalizedUpper || "USA" in normalizedUpper -> R.drawable.flag_us
            "UNITED KINGDOM" in normalizedUpper || "BRITAIN" in normalizedUpper || "ENGLAND" in normalizedUpper -> R.drawable.flag_gb
            else -> null
        }
    }

    // The country code from a leading "XX|" prefix, with the historical alias fixups
    // (a trailing I on a 3-letter prefix is dropped: "GERI|" style feeds).
    private fun prefixCountryCode(normalizedUpper: String): String {
        val prefixRaw = normalizedUpper.substringBefore('|', "").trim()
        val prefix = if (prefixRaw.length == 3 && prefixRaw.endsWith("I")) {
            prefixRaw.dropLast(1)
        } else {
            prefixRaw
        }
        return when (prefix) {
            "USA" -> "US"
            "UK" -> "GB"
            "ALB" -> "AL"
            "SW", "SU" -> "SE"
            else -> prefix
        }
    }

    // Ordered, first match wins — this is the old when-chain as data, so the precedence
    // (e.g. "GERMANY" before "AUSTRIA") is exactly the original branch order.
    private val FLAG_RES_BY_NAME_MARKER: List<Pair<String, Int>> = listOf(
        "DEUTSCHLAND" to R.drawable.flag_de, "GERMANY" to R.drawable.flag_de,
        "OSTERREICH" to R.drawable.flag_at, "AUSTRIA" to R.drawable.flag_at,
        "SCHWEIZ" to R.drawable.flag_ch, "SWITZERLAND" to R.drawable.flag_ch,
        "POLSKA" to R.drawable.flag_pl, "POLAND" to R.drawable.flag_pl,
        "ISLAND" to R.drawable.flag_is, "ICELAND" to R.drawable.flag_is,
        "IRLAND" to R.drawable.flag_ie, "IRELAND" to R.drawable.flag_ie,
        "BELGIQUE" to R.drawable.flag_be, "BELGIUM" to R.drawable.flag_be,
        "LUXEMBOURG" to R.drawable.flag_lu, "LETZEBUERG" to R.drawable.flag_lu,
        "PORTUGAL" to R.drawable.flag_pt,
        "FRANCE" to R.drawable.flag_fr,
        "ITALY" to R.drawable.flag_it,
        "SPAIN" to R.drawable.flag_es, "ESPANA" to R.drawable.flag_es,
        "NETHERLANDS" to R.drawable.flag_nl,
        "RUSSIA" to R.drawable.flag_ru,
        "TURKEY" to R.drawable.flag_tr,
        "NORWAY" to R.drawable.flag_no,
        "CANADA" to R.drawable.flag_ca,
        "HONG KONG" to R.drawable.flag_hk, "HONGKONG" to R.drawable.flag_hk,
        "FINLAND" to R.drawable.flag_fi,
        "DENMARK" to R.drawable.flag_dk,
        "SWEDEN" to R.drawable.flag_se,
        "ARGENTINA" to R.drawable.flag_ar,
        "MEXICO" to R.drawable.flag_mx,
        "BRAZIL" to R.drawable.flag_br,
        "MOROCCO" to R.drawable.flag_ma,
        "ALBANIA" to R.drawable.flag_al,
        "AUSTRALIA" to R.drawable.flag_au,
        "JAPAN" to R.drawable.flag_jp,
        "KOREA" to R.drawable.flag_kr,
        "UAE" to R.drawable.flag_ae, "UNITED ARAB EMIRATES" to R.drawable.flag_ae,
        "SAUDI" to R.drawable.flag_sa, "SAUDI ARABIA" to R.drawable.flag_sa,
        "EGYPT" to R.drawable.flag_eg,
        "CHINA" to R.drawable.flag_cn,
        "EUROPE" to R.drawable.flag_eu,
        "AFRICA" to R.drawable.flag_af,
        "AMERICA" to R.drawable.flag_am,
    )

    /**
     * Fallback badge for categories with no country mapping (Favorites, All,
     * generic Sports/News/...): a themed emoji, or a short text abbreviation as
     * the last resort. Never returns blank so the caller always has something
     * to render in place of a flag.
     */
    fun resolveCategoryIcon(categoryName: String): String {
        val trimmed = categoryName.trim()
        val upper = trimmed.uppercase(Locale.getDefault())
        val lower = trimmed.lowercase(Locale.getDefault())

        val alphaCode = upper.filter { it in 'A'..'Z' }
        if (alphaCode.length == 2) {
            return alphaCode
        }
        ICON_BY_NAME_MARKER.firstOrNull { (marker, _) -> marker in lower }?.let { return it.second }
        return trimmed
            .split(" ")
            .firstOrNull()
            ?.take(2)
            ?.uppercase(Locale.getDefault())
            // `firstOrNull` on a blank name yields "" rather than null, so the elvis below
            // never fired and the badge came back blank — breaking this function's one
            // promise, that the caller always has something to render.
            ?.takeIf { it.isNotBlank() }
            ?: "#"
    }

    // Ordered, first match wins — the old when-chain as data, original branch order kept.
    private val ICON_BY_NAME_MARKER: List<Pair<String, String>> = listOf(
        "favorit" to "★",
        "sport" to "⚽",
        "news" to "📰",
        "movie" to "🎬", "film" to "🎬",
        "entertain" to "✨", "show" to "✨",
        "music" to "🎵",
        "kids" to "👶", "child" to "👶",
        "doc" to "📖",
        "relig" to "✝",
        "edu" to "🎓",
        "animal" to "🌍", "nature" to "🌍",
    )

    private fun normalizeLabel(value: String): String {
        val normalized = Normalizer.normalize(value, Normalizer.Form.NFD)
        return normalized.replace("\\p{InCombiningDiacriticalMarks}+".toRegex(), "")
    }

    private val FLAG_RES_BY_CODE = mapOf(
        "US" to R.drawable.flag_us,
        "GB" to R.drawable.flag_gb,
        "EU" to R.drawable.flag_eu,
        "DE" to R.drawable.flag_de,
        "ES" to R.drawable.flag_es,
        "PL" to R.drawable.flag_pl,
        "NL" to R.drawable.flag_nl,
        "FR" to R.drawable.flag_fr,
        "IT" to R.drawable.flag_it,
        "RU" to R.drawable.flag_ru,
        "TR" to R.drawable.flag_tr,
        "NO" to R.drawable.flag_no,
        "CA" to R.drawable.flag_ca,
        "BE" to R.drawable.flag_be,
        "AT" to R.drawable.flag_at,
        "CH" to R.drawable.flag_ch,
        "HK" to R.drawable.flag_hk,
        "IE" to R.drawable.flag_ie,
        "FI" to R.drawable.flag_fi,
        "DK" to R.drawable.flag_dk,
        "SE" to R.drawable.flag_se,
        "AR" to R.drawable.flag_ar,
        "MX" to R.drawable.flag_mx,
        "BR" to R.drawable.flag_br,
        "MA" to R.drawable.flag_ma,
        "AL" to R.drawable.flag_al,
        "AU" to R.drawable.flag_au,
        "JP" to R.drawable.flag_jp,
        "KR" to R.drawable.flag_kr,
        "AE" to R.drawable.flag_ae,
        "SA" to R.drawable.flag_sa,
        "EG" to R.drawable.flag_eg,
        "PT" to R.drawable.flag_pt,
        "CN" to R.drawable.flag_cn,
        "AF" to R.drawable.flag_af,
        "AM" to R.drawable.flag_am,
        "IS" to R.drawable.flag_is,
        "LU" to R.drawable.flag_lu
    )
}
