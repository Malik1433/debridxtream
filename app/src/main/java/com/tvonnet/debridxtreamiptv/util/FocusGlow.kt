package com.tvonnet.debridxtreamiptv.util

import android.graphics.BlurMaskFilter
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorFilter
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.RectF
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.Drawable
import android.graphics.drawable.StateListDrawable
import android.util.StateSet
import android.view.View

/**
 * True soft focus bloom matching the design's `box-shadow: 0 0 22px rgba(0,240,255,0.35)`.
 * Drawn with a Gaussian BlurMaskFilter, so the host view must be software-layered and
 * sized ~12dp larger than the card on every side (the blur fills that padding).
 */
class CardGlowDrawable(
    private val cornerRadiusPx: Float,
    private val glowPadPx: Float,
    color: Int = GLOW_COLOR
) : Drawable() {

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        this.color = color
        style = Paint.Style.FILL
        maskFilter = BlurMaskFilter(glowPadPx * 0.95f, BlurMaskFilter.Blur.NORMAL)
    }
    private val rect = RectF()

    override fun draw(canvas: Canvas) {
        rect.set(bounds)
        rect.inset(glowPadPx, glowPadPx)
        canvas.drawRoundRect(rect, cornerRadiusPx + 2f, cornerRadiusPx + 2f, paint)
    }

    override fun setAlpha(alpha: Int) {
        paint.alpha = alpha
        invalidateSelf()
    }

    override fun setColorFilter(colorFilter: ColorFilter?) {
        paint.colorFilter = colorFilter
        invalidateSelf()
    }

    @Deprecated("Deprecated in Java")
    override fun getOpacity(): Int = PixelFormat.TRANSLUCENT

    companion object {
        // rgba(0,240,255,0.35)
        const val GLOW_COLOR = 0x5900F0FF
    }
}

object FocusGlow {

    const val GLOW_PAD_DP = 12f

    /** Card glow: appears only while the (duplicated) focused state is active. */
    /**
     * Re-hue a glow without changing how strong it is.
     *
     * The glow colours carry their own alpha (`0x59`), tuned so a focused card blooms without
     * washing out. Artwork-derived colours are opaque, so passing one straight in would make the
     * bloom several times too bright — this keeps [base]'s alpha and takes only [rgb]'s hue.
     */
    fun tinted(rgb: Int, base: Int = CardGlowDrawable.GLOW_COLOR): Int =
        (base and 0xFF000000.toInt()) or (rgb and 0x00FFFFFF)

    fun attachSelector(view: View?, cornerDp: Float = 4f, color: Int = CardGlowDrawable.GLOW_COLOR) {
        view ?: return
        val density = view.resources.displayMetrics.density
        val selector = StateListDrawable().apply {
            addState(
                intArrayOf(android.R.attr.state_focused),
                CardGlowDrawable(cornerDp * density, GLOW_PAD_DP * density, color)
            )
            addState(StateSet.WILD_CARD, ColorDrawable(Color.TRANSPARENT))
        }
        view.setLayerType(View.LAYER_TYPE_SOFTWARE, null)
        view.background = selector
    }

    /** Always-on glow drawable; caller controls visibility (hero buttons). */
    fun attachAlways(view: View?, cornerDp: Float = 3f) {
        view ?: return
        val density = view.resources.displayMetrics.density
        view.setLayerType(View.LAYER_TYPE_SOFTWARE, null)
        view.background = CardGlowDrawable(cornerDp * density, GLOW_PAD_DP * density)
    }
}
