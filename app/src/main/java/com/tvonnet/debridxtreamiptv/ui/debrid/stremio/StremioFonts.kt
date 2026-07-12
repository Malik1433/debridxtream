package com.tvonnet.debridxtreamiptv.ui.debrid.stremio

import android.content.Context
import android.graphics.Typeface
import android.os.Handler
import android.os.Looper
import android.widget.TextView
import androidx.core.content.res.ResourcesCompat

/**
 * Downloadable (Google Fonts) typefaces cannot be fetched with the synchronous
 * [ResourcesCompat.getFont] — that throws Resources.NotFoundException. This loads them
 * asynchronously, caches the result, and applies to a [TextView] when ready.
 */
internal object StremioFonts {
    private val cache = HashMap<Int, Typeface>()
    private val handler = Handler(Looper.getMainLooper())

    fun apply(tv: TextView, fontRes: Int) {
        cache[fontRes]?.let { tv.typeface = it; return }
        ResourcesCompat.getFont(
            tv.context.applicationContext, fontRes,
            object : ResourcesCompat.FontCallback() {
                override fun onFontRetrieved(typeface: Typeface) {
                    cache[fontRes] = typeface
                    tv.typeface = typeface
                }
                override fun onFontRetrievalFailed(reason: Int) {}
            },
            handler
        )
    }

    fun preload(ctx: Context, fontRes: Int) {
        if (cache.containsKey(fontRes)) return
        ResourcesCompat.getFont(
            ctx.applicationContext, fontRes,
            object : ResourcesCompat.FontCallback() {
                override fun onFontRetrieved(typeface: Typeface) { cache[fontRes] = typeface }
                override fun onFontRetrievalFailed(reason: Int) {}
            },
            handler
        )
    }
}
