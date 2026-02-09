package com.tvonnet.debridxtreamiptv.data.remote.adapter

import com.google.gson.Gson
import com.google.gson.JsonDeserializationContext
import com.google.gson.JsonDeserializer
import com.google.gson.JsonElement
import com.google.gson.JsonNull
import com.google.gson.JsonPrimitive
import com.tvonnet.debridxtreamiptv.data.model.XtreamEpisodeDetail
import java.lang.reflect.Type

/**
 * Xtream providers occasionally send `info` as either an object or an array.
 * This adapter normalizes the payload so Retrofit always receives a single detail object.
 */
class EpisodeDetailJsonAdapter : JsonDeserializer<XtreamEpisodeDetail?> {
    private val fallbackGson = Gson()

    override fun deserialize(
        json: JsonElement?,
        typeOfT: Type?,
        context: JsonDeserializationContext?
    ): XtreamEpisodeDetail? {
        if (json == null || json is JsonNull || context == null) {
            return null
        }

        val payload = when {
            json.isJsonObject -> json
            json.isJsonArray -> {
                json.asJsonArray.firstOrNull { element ->
                    element != null && element.isJsonObject
                }
            }
            json is JsonPrimitive && json.asJsonPrimitive.isString && json.asString.isBlank() -> null
            else -> null
        } ?: return null

        return fallbackGson.fromJson(payload, XtreamEpisodeDetail::class.java)
    }
}
