package com.tvonnet.debridxtreamiptv.data.debrid.api

import com.tvonnet.debridxtreamiptv.data.debrid.model.RealDebridCredentialsResponse
import com.tvonnet.debridxtreamiptv.data.debrid.model.RealDebridDeviceCodeResponse
import com.tvonnet.debridxtreamiptv.data.debrid.model.RealDebridTokenResponse
import com.tvonnet.debridxtreamiptv.data.debrid.model.RealDebridTorrentAddResponse
import com.tvonnet.debridxtreamiptv.data.debrid.model.RealDebridTorrentInfoResponse
import com.tvonnet.debridxtreamiptv.data.debrid.model.RealDebridUnrestrictLinkResponse
import com.tvonnet.debridxtreamiptv.data.debrid.model.RealDebridUserResponse
import retrofit2.http.Field
import retrofit2.http.FormUrlEncoded
import retrofit2.http.GET
import retrofit2.http.POST
import retrofit2.http.Path
import retrofit2.http.Query

/**
 * Retrofit interface for Real-Debrid REST + OAuth endpoints.
 * Covers device authorization, token lifecycle, torrent management, and unrestricted link retrieval.
 */
interface RealDebridApiService {

    @GET("oauth/v2/device/code")
    suspend fun requestDeviceCode(
        @Query("client_id") clientId: String,
        @Query("new_credentials") newCredentials: String = "yes"
    ): RealDebridDeviceCodeResponse

    @GET("oauth/v2/device/credentials")
    suspend fun pollDeviceCredentials(
        @Query("client_id") clientId: String,
        @Query("code") code: String
    ): RealDebridCredentialsResponse

    @FormUrlEncoded
    @POST("oauth/v2/token")
    suspend fun exchangeDeviceCodeForToken(
        @Field("client_id") clientId: String,
        @Field("client_secret") clientSecret: String,
        @Field("code") code: String,
        @Field("grant_type") grantType: String = "http://oauth.net/grant_type/device/1.0",
        @Field("redirect_uri") redirectUri: String = "urn:ietf:wg:oauth:2.0:oob"
    ): RealDebridTokenResponse

    @FormUrlEncoded
    @POST("oauth/v2/token")
    suspend fun refreshAccessToken(
        @Field("client_id") clientId: String,
        @Field("client_secret") clientSecret: String,
        @Field("refresh_token") refreshToken: String,
        @Field("grant_type") grantType: String = "refresh_token"
    ): RealDebridTokenResponse

    @GET("rest/1.0/user")
    suspend fun fetchUserInfo(): RealDebridUserResponse

    @FormUrlEncoded
    @POST("rest/1.0/torrents/addMagnet")
    suspend fun addMagnet(
        @Field("magnet") magnet: String,
        @Field("host") host: String? = null
    ): RealDebridTorrentAddResponse

    @GET("rest/1.0/torrents/info/{id}")
    suspend fun getTorrentInfo(@Path("id") torrentId: String): RealDebridTorrentInfoResponse

    @FormUrlEncoded
    @POST("rest/1.0/unrestrict/link")
    suspend fun unrestrictLink(
        @Field("link") link: String,
        @Field("password") password: String? = null
    ): RealDebridUnrestrictLinkResponse

    @FormUrlEncoded
    @POST("rest/1.0/torrents/selectFiles/{id}")
    suspend fun selectFiles(
        @Path("id") torrentId: String,
        @Field("files") files: String
    )
}


