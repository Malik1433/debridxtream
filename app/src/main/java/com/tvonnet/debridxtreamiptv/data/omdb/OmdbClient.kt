package com.tvonnet.debridxtreamiptv.data.omdb

import com.google.gson.Gson
import com.google.gson.JsonObject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request

/** Ratings fetched from OMDb for a movie (any field may be null / "N/A"). */
data class OmdbRatings(
    val imdbRating: String? = null,      // e.g. "8.6"
    val rottenTomatoes: String? = null,  // e.g. "92%"
    val metascore: String? = null,       // e.g. "79"
    val rated: String? = null            // e.g. "PG-13"
)

/**
 * Minimal OMDb (omdbapi.com) client used to enrich the Movie Detail ratings row
 * with IMDb + Rotten Tomatoes scores (TMDB already provides its own score).
 */
object OmdbClient {

    private const val API_KEY = "42b01442"
    private val client by lazy { OkHttpClient() }
    private val gson = Gson()

    suspend fun fetchRatings(imdbId: String?): OmdbRatings? = withContext(Dispatchers.IO) {
        val id = imdbId?.trim()
        if (id.isNullOrBlank() || !id.startsWith("tt")) return@withContext null
        try {
            val url = "https://www.omdbapi.com/?apikey=$API_KEY&i=$id&tomatoes=true"
            val request = Request.Builder().url(url).build()
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return@withContext null
                val body = response.body?.string() ?: return@withContext null
                val json = gson.fromJson(body, JsonObject::class.java) ?: return@withContext null
                if (!json.get("Response")?.asString.equals("True", ignoreCase = true)) {
                    return@withContext null
                }

                fun field(name: String): String? =
                    json.get(name)?.takeIf { !it.isJsonNull }?.asString
                        ?.trim()?.takeIf { it.isNotBlank() && it != "N/A" }

                var rottenTomatoes: String? = null
                json.getAsJsonArray("Ratings")?.forEach { element ->
                    val obj = element.asJsonObject
                    if (obj.get("Source")?.asString == "Rotten Tomatoes") {
                        rottenTomatoes = obj.get("Value")?.asString?.takeIf { it != "N/A" }
                    }
                }

                OmdbRatings(
                    imdbRating = field("imdbRating"),
                    rottenTomatoes = rottenTomatoes,
                    metascore = field("Metascore"),
                    rated = field("Rated")
                )
            }
        } catch (e: Exception) {
            null
        }
    }
}
