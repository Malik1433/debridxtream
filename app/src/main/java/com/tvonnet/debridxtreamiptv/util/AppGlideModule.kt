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

/**
 * Phase 2.4: Image Loading - Glide Configuration Module
 *
 * Optimizes Glide for Android TV IPTV application:
 * - Enhanced memory management for large channel/movie posters
 * - Disk caching for offline viewing
 * - TV-optimized image loading
 * - Error handling and fallback strategies
 */
@GlideModule
class AppGlideModule : AppGlideModule() {

    override fun applyOptions(context: Context, builder: GlideBuilder) {
        // Memory cache configuration - Optimized for TV with large images
        val memoryCacheSizeBytes = 1024 * 1024 * 20L // 20MB for image memory cache
        builder.setMemoryCache(LruResourceCache(memoryCacheSizeBytes))

        // Disk cache configuration - 500MB for channel logos and movie posters (Super Cache)
        val diskCacheSizeBytes = 1024 * 1024 * 500L
        builder.setDiskCache(InternalCacheDiskCacheFactory(context, "image_cache", diskCacheSizeBytes))

        // Default options for TV-optimized image loading
        val defaultOptions = RequestOptions()
            .format(DecodeFormat.PREFER_ARGB_8888) // High quality for TV
            .disallowHardwareConfig() // Avoid hardware acceleration issues on some TV devices
            .placeholder(R.drawable.tv_card_placeholder)
            .error(R.drawable.tv_card_placeholder)
            .diskCacheStrategy(DiskCacheStrategy.ALL) // Cache both original and resized images
            .skipMemoryCache(false) // Use memory cache

        builder.setDefaultRequestOptions(defaultOptions)

        // Enable logging for debug builds
        if (android.util.Log.isLoggable("Glide", android.util.Log.DEBUG)) {
            builder.setLogLevel(android.util.Log.DEBUG)
        }
    }

    override fun isManifestParsingEnabled(): Boolean {
        // Disable manifest parsing to improve startup performance
        return false
    }
}