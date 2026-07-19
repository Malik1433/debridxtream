package com.tvonnet.debridxtreamiptv.util

import android.content.Context
import android.graphics.drawable.PictureDrawable
import android.widget.ImageView
import androidx.appcompat.content.res.AppCompatResources
import androidx.fragment.app.Fragment
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import com.bumptech.glide.Glide
import com.bumptech.glide.RequestBuilder
import com.bumptech.glide.load.engine.DiskCacheStrategy
import com.bumptech.glide.load.resource.bitmap.CenterCrop
import com.bumptech.glide.load.resource.bitmap.FitCenter
import com.bumptech.glide.load.resource.bitmap.RoundedCorners
import com.bumptech.glide.request.RequestOptions
import com.tvonnet.debridxtreamiptv.R

/**
 * Phase 2.4: Image Loading - Glide Utilities
 *
 * Provides optimized image loading methods for Android TV IPTV application:
 * - Channel logo loading with fallback handling
 * - Movie/series poster loading with appropriate transformations
 * - Memory-efficient loading for large lists
 * - Fragment lifecycle-aware image loading to prevent leaks
 */
object GlideUtils {

    private const val CHANNEL_LOGO_SIZE = 300 // pixels
    private const val POSTER_WIDTH = 400 // pixels
    private const val POSTER_HEIGHT = 600 // pixels
    private const val THUMBNAIL_SIZE = 150 // pixels

    /**
     * Load channel logo with TV-optimized settings (Drawable overload)
     */
    fun loadChannelLogo(
        imageView: ImageView,
        logoUrl: String?,
        placeholder: android.graphics.drawable.Drawable?,
        error: android.graphics.drawable.Drawable?
    ) {
        val glideUrl = getGlideUrl(logoUrl)
        if (glideUrl != null) {
            Glide.with(imageView.context)
                .load(glideUrl)
                .addErrorListener(imageView.context)
                .apply(getChannelLogoOptions(placeholder, error))
                .thumbnail(0.25f)
                .dontAnimate()
                .into(imageView)
        } else {
            imageView.setImageDrawable(placeholder)
        }
    }

    /**
     * Load channel logo with TV-optimized settings
     */
    fun loadChannelLogo(
        imageView: ImageView,
        logoUrl: String?,
        placeholder: Int = R.drawable.tv_card_placeholder,
        error: Int = R.drawable.tv_card_placeholder
    ) {
        val glideUrl = getGlideUrl(logoUrl)
        if (glideUrl != null) {
            Glide.with(imageView.context)
                .load(glideUrl)
                .addErrorListener(imageView.context)
                .apply(getChannelLogoOptions(placeholder, error))
                .thumbnail(0.25f)
                .dontAnimate()
                .into(imageView)
        } else {
            Glide.with(imageView.context)
                .load(placeholder)
                .dontAnimate()
                .into(imageView)
        }
    }

    /**
     * Load movie poster with appropriate aspect ratio and transformations
     */
    fun loadMoviePoster(
        imageView: ImageView,
        posterUrl: String?,
        placeholder: Int = R.drawable.tv_card_placeholder,
        error: Int = R.drawable.tv_card_placeholder,
        useRoundedCorners: Boolean = true
    ) {
        val glideUrl = getGlideUrl(posterUrl)
        if (glideUrl != null) {
            val options = getPosterOptions(placeholder, error, useRoundedCorners)
            Glide.with(imageView.context)
                .load(glideUrl)
                .addErrorListener(imageView.context)
                .apply(options)
                .thumbnail(0.25f)
                .dontAnimate()
                .into(imageView)
        } else {
            Glide.with(imageView.context)
                .load(placeholder)
                .apply(if (useRoundedCorners) {
                    RequestOptions().transform(RoundedCorners(16))
                } else {
                    RequestOptions()
                })
                .dontAnimate()
                .into(imageView)
        }
    }

    /**
     * Load image with fragment lifecycle awareness
     * Automatically pauses loading when fragment is not visible
     */
    fun loadWithLifecycle(
        fragment: Fragment,
        imageView: ImageView,
        imageUrl: String?,
        placeholder: Int = R.drawable.tv_card_placeholder,
        error: Int = R.drawable.tv_card_placeholder
    ) {
        val glideUrl = getGlideUrl(imageUrl)
        if (glideUrl != null) {
            // Create a RequestBuilder that respects fragment lifecycle
            val requestBuilder = Glide.with(fragment)
                .load(glideUrl)
                .addErrorListener(fragment.requireContext())
                .apply(getDefaultOptions(placeholder, error))

            requestBuilder.into(imageView)
        } else {
            imageView.setImageResource(placeholder)
        }
    }

    /**
     * Load thumbnail for series episodes or preview images
     */
    fun loadThumbnail(
        imageView: ImageView,
        thumbnailUrl: String?,
        placeholder: Int = R.drawable.tv_card_placeholder,
        error: Int = R.drawable.tv_card_placeholder
    ) {
        val glideUrl = getGlideUrl(thumbnailUrl)
        if (glideUrl != null) {
            Glide.with(imageView.context)
                .load(glideUrl)
                .addErrorListener(imageView.context)
                .apply(getThumbnailOptions(placeholder, error))
                .thumbnail(0.25f)
                .dontAnimate()
                .into(imageView)
        } else {
            imageView.setImageResource(placeholder)
        }
    }

    /**
     * Clear image cache and memory
     * Call this when user logs out or to free up memory
     */
    fun clearCache(context: Context) {
        // Clear disk cache on background thread
        Thread {
            Glide.get(context).clearDiskCache()
        }.start()
    }

