package com.tvonnet.debridxtreamiptv.ui.live.guide

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import android.util.AttributeSet
import android.util.TypedValue
import android.view.KeyEvent
import android.view.View
import android.view.animation.OvershootInterpolator
import androidx.core.content.ContextCompat
import com.bumptech.glide.Glide
import com.bumptech.glide.load.model.GlideUrl
import com.bumptech.glide.load.model.LazyHeaders
import com.bumptech.glide.request.target.CustomTarget
import com.bumptech.glide.request.transition.Transition
import com.tvonnet.debridxtreamiptv.R
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.max
import kotlin.math.min

/**
 * Custom TV EPG grid (spec §4/§5). Renders a sticky time header, a frozen channel
 * column, absolutely-positioned program blocks, a NOW line, and handles D-pad focus
 * traversal + auto-scroll. Self-contained so focus/scroll are reliable on Android TV.
 */
class EpgGridView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyle: Int = 0
) : View(context, attrs, defStyle) {

    interface Listener {
        /** program == null means the frozen channel cell is focused. */
        fun onFocusChanged(channel: GuideChannel, program: GuideProgram?)
        fun onProgramSelected(channel: GuideChannel, program: GuideProgram?)
        fun onChannelSelected(channel: GuideChannel)
        /** D-pad LEFT from the channel column — let the host move focus out (e.g. to chips). */
        fun onExitLeft()
        /** D-pad UP from the top row — host should focus the category chips. */
        fun onExitTop()
    }

    var listener: Listener? = null

    private var channels: List<GuideChannel> = emptyList()
    private var windowStartMs: Long = 0L
    private var spanMinutes: Int = 24 * 60
    private var nowMs: Long = System.currentTimeMillis()

    private val channelColWidth = dimen(R.dimen.epg_channel_col_width)
    private val headerHeight = dimen(R.dimen.epg_header_height)
    private var rowHeight = dimen(R.dimen.epg_row_standard)
    private var ppm = dp(5f)                 // px per minute (standard zoom)
    private var genreTint = true

    private var scrollX = 0f
    private var scrollY = 0f

    private var focusRow = 0
    private var focusProg = -1               // -1 == channel cell

    private val timeFmt = SimpleDateFormat("HH:mm", Locale.getDefault())
    // Phase 6: reused across draws so the onDraw hot path doesn't allocate a new Date per label.
    private val scratchDate = Date()

    // ── Colors ──────────────────────────────────────────────────────────
    private val cBg = color(R.color.epg_bg)
    private val cHeaderBg = color(R.color.epg_header_bg)
    private val cChannelBg = color(R.color.epg_channel_cell_bg)
    private val cGridLine = color(R.color.epg_grid_line)
    private val cCyan = color(R.color.epg_cyan)
    private val cNow = color(R.color.epg_now)
    private val cFav = color(R.color.epg_fav)
    private val cTextPrimary = color(R.color.epg_text_primary)
    private val cTextSecondary = color(R.color.epg_text_secondary)
    private val cTextMuted = color(R.color.epg_text_muted)
    private val cTextFaint = color(R.color.epg_text_faint)
    private val cProgDefault = color(R.color.epg_program_default)
    private val cProgPast = color(R.color.epg_program_past)
    private val cProgNow = color(R.color.epg_program_now)
    private val cProgBorder = color(R.color.epg_program_border)
    private val cProgBorderNow = color(R.color.epg_program_border_now)
    private val cFocusBg = color(R.color.epg_focus_bg)
    private val cGenreGeneral = color(R.color.epg_genre_general)

    // ── Paints ──────────────────────────────────────────────────────────
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val textDisplay = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
    }
    private val textMono = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        typeface = Typeface.MONOSPACE
    }
    private val rect = RectF()

    // Reused per-program cell bounds — never allocate inside the draw loop.
    private val programBounds = RectF()

    private var animator: ValueAnimator? = null

    // Sliding focus highlight (glides + morphs between channels/programs).
    private val hlRect = RectF()
    private val hlTarget = RectF()
    private var hlValid = false
    private var hlAnimator: ValueAnimator? = null

    // Channel logo bitmaps loaded lazily via Glide (keyed by streamId).
    private val logoCache = HashMap<String, Bitmap>()
    private val logoRequested = HashSet<String>()
    private val bitmapPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { isFilterBitmap = true }
    private val tmpDest = RectF()

    private fun requestLogo(channel: GuideChannel) {
        val url = channel.logoUrl
        val key = channel.streamId
        if (url.isNullOrBlank() || key.isEmpty() || !logoRequested.add(key)) return
        val glideUrl = GlideUrl(
            url,
            LazyHeaders.Builder()
                .addHeader("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36")
                .addHeader("Accept", "image/avif,image/webp,image/apng,image/svg+xml,image/*,*/*;q=0.8")
                .build()
        )
        Glide.with(this).asBitmap().load(glideUrl).override(120, 120)
            .into(object : CustomTarget<Bitmap>() {
                override fun onResourceReady(resource: Bitmap, transition: Transition<in Bitmap>?) {
                    logoCache[key] = resource
                    postInvalidate()
                }
                override fun onLoadCleared(placeholder: Drawable?) {}
                override fun onLoadFailed(errorDrawable: Drawable?) {}
            })
    }

    init {
        isFocusable = true
        isFocusableInTouchMode = resources.getBoolean(R.bool.ui_uses_dpad_focus)
        setBackgroundColor(cBg)
    }

    fun setConfig(rowHeightPx: Int, ppmPx: Float, tint: Boolean) {
        rowHeight = rowHeightPx.toFloat()
        ppm = ppmPx
        genreTint = tint
        clampScroll()
        invalidate()
    }

    // Signature of the currently-shown view so a background EPG refresh (same channels,
    // same day window) can be told apart from a real category/day change.
    private var lastChannelSignature: List<String> = emptyList()
    private var lastWindowStart: Long = Long.MIN_VALUE

    fun setData(data: List<GuideChannel>, windowStart: Long, span: Int, now: Long) {
        val signature = data.map { it.streamId }
        // Same channel set AND same day window => a periodic EPG refresh, not a navigation.
        val sameView = signature.isNotEmpty() &&
            signature == lastChannelSignature &&
            windowStart == lastWindowStart
        lastChannelSignature = signature
        lastWindowStart = windowStart

        channels = data
        windowStartMs = windowStart
        spanMinutes = span
        nowMs = now

        if (sameView) {
            // CC-1: preserve the user's row / program / scroll across a refresh instead of
            // snapping back to the top and re-selecting NOW. Just re-validate + repaint.
            focusRow = focusRow.coerceIn(0, data.size - 1)
            val progs = data[focusRow].programs
            focusProg = if (focusProg >= 0 && progs.isNotEmpty()) focusProg.coerceIn(0, progs.size - 1) else focusProg
            clampScroll()
            if (hasFocus()) animateHighlight(snap = true)
            invalidate()
            return
        }

        // Real category/day change → reset to a sensible initial focus and reveal "now".
        focusRow = focusRow.coerceIn(0, max(0, data.size - 1))
        focusProg = -1
        scrollY = 0f
        hlValid = false
        if (data.isNotEmpty()) {
            val ch = data[focusRow]
            val nowIdx = ch.programs.indexOfFirst { it.isNow(now) }
            focusProg = if (nowIdx >= 0) nowIdx else -1
            post { scrollToNow(false) }
            listener?.onFocusChanged(ch, focusProg.takeIf { it >= 0 }?.let { ch.programs[it] })
        }
        clampScroll()
        invalidate()
    }

    private val nowMinute: Float get() = (nowMs - windowStartMs) / 60000f
    private val contentWidth: Float get() = channelColWidth + spanMinutes * ppm
    private val laneViewport: Float get() = (width - channelColWidth)
    private val rowsViewport: Float get() = (height - headerHeight)
    private val maxScrollX: Float get() = max(0f, spanMinutes * ppm - laneViewport)
    private val maxScrollY: Float get() = max(0f, channels.size * rowHeight - rowsViewport)

    private fun clampScroll() {
        scrollX = scrollX.coerceIn(0f, maxScrollX)
        scrollY = scrollY.coerceIn(0f, maxScrollY)
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        clampScroll()
        if (hasFocus()) animateHighlight(true)
    }

    // The channel currently playing in the mini preview (name drawn in cyan).
    private var playingStreamId: String? = null

    /** Mark the channel that is playing in the mini preview. */
    fun setPlayingChannel(streamId: String?) {
        if (playingStreamId == streamId) return
        playingStreamId = streamId
        invalidate()
    }

    /**
     * Programmatically move the focus/selection to a channel row — used when
     * returning from fullscreen after the user zapped to another channel there,
     * so OK doesn't re-tune the previously selected channel.
     */
    fun focusChannel(streamId: String) {
        val idx = channels.indexOfFirst { it.streamId == streamId }
        if (idx < 0 || idx == focusRow) return
        focusRow = idx
        focusProg = -1
        onFocusMoved(snap = true)
    }

    fun scrollToNow(animated: Boolean) {
        if (nowMinute < 0 || nowMinute > spanMinutes) return
        val target = (nowMinute * ppm - laneViewport / 4f).coerceIn(0f, maxScrollX)
        if (animated) {
            animator?.cancel()
            animator = ValueAnimator.ofFloat(scrollX, target).apply {
                duration = 320
                addUpdateListener { scrollX = it.animatedValue as Float; invalidate() }
                start()
            }
        } else {
            scrollX = target
            invalidate()
        }
    }

    // ────────────────────────────────────────────────────────────────────
    // Drawing
    // ────────────────────────────────────────────────────────────────────
    override fun onDraw(canvas: Canvas) {
        canvas.drawColor(cBg)
        if (channels.isEmpty()) return

        drawLanes(canvas)
        drawNowLine(canvas)
        drawChannelColumn(canvas)
        drawHighlight(canvas)
        drawTimeHeader(canvas)
    }

    /** The single sliding focus highlight, drawn as a translucent cyan pill overlay. */
    private fun drawHighlight(canvas: Canvas) {
        if (!hasFocus() || !hlValid || hlRect.isEmpty) return
        val save = canvas.save()
        canvas.clipRect(0f, headerHeight, width.toFloat(), height.toFloat())
        val radius = dp(9f)
        fill.color = cFocusBg
        canvas.drawRoundRect(hlRect, radius, radius, fill)
        stroke.color = cCyan
        stroke.strokeWidth = dp(2f)
        canvas.drawRoundRect(hlRect, radius, radius, stroke)
        canvas.restoreToCount(save)
    }

    /** Screen-space bounds of the currently focused channel cell or program block. */
    private fun computeFocusRect(out: RectF) {
        if (channels.isEmpty()) { out.setEmpty(); return }
        val top = headerHeight + focusRow * rowHeight - scrollY
        val p = if (focusProg >= 0) channels[focusRow].programs.getOrNull(focusProg) else null
        if (p == null) {
            out.set(0f, top, channelColWidth, top + rowHeight)
        } else {
            val startMin = (p.startMs - windowStartMs) / 60000f
            val left = channelColWidth + startMin * ppm - scrollX
            val w = p.durationMinutes() * ppm - dp(3f)
            out.set(left, top + dp(8f), left + w, top + rowHeight - dp(8f))
        }
    }

    private fun animateHighlight(snap: Boolean) {
        computeFocusRect(hlTarget)
        if (snap || !hlValid || hlRect.isEmpty) {
            hlRect.set(hlTarget)
            hlValid = true
            invalidate()
            return
        }
        val sL = hlRect.left; val sT = hlRect.top; val sR = hlRect.right; val sB = hlRect.bottom
        val tL = hlTarget.left; val tT = hlTarget.top; val tR = hlTarget.right; val tB = hlTarget.bottom
        hlAnimator?.cancel()
        hlAnimator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 260
            interpolator = OvershootInterpolator(0.6f)
            addUpdateListener {
                val f = it.animatedValue as Float
                hlRect.set(sL + (tL - sL) * f, sT + (tT - sT) * f, sR + (tR - sR) * f, sB + (tB - sB) * f)
                invalidate()
            }
            start()
        }
    }

    private fun drawLanes(canvas: Canvas) {
        val save = canvas.save()
        canvas.clipRect(channelColWidth, headerHeight, width.toFloat(), height.toFloat())
        val first = (scrollY / rowHeight).toInt().coerceAtLeast(0)
        val last = ((scrollY + rowsViewport) / rowHeight).toInt().coerceAtMost(channels.size - 1)
        for (r in first..last) {
            val ch = channels[r]
            val top = headerHeight + r * rowHeight - scrollY
            val bottom = top + rowHeight
            // row divider
            fill.color = cGridLine
            canvas.drawRect(channelColWidth, bottom - dp(1f), width.toFloat(), bottom, fill)

            if (ch.programs.isEmpty()) {
                textMono.color = cTextFaint
                textMono.textSize = sp(12f)
                canvas.drawText("No program information", channelColWidth + dp(16f), top + rowHeight / 2 + sp(4f), textMono)
                continue
            }
            for ((idx, p) in ch.programs.withIndex()) {
                val startMin = (p.startMs - windowStartMs) / 60000f
                val left = channelColWidth + startMin * ppm - scrollX
                val w = p.durationMinutes() * ppm - dp(3f)
                val right = left + w
                if (right < channelColWidth || left > width) continue
                programBounds.set(left, top, right, bottom)
                drawProgram(canvas, p, programBounds)
            }
        }
        canvas.restoreToCount(save)
    }

    private fun drawProgram(canvas: Canvas, p: GuideProgram, bounds: RectF) {
        val left = bounds.left
        val top = bounds.top
        val right = bounds.right
        val bottom = bounds.bottom
        val bt = top + dp(8f)
        val bb = bottom - dp(8f)
        val now = nowMs
        val bg: Int
        val border: Int
        val titleColor: Int
        when {
            p.isNow(now) -> { bg = cProgNow; border = cProgBorderNow; titleColor = cTextPrimary }
            p.hasEnded(now) -> { bg = cProgPast; border = cProgBorder; titleColor = cTextMuted }
            else -> { bg = cProgDefault; border = cProgBorder; titleColor = cTextPrimary }
        }
        rect.set(left, bt, right, bb)
        fill.color = bg
        canvas.drawRoundRect(rect, dp(9f), dp(9f), fill)
        stroke.color = border
        stroke.strokeWidth = dp(1f)
        canvas.drawRoundRect(rect, dp(9f), dp(9f), stroke)

        // genre accent bar (left)
        val accent = if (genreTint) color(p.genre.colorRes) else cGenreGeneral
        fill.color = accent
        rect.set(left, bt, left + dp(3f), bb)
        canvas.drawRect(rect, fill)

        // clip text to block
        val save = canvas.save()
        canvas.clipRect(left + dp(12f), bt, right - dp(6f), bb)
        textDisplay.color = titleColor
        textDisplay.textSize = sp(14f)
        canvas.drawText(p.title, left + dp(15f), bt + dp(6f) + sp(14f), textDisplay)
        textMono.color = if (p.isNow(now)) cCyan else cTextMuted
        textMono.textSize = sp(11f)
        val timeLabel = p.cachedTimeLabel ?: run {
            scratchDate.time = p.startMs
            val startLabel = timeFmt.format(scratchDate)
            scratchDate.time = p.stopMs
            "$startLabel - ${timeFmt.format(scratchDate)}".also { p.cachedTimeLabel = it }
        }
        canvas.drawText(timeLabel, left + dp(15f), bb - dp(8f), textMono)
        canvas.restoreToCount(save)

        // now-progress bar (current program only)
        if (p.isNow(now)) {
            val prog = ((now - p.startMs).toFloat() / (p.stopMs - p.startMs)).coerceIn(0f, 1f)
            fill.color = cCyan
            canvas.drawRect(left, bb - dp(3f), left + (right - left) * prog, bb, fill)
        }
        // reminder bell dot
        if (p.hasReminder) {
            fill.color = cCyan
            canvas.drawCircle(right - dp(10f), bt + dp(10f), dp(3f), fill)
        }
    }

    private fun drawChannelColumn(canvas: Canvas) {
        val save = canvas.save()
        canvas.clipRect(0f, headerHeight, channelColWidth, height.toFloat())
        fill.color = cChannelBg
        canvas.drawRect(0f, headerHeight, channelColWidth, height.toFloat(), fill)

        val first = (scrollY / rowHeight).toInt().coerceAtLeast(0)
        val last = ((scrollY + rowsViewport) / rowHeight).toInt().coerceAtMost(channels.size - 1)
        for (r in first..last) {
            val ch = channels[r]
            val top = headerHeight + r * rowHeight - scrollY
            // genre accent bar
            fill.color = if (genreTint) color(ch.genre.colorRes) else cGenreGeneral
            canvas.drawRect(0f, top, dp(3f), top + rowHeight, fill)
            // channel number
            textMono.color = cTextFaint
            textMono.textSize = sp(11f)
            canvas.drawText(ch.number?.toString() ?: "", dp(10f), top + rowHeight / 2 + sp(4f), textMono)
            // logo tile (real logo via Glide, initials until it loads)
            val tileSize = dp(40f)
            val tileLeft = dp(38f)
            val tileTop = top + (rowHeight - tileSize) / 2
            rect.set(tileLeft, tileTop, tileLeft + tileSize, tileTop + tileSize)
            fill.color = withAlpha(if (genreTint) color(ch.genre.colorRes) else cGenreGeneral, 60)
            canvas.drawRoundRect(rect, dp(9f), dp(9f), fill)
            val logo = logoCache[ch.streamId]
            if (logo != null) {
                val scale = min(tileSize / logo.width, tileSize / logo.height)
                val dw = logo.width * scale
                val dh = logo.height * scale
                val dl = tileLeft + (tileSize - dw) / 2
                val dTop = tileTop + (tileSize - dh) / 2
                tmpDest.set(dl, dTop, dl + dw, dTop + dh)
                canvas.drawBitmap(logo, null, tmpDest, bitmapPaint)
            } else {
                requestLogo(ch)
                textDisplay.color = Color.WHITE
                textDisplay.textSize = sp(11f)
                val initials = ch.initials()
                val iw = textDisplay.measureText(initials)
                canvas.drawText(initials, tileLeft + (tileSize - iw) / 2, tileTop + tileSize / 2 + sp(4f), textDisplay)
            }
            // fav star overlay
            if (ch.isFavorite) {
                textDisplay.color = cFav
                textDisplay.textSize = sp(10f)
                canvas.drawText("★", tileLeft + tileSize - dp(7f), tileTop + dp(9f), textDisplay)
            }
            // channel name (cyan for the channel playing in the mini preview)
            textDisplay.color = if (ch.streamId == playingStreamId) cCyan else cTextPrimary
            textDisplay.textSize = sp(13f)
            val nameLeft = tileLeft + tileSize + dp(10f)
            val name = ellipsize(ch.name, textDisplay, channelColWidth - nameLeft - dp(8f))
            canvas.drawText(name, nameLeft, top + rowHeight / 2 - dp(1f), textDisplay)
            // quality tag
            if (ch.quality != null) {
                textMono.color = if (ch.quality == "4K") cFav else cTextSecondary
                textMono.textSize = sp(9f)
                canvas.drawText(ch.quality, nameLeft, top + rowHeight / 2 + sp(12f), textMono)
            }
        }
        // right border
        fill.color = cProgBorder
        canvas.drawRect(channelColWidth - dp(1f), headerHeight, channelColWidth, height.toFloat(), fill)
        canvas.restoreToCount(save)
    }

    private fun drawTimeHeader(canvas: Canvas) {
        val save = canvas.save()
        canvas.clipRect(0f, 0f, width.toFloat(), headerHeight)
        fill.color = cHeaderBg
        canvas.drawRect(0f, 0f, width.toFloat(), headerHeight, fill)
        // left "CHANNEL" cell
        textMono.color = cTextFaint
        textMono.textSize = sp(12f)
        canvas.drawText("CHANNEL", dp(16f), headerHeight / 2 + sp(4f), textMono)
        fill.color = cProgBorder
        canvas.drawRect(channelColWidth - dp(1f), 0f, channelColWidth, headerHeight, fill)
        // ticks every 30 min
        var m = 0
        while (m <= spanMinutes) {
            val x = channelColWidth + m * ppm - scrollX
            if (x >= channelColWidth - dp(2f) && x <= width) {
                fill.color = cGridLine
                canvas.drawRect(x, 0f, x + dp(1f), headerHeight, fill)
                textMono.color = cTextSecondary
                textMono.textSize = sp(12f)
                scratchDate.time = windowStartMs + m * 60000L
                canvas.drawText(timeFmt.format(scratchDate), x + dp(6f), headerHeight / 2 + sp(4f), textMono)
            }
            m += 30
        }
        // NOW pill
        if (nowMinute in 0f..spanMinutes.toFloat()) {
            val x = channelColWidth + nowMinute * ppm - scrollX
            if (x in channelColWidth..width.toFloat()) {
                fill.color = cNow
                rect.set(x - dp(22f), dp(8f), x + dp(22f), headerHeight - dp(8f))
                canvas.drawRoundRect(rect, dp(10f), dp(10f), fill)
                textMono.color = Color.WHITE
                textMono.textSize = sp(10f)
                canvas.drawText("NOW", x - dp(13f), headerHeight / 2 + sp(4f), textMono)
            }
        }
        canvas.restoreToCount(save)
    }

    private fun drawNowLine(canvas: Canvas) {
        if (nowMinute < 0 || nowMinute > spanMinutes) return
        val x = channelColWidth + nowMinute * ppm - scrollX
        if (x < channelColWidth || x > width) return
        val save = canvas.save()
        canvas.clipRect(channelColWidth, headerHeight, width.toFloat(), height.toFloat())
        fill.color = cNow
        canvas.drawRect(x - dp(1f), headerHeight, x + dp(1f), height.toFloat(), fill)
        canvas.restoreToCount(save)
    }

    // ────────────────────────────────────────────────────────────────────
    // Focus + key handling
    // ────────────────────────────────────────────────────────────────────
    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        if (channels.isEmpty()) return super.onKeyDown(keyCode, event)
        return when (keyCode) {
            KeyEvent.KEYCODE_DPAD_LEFT -> moveFocusLeft()
            KeyEvent.KEYCODE_DPAD_RIGHT -> moveFocusRight()
            KeyEvent.KEYCODE_DPAD_UP -> moveFocusUp()
            KeyEvent.KEYCODE_DPAD_DOWN -> moveFocusDown()
            KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_BUTTON_A -> selectFocused()
            else -> super.onKeyDown(keyCode, event)
        }
    }

    private fun moveFocusLeft(): Boolean {
        if (focusProg > 0) { focusProg--; onFocusMoved(); return true }
        if (focusProg == 0) { focusProg = -1; onFocusMoved(); return true }
        listener?.onExitLeft(); return true
    }

    private fun moveFocusRight(): Boolean {
        val progs = channels[focusRow].programs
        if (focusProg == -1 && progs.isNotEmpty()) { focusProg = firstVisibleProgramIndex(focusRow); onFocusMoved(); return true }
        if (focusProg < progs.size - 1) { focusProg++; onFocusMoved(); return true }
        return true
    }

    private fun moveFocusUp(): Boolean {
        if (focusRow > 0) { focusRow--; adjustProgForRow(); onFocusMoved(); return true }
        listener?.onExitTop(); return true // hand focus to the category chips
    }

    private fun moveFocusDown(): Boolean {
        if (focusRow < channels.size - 1) { focusRow++; adjustProgForRow(); onFocusMoved(); return true }
        return false // release focus downward
    }

    private fun selectFocused(): Boolean {
        val ch = channels[focusRow]
        if (focusProg == -1) listener?.onChannelSelected(ch)
        else listener?.onProgramSelected(ch, ch.programs.getOrNull(focusProg))
        return true
    }

    private fun adjustProgForRow() {
        val progs = channels[focusRow].programs
        focusProg = when {
            focusProg == -1 -> -1
            progs.isEmpty() -> -1
            else -> focusProg.coerceIn(0, progs.size - 1)
        }
    }

    /** Choose the program under the current scroll position when entering a row's lane. */
    private fun firstVisibleProgramIndex(row: Int): Int {
        val progs = channels[row].programs
        val visMin = (scrollX / ppm)
        val idx = progs.indexOfFirst {
            val s = (it.startMs - windowStartMs) / 60000f
            val e = (it.stopMs - windowStartMs) / 60000f
            e > visMin && s <= visMin + laneViewport / ppm
        }
        return if (idx >= 0) idx else 0
    }

    private fun onFocusMoved(snap: Boolean = false) {
        ensureFocusVisible()
        animateHighlight(snap)
        val ch = channels[focusRow]
        listener?.onFocusChanged(ch, focusProg.takeIf { it >= 0 }?.let { ch.programs.getOrNull(it) })
    }

    private fun ensureFocusVisible() {
        // vertical
        val rowTop = focusRow * rowHeight
        if (rowTop < scrollY) scrollY = rowTop
        else if (rowTop + rowHeight > scrollY + rowsViewport) scrollY = rowTop + rowHeight - rowsViewport
        // horizontal
        if (focusProg >= 0) {
            val p = channels[focusRow].programs.getOrNull(focusProg)
            if (p != null) {
                val startMin = (p.startMs - windowStartMs) / 60000f
                val blockLeft = startMin * ppm
                val blockRight = blockLeft + p.durationMinutes() * ppm
                if (blockLeft < scrollX) scrollX = blockLeft - dp(24f)
                else if (blockRight > scrollX + laneViewport) scrollX = blockRight - laneViewport + dp(24f)
            }
        }
        clampScroll()
    }

    override fun onFocusChanged(gainFocus: Boolean, direction: Int, previouslyFocusedRect: android.graphics.Rect?) {
        super.onFocusChanged(gainFocus, direction, previouslyFocusedRect)
        invalidate() // repaint so the highlight appears/disappears with real focus
        if (gainFocus && channels.isNotEmpty()) {
            onFocusMoved(snap = true) // snap onto the current cell when (re)gaining focus
        }
    }

    // ── helpers ─────────────────────────────────────────────────────────
    private fun dp(v: Float) = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, resources.displayMetrics)
    private fun sp(v: Float) = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, v, resources.displayMetrics)
    private fun dimen(res: Int) = resources.getDimension(res)
    private fun color(res: Int) = ContextCompat.getColor(context, res)
    private fun withAlpha(c: Int, a: Int) = Color.argb(a, Color.red(c), Color.green(c), Color.blue(c))

    private fun ellipsize(text: String, paint: Paint, maxWidth: Float): String {
        if (paint.measureText(text) <= maxWidth) return text
        var end = text.length
        while (end > 1 && paint.measureText(text.substring(0, end) + "…") > maxWidth) end--
        return text.substring(0, end) + "…"
    }
}
