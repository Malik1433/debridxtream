package com.tvonnet.debridxtreamiptv.player.stabilized

import com.tvonnet.debridxtreamiptv.util.MediaTitleCleaner
import java.util.Locale

/**
 * Which CHANNEL a live entry is, ignoring how this provider chose to spell it.
 *
 * A playlist carries the same channel several times — "PK: PTV SPORTS", "PK: PTV SPORTS HD",
 * "PK | PTV Sports FHD" are one channel at three bitrates, on three different servers. That is the
 * raw material for failover: when one of them stalls, another is very often fine.
 *
 * ⭐ The whole risk of this feature lives in this one function, and it is the risk of matching TOO
 * MUCH. Deciding that "PTV SPORTS" and "A SPORTS" are the same channel would silently hand the
 * customer the wrong match, which is far worse than not failing over at all. So the rule strips
 * only what is demonstrably decoration — the country prefix and the quality words — and keeps
 * everything that could possibly distinguish two channels. Digits especially: "SKY SPORTS 1" and
 * "SKY SPORTS 2" differ by one character and are not the same thing.
 */
object LiveChannelIdentity {

    /** "PK:", "US -", "AR |" and friends: which feed of a channel, never which channel. */
    private val COUNTRY_PREFIX = Regex("""^[a-z]{2,4}\s*[:|\-]\s*""")

    /**
     * Quality and packaging words. Whole tokens only — "hd" must not eat the "hd" inside a name,
     * and "4k" as a token is safe where a bare "4" (as in "Channel 4") is not and is left alone.
     */
    private val QUALITY_TOKENS = setOf(
        "hd", "fhd", "uhd", "sd", "4k", "8k", "hq", "lq",
        "1080p", "1080", "720p", "720", "480p", "2160p",
        "h264", "h265", "hevc", "raw", "backup", "alt", "vip", "multi", "fps50", "50fps",
    )

    private val NON_ALNUM = Regex("""[^a-z0-9 ]""")
    private val MULTI_SPACE = Regex("""\s{2,}""")

    /**
     * @return a comparison key, or an empty string when the name carries nothing to compare —
     *   and an empty key must never be treated as a match for another empty one.
     */
    fun key(rawName: String?): String {
        val cleaned = MediaTitleCleaner.clean(rawName).lowercase(Locale.ROOT)
        if (cleaned.isBlank()) return ""
        val withoutCountry = COUNTRY_PREFIX.replace(cleaned, "")
        val plain = MULTI_SPACE.replace(NON_ALNUM.replace(withoutCountry, " "), " ").trim()
        if (plain.isEmpty()) return ""
        val words = plain.split(' ').filter { it.isNotBlank() && it !in QUALITY_TOKENS }
        // A name made only of quality words identifies nothing.
        return words.joinToString(" ")
    }

    /** True when both names name the same channel. Two blanks are NOT a match. */
    fun isSameChannel(a: String?, b: String?): Boolean {
        val keyA = key(a)
        return keyA.isNotEmpty() && keyA == key(b)
    }
}
