package com.tvonnet.debridxtreamiptv.player.stabilized

import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Shader
import android.graphics.drawable.Drawable
import android.graphics.drawable.PaintDrawable
import android.graphics.drawable.ShapeDrawable

/**
 * The channel's colour washing across its row: strongest at the left edge, gone before the text.
 *
 * This is what replaces the abandoned "bleed the logo itself" idea. The provider's logo files carry
 * a baked-in silver plate, so bleeding the artwork bled a grey slab; bleeding its *colour* gives the
 * list the same live, per-channel feel with none of that.
 *
 * Kept deliberately low: [PEAK_ALPHA] is a wash, not a fill. It has to survive being drawn under
 * white body text on a dark row, and a channel list is dozens of rows deep — anything stronger and
 * the screen turns into stripes.
 */
object ChannelAccentWash {

    /** Out of 255. Focused rows get [FOCUSED_MULTIPLIER] times this. */
    private const val PEAK_ALPHA = 46
    private const val FOCUSED_MULTIPLIER = 1.9f

    /** Fractions of the row width: solid to [HOLD], gone by [FADE_END]. */
    private const val HOLD = 0.10f
    private const val FADE_END = 0.65f

    private const val CORNER_RADIUS_DP = 6f

    fun forRow(color: Int, focused: Boolean, density: Float, cornerRadiusDp: Float = CORNER_RADIUS_DP): Drawable {
        val peak = (PEAK_ALPHA * if (focused) FOCUSED_MULTIPLIER else 1f).toInt().coerceIn(0, 255)
        val start = Color.argb(peak, Color.red(color), Color.green(color), Color.blue(color))
        val end = Color.argb(0, Color.red(color), Color.green(color), Color.blue(color))
        return PaintDrawable().apply {
            // Match the row background's corners, or the wash paints over the rounded edge.
            setCornerRadius(cornerRadiusDp * density)
            shaderFactory = object : ShapeDrawable.ShaderFactory() {
                override fun resize(width: Int, height: Int): Shader = LinearGradient(
                    0f, 0f, width.toFloat(), 0f,
                    intArrayOf(start, start, end),
                    floatArrayOf(0f, HOLD, FADE_END),
                    Shader.TileMode.CLAMP
                )
            }
        }
    }
}
