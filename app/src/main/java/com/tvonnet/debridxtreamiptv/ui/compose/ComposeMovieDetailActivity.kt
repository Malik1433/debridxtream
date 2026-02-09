package com.tvonnet.debridxtreamiptv.ui.compose

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dagger.hilt.android.AndroidEntryPoint
import com.tvonnet.debridxtreamiptv.ui.trailer.TrailerActivity

@AndroidEntryPoint
class ComposeMovieDetailActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        setContent {
            MaterialTheme {
                Surface {
                    val viewModel: ComposeMovieDetailViewModel = androidx.lifecycle.viewmodel.compose.viewModel()
                    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
                    
                    // Handle Playback Events
                    androidx.compose.runtime.LaunchedEffect(Unit) {
                        viewModel.playbackEvent.collect { event ->
                            when (event) {
                                is PlaybackEvent.NavigateToPlayer -> {
                                    val intent = com.tvonnet.debridxtreamiptv.player.PlayerActivity.createIntent(
                                        context = this@ComposeMovieDetailActivity,
                                        streamUrl = event.streamUrl,
                                        title = event.title,
                                        contentId = "", // Optional or could be passed
                                        contentType = com.tvonnet.debridxtreamiptv.data.model.ContentType.MOVIE,
                                        playbackSource = com.tvonnet.debridxtreamiptv.player.PlaybackSource.DEBRID,
                                        posterUrl = event.posterUrl,
                                        backdropUrl = event.backdropUrl
                                    )
                                    startActivity(intent)
                                }
                                is PlaybackEvent.Error -> {
                                    android.widget.Toast.makeText(this@ComposeMovieDetailActivity, event.message, android.widget.Toast.LENGTH_LONG).show()
                                }
                            }
                        }
                    }
                    
                    MovieDetailScreen(
                        uiState = uiState,
                        onPlayClick = { 
                            val state = uiState
                            if (state is MovieDetailUiState.Success && state.sources.isNotEmpty()) {
                                viewModel.playMovie(state.sources.first(), state.movie)
                            } else if (state is MovieDetailUiState.Success) {
                                viewModel.playDirect(state.movie)
                            } else {
                                android.widget.Toast.makeText(this@ComposeMovieDetailActivity, "Not ready yet", android.widget.Toast.LENGTH_SHORT).show()
                            }
                        },
                        onTrailerClick = { 
                            val trailerValue = intent.getStringExtra(com.tvonnet.debridxtreamiptv.ui.vod.MovieDetailActivity.EXTRA_MOVIE_TRAILER)
                            if (trailerValue.isNullOrBlank()) {
                                android.widget.Toast.makeText(this@ComposeMovieDetailActivity, "No trailer available", android.widget.Toast.LENGTH_SHORT).show()
                            } else {
                                startActivity(TrailerActivity.createIntent(this@ComposeMovieDetailActivity, trailerValue))
                            }
                        },
                        onSourceClick = { source ->
                            val state = uiState
                            if (state is MovieDetailUiState.Success) {
                                viewModel.playMovie(source, state.movie)
                            }
                        }
                    )
                }
            }
        }
    }
}
