package com.tvonnet.debridxtreamiptv.data.debrid.api

import com.tvonnet.debridxtreamiptv.data.debrid.model.AddonDefinition
import com.tvonnet.debridxtreamiptv.data.debrid.model.MediaFusionResponse
import com.tvonnet.debridxtreamiptv.data.debrid.model.ZileanStream
import retrofit2.http.GET
import retrofit2.http.Url

/**
 * Retrofit service capable of fetching different add-on catalog payloads.
 * Requests rely on dynamic URLs because each add-on provides its own endpoint.
 */
interface AddonCatalogService {

    @GET
    suspend fun fetchMediaFusionStreams(@Url url: String): MediaFusionResponse

    @GET
    suspend fun fetchZileanStreams(@Url url: String): List<ZileanStream>

    @GET
    suspend fun fetchAddonDefinitions(@Url url: String): List<AddonDefinition>
}


