package com.tvonnet.debridxtreamiptv.util

import java.util.regex.Pattern

/**
 * Utility for matching raw filenames (torrent names) to Movie/Series metadata.
 * Implements the "Clean -> Extract -> Query" pattern from Content Enrichment skill.
 */
object ContentEnricher {
    
    // Pattern to find 4-digit year. Matches common formats like (2021), [2021], .2021.
    private val YEAR_PATTERN = Pattern.compile("(?<=^|\\.|\\s|\\[|\\()(\\d{4})(?=\\.|\\s|\\]|\\)|\$)")
    
    private val NOISE_TAGS = listOf(
        "1080p", "720p", "2160p", "4K", "UHD", "HDR", "DV", "Dovi",
        "x264", "x265", "h264", "h265", "HEVC", 
        "BluRay", "BRRip", "DVDRip", "WEB-DL", "WEBRip", "HDTV",
        "DTS", "AC3", "DD5.1", "AAC", "Atmos",
        "RARBG", "YTS", "Eztv", "PSA", "QxR"
    )

    data class EnrichmentCandidate(
        val title: String,
        val year: Int?
    )

    /**
     * Parses a raw filename into a clean title and an optional year.
     */
    fun parse(rawName: String): EnrichmentCandidate {
        // Remove file extension if present
        var workName = rawName.substringBeforeLast(".")
        
        // 1. Extract Year
        val yearMatcher = YEAR_PATTERN.matcher(workName)
        var year: Int? = null
        var yearMatchStart = -1
        
        // Find the last 4-digit sequence that looks like a year
        while (yearMatcher.find()) {
            val potentialYear = yearMatcher.group(1).toIntOrNull()
            if (potentialYear != null && potentialYear in 1900..2030) {
                year = potentialYear
                yearMatchStart = yearMatcher.start()
            }
        }

        // If we found a year, the title is usually everything BEFORE it.
        if (year != null && yearMatchStart > 0) {
            workName = workName.substring(0, yearMatchStart)
        }

        // 2. Clean characters
        var cleaned = workName
            .replace(".", " ")
            .replace("_", " ")
            .replace("-", " ")
        
        // 3. Strip Noise Tags
        NOISE_TAGS.forEach { tag ->
            cleaned = cleaned.replace(Regex("\\b$tag\\b", RegexOption.IGNORE_CASE), "")
        }

        // 4. Strip everything after common clutter indicators
        val clutterIndicators = listOf("S\\d{2}E\\d{2}", "S\\d{1}E\\d{1}", "COMPLETE", "SEASON", "REPACK", "PROPER")
        clutterIndicators.forEach { indicator ->
            val match = Regex(indicator, RegexOption.IGNORE_CASE).find(cleaned)
            if (match != null) {
                cleaned = cleaned.substring(0, match.range.first)
            }
        }

        // 5. Final consolidation of whitespace
        cleaned = cleaned.replace(Regex("\\s+"), " ").trim()
        
        return EnrichmentCandidate(cleaned, year)
    }
}
