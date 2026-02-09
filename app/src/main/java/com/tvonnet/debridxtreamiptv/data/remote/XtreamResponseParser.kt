package com.tvonnet.debridxtreamiptv.data.remote

import android.util.Log
import com.google.gson.Gson
import com.google.gson.JsonElement
import com.google.gson.reflect.TypeToken
import com.tvonnet.debridxtreamiptv.data.model.XtreamEpisodeInfo

/**
 * A resilient parser for Xtream Codes API responses.
 * Handles inconsistent JSON shapes (e.g. empty array [] vs empty object {} vs boolean false)
 * that frequently cause crashes in standard Retrofit/Gson implementations.
 */
object XtreamResponseParser {
    private const val TAG = "XtreamResponseParser"
    private val gson = Gson()

    /**
     * Safely parses the "get_series_episodes" (get_show_episodes) response.
     * Guaranteed to never throw an exception.
     *
     * @param json The raw JsonElement from Retrofit
     * @return A map of Season Number -> List of Episodes. Empty map if parsing fails.
     */
    fun parseEpisodes(json: JsonElement?): Map<String, List<XtreamEpisodeInfo>> {
        if (json == null || json.isJsonNull) {
            Log.w(TAG, "Received null JSON for episodes")
            return emptyMap()
        }

        return try {
            // Case 1: Primitives (often "false", "", 0 sent by php on failure)
            if (json.isJsonPrimitive) {
                Log.w(TAG, "Received primitive JSON for episodes (likely error): ${json.asString}")
                return emptyMap()
            }

            // Case 2: Standard Map Format {"1": [ep...], "2": [ep...]}
            // Case 2: Standard Map Format {"1": [ep...], "2": [ep...]}
            if (json.isJsonObject) {
                val resultMap = mutableMapOf<String, List<XtreamEpisodeInfo>>()
                val listType = object : TypeToken<List<XtreamEpisodeInfo>>() {}.type
                
                for ((key, value) in json.asJsonObject.entrySet()) {
                    try {
                        if (value.isJsonArray) {
                            val episodes: List<XtreamEpisodeInfo>? = gson.fromJson(value, listType)
                            if (!episodes.isNullOrEmpty()) {
                                resultMap[key] = episodes
                            }
                        } else {
                           // Skip non-array values (metadata fields etc)
                           Log.w(TAG, "Skipping non-array value for key: $key")
                        }
                    } catch (e: Exception) {
                        Log.w(TAG, "Failed to parse episodes for season key: $key", e)
                    }
                }
                return resultMap
            }

            // Case 3: Array Format [ep1, ep2...] or Empty Array []
            // Some providers return a flat list instead of a map, or [] when no episodes exist.
            if (json.isJsonArray) {
                val array = json.asJsonArray
                if (array.size() == 0) {
                    return emptyMap()
                }

                Log.d(TAG, "Received Array JSON for episodes. Attempting to group by season.")
                val listType = object : TypeToken<List<XtreamEpisodeInfo>>() {}.type
                val flatList: List<XtreamEpisodeInfo> = gson.fromJson(json, listType) ?: emptyList()

                // Group by season. Default to "1" if season number is missing.
                return flatList.groupBy { episode -> 
                    val season = episode.season?.toString() 
                        ?: "1"
                    season
                }
            }

            Log.w(TAG, "Unknown JSON type for episodes: ${json.javaClass.simpleName}")
            emptyMap()

        } catch (e: Exception) {
            Log.e(TAG, "Critical parsing error in parseEpisodes", e)
            emptyMap()
        }
    }
}
