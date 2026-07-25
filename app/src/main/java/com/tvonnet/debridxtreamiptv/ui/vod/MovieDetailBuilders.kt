package com.tvonnet.debridxtreamiptv.ui.vod

import java.util.Locale

/**
 * MD-2: pure movie-detail formatting helpers lifted out of [MovieDetailActivity]. All are pure given
 * their inputs (no Activity/view/Context reference), so they relocate byte-identical from the old
 * private methods and stay unit-testable. Formatters that need string resources (`formatDuration`,
 * `displayRating`) intentionally stay in the Activity.
 */

/** Picks a content/age certification, preferring US then GB, else the first available. */
internal fun extractCertification(
    releaseDates: com.tvonnet.debridxtreamiptv.data.debrid.model.TmdbReleaseDatesResponse?
): String? {
    val results = releaseDates?.results ?: return null
    fun certFor(country: String): String? = results
        .firstOrNull { it.countryCode.equals(country, ignoreCase = true) }
        ?.releaseDates
        ?.mapNotNull { it.certification?.trim() }
        ?.firstOrNull { it.isNotBlank() }
    return certFor("US")
        ?: certFor("GB")
        ?: results.flatMap { it.releaseDates ?: emptyList() }
            .mapNotNull { it.certification?.trim() }
            .firstOrNull { it.isNotBlank() }
}

internal fun formatDurationCompact(duration: String): String {
    val seconds = duration.toLongOrNull()
    return if (seconds != null) {
        val hours = seconds / 3600
        val minutes = (seconds % 3600) / 60
        if (hours > 0) "${hours}H ${minutes}M" else "${minutes}M"
    } else {
        duration
    }
}

internal fun formatResumeTime(milliseconds: Long): String {
    val totalSeconds = milliseconds.coerceAtLeast(0L) / 1000L
    val hours = totalSeconds / 3600
    val minutes = (totalSeconds % 3600) / 60
    val seconds = totalSeconds % 60
    return if (hours > 0) {
        String.format(Locale.US, "%d:%02d:%02d", hours, minutes, seconds)
    } else {
        String.format(Locale.US, "%d:%02d", minutes, seconds)
    }
}

internal fun formatSizeLabel(sizeBytes: Long): String {
    val gb = 1024.0 * 1024.0 * 1024.0
    val mb = 1024.0 * 1024.0
    return if (sizeBytes >= gb) {
        String.format(Locale.US, "%.1f GB", sizeBytes / gb)
    } else {
        String.format(Locale.US, "%.0f MB", sizeBytes / mb)
    }
}
