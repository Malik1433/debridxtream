package com.tvonnet.debridxtreamiptv.data.remote

import com.tvonnet.debridxtreamiptv.data.model.*
import okhttp3.ResponseBody
import retrofit2.Response
import retrofit2.http.GET
import retrofit2.http.Query

interface XtreamApiService {
    @GET("player_api.php")
    suspend fun login(
        @Query("username") username: String,
        @Query("password") password: String
    ): Response<XtreamLoginResponse>
    
    @GET("player_api.php")
    suspend fun getLiveCategories(
        @Query("username") username: String,
        @Query("password") password: String,
        @Query("action") action: String = "get_live_categories"
    ): Response<List<XtreamCategory>>
    
    @GET("player_api.php")
    suspend fun getLiveStreams(
        @Query("username") username: String,
        @Query("password") password: String,
        @Query("action") action: String = "get_live_streams",
        @Query("category_id") categoryId: String? = null
    ): Response<List<XtreamStream>>
    
    @GET("player_api.php")
    suspend fun getVodCategories(
        @Query("username") username: String,
        @Query("password") password: String,
        @Query("action") action: String = "get_vod_categories"
    ): Response<List<XtreamCategory>>
    
    @GET("player_api.php")
    suspend fun getVodStreams(
        @Query("username") username: String,
        @Query("password") password: String,
        @Query("action") action: String = "get_vod_streams",
        @Query("category_id") categoryId: String? = null
    ): Response<List<XtreamVodInfo>>
    
    @GET("player_api.php")
    suspend fun getSeriesCategories(
        @Query("username") username: String,
        @Query("password") password: String,
        @Query("action") action: String = "get_series_categories"
    ): Response<List<XtreamCategory>>
    
    @GET("player_api.php")
    suspend fun getSeries(
        @Query("username") username: String,
        @Query("password") password: String,
        @Query("action") action: String = "get_series",
        @Query("category_id") categoryId: String? = null,
        @Query("series_id") seriesId: String? = null
    ): Response<List<XtreamSeriesInfo>>

    @GET("player_api.php")
    suspend fun getSeriesEpisodes(
        @Query("username") username: String,
        @Query("password") password: String,
        @Query("action") action: String = "get_show_episodes",
        @Query("series_id") seriesId: String
    ): Response<com.google.gson.JsonElement>
    
    @GET("player_api.php")
    suspend fun getSeriesInfo(
        @Query("username") username: String,
        @Query("password") password: String,
        @Query("action") action: String = "get_series_info",
        @Query("series_id") seriesId: String
    ): Response<XtreamSeriesDetailResponse>
    
    @GET("xmltv.php")
    suspend fun getEpg(
        @Query("username") username: String,
        @Query("password") password: String
    ): Response<ResponseBody>

    /**
     * Lightweight per-channel EPG (much safer on low-memory TV devices than full XMLTV).
     * Many Xtream providers support this action.
     */
    @GET("player_api.php")
    suspend fun getShortEpg(
        @Query("username") username: String,
        @Query("password") password: String,
        @Query("action") action: String = "get_short_epg",
        @Query("stream_id") streamId: String,
        @Query("limit") limit: Int = 2
    ): Response<XtreamShortEpgResponse>
}
