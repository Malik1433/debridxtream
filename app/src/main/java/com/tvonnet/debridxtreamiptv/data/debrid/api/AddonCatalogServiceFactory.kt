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

    fun create(): AddonCatalogService {
        val clientBuilder = OkHttpClient.Builder()
            .connectTimeout(TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .readTimeout(TIMEOUT_SECONDS, TimeUnit.SECONDS)

        val retrofit = Retrofit.Builder()
            .baseUrl(PLACEHOLDER_BASE_URL)
            .addConverterFactory(GsonConverterFactory.create())
            .client(clientBuilder.build())
            .build()

        return retrofit.create(AddonCatalogService::class.java)
    }
}


