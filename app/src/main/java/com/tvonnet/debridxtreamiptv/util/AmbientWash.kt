package com.tvonnet.debridxtreamiptv.util

import android.graphics.Color
import android.graphics.drawable.GradientDrawable

/**
 * A whole-screen hint of the focused item's colour, bleeding in from the top-left.
 *
 * Much weaker than the per-row wash: this sits behind an entire browse grid, so it has to read as
 * ambience rather than as a coloured screen. [PEAK_ALPHA] is the ceiling that still shows the
 * film's palette without lifting the black level of the page.
 */
object AmbientWash {

    private const val PEAK_ALPHA = 40

    fun forBackground(color: Int): GradientDrawable {
        val strong = Color.argb(PEAK_ALPHA, Color.red(color), Color.green(color), Color.blue(color))
        val gone = Color.argb(0, Color.red(color), Color.green(color), Color.blue(color))
        return GradientDrawable(
            GradientDrawable.Orientation.TL_BR,
            intArrayOf(strong, gone, gone)
        )
    }
}
