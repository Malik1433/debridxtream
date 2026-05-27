package com.tvonnet.debridxtreamiptv.ui.custom

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.util.AttributeSet
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import androidx.media3.ui.TimeBar

/**
 * A straight, focus-aware seek bar for Media3 player.
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

    init {
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
        val focused = isFocused || isScrubbing

        playedPaint.color = if (focused) Color.parseColor("#FF3B30") else Color.parseColor("#E6FF3B30")
        playedPaint.strokeWidth = (if (focused) 6f else 4f) * resources.displayMetrics.density
        bufferedPaint.color = if (focused) Color.parseColor("#80FFFFFF") else Color.parseColor("#4DFFFFFF")
        unplayedPaint.color = if (focused) Color.parseColor("#33FFFFFF") else Color.parseColor("#26FFFFFF")
        scrubberPaint.color = Color.parseColor("#FF3B30")

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

        // 3. Draw Played (straight path)
        canvas.drawLine(padding, centerY, playedWidth, centerY, playedPaint)

        // 4. Draw Scrubber
        val scrubberScale = if (focused) 1.6f else 1.0f
        val scrubberRadius = 7f * resources.displayMetrics.density * scrubberScale
        if (focused) {
            val glowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                style = Paint.Style.FILL
                color = Color.parseColor("#4DFF3B30")
            }
            canvas.drawCircle(playedWidth, centerY, scrubberRadius * 1.8f, glowPaint)
        }
        canvas.drawCircle(playedWidth, centerY, scrubberRadius, scrubberPaint)
    }

    override fun onFocusChanged(gainFocus: Boolean, direction: Int, previouslyFocusedRect: android.graphics.Rect?) {
        super.onFocusChanged(gainFocus, direction, previouslyFocusedRect)
        invalidate()
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        if (duration <= 0) return super.onKeyDown(keyCode, event)
        
        val increment = 60000L // 60s (1 min) skip for D-Pad on Seek Bar
        var handled = false
        
        when (keyCode) {
            KeyEvent.KEYCODE_DPAD_LEFT -> {
                val newPos = (if (isScrubbing) scrubPosition else position) - increment
                val seekPos = newPos.coerceIn(0, duration)
                if (!isScrubbing) {
                    isScrubbing = true
                    listeners.forEach { it.onScrubStart(this, position) }
                }
                scrubPosition = seekPos
                listeners.forEach { it.onScrubMove(this, seekPos) }
                invalidate()
                handled = true
            }
            KeyEvent.KEYCODE_DPAD_RIGHT -> {
                val newPos = (if (isScrubbing) scrubPosition else position) + increment
                val seekPos = newPos.coerceIn(0, duration)
                if (!isScrubbing) {
                    isScrubbing = true
                    listeners.forEach { it.onScrubStart(this, position) }
                }
                scrubPosition = seekPos
                listeners.forEach { it.onScrubMove(this, seekPos) }
                invalidate()
                handled = true
            }
            KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER -> {
                if (isScrubbing) {
                    val finalPos = scrubPosition
                    isScrubbing = false
                    listeners.forEach { it.onScrubStop(this, finalPos, false) }
                    invalidate()
                    handled = true
                }
            }
        }
        
        if (handled) return true
        return super.onKeyDown(keyCode, event)
    }

    override fun onKeyUp(keyCode: Int, event: KeyEvent): Boolean {
        if (isScrubbing && (keyCode == KeyEvent.KEYCODE_DPAD_LEFT || keyCode == KeyEvent.KEYCODE_DPAD_RIGHT)) {
            val finalPos = scrubPosition
            isScrubbing = false
            listeners.forEach { it.onScrubStop(this, finalPos, false) }
            invalidate()
            return true
        }
        return super.onKeyUp(keyCode, event)
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
