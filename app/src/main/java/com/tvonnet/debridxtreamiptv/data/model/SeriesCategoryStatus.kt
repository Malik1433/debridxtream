package com.tvonnet.debridxtreamiptv.data.model

/**
 * Describes the current availability of a series category endpoint.
 * The repository updates this whenever a fetch succeeds, returns empty,
 * or the server responds with an error (e.g., 404). The UI can observe
 * this status to decide whether to surface a warning message.
 */
data class SeriesCategoryStatus(
    val categoryId: String,
    val state: SeriesCategoryState,
    val message: String? = null,
    val lastUpdatedMillis: Long = System.currentTimeMillis()
) {
    val isActionable: Boolean
        get() = state != SeriesCategoryState.HEALTHY
}

enum class SeriesCategoryState {
    HEALTHY,
    EMPTY,
    TEMPORARILY_UNAVAILABLE
}
