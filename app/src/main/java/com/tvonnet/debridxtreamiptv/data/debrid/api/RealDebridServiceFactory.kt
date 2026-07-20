package com.tvonnet.debridxtreamiptv.data.debrid.api

import okhttp3.OkHttpClient
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import java.util.concurrent.TimeUnit

/**
 * Factory for building Retrofit instances targeting Real-Debrid endpoints.
 * Allows optional injection of the auth interceptor so the same service can be used
 * for both OAuth (unauthenticated) and REST (authenticated) calls.
 */
object RealDebridServiceFactory {

    private const val BASE_URL = "https://api.real-debrid.com/"
    private const val TIMEOUT_SECONDS = 30L

    fun create(
        baseClient: OkHttpClient,
        accessTokenProvider: RealDebridAuthInterceptor.AccessTokenProvider? = null,
        tokenRefresher: javax.inject.Provider<RealDebridAuthInterceptor.TokenRefresher>? = null
    ): RealDebridApiService {
        // Phase 6: derive from the shared base client so the connection pool + dispatcher are shared;
        // the RD auth interceptor/authenticator is added ONLY to this derived client, never the base
        // (so the RD token can never be attached to TMDB / addon / other-host requests).
        val clientBuilder = baseClient.newBuilder()
            .readTimeout(TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .connectTimeout(TIMEOUT_SECONDS, TimeUnit.SECONDS)

        if (accessTokenProvider != null && tokenRefresher != null) {
            val interceptor = RealDebridAuthInterceptor(accessTokenProvider, tokenRefresher)
            clientBuilder.addInterceptor(interceptor)
            clientBuilder.authenticator(interceptor)
        }

        val retrofit = Retrofit.Builder()
            .baseUrl(BASE_URL)
            .addConverterFactory(GsonConverterFactory.create())
            .client(clientBuilder.build())
            .build()

        return retrofit.create(RealDebridApiService::class.java)
    }
}


