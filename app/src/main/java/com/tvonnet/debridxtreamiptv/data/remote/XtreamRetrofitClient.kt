package com.tvonnet.debridxtreamiptv.data.remote

import android.content.Context
import com.google.gson.GsonBuilder
import com.tvonnet.debridxtreamiptv.BuildConfig
import com.tvonnet.debridxtreamiptv.data.model.XtreamEpisodeDetail
import com.tvonnet.debridxtreamiptv.data.remote.adapter.EpisodeDetailJsonAdapter
import com.tvonnet.debridxtreamiptv.data.remote.interceptor.CacheInterceptor
import okhttp3.Cache
import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import retrofit2.converter.scalars.ScalarsConverterFactory
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * Week 8: Updated with HTTP-level caching support
 * QA Rec #4: Logging disabled in production builds
 */
object XtreamRetrofitClient {
    
    private const val HTTP_CACHE_SIZE = 50L * 1024 * 1024 // 50MB
    
    /**
     * Create Retrofit API service with caching support
     * 
     * @param baseUrl Base URL for API
     * @param context Android context (for cache directory and network monitoring)
     */
    fun create(baseUrl: String, context: Context? = null): XtreamApiService {
        // Week 8: Setup HTTP cache directory
        val cache = context?.let {
            val cacheDir = File(it.cacheDir, "http_cache")
            Cache(cacheDir, HTTP_CACHE_SIZE)
        }
        
        val clientBuilder = OkHttpClient.Builder()
            // Add headers to prevent 403 errors (based on xtream probe results)
            .addInterceptor { chain ->
                val original = chain.request()
                val request = original.newBuilder()
                    .header("User-Agent", "VLC/3.0.16 LibVLC/3.0.16")
                    .header("Accept", "*/*")
                    .header("Connection", "keep-alive")
                    .method(original.method, original.body)
                    .build()
                chain.proceed(request)
            }
            // Week 8: Add simplified cache interceptor
            .apply {
                if (context != null) {
                    // Simplified cache interceptor that handles both online/offline scenarios
                    addInterceptor(CacheInterceptor(context))
                }
            }
            // QA Rec #4: HttpLoggingInterceptor also DEBUG only
            .apply {
                if (BuildConfig.DEBUG) {
                    addInterceptor(HttpLoggingInterceptor().apply {
                        level = HttpLoggingInterceptor.Level.BASIC
                    })
                }
            }
            // Week 8: Add HTTP cache
            .apply {
                if (cache != null) {
                    cache(cache)
                }
            }
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .writeTimeout(30, TimeUnit.SECONDS)
        
        val client = clientBuilder.build()
        
        val gson = GsonBuilder()
            // Week 16: Moved EpisodeDetailJsonAdapter to field level annotation to prevent StackOverflow
            // .registerTypeAdapter(XtreamEpisodeDetail::class.java, EpisodeDetailJsonAdapter())
            .create()

        val retrofit = Retrofit.Builder()
            .baseUrl(baseUrl)
            .client(client)
            .addConverterFactory(ScalarsConverterFactory.create()) // Week 14: Handle String/XML responses first
            .addConverterFactory(GsonConverterFactory.create(gson)) // Then JSON
            .build()
        
        return retrofit.create(XtreamApiService::class.java)
    }
}
