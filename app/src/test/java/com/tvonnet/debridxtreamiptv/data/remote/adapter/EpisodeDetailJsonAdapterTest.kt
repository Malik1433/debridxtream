package com.tvonnet.debridxtreamiptv.data.remote.adapter

import com.google.gson.GsonBuilder
import com.tvonnet.debridxtreamiptv.data.model.XtreamEpisodeDetail
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class EpisodeDetailJsonAdapterTest {

    private val gson = GsonBuilder()
        .registerTypeAdapter(XtreamEpisodeDetail::class.java, EpisodeDetailJsonAdapter())
        .create()

    @Test
    fun `deserializes episode detail object payload`() {
        val detail = gson.fromJson(
            """
            {
                "plot": "Plot",
                "genre": "Drama",
                "cover": "cover.jpg"
            }
            """.trimIndent(),
            XtreamEpisodeDetail::class.java
        )

        assertEquals("Plot", detail?.plot)
        assertEquals("Drama", detail?.genre)
        assertEquals("cover.jpg", detail?.cover)
    }

    @Test
    fun `deserializes first entry when payload is array`() {
        val detail = gson.fromJson(
            """
            [
                {
                    "plot": "Array Plot",
                    "genre": "Comedy"
                },
                {
                    "plot": "Ignored"
                }
            ]
            """.trimIndent(),
            XtreamEpisodeDetail::class.java
        )

        assertEquals("Array Plot", detail?.plot)
        assertEquals("Comedy", detail?.genre)
    }

    @Test
    fun `returns null for blank string payload`() {
        val detail = gson.fromJson("\"\"", XtreamEpisodeDetail::class.java)
        assertNull(detail)
    }
}

