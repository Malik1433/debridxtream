package com.tvonnet.debridxtreamiptv.util
import com.tvonnet.debridxtreamiptv.data.debrid.model.TmdbImageUrl

import android.graphics.drawable.Drawable
import android.util.Log
import android.widget.ImageView
import com.bumptech.glide.Glide
import com.bumptech.glide.load.DataSource
import com.bumptech.glide.load.engine.DiskCacheStrategy
import com.bumptech.glide.load.engine.GlideException
import com.bumptech.glide.load.resource.bitmap.CenterCrop
import com.bumptech.glide.load.resource.bitmap.RoundedCorners
import com.bumptech.glide.load.resource.drawable.DrawableTransitionOptions
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
    cornerRadiusDp: Int = 12,
    isBackdrop: Boolean = false
) {
    // Phase 6: BuildConfig.DEBUG-guarded — this runs on every poster bind (every card, every
    // scroll frame), and the message string (describeUrl + concat) was being built on every bind
    // even in release where the log itself is dropped.
    if (com.tvonnet.debridxtreamiptv.BuildConfig.DEBUG) {
        android.util.Log.d("GLIDE_DEBUG", "loadPosterOrPlaceholder entry: imageUrl=${SensitiveLogRedactor.describeUrl(imageUrl)}")
    }

    // Step 1: Centralized resolution via GlobalConfig
    val safeResolved = GlobalConfig.resolveIconUrl(imageUrl) ?: ""
    
    // Step 2: Determine final URL with intelligent fallback
    val finalUrl = when {
        safeResolved.startsWith("http") -> {
            // Already absolute (IPTV or pre-resolved TMDB)
            safeResolved
        }
        safeResolved.startsWith("/") && (imageUrl?.contains("tmdb", ignoreCase = true) == true || imageUrl?.startsWith("/") == true && !GlobalConfig.isInitialized()) -> {
            // It's a relative path and either explicitly TMDB or we don't have an IPTV base yet
            // Note: We use W500 for posters and W780 for backdrops
            if (isBackdrop) TmdbImageUrl.getBackdropUrl(safeResolved)
            else TmdbImageUrl.getPosterUrl(safeResolved, TmdbImageUrl.POSTER_SIZE_W500)
        }
        else -> {
            // If it's still relative and we have a baseUrl, resolveIconUrl should have caught it.
            // If it didn't, it might be a broken path.
            safeResolved
        }
    }

    android.util.Log.d(
        "GlideDiagnostics",
        "Loading image: input=${SensitiveLogRedactor.describeUrl(imageUrl)}, resolved=${SensitiveLogRedactor.describeUrl(safeResolved)}, final=${SensitiveLogRedactor.describeUrl(finalUrl)}, base=${SensitiveLogRedactor.describeUrl(GlobalConfig.baseUrl)}"
    )

    if (finalUrl.isNullOrEmpty()) {
        setImageResource(placeholder)
        return
    }

    val density = resources.displayMetrics.density
    val cornerRadius = (cornerRadiusDp * density).toInt()

    Glide.with(context)
        .load(finalUrl)
        .diskCacheStrategy(DiskCacheStrategy.ALL)
        .placeholder(placeholder)
        .error(error)
        .transform(CenterCrop(), RoundedCorners(cornerRadius))
        .transition(DrawableTransitionOptions.withCrossFade())
        .listener(object : RequestListener<Drawable> {
            override fun onLoadFailed(
                e: GlideException?,
                model: Any?,
                target: Target<Drawable>,
                isFirstResource: Boolean
            ): Boolean {
                android.util.Log.e("GLIDE_DEBUG", "FAILED loading image: final=${SensitiveLogRedactor.describeUrl(finalUrl)}, error=${SensitiveLogRedactor.describeException(e)}")
                return false
            }

            override fun onResourceReady(
                resource: Drawable,
                model: Any,
                target: Target<Drawable>?,
                dataSource: DataSource,
                isFirstResource: Boolean
            ): Boolean {
                android.util.Log.d("GLIDE_DEBUG", "SUCCESS loading image: final=${SensitiveLogRedactor.describeUrl(finalUrl)}, source=$dataSource")
                return false
            }
        })
        .into(this)
}
