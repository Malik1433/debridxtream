package com.tvonnet.debridxtreamiptv.data.debrid.api

import com.google.gson.JsonObject
import okhttp3.RequestBody
import retrofit2.Response
import retrofit2.http.GET
import retrofit2.http.Multipart
import retrofit2.http.POST
import retrofit2.http.Part
import retrofit2.http.Query

interface TorBoxApiService {
    @Multipart
    @POST("v1/api/torrents/createtorrent")
    suspend fun createTorrent(
        @Part("magnet") magnet: RequestBody
    ): Response<JsonObject>

    @GET("v1/api/torrents/mylist")
    suspend fun getTorrentStatus(
        @Query("id") torrentId: Long,
        @Query("bypass_cache") bypassCache: Boolean = true
    ): Response<JsonObject>
}
