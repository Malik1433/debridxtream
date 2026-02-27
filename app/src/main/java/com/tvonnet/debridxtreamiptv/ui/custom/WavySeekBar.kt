package com.tvonnet.debridxtreamiptv.ui.custom

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import android.view.animation.LinearInterpolator
import androidx.media3.ui.TimeBar
import kotlin.math.sin

/**
 * A modern wavy seek bar for Media3 player.
 * Draws an animated sine wave for the played portion of the bar.
 */
class WavySeekBar @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr), TimeBar {

    private val listeners = mutableListOf<TimeBar.OnScrubListener>()
    
    // Core state
    private var duration = 0L
    private var position = 0L
    private var bufferedPosition = 0L
    private var scrubPosition = -1L
    private var isScrubbing = false

    // Painting
    private val playedPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 4f * resources.displayMetrics.density
        strokeCap = Paint.Cap.ROUND
        color = Color.WHITE
    }

    private val bufferedPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 4f * resources.displayMetrics.density
        strokeCap = Paint.Cap.ROUND
        color = Color.parseColor("#80FFFFFF") // Semi-transparent white
    }

    private val unplayedPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 4f * resources.displayMetrics.density
        strokeCap = Paint.Cap.ROUND
        color = Color.parseColor("#26FFFFFF") // Very subtle unplayed line (15% white)
    }

    private val scrubberPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = Color.WHITE
    }

    // Wave properties
    private val wavePath = Path()
    private var waveOffset = 0f
    private val waveAnimator = ValueAnimator.ofFloat(0f, (2 * Math.PI).toFloat()).apply {
        duration = 2000
        repeatCount = ValueAnimator.INFINITE
        interpolator = LinearInterpolator()
        addUpdateListener {
            waveOffset = it.animatedValue as Float
            invalidate()
        }
    }

    init {
        waveAnimator.start()
        isFocusable = true
    }

    override fun addListener(listener: TimeBar.OnScrubListener) {
        listeners.add(listener)
    }

    override fun removeListener(listener: TimeBar.OnScrubListener) {
        listeners.remove(listener)
    }

    override fun setKeyTimeIncrement(timeDiffMs: Long) {}

    override fun setKeyCountIncrement(count: Int) {}

    override fun setPosition(position: Long) {
        if (this.position != position) {
            this.position = position
            invalidate()
        }
    }

    override fun setBufferedPosition(bufferedPosition: Long) {
        if (this.bufferedPosition != bufferedPosition) {
            this.bufferedPosition = bufferedPosition
            invalidate()
        }
    }

    override fun setDuration(duration: Long) {
        if (this.duration != duration) {
            this.duration = duration
            invalidate()
        }
    }

    override fun getPreferredUpdateDelay(): Long = 1000L

    override fun setAdGroupTimesMs(adGroupTimesMs: LongArray?, playedAdGroups: BooleanArray?, adGroupCount: Int) {}

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val w = width.toFloat()
        val h = height.toFloat()
        val centerY = h / 2f
        val padding = 20f

        if (duration <= 0) {
            canvas.drawLine(padding, centerY, w - padding, centerY, unplayedPaint)
            return
        }

        val currentPos = if (isScrubbing) scrubPosition else position
        val playedRatio = currentPos.toFloat() / duration
        val playedWidth = (w - 2 * padding) * playedRatio + padding

        val bufferedRatio = bufferedPosition.toFloat() / duration
        val bufferedWidth = (w - 2 * padding) * bufferedRatio + padding

        // 1. Draw Unplayed (Straight line - subtle background)
        canvas.drawLine(padding, centerY, w - padding, centerY, unplayedPaint)

        // 2. Draw Buffered (Semi-transparent straight line)
        canvas.drawLine(padding, centerY, bufferedWidth, centerY, bufferedPaint)

        // 3. Draw Played (Wavy path)
        drawWave(canvas, padding, playedWidth, centerY)

        // 3. Draw Scrubber
        val scrubberScale = if (isFocused || isScrubbing) 1.5f else 1.0f
        val scrubberRadius = 8f * resources.displayMetrics.density * scrubberScale
        canvas.drawCircle(playedWidth, centerY, scrubberRadius, scrubberPaint)
    }

    override fun onFocusChanged(gainFocus: Boolean, direction: Int, previouslyFocusedRect: android.graphics.Rect?) {
        super.onFocusChanged(gainFocus, direction, previouslyFocusedRect)
        invalidate()
    }

    private fun drawWave(canvas: Canvas, startX: Float, endX: Float, centerY: Float) {
        wavePath.reset()
        wavePath.moveTo(startX, centerY)

        val waveAmplitude = 3f * resources.displayMetrics.density
        val waveFrequency = 0.08f
        
        var x = startX
        while (x <= endX) {
            val relativeX = x - startX
            val y = centerY + (waveAmplitude * sin(waveFrequency * relativeX + waveOffset)).toFloat()
            wavePath.lineTo(x, y)
            x += 1f // Finer resolution for smoother curve
        }
        
        // Ensure it ends at the exact scrubber position
        wavePath.lineTo(endX, centerY)
        
        canvas.drawPath(wavePath, playedPaint)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (duration <= 0) return false

        val x = event.x
        val padding = 20f
        val w = width.toFloat()
        val selectableWidth = w - 2 * padding
        
        val ratio = ((x - padding) / selectableWidth).coerceIn(0f, 1f)
        val seekPos = (ratio * duration).toLong()

        when (event.action) {
            MotionEvent.ACTION_DOWN -> {
                isScrubbing = true
                scrubPosition = seekPos
                listeners.forEach { it.onScrubStart(this, seekPos) }
                requestFocus()
                invalidate()
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                scrubPosition = seekPos
                listeners.forEach { it.onScrubMove(this, seekPos) }
                invalidate()
                return true
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                isScrubbing = false
                listeners.forEach { it.onScrubStop(this, seekPos, false) }
                invalidate()
                return true
            }
        }
        return super.onTouchEvent(event)
    }
}
