package com.tvonnet.debridxtreamiptv.player.stabilized

import android.content.Context
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import android.util.AttributeSet
import android.view.View

/**
 * VOD Player seek bar, purely visual, drawn to VOD Player.dc.html (polish 2026-09-20):
 * a track of thin ticks (played = cyan, buffered = white 32%, unplayed = white 13%)
 * with a soft blue→cyan glow wash under the played span, a buffered-frontier line +
 * dot, and a white bar playhead with a cyan halo. Focus grows the track (24→34 px,
 * i.e. 12→17 dp) and the playhead, and shows a time bubble above the playhead.
 *
 * Scrubbing/focus is handled by the media3 DefaultTimeBar (exo_progress) layered
 * transparently on top; PlayerVodControlsUi feeds progress/buffered fractions and the
 * bubble text. The bubble is drawn ABOVE the view's own bounds, so every ancestor up
 * to the controls column keeps clipChildren=false.
 */
class VodSeekOverlay @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    private val density = resources.displayMetrics.density
    private fun dp(v: Float) = v * density

    private var progress = 0f
    private var buffered = 0f
    private var focusedState = false
    private var bubbleText: String? = null

    private val tickPlayed = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFF00F0FF.toInt() }
    private val tickBuffered = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0x52FFFFFF }
    private val tickUnplayed = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0x21FFFFFF }
    private val washPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val washOuterPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val edgeLine = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0x80FFFFFF.toInt() }
    private val edgeDot = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xBFFFFFFF.toInt() }
    private val headCore = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFFFFFFFF.toInt() }
    private val headHaloInner = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0x8000F0FF.toInt() }
    private val headHaloOuter = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0x3300B4FF }
    private val bubbleBg = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xF0060912.toInt() }
    private val bubbleStroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0x7300F0FF; style = Paint.Style.STROKE; strokeWidth = dp(1f)
    }
    private val bubbleTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFF041014.toInt()
        typeface = Typeface.MONOSPACE
        textSize = dp(6.5f)
        isFakeBoldText = true
        letterSpacing = 0.06f
    }
    private val bubbleChip = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFF00F0FF.toInt() }
    private val rect = RectF()
    private val tri = Path()

    fun setProgress(fraction: Float) {
        val f = fraction.coerceIn(0f, 1f)
        if (f != progress) { progress = f; invalidate() }
    }

    fun setBuffered(fraction: Float) {
        val f = fraction.coerceIn(0f, 1f)
        if (f != buffered) { buffered = f; invalidate() }
    }

    fun setFocusedVisual(focused: Boolean) {
        if (focused != focusedState) { focusedState = focused; invalidate() }
    }

    /** Text of the bubble above the playhead while the bar is focused; null hides it. */
    fun setBubbleText(text: String?) {
        if (text != bubbleText) { bubbleText = text; invalidate() }
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        // The wash gradient depends only on the width — build it once per resize, not per frame.
        washPaint.shader = LinearGradient(
            0f, 0f, w.toFloat(), 0f,
            intArrayOf(0x470077FF, 0x6B00F0FF), null, Shader.TileMode.CLAMP
        )
        washOuterPaint.shader = LinearGradient(
            0f, 0f, w.toFloat(), 0f,
            intArrayOf(0x1F0077FF, 0x2E00F0FF), null, Shader.TileMode.CLAMP
        )
    }

    /** Every size that changes with focus, resolved once per frame. */
    private class Metrics(dp: (Float) -> Float, focused: Boolean) {
        val trackH = if (focused) dp(17f) else dp(12f)
        val playedH = if (focused) trackH else trackH * 0.70f
        val idleH = if (focused) trackH * 0.54f else trackH * 0.36f
        val washH = if (focused) dp(11f) else dp(7f)
        val edgeH = if (focused) dp(13f) else dp(9f)
        val headW = if (focused) dp(2.5f) else dp(1.5f)
        val headH = if (focused) dp(22f) else dp(15f)
    }

    override fun onDraw(canvas: Canvas) {
        val w = width.toFloat()
        val cy = height / 2f
        val m = Metrics(::dp, focusedState)
        val px = w * progress
        if (progress > 0f) drawWash(canvas, px, cy, m)
        drawTicks(canvas, w, cy, m)
        if (buffered > progress && buffered < 1f) drawBufferedEdge(canvas, w * buffered, cy, m)
        val hx = px.coerceIn(m.headW / 2f, w - m.headW / 2f)
        drawPlayhead(canvas, hx, cy, m)
        val text = bubbleText
        if (focusedState && !text.isNullOrEmpty()) drawBubble(canvas, hx, cy - m.headH / 2f, w, text)
    }

    /** Glow wash under the played span: two soft rounded layers stand in for a blur. */
    private fun drawWash(canvas: Canvas, px: Float, cy: Float, m: Metrics) {
        rect.set(0f, cy - m.washH * 1.7f, px, cy + m.washH * 1.7f)
        canvas.drawRoundRect(rect, m.washH, m.washH, washOuterPaint)
        rect.set(0f, cy - m.washH, px, cy + m.washH)
        canvas.drawRoundRect(rect, m.washH, m.washH, washPaint)
    }

    private fun drawTicks(canvas: Canvas, w: Float, cy: Float, m: Metrics) {
        val gap = dp(1f)
        val ticks = (w / dp(5.5f)).toInt().coerceIn(24, 160)
        val tickW = ((w - gap * (ticks - 1)) / ticks).coerceAtLeast(dp(1f))
        for (i in 0 until ticks) {
            val x = i * (tickW + gap)
            val t = (x + tickW / 2f) / w
            val played = t <= progress
            val paint = if (played) tickPlayed else if (t <= buffered) tickBuffered else tickUnplayed
            val th = if (played) m.playedH else m.idleH
            rect.set(x, cy - th / 2f, x + tickW, cy + th / 2f)
            canvas.drawRoundRect(rect, dp(0.5f), dp(0.5f), paint)
        }
    }

    /** Buffered frontier: thin line + dot. */
    private fun drawBufferedEdge(canvas: Canvas, bx: Float, cy: Float, m: Metrics) {
        rect.set(bx - dp(0.5f), cy - m.edgeH / 2f, bx + dp(0.5f), cy + m.edgeH / 2f)
        canvas.drawRoundRect(rect, dp(0.5f), dp(0.5f), edgeLine)
        canvas.drawCircle(bx, cy, dp(1.5f), edgeDot)
    }

    /** Cyan halo, then the white bar. */
    private fun drawPlayhead(canvas: Canvas, hx: Float, cy: Float, m: Metrics) {
        val hw = m.headW
        val hh = m.headH
        rect.set(hx - hw / 2f - dp(3f), cy - hh / 2f - dp(3f), hx + hw / 2f + dp(3f), cy + hh / 2f + dp(3f))
        canvas.drawRoundRect(rect, dp(3f), dp(3f), headHaloOuter)
        rect.set(hx - hw / 2f - dp(1.2f), cy - hh / 2f - dp(1.2f), hx + hw / 2f + dp(1.2f), cy + hh / 2f + dp(1.2f))
        canvas.drawRoundRect(rect, dp(2f), dp(2f), headHaloInner)
        rect.set(hx - hw / 2f, cy - hh / 2f, hx + hw / 2f, cy + hh / 2f)
        canvas.drawRoundRect(rect, dp(1.5f), dp(1.5f), headCore)
    }

    private fun drawBubble(canvas: Canvas, headX: Float, headTop: Float, w: Float, text: String) {
        val padX = dp(6f)
        val chipH = dp(11f)
        val textW = bubbleTextPaint.measureText(text)
        val bw = textW + padX * 2 + dp(8f)
        val bh = chipH + dp(8f)
        val cx = headX.coerceIn(bw / 2f, w - bw / 2f)
        val bottom = headTop - dp(7f)
        val top = bottom - bh
        rect.set(cx - bw / 2f, top, cx + bw / 2f, bottom)
        canvas.drawRoundRect(rect, dp(5f), dp(5f), bubbleBg)
        canvas.drawRoundRect(rect, dp(5f), dp(5f), bubbleStroke)
        // pointer
        tri.reset()
        tri.moveTo(headX - dp(3f), bottom)
        tri.lineTo(headX + dp(3f), bottom)
        tri.lineTo(headX, bottom + dp(3.5f))
        tri.close()
        canvas.drawPath(tri, bubbleBg)
        // cyan time chip
        rect.set(cx - textW / 2f - padX, top + dp(4f), cx + textW / 2f + padX, top + dp(4f) + chipH)
        canvas.drawRoundRect(rect, dp(2f), dp(2f), bubbleChip)
        val ty = rect.centerY() - (bubbleTextPaint.descent() + bubbleTextPaint.ascent()) / 2f
        canvas.drawText(text, cx - textW / 2f, ty, bubbleTextPaint)
    }
}
