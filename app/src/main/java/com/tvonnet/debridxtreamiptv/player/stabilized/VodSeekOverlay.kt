package com.tvonnet.debridxtreamiptv.player.stabilized

import android.content.Context
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Shader
import android.util.AttributeSet
import android.view.View

/**
 * VOD Player redesign: purely-visual seek bar matching VOD Player.dc.html —
 * rounded track with chapter ticks, blue→cyan gradient progress with glow, and a
 * solid white thumb with a soft glow.
 *
 * Scrubbing/focus is handled by the media3 DefaultTimeBar (exo_progress) layered transparently
 * on top; PlayerActivity feeds this view progress/buffered fractions.
 */
class VodSeekOverlay @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    private val density = resources.displayMetrics.density
    private val barH = 6f * density
    private val thumbCore = 8f * density     // solid white thumb radius
    private val glowR = 14f * density        // soft cyan glow radius

    private var progress = 0f
    private var buffered = 0f
    private var focusedState = false

    private val trackPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0x24FFFFFF }
    private val bufferedPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0x40FFFFFF }
    private val tickPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0x3AFFFFFF }
    private val progressPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val progressGlow = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0x400A84FF }
    private val thumbGlow = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0x5500C8FF }
    private val thumbCorePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFFFFFFFF.toInt() }
    private val rect = RectF()

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

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        // Phase 6: the progress gradient depends only on the view width, so build it once per
        // resize instead of allocating a new LinearGradient on every onDraw frame.
        val left = glowR
        val right = w - glowR
        progressPaint.shader = LinearGradient(
            left, 0f, right, 0f,
            intArrayOf(0xFF0055CC.toInt(), 0xFF0A84FF.toInt(), 0xFF00C8FF.toInt()),
            floatArrayOf(0f, 0.55f, 1f),
            Shader.TileMode.CLAMP
        )
    }

    override fun onDraw(canvas: Canvas) {
        val cy = height / 2f
        val h = if (focusedState) barH * 1.25f else barH
        val r = h / 2f
        val left = glowR
        val right = width - glowR
        val usable = (right - left).coerceAtLeast(1f)
        val px = left + usable * progress

        // track
        rect.set(left, cy - r, right, cy + r)
        canvas.drawRoundRect(rect, r, r, trackPaint)

        // chapter ticks (evenly spaced, faint) — drawn under progress so only unplayed ones show
        val ticks = 6
        for (i in 1 until ticks) {
            val tx = left + usable * (i.toFloat() / ticks)
            rect.set(tx - 0.75f * density, cy - r, tx + 0.75f * density, cy + r)
            canvas.drawRect(rect, tickPaint)
        }

        // buffered
        if (buffered > 0f) {
            rect.set(left, cy - r, left + usable * buffered, cy + r)
            canvas.drawRoundRect(rect, r, r, bufferedPaint)
        }

        // progress glow (taller, low alpha) then progress gradient
        if (progress > 0f) {
            val gr = r * 2.4f
            rect.set(left, cy - gr, px, cy + gr)
            canvas.drawRoundRect(rect, gr, gr, progressGlow)

            // progressPaint.shader is built once in onSizeChanged (Phase 6) — no per-frame alloc.
            rect.set(left, cy - r, px, cy + r)
            canvas.drawRoundRect(rect, r, r, progressPaint)
        }

        // thumb: soft glow, then solid white core
        canvas.drawCircle(px, cy, glowR, thumbGlow)
        canvas.drawCircle(px, cy, if (focusedState) thumbCore * 1.12f else thumbCore, thumbCorePaint)
    }
}
