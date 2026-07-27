package com.tvonnet.debridxtreamiptv.util

import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable

/**
 * The one colour that identifies a piece of content, pulled out of its artwork — a channel logo,
 * or a movie/series poster.
 *
 * **Why not just "the most common colour".** This provider bakes a silver plate and an "OTT"
 * watermark into every logo file, so the most common colour is always grey — every row would end up
 * the same. [dominantSaturated] therefore ignores anything unsaturated, near-white or near-black and
 * looks only at the branding: arte comes out orange, ARD blue, 3sat red, ZDF orange.
 *
 * A genuinely monochrome logo (ZDF neo is black on white) has no such colour, and that is reported
 * honestly as null rather than as a muddy grey — the caller falls back to the app's own accent.
 *
 * Results are cached by artwork URL: the extraction walks a 32×32 copy, which is cheap, but a channel
 * list re-binds constantly while scrolling and there is no reason to pay for it twice.
 */
object ArtworkPalette {

    private const val SAMPLE = 32

    /** Below this saturation a pixel is plate/ink, not branding. */
    private const val MIN_SATURATION = 0.35f
    private const val MIN_VALUE = 0.25f

    /** A colour needs this many sampled pixels to count as the brand, not an anti-aliasing artefact. */
    private const val MIN_PIXELS = 6

    private val cache = java.util.concurrent.ConcurrentHashMap<String, Int>()

    /**
     * @param fallback used when the artwork is missing, monochrome, or not yet loaded — normally
     *   a stable per-name colour, so the item still gets a colour of its own.
     */
    fun accentFor(artworkUrl: String?, drawable: Drawable?, fallback: Int): Int {
        val key = artworkUrl?.takeIf { it.isNotBlank() } ?: return fallback
        cache[key]?.let { return it }
        val bmp = (drawable as? BitmapDrawable)?.bitmap ?: return fallback
        val found = dominantSaturated(bmp) ?: fallback
        cache[key] = found
        return found
    }

    /**
     * The most-used *saturated* colour in [bitmap], or null when there isn't one.
     *
     * Pixels are grouped into coarse hue/saturation/value buckets so near-identical shades of the
     * same brand colour count together instead of splitting the vote between themselves.
     */
    fun dominantSaturated(bitmap: Bitmap): Int? {
        val small = runCatching {
            Bitmap.createScaledBitmap(bitmap, SAMPLE, SAMPLE, true)
        }.getOrNull() ?: return null

        val sums = HashMap<Int, IntArray>()
        val hsv = FloatArray(3)
        for (y in 0 until small.height) {
            for (x in 0 until small.width) {
                val px = small.getPixel(x, y)
                if (Color.alpha(px) < 180) continue
                Color.colorToHSV(px, hsv)
                if (hsv[1] < MIN_SATURATION || hsv[2] < MIN_VALUE) continue
                val bucket = (hsv[0].toInt() / 20) * 100 + (hsv[1] * 3).toInt() * 10 + (hsv[2] * 3).toInt()
                val e = sums.getOrPut(bucket) { IntArray(4) }
                e[0] += Color.red(px); e[1] += Color.green(px); e[2] += Color.blue(px); e[3]++
            }
        }
        val best = sums.values.maxByOrNull { it[3] } ?: return null
        val n = best[3]
        if (n < MIN_PIXELS) return null
        return Color.rgb(best[0] / n, best[1] / n, best[2] / n)
    }
}
