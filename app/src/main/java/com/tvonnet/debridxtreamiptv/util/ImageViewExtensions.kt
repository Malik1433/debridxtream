package com.tvonnet.debridxtreamiptv.util

import android.graphics.drawable.Drawable
import android.util.Log
import android.widget.ImageView
import com.bumptech.glide.Glide
import com.bumptech.glide.load.DataSource
import com.bumptech.glide.load.engine.GlideException
import com.bumptech.glide.load.resource.bitmap.CenterCrop
import com.bumptech.glide.load.resource.bitmap.RoundedCorners
import com.bumptech.glide.request.RequestListener
import com.bumptech.glide.request.RequestOptions
import com.bumptech.glide.request.target.Target
import com.tvonnet.debridxtreamiptv.R

/**
 * Safely load an image URL into an ImageView, reverting to a TV-friendly placeholder when the URL
 * is missing or blank to avoid Glide warnings and empty tiles on Android TV.
 * Consistent styling: CenterCrop + 12dp Rounded Corners + Logcat Failure diagnostics.
 */
fun ImageView.loadPosterOrPlaceholder(
    imageUrl: String?,
    placeholder: Int = R.drawable.tv_card_placeholder,
    error: Int = R.drawable.tv_card_placeholder,
    cornerRadiusDp: Int = 12
) {
    if (!imageUrl.isNullOrBlank()) {
        val cornerRadius = (cornerRadiusDp * resources.displayMetrics.density).toInt()
        val resolved = com.tvonnet.debridxtreamiptv.util.GlobalConfig.resolveIconUrl(imageUrl)
        
        val glideUrl = com.bumptech.glide.load.model.GlideUrl(
            resolved,
            com.bumptech.glide.load.model.LazyHeaders.Builder()
                .addHeader("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36")
                .addHeader("Accept", "image/avif,image/webp,image/apng,image/svg+xml,image/*,*/*;q=0.8")
                .build()
        )
        
        Glide.with(context)
            .load(glideUrl)
            .listener(object : RequestListener<Drawable> {
                override fun onLoadFailed(
                    e: GlideException?,
                    model: Any?,
                    target: Target<Drawable>,
                    isFirstResource: Boolean
                ): Boolean {
                    val errorMsg = "Glide Error: ${e?.message?.take(50)}"
                    Log.e("GLIDE_DEBUG", "FAILED loading IPTV Picture: $model | Error: ${e?.message}")
                    // On-screen debug feedback
                    
                    // Also print stack trace to see detailed causes (SSL, 404, etc)
                    e?.logRootCauses("GLIDE_DEBUG")
                    return false
                }

                override fun onResourceReady(
                    resource: Drawable,
                    model: Any,
                    target: Target<Drawable>?,
                    dataSource: DataSource,
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
            .apply(
                RequestOptions()
                    .transform(CenterCrop(), RoundedCorners(cornerRadius))
                    .skipMemoryCache(true)
                    .diskCacheStrategy(com.bumptech.glide.load.engine.DiskCacheStrategy.NONE)
                    .override(com.bumptech.glide.request.target.Target.SIZE_ORIGINAL)
            )
            .placeholder(placeholder)
            .error(error)
            .into(this)
    } else {
        // Apply RoundedCorners/CenterCrop to placeholder if possible, or just set it
        setImageResource(placeholder)
    }
}
