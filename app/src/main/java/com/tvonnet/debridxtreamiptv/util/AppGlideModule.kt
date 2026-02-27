package com.tvonnet.debridxtreamiptv.util

import android.content.Context
import com.bumptech.glide.GlideBuilder
import com.bumptech.glide.annotation.GlideModule
import com.bumptech.glide.load.DecodeFormat
import com.bumptech.glide.load.engine.DiskCacheStrategy
import com.bumptech.glide.load.engine.cache.InternalCacheDiskCacheFactory
import com.bumptech.glide.load.engine.cache.LruResourceCache
import com.bumptech.glide.module.AppGlideModule
import com.bumptech.glide.request.RequestOptions
import com.tvonnet.debridxtreamiptv.R
import com.bumptech.glide.Registry
import com.bumptech.glide.integration.okhttp3.OkHttpUrlLoader
import com.bumptech.glide.load.model.GlideUrl
import okhttp3.OkHttpClient
import java.io.InputStream
import java.util.concurrent.TimeUnit

/**
 * Phase 2.4: Image Loading - Glide Configuration Module
 *
 * Optimizes Glide for Android TV IPTV application:
 * - Enhanced memory management for large channel/movie posters
 * - Disk caching for offline viewing
 * - TV-optimized image loading
 * - Custom OkHttp Interceptor for User-Agent spoofing (fixes 403 blank posters)
 */
@GlideModule
class AppGlideModule : AppGlideModule() {

    override fun applyOptions(context: Context, builder: GlideBuilder) {
        val memoryCacheSizeBytes = 1024 * 1024 * 32L // 32MB for image memory cache
        builder.setMemoryCache(LruResourceCache(memoryCacheSizeBytes))

        val diskCacheSizeBytes = 1024 * 1024 * 500L
        builder.setDiskCache(InternalCacheDiskCacheFactory(context, "image_cache", diskCacheSizeBytes))

        val defaultOptions = RequestOptions()
            .format(DecodeFormat.PREFER_ARGB_8888)
            .disallowHardwareConfig()
            .placeholder(R.drawable.tv_card_placeholder)
            .error(R.drawable.tv_card_placeholder)
            .diskCacheStrategy(DiskCacheStrategy.ALL)
            .skipMemoryCache(false)

        builder.setDefaultRequestOptions(defaultOptions)

        if (android.util.Log.isLoggable("Glide", android.util.Log.DEBUG)) {
            builder.setLogLevel(android.util.Log.DEBUG)
        }
    }

    override fun registerComponents(context: Context, glide: com.bumptech.glide.Glide, registry: Registry) {
        try {
            // Create a trust manager that does not validate certificate chains
            val trustAllCerts = arrayOf<javax.net.ssl.TrustManager>(
                object : javax.net.ssl.X509TrustManager {
                    override fun checkClientTrusted(chain: Array<java.security.cert.X509Certificate>, authType: String) {}
                    override fun checkServerTrusted(chain: Array<java.security.cert.X509Certificate>, authType: String) {}
                    override fun getAcceptedIssuers(): Array<java.security.cert.X509Certificate> = arrayOf()
                }
            )

            // Install the all-trusting trust manager
            val sslContext = javax.net.ssl.SSLContext.getInstance("SSL")
            sslContext.init(null, trustAllCerts, java.security.SecureRandom())
            
            // Create an ssl socket factory with our all-trusting manager
            val sslSocketFactory = sslContext.socketFactory

            val client = OkHttpClient.Builder()
                .sslSocketFactory(sslSocketFactory, trustAllCerts[0] as javax.net.ssl.X509TrustManager)
                .hostnameVerifier { _, _ -> true }
                .addInterceptor { chain ->
                    val request = chain.request().newBuilder()
                        .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/122.0.0.0 Safari/537.36")
                        .header("Connection", "keep-alive")
                        .header("Accept", "image/avif,image/webp,*/*")
                        .header("Accept-Language", "en-US,en;q=0.5")
                        .build()
                    
                    val response = chain.proceed(request)
                    response
                }
                .connectTimeout(15, TimeUnit.SECONDS)
                .readTimeout(15, TimeUnit.SECONDS)
                .build()
            
            registry.replace(GlideUrl::class.java, InputStream::class.java, OkHttpUrlLoader.Factory(client))
        } catch (e: Exception) {
            android.util.Log.e("GLIDE_DEBUG", "Failed to create unsafe OkHttpClient", e)
        }
    }

    override fun isManifestParsingEnabled(): Boolean = false
}