    /**
     * Pause image loading (useful during scrolling or background operations)
     */
    fun pauseLoading(context: Context) {
        Glide.with(context).pauseRequests()
    }

    /**
     * Resume image loading
     */
    fun resumeLoading(context: Context) {
        Glide.with(context).resumeRequests()
    }

    /**
     * Preload critical images (like main category logos)
     */
    fun preloadImage(context: Context, imageUrl: String?) {
        val glideUrl = getGlideUrl(imageUrl)
        if (glideUrl != null) {
            Glide.with(context)
                .load(glideUrl)
                .preload()
        }
    }

    // Private helper methods for request options
    
    private fun <T : Any> RequestBuilder<T>.addErrorListener(context: Context): RequestBuilder<T> {
        return this.listener(object : com.bumptech.glide.request.RequestListener<T> {
            override fun onLoadFailed(
                e: com.bumptech.glide.load.engine.GlideException?,
                model: Any?,
                target: com.bumptech.glide.request.target.Target<T>,
                isFirstResource: Boolean
            ): Boolean {
                val errorMsg = "Glide Error: ${e?.message?.take(80)}"
                android.util.Log.e("GLIDE_DEBUG", "FAILED loading IPTV Picture: $model | Error: ${e?.message}")
                e?.logRootCauses("GLIDE_DEBUG")
                return false
            }

            override fun onResourceReady(
                resource: T,
                model: Any,
                target: com.bumptech.glide.request.target.Target<T>?,
                dataSource: com.bumptech.glide.load.DataSource,
                isFirstResource: Boolean
            ): Boolean {
                var width = -1
                var height = -1
                if (resource is android.graphics.drawable.Drawable) {
                    width = resource.intrinsicWidth
                    height = resource.intrinsicHeight
                }
                val msg = "Glide Success! ${width}x${height}"
                android.util.Log.d("GLIDE_DEBUG", "SUCCESS loading IPTV Picture: $model | Size: ${width}x${height} | Type: ${resource.javaClass.simpleName}")
                return false
            }
        })
    }
    
    private fun getGlideUrl(url: String?): com.bumptech.glide.load.model.GlideUrl? {
        val resolved = com.tvonnet.debridxtreamiptv.util.GlobalConfig.resolveIconUrl(url)
        if (resolved.isNullOrBlank()) return null
        
        return com.bumptech.glide.load.model.GlideUrl(
            resolved,
            com.bumptech.glide.load.model.LazyHeaders.Builder()
                .addHeader("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36")
                .addHeader("Accept", "image/avif,image/webp,image/apng,image/svg+xml,image/*,*/*;q=0.8")
                .build()
        )
    }

    private fun getChannelLogoOptions(placeholder: Int, error: Int): RequestOptions {
        return RequestOptions()
            .placeholder(placeholder)
            .error(error)
            .override(CHANNEL_LOGO_SIZE, CHANNEL_LOGO_SIZE)
            .transform(CenterCrop())
            .diskCacheStrategy(DiskCacheStrategy.ALL)
            .skipMemoryCache(false)
    }

    private fun getChannelLogoOptions(placeholder: android.graphics.drawable.Drawable?, error: android.graphics.drawable.Drawable?): RequestOptions {
        return RequestOptions()
            .placeholder(placeholder)
            .error(error)
            .override(CHANNEL_LOGO_SIZE, CHANNEL_LOGO_SIZE)
            .transform(CenterCrop())
            .diskCacheStrategy(DiskCacheStrategy.ALL)
            .skipMemoryCache(false)
    }

    private fun getPosterOptions(
        placeholder: Int,
        error: Int,
        useRoundedCorners: Boolean
    ): RequestOptions {
        val options = RequestOptions()
            .placeholder(placeholder)
            .error(error)
            .override(POSTER_WIDTH, POSTER_HEIGHT)
            // IMG-1: posters have no alpha — RGB_565 halves their heap footprint vs the
            // global ARGB_8888 default (logos keep 8888 via getChannelLogoOptions).
            .format(com.bumptech.glide.load.DecodeFormat.PREFER_RGB_565)
            .transform(CenterCrop())
            .diskCacheStrategy(DiskCacheStrategy.ALL)
            .skipMemoryCache(false)

        return if (useRoundedCorners) {
            options.transform(RoundedCorners(16))
        } else {
            options
        }
    }

    private fun getThumbnailOptions(placeholder: Int, error: Int): RequestOptions {
        return RequestOptions()
            .placeholder(placeholder)
            .error(error)
            .override(THUMBNAIL_SIZE, THUMBNAIL_SIZE)
            .transform(FitCenter())
            .diskCacheStrategy(DiskCacheStrategy.ALL)
            .skipMemoryCache(false)
    }

    private fun getDefaultOptions(placeholder: Int, error: Int): RequestOptions {
        return RequestOptions()
            .placeholder(placeholder)
            .error(error)
            .diskCacheStrategy(DiskCacheStrategy.ALL)
            .skipMemoryCache(false)
    }

    /**
     * Lifecycle observer to automatically manage Glide requests with fragment lifecycle
     */
    class GlideLifecycleObserver(private val context: Context) : DefaultLifecycleObserver {
        override fun onPause(owner: LifecycleOwner) {
            pauseLoading(context)
        }

        override fun onResume(owner: LifecycleOwner) {
            resumeLoading(context)
        }

        override fun onDestroy(owner: LifecycleOwner) {
            // Clean up any ongoing requests
            Glide.with(context).onDestroy()
        }
    }
}
