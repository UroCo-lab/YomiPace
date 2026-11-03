package jp.cn22.yomipace

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.os.SystemClock
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import android.util.TypedValue
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.View
import android.widget.OverScroller
import jp.cn22.yomipace.core.*
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.roundToInt
import kotlin.math.pow

/** Native text layout hosted in Compose. Only user gestures change actual position.
 * The anchor is an item ID + character offset; no saved pixel scroll is used on reflow.
 */
class ScriptReaderView(context: Context) : View(context) {
    private data class Row(val item: ScriptItem, val top: Float, val height: Float, val layout: StaticLayout?, val blockLabelLayout: StaticLayout?)
    private var items: List<ScriptItem> = emptyList()
    private var rows: List<Row> = emptyList()
    private var timeline: Timeline? = null
    private var font = 18
    private var scrollbar = ReaderScrollbarPosition.LEFT
    private fun sp(value: Float) = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, value, resources.displayMetrics)
    private var fontPixels = sp(18f)
    private val density get() = resources.displayMetrics.density
    private fun dp(v: Float) = v * density
    private var scrollYf = 0f
    private var contentHeight = 0f
    private var elapsed = 0.0
    private var anchor = ReadingAnchor()
    private var colorInk = Color.rgb(23, 33, 43)
    private var colorMuted = Color.rgb(131, 141, 151)
    private var colorBackground = Color.WHITE
    private var colorTrack = Color.rgb(229, 234, 240)
    private val blue = Color.rgb(23, 100, 166)
    private val red = Color.rgb(197, 46, 64)
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val blockRect = RectF()
    private val textPaint = TextPaint(Paint.ANTI_ALIAS_FLAG)
    private val effectsRenderer by lazy { DopamineRenderer() }
    private var effectEngine: DopamineEngine? = null
    private var effectTime: (() -> Long)? = null
    private val effectFrame: () -> Unit = { invalidate() }
    private val foreground=DrawCommandCache("Reader glyphs")
    private val plates=DrawCommandCache("Reader line plates")
    private var layoutRevision=0L
    internal val foregroundRecordings get()=foreground.recordings
    internal val plateRecordings get()=plates.recordings
    private fun effectNow()=effectTime?.invoke() ?: effectEngine?.frameTime?.takeIf { it>0 } ?: SystemClock.elapsedRealtime()
    internal fun bindDopamine(engine: DopamineEngine?, time: (() -> Long)? = null) {
        if(effectEngine===engine && effectTime===time)return
        effectEngine?.removeFrameListener(effectFrame)
        effectEngine=engine;effectTime=time
        if(isAttachedToWindow)engine?.addFrameListener(effectFrame)
        invalidate()
    }
    override fun onAttachedToWindow() {
        super.onAttachedToWindow();effectEngine?.addFrameListener(effectFrame)
    }
    override fun onDetachedFromWindow() {
        effectEngine?.removeFrameListener(effectFrame);super.onDetachedFromWindow()
    }
    private val scroller = OverScroller(context)
    private var inertia = 0.5
    private var draggingRail = false
    private var railGrabOffset = 0f
    var onAnchorChanged: ((ReadingAnchor, Int) -> Unit)? = null
    private val markerY get() = height / 3f
    private val maxScroll get() = max(0f, contentHeight - height)
    private val textLeft get() = dp(if (scrollbar == ReaderScrollbarPosition.LEFT) 26f else 16f)
    private val textRight get() = width - dp(if (scrollbar == ReaderScrollbarPosition.RIGHT) 26f else 16f)
    private val gestures = GestureDetector(context, object : GestureDetector.SimpleOnGestureListener() {
        override fun onDown(e: MotionEvent): Boolean { scroller.forceFinished(true); return true }
        override fun onScroll(e1: MotionEvent?, e2: MotionEvent, distanceX: Float, distanceY: Float): Boolean {
            moveTo(scrollYf + distanceY); return true
        }
        override fun onFling(e1: MotionEvent?, e2: MotionEvent, velocityX: Float, velocityY: Float): Boolean {
            if (inertia == 0.0) return true
            scroller.fling(0, scrollYf.roundToInt(), 0, -velocityY.roundToInt(), 0, 0, 0, maxScroll.roundToInt())
            postInvalidateOnAnimation(); return true
        }
    })
    init { isFocusable = true; importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_YES; contentDescription = "読み上げ原稿。上下にスクロールして赤線に実際の位置を合わせます。" }
    fun bind(script: Script?, planned: Timeline?, size: Int, seconds: Double, position: ReadingAnchor, dark: Boolean, scrollInertia: Double = 0.5, scrollbarPosition: ReaderScrollbarPosition = ReaderScrollbarPosition.LEFT) {
        val newInertia = scrollInertia.takeIf { it.isFinite() }?.coerceIn(0.0, 1.0) ?: 0.5
        if (inertia != newInertia) scroller.forceFinished(true)
        inertia = newInertia
        scroller.setFriction((0.12 * (0.002 / 0.12).pow(inertia)).toFloat())
        val changed = items !== script?.items && items != script?.items || size != font || fontPixels != sp(size.toFloat()) || scrollbar != scrollbarPosition
        if (scrollbar != scrollbarPosition) draggingRail = false
        scrollbar = scrollbarPosition
        if (dark) { colorInk = Color.rgb(237, 241, 245); colorMuted = Color.rgb(153, 164, 175); colorBackground = Color.rgb(32, 38, 45); colorTrack = Color.rgb(55, 66, 78) }
        else { colorInk = Color.rgb(23, 33, 43); colorMuted = Color.rgb(131, 141, 151); colorBackground = Color.WHITE; colorTrack = Color.rgb(229, 234, 240) }
        timeline = planned; elapsed = seconds
        if (changed) {
            anchor = position; items = script?.items.orEmpty(); font = size; fontPixels = sp(size.toFloat())
            scroller.forceFinished(true); reflow()
        } else if (position != anchor) {
            anchor = position; scroller.forceFinished(true); restoreAnchor()
        }
        invalidate()
    }
    private fun reflow() {
        if (width <= 0 || height <= 0) return
        layoutRevision++
        val available = (textRight - textLeft).roundToInt().coerceAtLeast(1)
        var top = markerY
        textPaint.textSize = fontPixels
        rows = items.map { item ->
            val layout = if (item is ScriptItem.Sentence) StaticLayout.Builder.obtain(item.text, 0, item.text.length, textPaint, available)
                .setAlignment(Layout.Alignment.ALIGN_NORMAL).setIncludePad(false).setLineSpacing(0f, 1.65f).setBreakStrategy(android.graphics.text.LineBreaker.BREAK_STRATEGY_HIGH_QUALITY).build() else null
            val rowHeight = when (item) {
                is ScriptItem.Heading -> dp(if (item.slide == 1) 32f else 42f)
                is ScriptItem.Fixed -> dp(88f)
                is ScriptItem.Sentence -> layout!!.height + dp(9f)
            }
            val labelLayout = if (item is ScriptItem.Fixed) {
                val labelPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { textSize = sp(13f) }
                StaticLayout.Builder.obtain(item.label, 0, item.label.length, labelPaint, (available - dp(12f)).roundToInt().coerceAtLeast(1))
                    .setAlignment(Layout.Alignment.ALIGN_CENTER).setMaxLines(2).setEllipsize(android.text.TextUtils.TruncateAt.END).setIncludePad(false).build()
            } else null
            Row(item, top, rowHeight, layout, labelLayout).also { top += rowHeight }
        }
        contentHeight = top + height - markerY
        restoreAnchor()
        invalidate()
    }
    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        scroller.forceFinished(true); reflow()
    }
    private fun restoreAnchor() {
        val row = rows.find { it.item.id == anchor.itemId } ?: rows.firstOrNull() ?: run { scrollYf = 0f; return }
        val layout = row.layout
        val offsetY = if (layout != null) layout.getLineTop(layout.getLineForOffset(anchor.utf16Offset.coerceIn(0, layout.text.length))).toFloat() else 0f
        scrollYf = (row.top + offsetY - markerY).coerceIn(0f, maxScroll)
        // Keep the exact logical anchor through layout changes, even when a new wrap starts earlier.
        onAnchorChanged?.invoke(anchor, row.item.slide)
    }
    private fun moveTo(value: Float) {
        scrollYf = value.coerceIn(0f, maxScroll)
        val y = scrollYf + markerY
        val row = rows.lastOrNull { it.top <= y + 0.5f } ?: rows.firstOrNull()
        if (row != null) {
            val offset = row.layout?.let { layout ->
                val line = layout.getLineForVertical((y - row.top).roundToInt().coerceIn(0, layout.height - 1))
                layout.getLineStart(line)
            } ?: 0
            anchor = ReadingAnchor(row.item.id, offset)
            onAnchorChanged?.invoke(anchor, row.item.slide)
        }
        invalidate()
    }
    override fun onTouchEvent(event: MotionEvent): Boolean {
        parent?.requestDisallowInterceptTouchEvent(true)
        if (event.actionMasked == MotionEvent.ACTION_DOWN) {
            scroller.forceFinished(true)
            draggingRail = when (scrollbar) {
                ReaderScrollbarPosition.LEFT -> event.x < dp(22f)
                ReaderScrollbarPosition.RIGHT -> event.x >= width - dp(22f)
                ReaderScrollbarPosition.NONE -> false
            }
            if (draggingRail) {
                val thumb = scrollbarThumb()
                if (event.y >= thumb.top && event.y <= thumb.bottom) {
                    railGrabOffset = event.y - thumb.top
                    return true
                }
                railGrabOffset = thumb.height() / 2
            }
        }
        if (draggingRail) {
            if (event.actionMasked != MotionEvent.ACTION_CANCEL) {
                val thumb = scrollbarThumb()
                val travel = (height - dp(12f) - thumb.height()).coerceAtLeast(1f)
                moveTo((event.y - dp(6f) - railGrabOffset) / travel * maxScroll)
            }
            if (event.actionMasked == MotionEvent.ACTION_UP || event.actionMasked == MotionEvent.ACTION_CANCEL) {
                draggingRail = false
                if (event.actionMasked == MotionEvent.ACTION_UP) performClick()
                parent?.requestDisallowInterceptTouchEvent(false)
            }
            return true
        }
        val handled = gestures.onTouchEvent(event)
        if (event.actionMasked == MotionEvent.ACTION_CANCEL || event.actionMasked == MotionEvent.ACTION_UP) {
            if (inertia == 0.0 || event.actionMasked == MotionEvent.ACTION_CANCEL) scroller.forceFinished(true)
            if (event.actionMasked == MotionEvent.ACTION_UP) performClick()
            parent?.requestDisallowInterceptTouchEvent(false)
        }
        return handled || event.actionMasked == MotionEvent.ACTION_UP
    }
    override fun performClick(): Boolean { super.performClick(); return true }
    override fun computeScroll() {
        if (scroller.computeScrollOffset()) { moveTo(scroller.currY.toFloat()); postInvalidateOnAnimation() }
    }
    override fun draw(canvas: Canvas) {
        // AndroidView does not clip to the native View's bounds. Never paint into siblings.
        val saved = canvas.save()
        canvas.clipRect(0, 0, width, height)
        try { super.draw(canvas) } finally { canvas.restoreToCount(saved) }
    }
    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        paint.color = colorBackground
        canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), paint)
        val effects = effectEngine?.takeIf { it.enabled && it.available }
        val chaos = effects != null
        val ink = if (chaos) Color.rgb(250, 253, 255) else colorInk
        val muted = if (chaos) Color.rgb(172, 181, 202) else colorMuted
        val accent = if (chaos) Color.rgb(92, 225, 255) else blue
        val track = if (chaos) Color.rgb(24, 22, 49) else colorTrack
        if (effects != null) {
            effectsRenderer.drawReaderBackground(canvas, width.toFloat(), height.toFloat(), density, effectNow(), effects)
        }
        val left = textLeft
        val right = textRight
        // Only changes that affect visible glyphs/plates invalidate their display lists.
        // Effect animation time is deliberately absent: text is independent of the moving field.
        var textKey=layoutRevision*31+scrollYf.toRawBits()
        textKey=textKey*31+ink;textKey=textKey*31+muted;textKey=textKey*31+accent
        textKey=textKey*31+track;textKey=textKey*31+if(chaos)1 else 0
        var plateKey=textKey
        rows.forEach { row ->
            val y=row.top-scrollYf
            if(y+row.height>=0 && y<=height) {
                val item=row.item
                val segment=timeline?.byId?.get(if(item is ScriptItem.Heading)"switch${item.slide}" else item.id)
                if(item is ScriptItem.Sentence) {
                    val state=if(segment!=null && elapsed>=segment.end && elapsed>0)2 else if(segment!=null && elapsed>=segment.start && elapsed<segment.end)1 else 0
                    textKey=textKey*31+state
                } else {
                    val fraction=segment?.fraction(elapsed)?.toFloat() ?: 0f
                    textKey=textKey*31+fraction.toRawBits()
                    if(item is ScriptItem.Fixed)plateKey=plateKey*31+fraction.toRawBits()
                }
            }
        }
        if(effects!=null) {
            val recording=plates.beginIfChanged(canvas,width,height,plateKey)
            if(recording!=null) {
                val canvas=recording
                textPaint.textSize=fontPixels
                paint.style=Paint.Style.FILL;paint.color=Color.argb(210,5,2,15)
                rows.forEach { row ->
                    val y=row.top-scrollYf
                    if(row.item is ScriptItem.Sentence && y+row.height>=0 && y<=height) {
                        val layout=row.layout!!
                        for(line in 0 until layout.lineCount) {
                            val baseline=y+layout.getLineBaseline(line)
                            canvas.drawRoundRect(left+layout.getLineLeft(line)-dp(3f),baseline+textPaint.ascent()-dp(2f),
                                left+layout.getLineRight(line)+dp(3f),baseline+textPaint.descent()+dp(2f),dp(3f),dp(3f),paint)
                        }
                    }
                    if(row.item is ScriptItem.Fixed && y+row.height>=0 && y<=height) {
                        val fraction=timeline?.byId?.get(row.item.id)?.fraction(elapsed)?.toFloat() ?: 0f
                        val rect=blockRect.apply { set(left,y+dp(8f),right,y+dp(56f)) }
                        paint.color=track;canvas.drawRoundRect(rect,dp(7f),dp(7f),paint)
                        canvas.save();canvas.clipRect(rect.left,rect.top,rect.left+rect.width()*fraction,rect.bottom)
                        paint.color=Color.argb(100,45,134,221);canvas.drawRoundRect(rect,dp(7f),dp(7f),paint);canvas.restore()
                        paint.color=Color.argb(210,5,2,15)
                    }
                }
                plates.endRecording()
            }
            plates.draw(canvas)
            effectsRenderer.drawReaderMascots(canvas,width.toFloat(),height.toFloat(),density,effectNow(),effects)
        }
        val recording=foreground.beginIfChanged(canvas,width,height,textKey)
        if(recording!=null) {
            val canvas=recording
            rows.forEach { row ->
                val y = row.top - scrollYf
                if (y + row.height < 0 || y > height) return@forEach
                val item = row.item
                when (item) {
                    is ScriptItem.Sentence -> {
                        val segment = timeline?.byId?.get(item.id)
                        val textColor = if (segment != null && elapsed >= segment.end && elapsed > 0) muted else ink
                        textPaint.color = textColor
                        textPaint.textSize = fontPixels
                        canvas.save(); canvas.translate(left, y)
                        val layout = row.layout!!
                        if (chaos) {
                            // Opaque outlined glyphs stay in front of the mascots as well as the other effects.
                            textPaint.style = Paint.Style.STROKE; textPaint.strokeWidth = dp(2.5f); textPaint.color = Color.rgb(5, 2, 15)
                            layout.draw(canvas)
                            textPaint.style = Paint.Style.FILL; textPaint.color = textColor
                        }
                        layout.draw(canvas)
                        if (segment != null && elapsed >= segment.start && elapsed < segment.end) {
                            paint.color = accent; paint.strokeWidth = dp(1f)
                            for (line in 0 until row.layout.lineCount) {
                                val bottom = row.layout.getLineBaseline(line) + dp(4f)
                                canvas.drawLine(row.layout.getLineLeft(line), bottom, row.layout.getLineRight(line), bottom, paint)
                            }
                        }
                        canvas.restore()
                    }
                    is ScriptItem.Heading -> {
                        paint.color = accent; paint.textSize = sp(12f); paint.isFakeBoldText = false
                        if (chaos) {
                            paint.color = Color.rgb(5, 2, 15); paint.style = Paint.Style.STROKE; paint.strokeWidth = dp(3f)
                            canvas.drawText("#${item.slide}", left, y + dp(20f), paint)
                            paint.style = Paint.Style.FILL; paint.color = accent
                        }
                        canvas.drawText("#${item.slide}", left, y + dp(20f), paint)
                        if (item.slide > 1) {
                            val segment = timeline?.byId?.get("switch${item.slide}")
                            val f = segment?.fraction(elapsed)?.toFloat() ?: 0f
                            paint.color = track; canvas.drawRect(left, y + dp(28f), right, y + dp(31f), paint)
                            paint.color = accent; canvas.drawRect(left, y + dp(28f), left + (right - left) * f, y + dp(31f), paint)
                        }
                    }
                    is ScriptItem.Fixed -> {
                        val segment = timeline?.byId?.get(item.id)
                        val fraction = segment?.fraction(elapsed)?.toFloat() ?: 0f
                        val rect = blockRect.apply { set(left, y + dp(8f), right, y + dp(56f)) }
                        if(!chaos) {
                            paint.color = track; canvas.drawRoundRect(rect, dp(7f), dp(7f), paint)
                            canvas.save(); canvas.clipRect(rect.left, rect.top, rect.left + rect.width() * fraction, rect.bottom)
                            paint.color = Color.argb(100, 45, 134, 221); canvas.drawRoundRect(rect, dp(7f), dp(7f), paint); canvas.restore()
                        }
                        paint.color = ink; paint.textSize = sp(13f)
                        // Labels can be long: wrap in a compact layout rather than paint outside the bar.
                        val label = row.blockLabelLayout!!
                        label.paint.color = ink
                        canvas.save(); canvas.clipRect(rect); canvas.translate(rect.left + dp(6f), rect.centerY() - label.height / 2f); label.draw(canvas); canvas.restore()
                        paint.color = muted; paint.textSize = sp(11f)
                        val seconds = item.seconds * fraction
                        val status = "${(fraction * 100).roundToInt()}%    ${formatTime(seconds)} / ${formatTime(item.seconds)}"
                        if (chaos) {
                            paint.color = Color.rgb(5, 2, 15); paint.style = Paint.Style.STROKE; paint.strokeWidth = dp(3f)
                            canvas.drawText(status, left, y + dp(75f), paint)
                            paint.style = Paint.Style.FILL; paint.color = muted
                        }
                        canvas.drawText(status, left, y + dp(75f), paint)
                    }
                }
            }
            foreground.endRecording()
        }
        foreground.draw(canvas)
        if (items.isNotEmpty()) {
            paint.color = red; paint.strokeWidth = dp(2f)
            val markerLeft = dp(if (scrollbar == ReaderScrollbarPosition.LEFT) 24f else 6f)
            val markerRight = width - dp(if (scrollbar == ReaderScrollbarPosition.RIGHT) 24f else 6f)
            if (chaos) {
                paint.color = Color.rgb(5, 2, 15); paint.strokeWidth = dp(5f)
                canvas.drawLine(markerLeft, markerY, markerRight, markerY, paint)
                paint.color = Color.rgb(255, 58, 100); paint.strokeWidth = dp(2f)
            }
            canvas.drawLine(markerLeft, markerY, markerRight, markerY, paint)
            paint.style = Paint.Style.FILL
            canvas.drawCircle(markerLeft, markerY, dp(3f), paint)
        }
        if (scrollbar == ReaderScrollbarPosition.NONE) return
        // Draw slide starts over the thumb, entirely inside the same scrollbar track.
        val thumb = scrollbarThumb()
        paint.color = track
        canvas.drawRoundRect(thumb.left, dp(6f), thumb.right, height - dp(6f), dp(3f), dp(3f), paint)
        paint.color = accent
        canvas.drawRoundRect(thumb, dp(3f), dp(3f), paint)
        val dotX = thumb.centerX()
        slideMarkerPositions().forEach { y ->
            paint.color = Color.rgb(123, 89, 0); canvas.drawCircle(dotX, y, dp(2.5f), paint)
            paint.color = Color.rgb(255, 211, 55); canvas.drawCircle(dotX, y, dp(2f), paint)
        }
    }
    private fun scrollbarThumb(): RectF {
        val track = (height - dp(12f)).coerceAtLeast(1f)
        val thumbHeight = (track * height / contentHeight.coerceAtLeast(height.toFloat()).coerceAtLeast(1f)).coerceIn(minOf(dp(24f), track), track)
        val top = dp(6f) + if (maxScroll > 0) scrollYf / maxScroll * (track - thumbHeight) else 0f
        val left = if (scrollbar == ReaderScrollbarPosition.RIGHT) width - dp(10f) else dp(4f)
        return RectF(left, top, left + dp(6f), top + thumbHeight)
    }
    private fun slideMarkerPositions(): List<Float> = rows.filter { it.item is ScriptItem.Heading }.map {
        (dp(6f) + it.top / contentHeight.coerceAtLeast(1f) * (height - dp(12f)).coerceAtLeast(1f))
            .coerceIn(dp(9f), (height - dp(9f)).coerceAtLeast(dp(9f)))
    }
    fun logicalAnchorForTest() = anchor
}

fun formatTime(seconds: Double): String {
    val value = abs(seconds).toLong()
    return "%d:%02d".format(java.util.Locale.ROOT, value / 60, value % 60)
}
