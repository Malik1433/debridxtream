package com.tvonnet.debridxtreamiptv.util

import android.graphics.*
import android.graphics.drawable.Drawable
import kotlin.math.abs

/**
 * A Drawable that displays a character on a colored background.
 * Used as a fallback when channel logos are missing.
 */
class LetterDrawable(private val text: String?) : Drawable() {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val textBounds = Rect()
    private val displayChar: String

    init {
        displayChar = if (!text.isNullOrBlank()) {
             // Get first letter, capitalized
             text.trim().first().uppercaseChar().toString()
        } else {
            "?"
        }

        // Generate consistent pastel color from text hash
        val color = generateColor(text ?: "default")
        bgPaint.color = color
        
        paint.color = Color.WHITE
        paint.textSize = 60f // Will be scaled in draw
        paint.textAlign = Paint.Align.CENTER
        paint.typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
    }

    private fun generateColor(key: String): Int {
        val hash = abs(key.hashCode())
        // Generate nice material-ish colors
        // Hue: 0-360 based on hash
        // Saturation: 0.6-0.8
        // Value: 0.4-0.6 (darker for contrast with white text)
        val h = (hash % 360).toFloat()
        val s = 0.7f
        val v = 0.5f 
        return Color.HSVToColor(floatArrayOf(h, s, v))
    }

    override fun draw(canvas: Canvas) {
        val bounds = bounds
        val width = bounds.width().toFloat()
        val height = bounds.height().toFloat()
        
        // Draw Background (Circle or Rounded Rect)
        // For grid card, circle looks nice inside the square container
        val radius = Math.min(width, height) / 2f
        canvas.drawCircle(bounds.centerX().toFloat(), bounds.centerY().toFloat(), radius, bgPaint)
        
        // Draw Text
        // Dynamic text size based on drawable size
        paint.textSize = Math.min(width, height) * 0.5f
        paint.getTextBounds(displayChar, 0, displayChar.length, textBounds)
        val textHeight = textBounds.height()
        val textOffset = textHeight / 2f - textBounds.bottom
        
        canvas.drawText(displayChar, bounds.centerX().toFloat(), bounds.centerY().toFloat() + textOffset, paint)
    }

    override fun setAlpha(alpha: Int) {
        paint.alpha = alpha
        bgPaint.alpha = alpha
    }

    override fun setColorFilter(colorFilter: ColorFilter?) {
        paint.colorFilter = colorFilter
        bgPaint.colorFilter = colorFilter
    }

    override fun getOpacity(): Int = PixelFormat.TRANSLUCENT
}
