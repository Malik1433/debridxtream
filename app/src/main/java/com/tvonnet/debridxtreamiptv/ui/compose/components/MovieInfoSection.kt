package com.tvonnet.debridxtreamiptv.ui.compose.components

import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.tvonnet.debridxtreamiptv.ui.compose.MovieUiModel

@Composable
fun MovieInfoSection(
    movie: MovieUiModel,
    onPlayClick: () -> Unit,
    onTrailerClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp)
    ) {
        // Title
        Text(
            text = movie.title,
            style = MaterialTheme.typography.headlineMedium,
            fontWeight = FontWeight.Bold,
            color = Color.White
        )
        
        Spacer(modifier = Modifier.height(8.dp))
        
        MovieMetadataRow(movie)

        Spacer(modifier = Modifier.height(16.dp))

        MovieActionButtons(onPlayClick, onTrailerClick)

        Spacer(modifier = Modifier.height(16.dp))

        // Overview
        Text(
            text = movie.overview,
            style = MaterialTheme.typography.bodyMedium,
            color = Color.LightGray,
            lineHeight = 20.sp
        )
    }
}

@Composable
private fun MovieMetadataRow(movie: MovieUiModel) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(
            imageVector = Icons.Default.Star,
            contentDescription = "Rating",
            tint = Color(0xFFFFC107), // Gold
            modifier = Modifier.size(16.dp)
        )
        Spacer(modifier = Modifier.width(4.dp))
        Text(
            text = String.format(java.util.Locale.US, "%.1f", movie.rating),
            color = Color.White,
            fontWeight = FontWeight.Bold
        )

        Spacer(modifier = Modifier.width(16.dp))

        Text(text = movie.releaseYear, color = Color.Gray)
        Spacer(modifier = Modifier.width(16.dp))
        Text(text = movie.duration, color = Color.Gray)

        if (movie.pgRating != null) {
            Spacer(modifier = Modifier.width(16.dp))
            Surface(
                color = Color.DarkGray,
                shape = MaterialTheme.shapes.small
            ) {
                Text(
                    text = movie.pgRating,
                    modifier = Modifier.padding(horizontal = 4.dp, vertical = 2.dp),
                    style = MaterialTheme.typography.labelSmall,
                    color = Color.White
                )
            }
        }
    }
}

@Composable
private fun MovieActionButtons(onPlayClick: () -> Unit, onTrailerClick: () -> Unit) {
    Row(modifier = Modifier.fillMaxWidth()) {
        Button(
            onClick = onPlayClick,
            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFE50914)), // Netflix Red
            modifier = Modifier.weight(1f)
        ) {
            Icon(Icons.Default.PlayArrow, contentDescription = null)
            Spacer(modifier = Modifier.width(8.dp))
            Text("Play")
        }

        Spacer(modifier = Modifier.width(8.dp))

        OutlinedButton(
            onClick = onTrailerClick,
            colors = ButtonDefaults.outlinedButtonColors(contentColor = Color.White),
            modifier = Modifier.weight(1f)
        ) {
            Text("Trailer")
        }
    }
}
