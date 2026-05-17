package com.tvonnet.debridxtreamiptv.ui.compose

import androidx.compose.runtime.Immutable
import com.tvonnet.debridxtreamiptv.data.repository.DebridCacheStatus

@Immutable
sealed interface MovieDetailUiState {
    data object Loading : MovieDetailUiState
    data class Error(val message: String) : MovieDetailUiState
    data class Success(
        val movie: MovieUiModel,
        val sources: List<SourceUiModel> = emptyList(),
        val cast: List<CastMemberUiModel> = emptyList(),
        val selectedSourceId: String? = null,
        val isFavorite: Boolean = false
    ) : MovieDetailUiState
}

@Immutable
data class MovieUiModel(
    val id: String,
    val title: String,
    val overview: String,
    val posterUrl: String?,
    val backdropUrl: String?,
    val releaseYear: String,
    val duration: String,
    val rating: Float,
    val genres: List<String>,
    val pgRating: String? = null
)

@Immutable
data class SourceUiModel(
    val id: String,
    val name: String,
    val quality: String? = null, // 4K, 1080p, etc.
    val size: String? = null,
    val sizeBytes: Long? = null,
    val seeds: Int? = null,
    val languageFlag: String? = null,
    val languages: List<String>? = null,
    val source: String? = null, // RD, AD, etc.
    val isCached: Boolean? = null,
    val cacheStatus: DebridCacheStatus = DebridCacheStatus.UNKNOWN,
    val extension: String? = null // mp4, mkv, etc.
)

@Immutable
data class CastMemberUiModel(
    val id: String,
    val name: String,
    val character: String,
    val imageUrl: String?
)
