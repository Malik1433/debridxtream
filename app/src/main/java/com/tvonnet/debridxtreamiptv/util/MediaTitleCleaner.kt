package com.tvonnet.debridxtreamiptv.util

/**
 * Shared title cleanup for provider-supplied VOD/Series names.
 *
 * Xtream providers frequently prefix names with pipe-delimited tags such as
 * `|MULTI|`, `|EN|`, `|4K|`, or country/flag markers, and sometimes ship
 * JSON-escaped punctuation (`Don\'t`). The browse cards want the clean human
 * title, so strip leading `|...|` tag blocks, unescape backslash escapes and
 * collapse any leftover leading separators/whitespace.
 *
 * One shared place so Series (and anything else) stay consistent.
 */
object MediaTitleCleaner {

    // One or more LEADING `|...|` blocks (optionally separated by spaces), e.g.
    // "|MULTI| |EN| Title", "|4K|Title", or flag/word blocks like "|🇬🇧 English|".
    private val LEADING_TAG_BLOCKS = Regex("""^(?:\s*\|[^|]*\|\s*)+""")

    // Short provider tags ANYWHERE (trailing/embedded): |AR|, |MULTI|, |EN|, |4K|,
    // |FR|, |VOSTFR|, |UHD|, |1080P| … Conservative: only 1–6 alphanumeric chars so
    // legitimate title words in pipes aren't nuked.
    private val PIPE_TAG_ANYWHERE = Regex("""\s*\|\s*[A-Za-z0-9]{1,6}\s*\|\s*""")

    // Leftover leading / trailing separators once tags are gone.
    private val LEADING_SEPARATORS = Regex("""^[\s\-–—·:|]+""")
    private val TRAILING_SEPARATORS = Regex("""[\s\-–—·:|]+$""")

    private val MULTI_WHITESPACE = Regex("""\s{2,}""")

    fun clean(raw: String?): String {
        if (raw.isNullOrBlank()) return ""

        var name = raw.trim()

        // 1) Unescape common JSON/backslash escapes (Don\'t -> Don't, \" -> ")
        name = name
            .replace("\\'", "'")
            .replace("\\\"", "\"")
            .replace("\\/", "/")

        // 2) Strip leading pipe-tag blocks (|MULTI|, |EN|, |4K|, |🇬🇧|, ...)
        name = LEADING_TAG_BLOCKS.replace(name, " ")

        // 3) Strip short pipe tags anywhere else (trailing/embedded), e.g. "… |AR|"
        name = PIPE_TAG_ANYWHERE.replace(name, " ")

        // 4) Collapse leftover leading/trailing separators + internal whitespace
        name = LEADING_SEPARATORS.replace(name, "")
        name = TRAILING_SEPARATORS.replace(name, "")
        name = MULTI_WHITESPACE.replace(name, " ").trim()

        return name
    }
}
