package com.tvonnet.debridxtreamiptv.ui.settings

/**
 * C1-a: the **option vocabulary** of the settings screen — the stored value for each choice, the
 * label the user reads, and the mapping between them.
 *
 * Lifted out of [SettingsFragment] because it is the one part of that screen with no Android in it:
 * every selector is "given a stored string, which row is checked, and what does the summary line
 * say". Those lookups all shared the same `indexOf(...).takeIf { it != -1 } ?: default` shape, and
 * each carried its own default — a wrong default here silently mislabels a setting or checks the
 * wrong radio button, which is exactly the sort of thing nobody re-reads.
 *
 * Values are the persisted strings; do not renumber or reorder them without a migration thought.
 */
object SettingsOptionLabels {

    // H9: the full priority vocabulary — Hindi/English plus the major European and
    // Indian languages the source scorer (StreamLanguage) can actually match.
    val audioLangValues = arrayOf(
        "EN", "HI", "DE", "FR", "IT", "ES", "PT", "RU", "NL", "PL", "TR",
        "UR", "PA", "TA", "TE", "ML", "KN", "MULTI", "ALL"
    )
    val audioLangLabels = arrayOf(
        "English (EN)", "Hindi (HI)", "German (DE)", "French (FR)", "Italian (IT)",
        "Spanish (ES)", "Portuguese (PT)", "Russian (RU)", "Dutch (NL)", "Polish (PL)",
        "Turkish (TR)", "Urdu (UR)", "Punjabi (PA)", "Tamil (TA)", "Telugu (TE)",
        "Malayalam (ML)", "Kannada (KN)", "Multilingual (MULTI)", "No preference (ALL)"
    )

    /** H9: secondary priority — same languages, led by the "off" option. */
    val audioLang2Values = arrayOf("NONE") + audioLangValues.filter { it != "ALL" && it != "MULTI" }
    val audioLang2Labels = arrayOf("No secondary (off)") +
        audioLangLabels.filterIndexed { i, _ -> audioLangValues[i] != "ALL" && audioLangValues[i] != "MULTI" }

    fun audioLang2Index(code: String): Int =
        audioLang2Values.indexOf(code.uppercase()).takeIf { it != -1 } ?: 0

    fun audioLang2Name(code: String): String = audioLang2Labels[audioLang2Index(code)]

    // M0 (MOBILE_DUAL_MODE_PLAN): which layout the app renders.
    val uiModeValues = arrayOf("auto", "tv", "mobile")
    val uiModeLabels = arrayOf("Automatic (detect device)", "TV layout (D-pad)", "Mobile layout (touch)")

    fun uiModeIndex(value: String): Int = uiModeValues.indexOf(value.lowercase()).takeIf { it != -1 } ?: 0

    fun uiModeName(value: String): String = uiModeLabels[uiModeIndex(value)]

    val epgIntervalValues = arrayOf("3", "6", "12", "24")
    val epgIntervalLabels = arrayOf("Every 3 hours", "Every 6 hours", "Every 12 hours", "Every 24 hours")

    val liveTvStyleValues = arrayOf("guide", "classic")
    val liveTvStyleLabels = arrayOf("New EPG Guide", "Classic (3-column)")

    val epgZoomValues = arrayOf("compact", "standard", "wide")
    val epgZoomLabels = arrayOf("Compact", "Standard", "Wide")

    val epgDensityValues = arrayOf("cozy", "standard", "roomy")
    val epgDensityLabels = arrayOf("Cozy", "Standard", "Roomy")

    /** Audio language is matched case-insensitively; anything unknown falls back to English. */
    fun audioLangIndex(code: String): Int =
        audioLangValues.indexOf(code.uppercase()).takeIf { it != -1 } ?: 0

    fun audioLangName(code: String): String = audioLangLabels[audioLangIndex(code)]

    /** EPG interval defaults to the SECOND option (6 hours), not the first. */
    fun epgIntervalIndex(hours: String): Int =
        epgIntervalValues.indexOf(hours).takeIf { it != -1 } ?: 1

    fun epgIntervalName(hours: String): String = "Every $hours hours"

    fun liveTvStyleIndex(value: String): Int =
        liveTvStyleValues.indexOf(value).takeIf { it != -1 } ?: 0

    fun liveTvStyleName(value: String): String = liveTvStyleLabels[liveTvStyleIndex(value)]

    /** Zoom and density both default to "standard", i.e. the middle option. */
    fun epgZoomIndex(value: String): Int = epgZoomValues.indexOf(value).takeIf { it != -1 } ?: 1

    fun epgZoomName(value: String): String = epgZoomLabels[epgZoomIndex(value)]

    fun epgDensityIndex(value: String): Int = epgDensityValues.indexOf(value).takeIf { it != -1 } ?: 1

    fun epgDensityName(value: String): String = epgDensityLabels[epgDensityIndex(value)]

    /**
     * A Stremio addon is added by pasting a manifest URL. Only https (or the stremio scheme) is
     * accepted — a plain-http addon would send the user's requests in the clear.
     */
    fun isValidStremioManifestUrl(url: String): Boolean {
        val lower = url.trim().lowercase()
        return (lower.startsWith("https://") || lower.startsWith("stremio://")) &&
            lower.contains("manifest.json")
    }
}
