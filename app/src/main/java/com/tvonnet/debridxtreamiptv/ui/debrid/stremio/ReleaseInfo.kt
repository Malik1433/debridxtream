package com.tvonnet.debridxtreamiptv.ui.debrid.stremio

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Helper for showing a release date on cards for **unreleased** (upcoming) movies/series.
 * TMDB gives a "yyyy-MM-dd" release/first-air date; when that date is in the future the title has no
 * rating yet (0.0) and only a placeholder poster, so we surface "Coming <date>" instead of the year.
 */
internal object ReleaseInfo {
    private val IN = SimpleDateFormat("yyyy-MM-dd", Locale.US)
    private val OUT = SimpleDateFormat("d MMM yyyy", Locale.US)

    private fun parse(releaseDate: String?): Date? {
        val s = releaseDate?.trim().orEmpty()
        if (s.length < 10) return null
        return try { IN.parse(s.substring(0, 10)) } catch (e: Exception) { null }
    }

    fun isUpcoming(releaseDate: String?): Boolean {
        val d = parse(releaseDate) ?: return false
        return d.time > System.currentTimeMillis()
    }

    /** "15 Jul 2026" for a future release (kept short to fit small cards); otherwise [year]/[fallback]. */
    fun metaLabel(year: String?, releaseDate: String?, fallback: String = ""): String {
        val d = parse(releaseDate)
        return if (d != null && d.time > System.currentTimeMillis()) OUT.format(d)
        else year?.takeIf { it.isNotBlank() } ?: fallback
    }
}
