package jp.cn22.yomipace

import android.graphics.*
import kotlin.math.*

/** The root decoration excludes clocks and reader foreground; the reader draws its own background. */
internal class DopamineRenderer {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val path = Path()
    private val clip = Path()
    private val groups = Array(6) { Path() }
    private val palette = intArrayOf(0xFFFF17C8.toInt(), 0xFF00EEFF.toInt(), 0xFFFFFF00.toInt(),
        0xFF9850FF.toInt(), 0xFF39FF65.toInt(), 0xFFFF5328.toInt())
    private val bold = Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)
    private val italic = Typeface.create(Typeface.SERIF, Typeface.BOLD_ITALIC)
    private val mono = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
    private var clipWidth = -1f
    private var clipHeight = -1f
    private var clipWindows: List<RectF> = emptyList()

    private fun ink(color: Int, alpha: Float = 1f, stroke: Float = 0f) {
        paint.shader = null; paint.color = color; paint.alpha = (alpha.coerceIn(0f, 1f) * 255).toInt()
        paint.style = if (stroke > 0) Paint.Style.STROKE else Paint.Style.FILL
        paint.strokeWidth = stroke; paint.strokeCap = Paint.Cap.ROUND
    }
    private fun outside(width: Float, height: Float, windows: List<RectF>) {
        if (width == clipWidth && height == clipHeight && windows == clipWindows) return
        clipWidth = width; clipHeight = height; clipWindows = windows.map(::RectF)
        clip.reset(); clip.fillType = Path.FillType.EVEN_ODD
        clip.addRect(0f, 0f, width, height, Path.Direction.CW)
        val union = Path()
        windows.forEach { r ->
            path.reset(); path.addRect(r, Path.Direction.CW); union.op(path, Path.Op.UNION)
        }
        clip.addPath(union)
    }

    fun draw(canvas: Canvas, width: Float, height: Float, density: Float, now: Long,
             engine: DopamineEngine, protected: List<RectF>) {
        if (!engine.enabled || !engine.available || width <= 0 || height <= 0) return
        outside(width, height, protected)
        val saved = canvas.save(); canvas.clipPath(clip)
        val t = (now % 120_000L) / 1000f
        val e = if (engine.running) engine.level.energy else .08f
        val reader = protected.maxByOrNull { it.height() }
        val split = reader != null && reader.width() < width * .75f
        val left = if (split && reader!!.centerX() < width / 2) reader.right else 0f
        val right = if (split && reader!!.centerX() > width / 2) reader.left else width
        val cx = (left + right) / 2
        val topRoom = protected.filter { it.top > 0 && cx > it.left && cx < it.right }
            .minOfOrNull { it.top } ?: (height * .42f)
        val cy = minOf(height * .24f, topRoom * .60f)
        if (engine.running) {
            ink(0xFF080014.toInt(), .60f + .14f * e); canvas.drawRect(0f, 0f, width, height, paint)
            field(canvas, width, height, density, t, e, cx, cy, false)
            frame(canvas, width, height, density, t, e)
        }
        bursts(canvas, width, height, density, now, engine, protected)
        if (engine.running) retro(canvas, left, right, topRoom, density, t, e, now, engine)
        canvas.restoreToCount(saved)
    }

    /** Called before line plates, text, progress and the red marker inside ScriptReaderView. */
    fun drawReaderBackground(canvas: Canvas, width: Float, height: Float, density: Float, now: Long, engine: DopamineEngine) {
        if (!engine.enabled || !engine.available || width <= 0 || height <= 0) return
        val t = if (engine.running) (now % 120_000L) / 1000f else 0f
        val e = if (engine.running) engine.level.energy else .08f
        val saved = canvas.save(); canvas.clipRect(0f, 0f, width, height)
        ink(0xFF09031B.toInt()); canvas.drawRect(0f, 0f, width, height, paint)
        field(canvas, width, height, density, t, e, width * .5f, height * .35f, true)
        canvas.restoreToCount(saved)
    }

    private fun field(c: Canvas, w: Float, h: Float, d: Float, t: Float, e: Float, cx: Float, cy: Float, reader: Boolean) {
        val cell = (if (reader) 34 else 42) * d
        // Tiled neon wallpaper, reminiscent of early personal homepages.
        for (group in groups) group.reset()
        val columns = (w / cell).toInt() + 2; val lines = (h / cell).toInt() + 2
        for (y in 0..lines) for (x in 0..columns) {
            val px = x * cell + sin(t * .7f + y) * 5 * d
            val py = y * cell + cos(t * .5f + x) * 5 * d
            val arm = (2 + 3 * e) * d
            val p = groups[(x + y * 3).mod(6)]
            p.moveTo(px - arm, py); p.lineTo(px + arm, py)
            p.moveTo(px, py - arm); p.lineTo(px, py + arm)
        }
        groups.forEachIndexed { i, p -> ink(palette[i], .4f + .35f * e, d); c.drawPath(p, paint) }
        // Broad moving ribbons supply energy without full-screen flashes.
        repeat(6) { i ->
            path.reset()
            for (j in 0..26) {
                val x = w * j / 26f
                val y = h * ((i + .5f) / 6) + sin(x / (65 * d) + t * (1.3f + e * 2) + i) * (18 + 24 * e) * d
                if (j == 0) path.moveTo(x, y) else path.lineTo(x, y)
            }
            ink(palette[i], if (reader) .30f else .42f, (12 + 15 * e) * d); c.drawPath(path, paint)
            ink(palette[i], .75f, (1 + e * 2) * d); c.drawPath(path, paint)
        }
        val rays = (if (reader) 36 + 52 * e else 70 + 110 * e).toInt()
        for (group in groups) group.reset()
        repeat(rays) { i ->
            val a = i * 2.399963f + t * (.13f + e * .32f)
            val phase = (t * (.8f + e * 1.6f) + i * .137f) % 1
            val r = min(w, h) * (.02f + .72f * phase)
            val len = (24 + 100 * e) * d * (.4f + phase)
            val p = groups[i.mod(6)]
            p.moveTo(cx + cos(a) * r, cy + sin(a) * r)
            p.lineTo(cx + cos(a) * (r + len), cy + sin(a) * (r + len))
        }
        groups.forEachIndexed { i, p ->
            ink(palette[i], if (reader) .22f else .26f, (6 + e * 5) * d); c.drawPath(p, paint)
            ink(palette[i], if (reader) .65f else .95f, (1 + e * 1.5f) * d); c.drawPath(p, paint)
        }
        repeat(if (reader) 3 else 7) { i ->
            val phase = (t * (.45f + e * .4f) + i / 7f) % 1
            val radius = min(w, h) * (.12f + phase * .95f)
            val color = palette[i.mod(6)]
            ink(color, (1 - phase) * .35f, (9 + 9 * e) * d); c.drawCircle(cx, cy, radius, paint)
            ink(color, (1 - phase) * .95f, 2 * d); c.drawCircle(cx, cy, radius, paint)
        }
        for (group in groups) group.reset()
        repeat((if (reader) 38 + 62 * e else 85 + 155 * e).toInt()) { i ->
            val x = (((i * .618034f + sin(t * 1.8f + i) * .055f) % 1) + 1) % 1 * w
            val y = (i * .3791f + t * (.08f + .32f * e)) % 1 * h
            val r = (2 + i % 4 + e * 3) * d
            val a = t * (2 + e * 3) + i
            val dx = cos(a) * r; val dy = sin(a) * r
            val p = groups[i.mod(6)]
            p.moveTo(x - dx, y - dy); p.lineTo(x + dx, y + dy)
            if (i % 3 == 0) { p.moveTo(x - dy, y + dx); p.lineTo(x + dy, y - dx) }
        }
        groups.forEachIndexed { i, p -> ink(palette[i], if (reader) .75f else 1f, (2 + e) * d); c.drawPath(p, paint) }
        // Zig-zag lightning around the field.
        repeat(if (reader) 2 else 5) { i ->
            path.reset()
            val center = if (i == 0) 10 * d else if (i == 1) w - 10 * d else w * (i - 1) / 4
            for (j in 0..22) {
                val x = center + sin(j * 4.17f + t * (9 + e * 9) + i) * (4 + e * 16) * d
                val y = j * h / 22
                if (j == 0) path.moveTo(x, y) else path.lineTo(x, y)
            }
            ink(palette[(i + (t * 2).toInt()).mod(6)], if (reader) .35f else .8f, (5 + 3 * e) * d); c.drawPath(path, paint)
            ink(Color.WHITE, if (reader) .45f else .95f, d); c.drawPath(path, paint)
        }
    }

    private fun frame(c: Canvas, w: Float, h: Float, d: Float, t: Float, e: Float) {
        repeat(8) { i ->
            val inset = (2 + i * 2) * d
            ink(palette[(i + (t * 3).toInt()).mod(6)], .85f / (1 + i * .08f), (3 + e * 4) * d)
            c.drawRoundRect(inset, inset, w - inset, h - inset, 12 * d, 12 * d, paint)
        }
    }

    private fun bursts(c: Canvas, w: Float, h: Float, d: Float, now: Long, engine: DopamineEngine, windows: List<RectF>) {
        engine.bursts.forEach { b ->
            val age = ((now - b.time) / 1000f).coerceAtLeast(0f)
            if (age > DopamineEngine.BURST_LIFETIME / 1000f) return@forEach
            val alpha = (1 - age / 1.4f).coerceAtLeast(0f).pow(1.5f)
            var x = b.x * w; var y = b.y * h
            windows.firstOrNull { it.contains(x, y) }?.let { r ->
                val distance = floatArrayOf(x - r.left, r.right - x, y - r.top, r.bottom - y)
                when (distance.indices.minBy { distance[it] }) {
                    0 -> x = r.left - 4 * d
                    1 -> x = r.right + 4 * d
                    2 -> y = r.top - 4 * d
                    else -> y = r.bottom + 4 * d
                }
            }
            val special = b.kind == DopamineBurstKind.JACKPOT || b.kind == DopamineBurstKind.PARAGRAPH
            val radius = age * (80 + 180 * b.power) * d
            ink(palette[b.seed.mod(6)], alpha * .25f, (10 + 12 * b.power) * d); c.drawCircle(x, y, radius, paint)
            ink(Color.WHITE, alpha, 2 * d); c.drawCircle(x, y, radius, paint)
            path.reset()
            repeat((12 + b.power * (if (special) 72 else 48)).toInt()) { i ->
                val a = i * 2.399963f + b.seed
                val distance = radius * (.3f + i % 7 / 7f)
                val sx = x + cos(a) * distance; val sy = y + sin(a) * distance + age * age * 60 * d
                val arm = (2 + b.power * 6) * d * alpha
                path.moveTo(sx - arm, sy); path.lineTo(sx + arm, sy)
                path.moveTo(sx, sy - arm); path.lineTo(sx, sy + arm)
            }
            ink(palette[b.seed.mod(6)], alpha, (2 + b.power) * d); c.drawPath(path, paint)
        }
    }

    private fun retro(c: Canvas, left: Float, right: Float, room: Float, d: Float, t: Float, e: Float, now: Long, engine: DopamineEngine) {
        val width = right - left; val cx = (left + right) / 2
        cat(c, right - 31 * d, max(62 * d, room * .26f), d, t)
        val welcomeSize = minOf(32 * d, width / 10)
        rainbow(c, "WELCOME!!", cx, max(42 * d, room * .17f), welcomeSize * (1 + .08f * sin(t * 5)), t, d)
        val headlineY = minOf(room * .52f, 190 * d)
        val headline = when {
            engine.level.title == "OVER DRIVE" -> "超☆覚☆醒"
            e > .95f -> "限界突破!!"
            e > .75f -> "爆熱FEVER!!"
            else -> "ドパッ!!"
        }
        val n = c.save(); c.rotate(sin(t * 7) * (1 + e * 3), cx, headlineY)
        label(c, headline, cx, headlineY, minOf((39 + e * 20) * d, width / 6), palette[(t * 3).toInt().mod(6)], d)
        c.restoreToCount(n)
        if (engine.combo > 1) label(c, "${engine.combo} COMBO!!", cx, headlineY + 31 * d,
            minOf(26 * d, width / 9), Color.WHITE, d)
        val marqueeY = max(95 * d, room * .68f)
        val box = RectF(left + 13 * d, marqueeY, right - 13 * d, marqueeY + 25 * d)
        bevel(c, box, 0xFF080008.toInt(), d)
        val saved = c.save(); c.clipRect(box.left + 3 * d, box.top + 3 * d, box.right - 3 * d, box.bottom - 3 * d)
        paint.typeface = bold; paint.textSize = 13 * d; paint.textAlign = Paint.Align.LEFT
        val message = "★ 熱烈歓迎 ★ ドパガキ発表会 ★ ただいま限界突破中 ★ NO SOUND / ALL DOPA ★ "
        val period = paint.measureText(message) + 30 * d
        val x = box.left - (t * (55 + e * 85) * d) % period
        ink(palette[(t * 2).toInt().mod(6)])
        c.drawText(message, x, box.bottom - 7 * d, paint); c.drawText(message, x + period, box.bottom - 7 * d, paint)
        c.restoreToCount(saved)
        val count = RectF(left + 15 * d, box.bottom + 6 * d, right - 15 * d, box.bottom + 38 * d)
        bevel(c, count, Color.BLACK, d)
        paint.typeface = mono; paint.textAlign = Paint.Align.LEFT; paint.textSize = minOf(20 * d, width / 15)
        ink(0xFF39FF65.toInt())
        val slide = if (engine.slideCount > 0) "#%02d / %02d".format(engine.slideNumber, engine.slideCount) else "SLIDE --"
        c.drawText(slide, count.left + 7 * d, count.bottom - 9 * d, paint)
        paint.textAlign = Paint.Align.RIGHT; paint.textSize = minOf(11 * d, width / 28)
        ink(0xFFFFFF00.toInt())
        c.drawText("DOPA " + (e * 9999 + engine.combo * 77).toInt().toString().padStart(6, '0'), count.right - 6 * d, count.bottom - 11 * d, paint)
        paint.textAlign = Paint.Align.CENTER; paint.typeface = bold; paint.textSize = minOf(11 * d, width / 25)
        ink(palette[(t * 3).toInt().mod(6)], .75f + .25f * sin(t * 5).pow(2))
        c.drawText("NEW!!  ${engine.level.title}  NEW!!", cx, headlineY - 35 * d, paint)
        val celebration = engine.bursts.lastOrNull { now - it.time in 0..800 &&
            (it.kind == DopamineBurstKind.JACKPOT || it.kind == DopamineBurstKind.PARAGRAPH) }
        celebration?.let {
            val age = (now - it.time) / 800f
            val y = max(count.bottom + 30 * d, room * 1.4f) - age * 20 * d
            label(c, if (it.kind == DopamineBurstKind.JACKPOT) "ドパ大爆発!!" else "段落完了!!!", cx, y,
                minOf((30 + 12 * sin(age * PI).toFloat()) * d, width / 7), palette[it.seed.mod(6)], d)
        }
    }

    private fun bevel(c: Canvas, r: RectF, fill: Int, d: Float) {
        ink(fill, .96f); c.drawRect(r, paint)
        ink(Color.WHITE, 1f, 2 * d); c.drawLine(r.left, r.bottom, r.left, r.top, paint); c.drawLine(r.left, r.top, r.right, r.top, paint)
        ink(0xFF727299.toInt(), 1f, 2 * d); c.drawLine(r.right, r.top, r.right, r.bottom, paint); c.drawLine(r.right, r.bottom, r.left, r.bottom, paint)
    }
    private fun cat(c: Canvas, x: Float, y: Float, d: Float, t: Float) {
        // An original blocky beckoning cat; no historical website images are reused.
        val saved = c.save(); c.translate(x, y); c.rotate(sin(t * 4) * 5)
        ink(0xFFFFCF40.toInt()); c.drawRect(-17 * d, -14 * d, 17 * d, 14 * d, paint)
        c.drawRect(-16 * d, -23 * d, -5 * d, -10 * d, paint); c.drawRect(5 * d, -23 * d, 16 * d, -10 * d, paint)
        c.drawRect(-13 * d, 12 * d, 13 * d, 29 * d, paint)
        ink(0xFFFF40C8.toInt()); c.drawRect(-12 * d, -20 * d, -8 * d, -12 * d, paint)
        c.drawRect(8 * d, -20 * d, 12 * d, -12 * d, paint)
        ink(0xFFFF3E3E.toInt()); c.drawRect(-12 * d, 14 * d, 12 * d, 18 * d, paint)
        val paw = -4 * d * sin(t * 8)
        ink(0xFFFFCF40.toInt()); c.drawRect(17 * d, -6 * d + paw, 24 * d, 17 * d + paw, paint)
        for (eye in listOf(-7f, 7f)) {
            ink(0xFF39FF65.toInt(), .6f); c.drawCircle(eye * d, -3 * d, 7 * d, paint)
            ink(Color.WHITE); c.drawRect((eye - 2) * d, -5 * d, (eye + 2) * d, -d, paint)
            ink(0xFF39FF65.toInt(), .8f, d)
            c.drawLine(eye * d, -3 * d, (eye - 20 - sin(t * 3) * 12) * d, 80 * d, paint)
        }
        ink(Color.BLACK, 1f, d); c.drawLine(-3 * d, 6 * d, 3 * d, 6 * d, paint)
        c.restoreToCount(saved)
    }
    private fun rainbow(c: Canvas, text: String, x: Float, y: Float, size: Float, t: Float, d: Float) {
        paint.typeface = italic; paint.textSize = size; paint.textAlign = Paint.Align.LEFT
        var at = x - paint.measureText(text) / 2
        text.forEachIndexed { i, ch ->
            val s = ch.toString(); val advance = paint.measureText(s)
            ink(Color.BLACK, 1f, 5 * d); c.drawText(s, at + 3 * d, y + 3 * d, paint)
            ink(palette[(i + (t * 2).toInt()).mod(6)]); c.drawText(s, at, y + sin(t * 4 + i) * 3 * d, paint)
            at += advance
        }
    }
    private fun label(c: Canvas, text: String, x: Float, y: Float, size: Float, color: Int, d: Float) {
        paint.typeface = bold; paint.textSize = size; paint.textAlign = Paint.Align.CENTER
        ink(0xFF160021.toInt(), 1f, 7 * d); c.drawText(text, x + 2 * d, y + 3 * d, paint)
        ink(Color.WHITE, .9f, 3 * d); c.drawText(text, x, y, paint)
        ink(color); c.drawText(text, x, y, paint)
    }
}
