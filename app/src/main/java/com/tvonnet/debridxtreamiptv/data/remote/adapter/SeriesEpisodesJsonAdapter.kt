package com.tvonnet.debridxtreamiptv.data.remote.adapter

import com.google.gson.JsonDeserializationContext
import com.google.gson.JsonDeserializer
import com.google.gson.JsonElement
import com.google.gson.reflect.TypeToken
import com.tvonnet.debridxtreamiptv.data.model.XtreamEpisodeInfo
import java.lang.reflect.Type

/**
 * Handles the "episodes" field often returned by Xtream Codes API.
 * 
 * Standard format: "episodes": { "1": [ ...eps... ], "2": [ ...eps... ] } (Map)
 * Malformed format: "episodes": [] (Empty List)
 * 
 * This adapter ensures we always return a Map, avoiding JsonSyntaxException.
 */
class SeriesEpisodesJsonAdapter : JsonDeserializer<Map<String, List<XtreamEpisodeInfo>>?> {
    override fun deserialize(
        json: JsonElement,
        typeOfT: Type,
        context: JsonDeserializationContext
    ): Map<String, List<XtreamEpisodeInfo>>? {
        // Case 1: Empty Array -> Return empty map
        if (json.isJsonArray) {
            return emptyMap()
        }

        // Case 2: Object -> Delegate to standard deserialization
        if (json.isJsonObject) {
            val type = object : TypeToken<Map<String, List<XtreamEpisodeInfo>>>() {}.type
            return context.deserialize(json, type)
        }

        // Case 3: Null or Primitive -> Return null or empty
        return null
    }
}
