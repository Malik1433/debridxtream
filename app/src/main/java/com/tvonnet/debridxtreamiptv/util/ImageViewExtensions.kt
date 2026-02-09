package com.tvonnet.debridxtreamiptv.util

import android.widget.ImageView
import com.bumptech.glide.Glide
import com.tvonnet.debridxtreamiptv.R

/**
 * Safely load an image URL into an ImageView, reverting to a TV-friendly placeholder when the URL
 * is missing or blank to avoid Glide warnings and empty tiles on Android TV.
 */
fun ImageView.loadPosterOrPlaceholder(
    imageUrl: String?,
    placeholder: Int = R.drawable.tv_card_placeholder,
    error: Int = R.drawable.tv_card_placeholder
) {
    if (!imageUrl.isNullOrBlank()) {
        Glide.with(context)
            .load(imageUrl)
            .placeholder(placeholder)
            .error(error)
            .centerCrop()
            .into(this)
    } else {
        setImageResource(placeholder)
    }
}
