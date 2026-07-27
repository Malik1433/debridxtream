package com.tvonnet.debridxtreamiptv.features.seriesv2.ui

/**
 * C4: the page-scoped facts every Series-detail collaborator needs, in one place.
 *
 * [trailer] is the one piece of genuinely shared mutable state on this screen: it can come from
 * the launch arguments, from the provider's `get_series_info`, or from the TMDB pass — and the
 * Trailer button has to reflect whichever arrived last. Holding it here is what keeps the three
 * writers from each carrying their own copy.
 *
 * [isViewAlive] is the fragment's `_binding != null` check, passed down so an async continuation
 * can bail out after `onDestroyView` exactly as it did before the split.
 */
class SeriesDetailPage(
    val seriesId: String?,
    private val posterUrl: String?,
    private val backdropUrl: String?,
    val isViewAlive: () -> Boolean,
) {
    var trailer: String? = null

    /** Poster first, backdrop as the fallback — what a favourite entry is stored with. */
    val favoriteIconUrl: String? get() = posterUrl ?: backdropUrl
}
