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

/**
 * Fills the detail poster, when the layout in play HAS one.
 *
 * Only the phone layout does: the television layout keeps `iv_poster` as a 0dp stub and leads with
 * the backdrop alone. Loading into whichever arrived costs the television nothing and saves the
 * phone from an empty rectangle where the art should be.
 */
internal fun bindDetailPoster(
    activity: androidx.appcompat.app.AppCompatActivity,
    iconUrl: String?,
    backdropUrl: String?,
) {
    val poster = activity.findViewById<android.widget.ImageView>(
        com.tvonnet.debridxtreamiptv.R.id.iv_poster
    ) ?: return
    val art = iconUrl?.takeIf { it.isNotBlank() } ?: backdropUrl
    if (art.isNullOrBlank()) return
    com.bumptech.glide.Glide.with(activity).load(art).centerCrop().into(poster)
}

/**
 * What the top bar says. The television shows the trail it came down ("/ MOVIES"); the phone shows
 * the title, because a breadcrumb is a 10-foot idea and the screen behind is one tap away.
 */
internal fun detailTrailLabel(
    activity: androidx.appcompat.app.AppCompatActivity,
    isDebrid: Boolean,
    title: String?,
): String = if (activity.resources.getBoolean(com.tvonnet.debridxtreamiptv.R.bool.ui_uses_dpad_focus)) {
    if (isDebrid) "/ MOVIES" else "/ VOD"
} else {
    title.orEmpty()
}

/**
 * Hides the labels that have nothing to say.
 *
 * The television layout can afford an empty pill or a blank line in a 900dp column; a phone
 * cannot — an empty badge reads as a rendering fault, and a blank director line leaves a hole
 * between the plot and what comes next. A view with no text takes no space.
 */
internal fun hideBlankLabels(activity: androidx.appcompat.app.AppCompatActivity, vararg ids: Int) {
    ids.forEach { id ->
        val view = activity.findViewById<android.widget.TextView>(id) ?: return@forEach
        if (view.text.isNullOrBlank()) view.visibility = android.view.View.GONE
    }
}

/**
 * The three text bindings that are pure "field in, view out".
 *
 * They live here rather than in the Activity for the reason the ledger cares about: that file was
 * already past the size this project allows, and a screen that gains a phone layout should not
 * make it worse. Nothing about their behaviour changed in the move.
 */
internal fun bindBackHint(view: android.widget.TextView, sourceRail: String?) {
    val rail = sourceRail?.trim()
    if (!rail.isNullOrBlank()) {
        view.text = view.context.getString(
            com.tvonnet.debridxtreamiptv.R.string.f_back_to,
            rail.uppercase(java.util.Locale.getDefault()),
        )
        view.visibility = android.view.View.VISIBLE
    } else {
        view.visibility = android.view.View.GONE
    }
}

internal fun bindContentTypeLabel(view: android.widget.TextView, isDebrid: Boolean) {
    if (isDebrid) {
        view.text = view.context.getString(com.tvonnet.debridxtreamiptv.R.string.ui_feature_film)
        view.visibility = android.view.View.VISIBLE
    } else {
        view.visibility = android.view.View.GONE
    }
}

internal fun bindDirectorAndPlot(
    directorView: android.widget.TextView,
    plotView: android.widget.TextView,
    director: String?,
    plot: String?,
) {
    if (!director.isNullOrBlank()) {
        directorView.text = director
        directorView.visibility = android.view.View.VISIBLE
    } else {
        directorView.visibility = android.view.View.GONE
    }
    val description = plot?.takeIf { it.isNotBlank() }
    plotView.text = description.orEmpty()
    plotView.visibility =
        if (description.isNullOrBlank()) android.view.View.GONE else android.view.View.VISIBLE
}
