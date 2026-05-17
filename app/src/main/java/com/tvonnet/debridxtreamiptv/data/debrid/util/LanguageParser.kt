package com.tvonnet.debridxtreamiptv.data.debrid.util

object LanguageParser {
    
    private val LANGUAGE_MAP = mapOf(
        "hi" to listOf("hin", "hindi", "hindi dubbed", "hindi audio", "bollywood"),
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
        if (title.isNullOrBlank()) return listOf("en")

        val languages = mutableSetOf<String>()
        val lowerTitle = title.lowercase()

        // Check for Multi Audio
        if (
            lowerTitle.contains("multi") ||
            lowerTitle.contains("multi audio") ||
            lowerTitle.contains("multi-audio") ||
            lowerTitle.contains("dual audio") ||
            lowerTitle.contains("dual-audio") ||
            lowerTitle.contains("dualaudio")
        ) {
            languages.add("multi")
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

        // If no languages found, default to English
        if (languages.isEmpty()) {
            return listOf("en")
        }

        // If "multi" is the only tag, try to infer English as well if not explicitly present
        if (languages.size == 1 && languages.contains("multi")) {
             languages.add("en")
        }

        return languages.toList()
    }
    fun getLanguageFlag(languages: List<String>): String {
        if (languages.isEmpty()) return ""

        if (languages.any { it.equals("multi", ignoreCase = true) }) {
            return "MULTI"
        }

        val labels = languages.mapNotNull { lang ->
            when (lang.lowercase()) {
                "hi", "ta", "te", "ml", "kn", "pa" -> "HI"
                "en" -> "EN"
                "es" -> "ES"
                "fr" -> "FR"
                "de" -> "DE"
                "it" -> "IT"
                "ru" -> "RU"
                "ja" -> "JA"
                "ko" -> "KO"
                "zh" -> "ZH"
                "ar" -> "AR"
                else -> null
            }
        }.distinct()

        return if (labels.isEmpty()) {
            "UNK"
        } else {
            labels.joinToString("/")
        }
    }
}
