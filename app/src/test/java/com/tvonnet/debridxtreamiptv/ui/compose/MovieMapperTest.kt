package com.tvonnet.debridxtreamiptv.ui.compose

import com.tvonnet.debridxtreamiptv.data.model.XtreamVodInfo
import org.junit.Assert.assertEquals
import org.junit.Test

class MovieMapperTest {

    @Test
    fun `map XtreamVodInfo to MovieUiModel`() {
        val vodInfo = XtreamVodInfo(
            num = 1,
            name = "Test Movie",
            stream_type = "movie",
            stream_id = "123",
            stream_icon = "http://example.com/icon.png",
            rating = "8.5",
            rating_5based = 4.25,
            added = "123456789",
            category_id = "1",
            category_ids = listOf(1),
            container_extension = "mp4",
            custom_sid = null,
            direct_source = "",
            releaseDate = "2023-01-01",
            duration = "120 min",
            plot = "Test Plot",
            cast = "Actor 1, Actor 2",
            director = "Director 1",
            genre = "Action, Drama",
            youtube_trailer = "trailer_id",
            cover = "http://example.com/cover.png",
            rating_imdb = "8.5"
        )

        val movieUiModel = MovieMapper.mapToUiModel(vodInfo)

        assertEquals("123", movieUiModel.id)
        assertEquals("Test Movie", movieUiModel.title)
        assertEquals("Test Plot", movieUiModel.overview)
        assertEquals("http://example.com/icon.png", movieUiModel.posterUrl)
        // Assuming cover is backdrop for now, or we might need to adjust logic
        assertEquals("http://example.com/cover.png", movieUiModel.backdropUrl) 
        assertEquals("2023", movieUiModel.releaseYear)
        assertEquals("120 min", movieUiModel.duration)
        assertEquals(8.5f, movieUiModel.rating, 0.0f)
        assertEquals(listOf("Action", "Drama"), movieUiModel.genres)
    }
}
