package com.tvonnet.debridxtreamiptv.ui.debrid.stremio

import android.graphics.LinearGradient
import android.graphics.Matrix
import android.graphics.RadialGradient
import android.graphics.Shader
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.PaintDrawable
import android.graphics.drawable.ShapeDrawable
import android.graphics.drawable.shapes.RectShape

/**
 * Exact multi-stop gradients from "Stremio Home.dc.html". Android shape <gradient> only supports
 * evenly-spaced 3-stop gradients, so the design's positioned multi-stop fades are built here with
 * real [LinearGradient]/[RadialGradient] shaders that resize with the view.
 */
object StremioGradients {

    private const val BG = 0xFF06090E.toInt()

    private fun shaderDrawable(factory: ShapeDrawable.ShaderFactory): Drawable =
        PaintDrawable().apply {
            shape = RectShape()
            shaderFactory = factory
        }

    /** radial-gradient(ellipse 70% 55% at 50% 0%, rgba(0,119,255,0.07) 0%, transparent 65%) */
    fun ambientGlow(): Drawable = shaderDrawable(object : ShapeDrawable.ShaderFactory() {
        override fun resize(w: Int, h: Int): Shader {
            val radius = (w * 0.7f).coerceAtLeast(1f)
            val g = RadialGradient(
                w / 2f, 0f, radius,
                intArrayOf(0x120077FF, 0x000077FF),
                floatArrayOf(0f, 1f),
                Shader.TileMode.CLAMP
            )
            // squash vertically to 55%/70% ratio -> ellipse
            val m = Matrix()
            m.setScale(1f, (h * 0.55f) / radius, w / 2f, 0f)
            g.setLocalMatrix(m)
            return g
        }
    })

    /** linear-gradient(90deg,#06090e 0%,rgba(6,9,14,0.88) 38%,rgba(6,9,14,0.3) 68%,transparent 100%) */
    fun heroFadeLr(): Drawable = shaderDrawable(object : ShapeDrawable.ShaderFactory() {
        override fun resize(w: Int, h: Int): Shader = LinearGradient(
            0f, 0f, w.toFloat(), 0f,
            intArrayOf(BG, 0xE006090E.toInt(), 0x4D06090E, 0x0006090E),
            floatArrayOf(0f, 0.38f, 0.68f, 1f),
            Shader.TileMode.CLAMP
        )
    })

    /** Bottom fade — solid #06090E for the bottom ~45% so the hero blends seamlessly into the
     *  content area (no visible bright seam at the hero/content boundary), then fades to transparent. */
    fun heroFadeBottom(): Drawable = shaderDrawable(object : ShapeDrawable.ShaderFactory() {
        override fun resize(w: Int, h: Int): Shader = LinearGradient(
            0f, h.toFloat(), 0f, 0f,
            intArrayOf(BG, BG, 0x0006090E),
            floatArrayOf(0f, 0.45f, 0.82f),
            Shader.TileMode.CLAMP
        )
    })

    /** linear-gradient(to bottom,rgba(6,9,14,0.97) 0%,rgba(6,9,14,0.6) 70%,transparent 100%) */
    fun navBarBg(): Drawable = shaderDrawable(object : ShapeDrawable.ShaderFactory() {
        override fun resize(w: Int, h: Int): Shader = LinearGradient(
            0f, 0f, 0f, h.toFloat(),
            intArrayOf(0xF706090E.toInt(), 0x9906090E.toInt(), 0x0006090E),
            floatArrayOf(0f, 0.7f, 1f),
            Shader.TileMode.CLAMP
        )
    })

    /** Poster/card placeholder: linear-gradient(160deg, base, base@0.53). */
    fun cardBg(base: Int, cornerDp: Float, density: Float, borderColor: Int = 0x12FFFFFF): Drawable {
        val end = (base and 0x00FFFFFF) or 0x88000000.toInt()
        val d = PaintDrawable()
        d.shape = RectShape()
        d.setCornerRadius(cornerDp * density)
        d.shaderFactory = object : ShapeDrawable.ShaderFactory() {
            override fun resize(w: Int, h: Int): Shader = LinearGradient(
                w * 0.17f, 0f, w * 0.83f, h.toFloat(),
                intArrayOf(base, end), null, Shader.TileMode.CLAMP
            )
        }
        return d
    }

    /** Two-stop vertical top-fade overlay used on poster cards. */
    fun topFade(startColor: Int, midColor: Int, endStop: Float, cornerDp: Float, density: Float): Drawable {
        val d = PaintDrawable()
        d.shape = RectShape()
        d.setCornerRadius(cornerDp * density)
        d.shaderFactory = object : ShapeDrawable.ShaderFactory() {
            override fun resize(w: Int, h: Int): Shader = LinearGradient(
                0f, h.toFloat(), 0f, 0f,
                intArrayOf(startColor, midColor, 0x00000000),
                floatArrayOf(0f, endStop, 1f),
                Shader.TileMode.CLAMP
            )
        }
        return d
    }

    /** Solid rounded gradient two-stop 135deg (blue->cyan) as a GradientDrawable. */
    fun diagonal(startColor: Int, endColor: Int, cornerDp: Float, density: Float): GradientDrawable =
        GradientDrawable(
            GradientDrawable.Orientation.TL_BR,
            intArrayOf(startColor, endColor)
        ).apply {
            cornerRadius = cornerDp * density
        }
}
