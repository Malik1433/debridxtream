package com.tvonnet.debridxtreamiptv.data.debrid.util

object LanguageParser {

    private val MULTI_AUDIO_MARKERS = listOf(
        "multi", "multi audio", "multi-audio", "dual audio", "dual-audio", "dualaudio"
    )

    private val LANGUAGE_MAP = mapOf(
        "hi" to listOf(
            "hi",
            "hin",
            "hindi",
            "hindi english",
            "hindi dubbed",
            "hindi audio",
            "dubbed hindi",
            "dual hindi",
            "dual audio hindi",
            "hindi dual audio",
            "multi hindi",
            "multi audio hindi",
            "\u0939\u093f\u0928\u094d\u0926\u0940",
            "bollywood"
        ),
        "ta" to listOf("tam", "tamil"),
        "te" to listOf("tel", "telugu"),
        "ml" to listOf("mal", "malayalam"),
        "kn" to listOf("kan", "kannada"),
        "pa" to listOf("pan", "punjabi"),
        "en" to listOf("eng", "english"),
        "es" to listOf("spa", "spanish", "espanol"),
        "fr" to listOf("fra", "french"),
        "de" to listOf("ger", "german", "deu", "deutsch", "german dubbed", "german audio"),
        "it" to listOf("ita", "italian"),
        "ru" to listOf("rus", "russian"),
        "ja" to listOf("jpn", "japanese"),
        "ko" to listOf("kor", "korean"),
        "zh" to listOf("chi", "chinese"),
        "ar" to listOf("ara", "arabic")
    )

    fun extractLanguages(title: String?): List<String> {
        // H3: no invented languages — a blank title tells us NOTHING about audio.
        // Claiming "en" here made nearly every row claim English, which both
        // polluted the English filter and made "Unknown" unreachable.
        if (title.isNullOrBlank()) return emptyList()

        val languages = mutableSetOf<String>()
        val lowerTitle = title.lowercase()

        // Check for Multi Audio
        if (MULTI_AUDIO_MARKERS.any { lowerTitle.contains(it) }) {
            languages.add("multi")
        }

        if (hasHindiAudioHint(lowerTitle)) {
            languages.add("hi")
        }

        // Check for specific languages
        LANGUAGE_MAP.forEach { (code, keywords) ->
            // Regex to match whole words or words separated by dots/hyphens/plus/brackets
            // e.g. .Hin. or -Hin- or +Hin+ or [Hin] or " Hin "
            val pattern = keywords.joinToString("|") { Regex.escape(it) }
            // Updated regex to include +, [, ] as delimiters
            val regex = Regex("(?<=^|[\\s._\\-+\\[\\]])($pattern)(?=$|[\\s._\\-+\\[\\]])", RegexOption.IGNORE_CASE)
            
            if (regex.containsMatchIn(lowerTitle) || keywords.any { lowerTitle.contains(" $it ") }) {
                languages.add(code)
            }
        }

        // H3: no languages found = honestly unknown (renders as one neutral chip,
        // filter-side MULTI/unknown handling keeps such rows reachable). A MULTI-only
        // result stays MULTI — inferring "en" was a guess dressed up as data.
        return languages.toList()
    }

    private fun hasHindiAudioHint(text: String): Boolean {
        val patterns = listOf(
            Regex("(?i)(^|[\\s._\\-+\\[\\]()])hi(?=$|[\\s._\\-+\\[\\]()])"),
            Regex("(?i)(^|[\\s._\\-+\\[\\]()])hin(?=$|[\\s._\\-+\\[\\]()])"),
            Regex("(?i)hindi[\\s._\\-+]*(english|audio|dubbed|dual)"),
            Regex("(?i)(english|dual|multi)[\\s._\\-+]*hindi"),
            Regex("(?i)(dual|multi)[\\s._\\-+]*audio[\\s._\\-+]*hindi"),
            Regex("(?i)hindi[\\s._\\-+]*(dual|multi)[\\s._\\-+]*audio"),
            Regex("\u0939\u093f\u0928\u094d\u0926\u0940")
        )
        return patterns.any { it.containsMatchIn(text) }
    }

    // All Indian-language codes render as the single "HI" label; a code with no
    // mapping contributes nothing (an unknown-only list renders "UNK" below).
    private val FLAG_LABEL_BY_CODE = mapOf(
        "hi" to "HI", "ta" to "HI", "te" to "HI", "ml" to "HI", "kn" to "HI", "pa" to "HI",
        "en" to "EN",
        "es" to "ES",
        "fr" to "FR",
        "de" to "DE",
        "it" to "IT",
        "ru" to "RU",
        "ja" to "JA",
        "ko" to "KO",
        "zh" to "ZH",
        "ar" to "AR",
    )

    fun getLanguageFlag(languages: List<String>): String {
        if (languages.isEmpty()) return ""

        if (languages.any { it.equals("multi", ignoreCase = true) }) {
            return "MULTI"
        }

        val labels = languages.mapNotNull { lang -> FLAG_LABEL_BY_CODE[lang.lowercase()] }.distinct()

        return if (labels.isEmpty()) {
            "UNK"
        } else {
            labels.joinToString("/")
        }
    }
}
