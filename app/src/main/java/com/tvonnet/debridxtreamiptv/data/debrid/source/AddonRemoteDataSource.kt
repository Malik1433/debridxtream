package com.tvonnet.debridxtreamiptv.data.debrid.source

import com.tvonnet.debridxtreamiptv.data.Result
import com.tvonnet.debridxtreamiptv.data.debrid.api.AddonCatalogService
import com.tvonnet.debridxtreamiptv.data.debrid.model.AddonDefinition
import com.tvonnet.debridxtreamiptv.data.debrid.model.AddonStream
import com.tvonnet.debridxtreamiptv.data.debrid.model.AddonStreamMapper
import com.tvonnet.debridxtreamiptv.data.debrid.model.MediaFusionResponse
import com.tvonnet.debridxtreamiptv.data.debrid.model.ZileanStream
import com.tvonnet.debridxtreamiptv.data.resultOf
import javax.inject.Inject

/**
 * Fetches data from OTT add-on sources (MediaFusion, Zilean, addon registries) and maps them
 * into normalized stream representations.
 */
class AddonRemoteDataSource @Inject constructor(
    private val service: AddonCatalogService
) {

    suspend fun fetchAddonDefinitions(url: String): Result<List<AddonDefinition>> = resultOf {
        service.fetchAddonDefinitions(url)
    }

    suspend fun fetchMediaFusionStreams(url: String): Result<List<AddonStream>> = resultOf {
        val response: MediaFusionResponse = service.fetchMediaFusionStreams(url)
        response.streams.flatMap { AddonStreamMapper.fromMediaFusionExpanded(it) }
    }

    suspend fun fetchZileanStreams(url: String): Result<List<AddonStream>> = resultOf {
        val response: List<ZileanStream> = service.fetchZileanStreams(url)
        response.map { AddonStreamMapper.fromZilean(it) }
    }
}


