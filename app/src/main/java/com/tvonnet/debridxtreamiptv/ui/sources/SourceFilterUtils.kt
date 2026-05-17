package com.tvonnet.debridxtreamiptv.ui.sources

import com.tvonnet.debridxtreamiptv.data.repository.DebridCacheStatus
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
            filtered = filtered.filter { it.cacheStatus == DebridCacheStatus.VERIFIED_CACHED }
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
                    .thenByDescending { getCachePriority(it) }
                    .thenByDescending { it.seeders ?: -1 }
            )
        } else {
            filtered = filtered.sortedWith(
                compareByDescending<MovieSource> { getCachePriority(it) }
                    .thenByDescending { getRecoveryLanguageScore(it) }
                    .thenByDescending { it.seeders ?: -1 }
            )
        }

        return filtered
    }

    fun hasCacheConfidence(source: MovieSource): Boolean {
        return source.cacheStatus != DebridCacheStatus.UNKNOWN || source.isCached != null
    }

    fun isPlaybackReady(source: MovieSource): Boolean {
        return source.cacheStatus == DebridCacheStatus.VERIFIED_CACHED ||
            source.cacheStatus == DebridCacheStatus.DIRECT_STREAM
    }

    private fun getCachePriority(source: MovieSource): Int {
        return when (source.cacheStatus) {
            DebridCacheStatus.VERIFIED_CACHED -> 4
            DebridCacheStatus.DIRECT_STREAM -> 3
            DebridCacheStatus.UNKNOWN -> 1
            DebridCacheStatus.NOT_CACHED -> 0
        }
    }

    private fun getLanguageMatchScore(source: MovieSource, preferred: String): Int {
        val languages = source.languages?.map { it.lowercase() } ?: return 0
        return when {
            languages.contains(preferred) -> 2
            languages.contains("multi") -> 1
            else -> 0
        }
    }

    private fun getRecoveryLanguageScore(source: MovieSource): Int {
        val languages = source.languages?.map { it.lowercase() } ?: return 0
        return when {
            languages.contains("hi") || languages.contains("de") -> 2
            languages.contains("multi") -> 1
            else -> 0
        }
    }
}
