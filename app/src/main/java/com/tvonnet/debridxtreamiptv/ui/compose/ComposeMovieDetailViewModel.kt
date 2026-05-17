package com.tvonnet.debridxtreamiptv.ui.compose

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.tvonnet.debridxtreamiptv.data.debrid.repository.UnifiedSourceProvider
import com.tvonnet.debridxtreamiptv.data.debrid.source.TmdbRemoteDataSource
import com.tvonnet.debridxtreamiptv.data.debrid.util.LanguageParser
import com.tvonnet.debridxtreamiptv.data.onFailure
import com.tvonnet.debridxtreamiptv.data.onSuccess
import com.tvonnet.debridxtreamiptv.ui.vod.MovieDetailActivity
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Locale
import javax.inject.Inject

@HiltViewModel
class ComposeMovieDetailViewModel @Inject constructor(
    private val savedStateHandle: SavedStateHandle,
    private val tmdbRemoteDataSource: TmdbRemoteDataSource,
    private val unifiedSourceProvider: UnifiedSourceProvider,
    private val debridPlaybackRepository: com.tvonnet.debridxtreamiptv.data.debrid.repository.DebridPlaybackRepository,
    @dagger.hilt.android.qualifiers.ApplicationContext private val context: android.content.Context
) : ViewModel() {

    private val _uiState = MutableStateFlow<MovieDetailUiState>(MovieDetailUiState.Loading)
    val uiState: StateFlow<MovieDetailUiState> = _uiState.asStateFlow()

    private val _playbackEvent = MutableSharedFlow<PlaybackEvent>()
    val playbackEvent: SharedFlow<PlaybackEvent> = _playbackEvent.asSharedFlow()

    init {
        loadData()
    }

    private fun loadData() {
        val movieId = savedStateHandle.get<String>(MovieDetailActivity.EXTRA_MOVIE_ID)
        val categoryId = savedStateHandle.get<String>(MovieDetailActivity.EXTRA_MOVIE_CATEGORY_ID)
        
        if (movieId == null) {
            _uiState.value = MovieDetailUiState.Error("Movie ID not found")
            return
        }

        if (categoryId == "debrid") {
            fetchTmdbDetails(movieId)
        } else {
            // Xtream Codes flow - construct from intent data
            val movie = MovieUiModel(
                id = movieId,
                title = savedStateHandle[MovieDetailActivity.EXTRA_MOVIE_NAME] ?: "",
                overview = savedStateHandle[MovieDetailActivity.EXTRA_MOVIE_PLOT] ?: "",
                posterUrl = savedStateHandle[MovieDetailActivity.EXTRA_MOVIE_ICON],
                backdropUrl = savedStateHandle[MovieDetailActivity.EXTRA_MOVIE_BACKDROP],
                releaseYear = savedStateHandle[MovieDetailActivity.EXTRA_MOVIE_YEAR] ?: "",
                duration = savedStateHandle[MovieDetailActivity.EXTRA_MOVIE_DURATION] ?: "",
                rating = savedStateHandle.get<String>(MovieDetailActivity.EXTRA_MOVIE_RATING)?.toFloatOrNull() ?: 0f,
                genres = savedStateHandle.get<String>(MovieDetailActivity.EXTRA_MOVIE_GENRE)?.split(",")?.map { it.trim() } ?: emptyList()
            )
            
            // User request: do not search sources across categories for IPTV VOD movies.
            // We only need the direct Xtream URL built from the selected movie's stream_id + container extension.
            _uiState.value = MovieDetailUiState.Success(movie = movie, sources = emptyList())
        }
    }

    private fun fetchTmdbDetails(tmdbId: String) {
        viewModelScope.launch {
            val id = tmdbId.toIntOrNull()
            if (id != null) {
                val result = withContext(Dispatchers.IO) {
                    tmdbRemoteDataSource.getMovieDetails(id)
                }

                result.onSuccess { details ->
                    val movie = MovieMapper.mapTmdbToUiModel(details)
                    _uiState.value = MovieDetailUiState.Success(movie = movie, sources = emptyList())
                    loadSources(movie, details.imdbId, "debrid")
                }.onFailure { e ->
                    _uiState.value = MovieDetailUiState.Error("Failed to load details: ${e.message}")
                }
            } else {
                _uiState.value = MovieDetailUiState.Error("Invalid TMDB ID")
            }
        }
    }

    private fun loadSources(movie: MovieUiModel, imdbId: String?, categoryId: String?) {
        viewModelScope.launch {
            try {
                val sources = withContext(Dispatchers.IO) {
                    unifiedSourceProvider.getMovieSources(
                        streamId = movie.id,
                        title = movie.title,
                        primaryCategoryId = categoryId,
                        yearHint = movie.releaseYear,
                        imdbId = imdbId
                    )
                }

                val sourceUiModels = sources.map { source ->
                    val languages = source.languages ?: emptyList()
                    val quality = source.quality?.trim()
                        ?.takeIf { it.isNotBlank() && it.lowercase(Locale.US) != "unknown" }
                        ?: extractQuality(source.label)
                    val sizeLabel = source.sizeBytes?.let { formatSizeLabel(it) }
                        ?: extractSize(source.label).takeIf { it.isNotBlank() }
                    SourceUiModel(
                        id = source.stream.stream_id ?: source.stream.direct_source ?: source.label.hashCode().toString(),
                        name = source.label,
                        quality = quality,
                        size = sizeLabel,
                        sizeBytes = source.sizeBytes,
                        languageFlag = languages.takeIf { it.isNotEmpty() }?.let { LanguageParser.getLanguageFlag(it) },
                        languages = languages.takeIf { it.isNotEmpty() },
                      seeds = source.seeders,
                      source = source.provider,
                      isCached = source.isCached,
                      cacheStatus = source.cacheStatus,
                      extension = source.stream.container_extension
                  )
                }

                _uiState.value = MovieDetailUiState.Success(
                    movie = movie,
                    sources = sourceUiModels
                )
            } catch (e: Exception) {
                _uiState.value = MovieDetailUiState.Success(
                    movie = movie,
                    sources = emptyList()
                )
                _playbackEvent.emit(PlaybackEvent.Error("Failed to load links: ${e.message}"))
            }
        }
    }

    fun playMovie(source: SourceUiModel, movie: MovieUiModel) {
        viewModelScope.launch {
            try {
                val credentialsPrefs = com.tvonnet.debridxtreamiptv.data.prefs.CredentialsPreferences(context)
                val serverUrl = credentialsPrefs.getServerUrl()
                val username = credentialsPrefs.getUsername()
                val password = credentialsPrefs.getPassword()

                if (serverUrl.isNullOrBlank() || username.isNullOrBlank() || password.isNullOrBlank()) {
                    _playbackEvent.emit(PlaybackEvent.Error("Missing credentials"))
                    return@launch
                }

                // Check if it's a Debrid source
                val isDebrid = source.name.contains("Torrentio", ignoreCase = true) ||
                               source.name.contains("MediaFusion", ignoreCase = true) ||
                               source.name.contains("PureFire", ignoreCase = true) ||
                               source.id.length > 20 // Heuristic for hash/magnet

                if (isDebrid) {
                    // Resolve Debrid Link
                    val infoHash = source.id // We mapped stream_id/infoHash to id
                    val magnet = "" // We don't have the magnet here easily, but infoHash is usually enough for RD
                    
                    val result = withContext(Dispatchers.IO) {
                        debridPlaybackRepository.resolveDebridUrl(
                            infoHash = infoHash,
                            magnet = magnet
                        )
                    }

                    result.onSuccess { streamUrl ->
                        _playbackEvent.emit(PlaybackEvent.NavigateToPlayer(
                            streamUrl = streamUrl,
                            title = movie.title,
                            posterUrl = movie.posterUrl,
                            backdropUrl = movie.backdropUrl
                        ))
                    }.onFailure { e ->
                        _playbackEvent.emit(PlaybackEvent.Error("Debrid resolution failed: ${e.message}"))
                    }
                } else {
                    // Standard Xtream Codes VOD
                    val streamId = source.id
                    val containerExt = source.extension ?: "mp4" // Use mapped extension or default
                    val baseUrl = serverUrl.trimEnd('/')
                    val streamUrl = "$baseUrl/movie/$username/$password/$streamId.$containerExt"

                    _playbackEvent.emit(PlaybackEvent.NavigateToPlayer(
                        streamUrl = streamUrl,
                        title = movie.title,
                        posterUrl = movie.posterUrl,
                        backdropUrl = movie.backdropUrl
                    ))
                }
            } catch (e: Exception) {
                _playbackEvent.emit(PlaybackEvent.Error("Playback failed: ${e.message}"))
            }
        }
    }

    fun playDirect(movie: MovieUiModel) {
        viewModelScope.launch {
            try {
                val credentialsPrefs = com.tvonnet.debridxtreamiptv.data.prefs.CredentialsPreferences(context)
                val serverUrl = credentialsPrefs.getServerUrl()
                val username = credentialsPrefs.getUsername()
                val password = credentialsPrefs.getPassword()

                if (serverUrl.isNullOrBlank() || username.isNullOrBlank() || password.isNullOrBlank()) {
                    _playbackEvent.emit(PlaybackEvent.Error("Missing credentials"))
                    return@launch
                }

                val ext = savedStateHandle.get<String>(MovieDetailActivity.EXTRA_MOVIE_CONTAINER)?.ifBlank { "mp4" } ?: "mp4"
                val baseUrl = serverUrl.trimEnd('/')
                val streamUrl = "$baseUrl/movie/$username/$password/${movie.id}.$ext"

                _playbackEvent.emit(
                    PlaybackEvent.NavigateToPlayer(
                        streamUrl = streamUrl,
                        title = movie.title,
                        posterUrl = movie.posterUrl,
                        backdropUrl = movie.backdropUrl
                    )
                )
            } catch (e: Exception) {
                _playbackEvent.emit(PlaybackEvent.Error("Playback failed: ${e.message}"))
            }
        }
    }

    private fun extractQuality(label: String): String? {
        return when {
            label.contains("4K", true) -> "4K"
            label.contains("2160", true) -> "4K"
            label.contains("1080p", true) -> "1080P"
            label.contains("720p", true) -> "720P"
            label.contains("480p", true) -> "480P"
            else -> null
        }
    }

    private fun extractSize(label: String): String {
        val regex = Regex("\\((\\d+(\\.\\d+)?[KMGT]B)\\)")
        return regex.find(label)?.groupValues?.get(1) ?: ""
    }

    private fun formatSizeLabel(sizeBytes: Long): String {
        val gb = 1024.0 * 1024.0 * 1024.0
        val mb = 1024.0 * 1024.0
        return if (sizeBytes >= gb) {
            String.format(Locale.US, "%.1f GB", sizeBytes / gb)
        } else {
            String.format(Locale.US, "%.0f MB", sizeBytes / mb)
        }
    }
}

sealed class PlaybackEvent {
    data class NavigateToPlayer(
        val streamUrl: String,
        val title: String,
        val posterUrl: String?,
        val backdropUrl: String?
    ) : PlaybackEvent()
    data class Error(val message: String) : PlaybackEvent()
}
