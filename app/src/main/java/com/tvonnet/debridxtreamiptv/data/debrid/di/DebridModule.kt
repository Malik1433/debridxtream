package com.tvonnet.debridxtreamiptv.data.debrid.di

import com.tvonnet.debridxtreamiptv.data.debrid.api.AddonCatalogService
import com.tvonnet.debridxtreamiptv.data.debrid.api.AddonCatalogServiceFactory
import com.tvonnet.debridxtreamiptv.data.debrid.api.TmdbApiService
import com.tvonnet.debridxtreamiptv.data.debrid.api.TorBoxApiService
import com.tvonnet.debridxtreamiptv.data.prefs.DebridPreferences
import com.tvonnet.debridxtreamiptv.data.prefs.TorBoxPreferences
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import okhttp3.OkHttpClient
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import java.util.concurrent.TimeUnit
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object DebridModule {

    @Provides
    @Singleton
    fun provideAddonCatalogService(base: OkHttpClient): AddonCatalogService {
        return AddonCatalogServiceFactory.create(base)
    }

    @Provides
    @Singleton
    fun provideTorBoxApiService(base: OkHttpClient, preferences: TorBoxPreferences): TorBoxApiService {
        // Phase 6: share the base pool/dispatcher; the TorBox Bearer auth is added ONLY here.
        val client = base.newBuilder()
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .addInterceptor { chain ->
                val token = preferences.getToken()
                val request = if (token == null) {
                    chain.request()
                } else {
                    chain.request()
                        .newBuilder()
                        .header("Authorization", "Bearer $token")
                        .build()
                }
                chain.proceed(request)
            }
            .build()

        return Retrofit.Builder()
            .baseUrl("https://api.torbox.app/")
            .client(client)
            .addConverterFactory(GsonConverterFactory.create())
            .build()
            .create(TorBoxApiService::class.java)
    }

    @Provides
    @Singleton
    fun provideTmdbApiService(base: OkHttpClient): TmdbApiService {
        // Phase 6: share the base pool/dispatcher. TMDB uses an api_key query param, no auth header.
        val client = base.newBuilder()
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .build()

        return Retrofit.Builder()
            .baseUrl("https://api.themoviedb.org/3/")
            .client(client)
            .addConverterFactory(GsonConverterFactory.create())
            .build()
            .create(TmdbApiService::class.java)
    }

    @Provides
    @Singleton
    fun provideGson(): com.google.gson.Gson {
        return com.google.gson.Gson()
    }

    @Provides
    @Singleton
    fun provideMediaFusionFetcher(
        service: AddonCatalogService,
        preferences: DebridPreferences
    ): com.tvonnet.debridxtreamiptv.data.debrid.source.MediaFusionFetcher {
        return com.tvonnet.debridxtreamiptv.data.debrid.source.MediaFusionFetcher(service, preferences)
    }
}


