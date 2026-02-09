package com.tvonnet.debridxtreamiptv.ui.sources

import com.tvonnet.debridxtreamiptv.data.repository.MovieSource

data class SourceFilterState(
    val cachedOnly: Boolean = false,
    val maxSizeBytes: Long? = null,
    val preferredLanguage: String? = null
)

data class SizeFilterOption(
    val label: String,
    val maxSizeBytes: Long?
)

object SourceFilterUtils {
    private const val GB_BYTES = 1024L * 1024L * 1024L

    val SIZE_OPTIONS = listOf(
        SizeFilterOption("No cap", null),
        SizeFilterOption("2 GB", 2L * GB_BYTES),
        SizeFilterOption("5 GB", 5L * GB_BYTES),
        SizeFilterOption("10 GB", 10L * GB_BYTES),
        SizeFilterOption("25 GB", 25L * GB_BYTES)
    )

    fun apply(sources: List<MovieSource>, state: SourceFilterState): List<MovieSource> {
        var filtered = sources

        if (state.cachedOnly) {
            filtered = filtered.filter { it.isCached == true }
        }

        val maxSize = state.maxSizeBytes
        if (maxSize != null) {
            filtered = filtered.filter { source ->
                val size = source.sizeBytes
                size == null || size <= maxSize
            }
        }

        val preferred = state.preferredLanguage?.lowercase()
        if (preferred != null) {
            filtered = filtered.sortedWith(
                compareByDescending<MovieSource> { getLanguageMatchScore(it, preferred) }
                    .thenByDescending { it.isCached == true }
                    .thenByDescending { it.seeders ?: -1 }
            )
        }

        return filtered
    }

    private fun getLanguageMatchScore(source: MovieSource, preferred: String): Int {
        val languages = source.languages?.map { it.lowercase() } ?: return 0
        return when {
            languages.contains(preferred) -> 2
            languages.contains("multi") -> 1
            else -> 0
        }
    }
}
