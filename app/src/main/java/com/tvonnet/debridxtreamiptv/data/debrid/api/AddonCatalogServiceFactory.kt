package com.tvonnet.debridxtreamiptv.data.debrid.api

import okhttp3.OkHttpClient
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import java.util.concurrent.TimeUnit

/**
 * Factory for creating Retrofit service instances targeting third-party addon catalogs.
 */
object AddonCatalogServiceFactory {

    private const val PLACEHOLDER_BASE_URL = "https://addons-placeholder.tv/"
    private const val TIMEOUT_SECONDS = 30L

    fun create(baseClient: OkHttpClient): AddonCatalogService {
        // Phase 6: derive from the shared base client (shared pool + dispatcher). Only the default
        // User-Agent/Accept header interceptor is added here — no auth ever goes on the shared base.
        val clientBuilder = baseClient.newBuilder()
            .connectTimeout(TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .readTimeout(TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .addInterceptor { chain ->
                val original = chain.request()
                val requestBuilder = original.newBuilder()
                if (original.header("User-Agent") == null) {
                    requestBuilder.header("User-Agent", "Stremio/1.0")
                }
                if (original.header("Accept") == null) {
                    requestBuilder.header("Accept", "application/json")
                }
                chain.proceed(requestBuilder.build())
            }

        val retrofit = Retrofit.Builder()
            .baseUrl(PLACEHOLDER_BASE_URL)
            .addConverterFactory(GsonConverterFactory.create())
            .client(clientBuilder.build())
            .build()

        return retrofit.create(AddonCatalogService::class.java)
    }
}


