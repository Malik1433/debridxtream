package com.tvonnet.debridxtreamiptv.ui.compose

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import com.tvonnet.debridxtreamiptv.ui.compose.components.BackdropHeader
import com.tvonnet.debridxtreamiptv.ui.compose.components.MovieInfoSection
import com.tvonnet.debridxtreamiptv.ui.compose.components.SourceListSection

@Composable
fun MovieDetailScreen(
    uiState: MovieDetailUiState,
    onPlayClick: () -> Unit = {},
    onTrailerClick: () -> Unit = {},
    onSourceClick: (SourceUiModel) -> Unit = {}
) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xFF0f1014)) // Dark background
    ) {
        when (uiState) {
            is MovieDetailUiState.Loading -> {
                CircularProgressIndicator(modifier = Modifier.align(Alignment.Center))
            }
            is MovieDetailUiState.Error -> {
                Text(
                    text = uiState.message,
                    color = Color.Red,
                    modifier = Modifier.align(Alignment.Center)
                )
            }
            is MovieDetailUiState.Success -> {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .verticalScroll(rememberScrollState())
                ) {
                    BackdropHeader(backdropUrl = uiState.movie.backdropUrl)
                    
                    MovieInfoSection(
                        movie = uiState.movie,
                        onPlayClick = onPlayClick,
                        onTrailerClick = onTrailerClick
                    )

                    if (uiState.sources.isNotEmpty()) {
                        SourceListSection(
                            sources = uiState.sources,
                            onSourceClick = onSourceClick
                        )
                    }
                }
            }
        }
    }
}
