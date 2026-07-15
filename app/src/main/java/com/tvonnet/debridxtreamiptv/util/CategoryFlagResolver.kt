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
        when {
            "DEUTSCHLAND" in normalizedUpper || "GERMANY" in normalizedUpper -> return R.drawable.flag_de
            "OSTERREICH" in normalizedUpper || "AUSTRIA" in normalizedUpper -> return R.drawable.flag_at
            "SCHWEIZ" in normalizedUpper || "SWITZERLAND" in normalizedUpper -> return R.drawable.flag_ch
            "POLSKA" in normalizedUpper || "POLAND" in normalizedUpper -> return R.drawable.flag_pl
            "ISLAND" in normalizedUpper || "ICELAND" in normalizedUpper -> return R.drawable.flag_is
            "IRLAND" in normalizedUpper || "IRELAND" in normalizedUpper -> return R.drawable.flag_ie
            "BELGIQUE" in normalizedUpper || "BELGIUM" in normalizedUpper -> return R.drawable.flag_be
            "LUXEMBOURG" in normalizedUpper || "LETZEBUERG" in normalizedUpper -> return R.drawable.flag_lu
            "PORTUGAL" in normalizedUpper -> return R.drawable.flag_pt
            "FRANCE" in normalizedUpper -> return R.drawable.flag_fr
            "ITALY" in normalizedUpper -> return R.drawable.flag_it
            "SPAIN" in normalizedUpper || "ESPANA" in normalizedUpper -> return R.drawable.flag_es
            "NETHERLANDS" in normalizedUpper -> return R.drawable.flag_nl
            "RUSSIA" in normalizedUpper -> return R.drawable.flag_ru
            "TURKEY" in normalizedUpper -> return R.drawable.flag_tr
            "NORWAY" in normalizedUpper -> return R.drawable.flag_no
            "CANADA" in normalizedUpper -> return R.drawable.flag_ca
            "HONG KONG" in normalizedUpper || "HONGKONG" in normalizedUpper -> return R.drawable.flag_hk
            "FINLAND" in normalizedUpper -> return R.drawable.flag_fi
            "DENMARK" in normalizedUpper -> return R.drawable.flag_dk
            "SWEDEN" in normalizedUpper -> return R.drawable.flag_se
            "ARGENTINA" in normalizedUpper -> return R.drawable.flag_ar
            "MEXICO" in normalizedUpper -> return R.drawable.flag_mx
            "BRAZIL" in normalizedUpper -> return R.drawable.flag_br
            "MOROCCO" in normalizedUpper -> return R.drawable.flag_ma
            "ALBANIA" in normalizedUpper -> return R.drawable.flag_al
            "AUSTRALIA" in normalizedUpper -> return R.drawable.flag_au
            "JAPAN" in normalizedUpper -> return R.drawable.flag_jp
            "KOREA" in normalizedUpper -> return R.drawable.flag_kr
            "UAE" in normalizedUpper || "UNITED ARAB EMIRATES" in normalizedUpper -> return R.drawable.flag_ae
            "SAUDI" in normalizedUpper || "SAUDI ARABIA" in normalizedUpper -> return R.drawable.flag_sa
            "EGYPT" in normalizedUpper -> return R.drawable.flag_eg
            "CHINA" in normalizedUpper -> return R.drawable.flag_cn
            "EUROPE" in normalizedUpper -> return R.drawable.flag_eu
            "AFRICA" in normalizedUpper -> return R.drawable.flag_af
            "AMERICA" in normalizedUpper -> return R.drawable.flag_am
        }
        val prefixRaw = normalizedUpper.substringBefore('|', "").trim()
        val prefix = if (prefixRaw.length == 3 && prefixRaw.endsWith("I")) {
            prefixRaw.dropLast(1)
        } else {
            prefixRaw
        }
        val normalizedCode = when (prefix) {
            "USA" -> "US"
            "UK" -> "GB"
            "ALB" -> "AL"
            "SW", "SU" -> "SE"
            else -> prefix
        }

        FLAG_RES_BY_CODE[normalizedCode]?.let { return it }

        return when {
            "UNITED STATES" in normalizedUpper || "USA" in normalizedUpper -> R.drawable.flag_us
            "UNITED KINGDOM" in normalizedUpper || "BRITAIN" in normalizedUpper || "ENGLAND" in normalizedUpper -> R.drawable.flag_gb
            else -> null
        }
    }

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
        return when {
            "favorit" in lower -> "★" // ★
            "sport" in lower -> "⚽"
            "news" in lower -> "📰"
            "movie" in lower || "film" in lower -> "🎬"
            "entertain" in lower || "show" in lower -> "✨"
            "music" in lower -> "🎵"
            "kids" in lower || "child" in lower -> "👶"
            "doc" in lower -> "📖"
            "relig" in lower -> "✝"
            "edu" in lower -> "🎓"
            "animal" in lower || "nature" in lower -> "🌍"
            else -> trimmed
                .split(" ")
                .firstOrNull()
                ?.take(2)
                ?.uppercase(Locale.getDefault())
                ?: "#"
        }
    }

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
