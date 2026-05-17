package com.tvonnet.debridxtreamiptv.data.debrid.di

import com.tvonnet.debridxtreamiptv.data.debrid.api.AddonCatalogService
import com.tvonnet.debridxtreamiptv.data.debrid.api.AddonCatalogServiceFactory
import com.tvonnet.debridxtreamiptv.data.debrid.api.RealDebridApiService
import com.tvonnet.debridxtreamiptv.data.debrid.api.RealDebridServiceFactory
import com.tvonnet.debridxtreamiptv.data.debrid.api.TmdbApiService
import com.tvonnet.debridxtreamiptv.data.prefs.DebridPreferences
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
    @RealDebridOAuth
    fun provideRealDebridOAuthService(): RealDebridApiService {
        return RealDebridServiceFactory.create()
    }

    @Provides
    @Singleton
    @RealDebridAuthorized
    fun provideRealDebridAuthorizedService(
        preferences: DebridPreferences,
        repoProvider: javax.inject.Provider<com.tvonnet.debridxtreamiptv.data.debrid.repository.DebridAccountRepository>
    ): RealDebridApiService {
        // Adapt Provider<Repo> to Provider<TokenRefresher>
        val refresherProvider = javax.inject.Provider<com.tvonnet.debridxtreamiptv.data.debrid.api.RealDebridAuthInterceptor.TokenRefresher> { 
            repoProvider.get() 
        }
        return RealDebridServiceFactory.create(preferences, refresherProvider)
    }

    @Provides
    @Singleton
    fun provideAddonCatalogService(): AddonCatalogService {
        return AddonCatalogServiceFactory.create()
    }

    @Provides
    @Singleton
    fun provideTmdbApiService(): TmdbApiService {
        val client = OkHttpClient.Builder()
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


