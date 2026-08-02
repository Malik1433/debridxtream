package com.tvonnet.debridxtreamiptv.features.seriesv2.ui.model

/**
 * Aggregated IPTV stream options for a single episode.
 *
 * The same show is often listed across several Xtream playlist categories (e.g.
 * "Netflix Originals HD", "English Drama 4K"), each as its own series_id with its
 * own per-episode stream. [IptvStreamOption] is one such stream; [IptvStreamGroup]
 * bundles all streams that belong to the same playlist category.
 */
data class IptvStreamOption(
    val streamId: String,          // episode_id of the sibling series' matching episode
    val playUrl: String,           // fully-built playback URL
    val containerExtension: String?,
    val sourceSeriesId: String,    // sibling series this stream came from
    val name: String,              // display name (episode/series title + tags)
    val quality: StreamQuality,
    val container: String,         // MP4 / MKV / TS (uppercase)
    val languages: List<StreamLanguage>,
    val categoryTag: String        // short tag shown on the row's provider chip (EN, MULTI…)
)

data class IptvStreamGroup(
    val categoryId: String?,
    val categoryName: String,
    val categoryTag: String,       // short badge, derived from name/languages
    val streams: List<IptvStreamOption>
)

enum class StreamQuality(val label: String, val rank: Int) {
    UHD_4K("4K", 4),
    FHD_1080("1080P", 3),
    HD_720("720P", 2),
    SD("SD", 1);

    companion object {
        /** Infer quality from a stream/series title. */
        fun fromName(name: String?): StreamQuality {
            val n = name?.lowercase().orEmpty()
            return when {
                n.contains("2160") || n.contains("4k") || n.contains("uhd") -> UHD_4K
                n.contains("1080") -> FHD_1080
                n.contains("720") -> HD_720
                else -> SD
            }
        }
    }
}

data class StreamLanguage(val code: String, val flag: String) {
    companion object {
        private val FLAGS = mapOf(
            "EN" to "🇺🇸",
            "HI" to "🇮🇳",
            "FR" to "🇫🇷",
            "AR" to "🇸🇦",
            "ES" to "🇪🇸",
            "DE" to "🇩🇪",
            "IT" to "🇮🇹",
            "NL" to "🇳🇱",
            "PT" to "🇵🇹",
            "BR" to "🇧🇷",
            "TR" to "🇹🇷",
            "SE" to "🇸🇪",
            "PL" to "🇵🇱",
            "LAT" to "🌎",
            "MULTI" to "🌎"
        )

        fun of(code: String): StreamLanguage {
            val up = code.uppercase()
            return StreamLanguage(up, FLAGS[up] ?: "🌐")
        }

        /** Title markers per language code, in display order — each spelling was observed in the wild. */
        private val LANGUAGE_MARKERS = linkedMapOf(
            "MULTI" to listOf("multi"),
            "FR" to listOf("vostfr", "vf", "french", "fr-"),
            "HI" to listOf("hindi", "hin ", "-hi", " hi "),
            "AR" to listOf("arab", "ar-sub", " ar "),
            "ES" to listOf("span", "esp", " es "),
            "EN" to listOf("english", "eng ", "-en", " en ")
        )

        /**
         * Infer languages from a stream/series name and its category tag. Providers
         * encode language hints in the title (MULTI, VOSTFR, AR-Sub, VF…) so we scan
         * for common markers and fall back to the category tag.
         */
        fun inferFrom(name: String?, categoryTag: String?): List<StreamLanguage> {
            val n = name?.lowercase().orEmpty()
            val found = linkedSetOf<String>()
            LANGUAGE_MARKERS.forEach { (code, markers) ->
                if (markers.any { n.contains(it) }) found.add(code)
            }
            if (found.isEmpty()) {
                val tag = categoryTag?.uppercase()?.takeIf { it.isNotBlank() && it != "SERIES" && it != "SRV" }
                found.add(tag ?: "EN")
            }
            return found.map { of(it) }
        }
    }
}
