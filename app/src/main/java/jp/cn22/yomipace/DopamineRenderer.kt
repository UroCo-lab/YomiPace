package jp.cn22.yomipace

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Typeface
import kotlin.math.*

/** One transparent decorative layer; protected regions are clipped before ANY paint. */
internal class DopamineRenderer {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val path = Path()
    private val palette = intArrayOf(0xFFFF40C8.toInt(), 0xFF17DFFF.toInt(), 0xFFFFD642.toInt(), 0xFF9C65FF.toInt(), 0xFF4EFFA8.toInt())
    private fun ink(color: Int, alpha: Float = 1f, stroke: Float = 0f) {
        paint.color = color; paint.alpha = (alpha.coerceIn(0f, 1f) * 255).toInt()
        paint.style = if (stroke > 0) Paint.Style.STROKE else Paint.Style.FILL
        paint.strokeWidth = stroke
    }
    fun draw(canvas: Canvas, width: Float, height: Float, density: Float, now: Long,
             engine: DopamineEngine, protected: List<RectF>) {
        if (!engine.enabled || !engine.available || width <= 0 || height <= 0) return
        val saved = canvas.save()
        // EVEN_ODD also works on API 26 without deprecated Region.Op.DIFFERENCE.
        path.reset(); path.fillType = Path.FillType.EVEN_ODD
        path.addRect(0f, 0f, width, height, Path.Direction.CW)
        // Union first so overlapping protected rectangles cannot make a hole in protection.
        val exclusions = Path()
        protected.forEach { r ->
            val one = Path().apply { addRect(r, Path.Direction.CW) }
            exclusions.op(one, Path.Op.UNION)
        }
        path.addPath(exclusions)
        canvas.clipPath(path)
        val energy = if (engine.running) engine.level.energy else .06f
        val t = (now % 120_000L) / 1000f
        val cx = width / 2; val cy = height * .26f

        // A neon arena frame builds from a thin rim to a broad, travelling rainbow.
        if (engine.running) {
            ink(0xFF110D26.toInt(), .05f + .28f * energy)
            canvas.drawRect(0f, 0f, width, height, paint)
            for (i in 0..5) {
                val inset = (2 + i * 2) * density
                ink(palette[(i + (now / 400).toInt()).mod(palette.size)], (.15f + .6f * energy) / (1 + i * .35f), (2 + energy * 4) * density)
                canvas.drawRoundRect(inset, inset, width - inset, height - inset, 16 * density, 16 * density, paint)
            }
            // Constant travel, no full-screen flashing. More speed lines and confetti near zero.
            val count = (6 + 64 * energy.pow(2)).toInt()
            for (i in 0 until count) {
                val a = i * 2.399963f + sin(t * .18f) * .15f
                val phase = ((t * (.25f + energy) + i * .137f) % 1)
                val radius = min(width, height) * (.08f + .65f * phase)
                val length = (20 + 120 * energy) * density
                ink(palette[i % 5], (.16f + .65f * energy) * phase, (1 + energy * 2) * density)
                canvas.drawLine(cx + cos(a) * radius, cy + sin(a) * radius,
                    cx + cos(a) * (radius + length), cy + sin(a) * (radius + length), paint)
            }
            if (energy > .5f) repeat(4) { i ->
                val phase = ((t * (.4f + energy * .6f) + i * .25f) % 1)
                ink(palette[(i + (now / 900).toInt()).mod(5)], (1 - phase) * energy * .45f, (1 + 2 * energy) * density)
                canvas.drawCircle(cx, cy, min(width, height) * (.15f + phase * .9f), paint)
            }
            repeat((4 + 90 * energy.pow(2)).toInt()) { i ->
                val x = ((i * .618034f + sin(t * .7f + i) * .035f) % 1) * width
                val y = ((i * .3791f + t * (.035f + .16f * energy)) % 1) * height
                val r = (1.5f + (i % 4) + energy * 2) * density
                ink(palette[i % 5], .45f + energy * .4f)
                val n = canvas.save(); canvas.rotate(t * 60 + i * 37f, x, y)
                canvas.drawRect(x - r, y - r * .35f, x + r, y + r * .35f, paint); canvas.restoreToCount(n)
            }
        }

        engine.bursts.forEach { burst ->
            val age = ((now - burst.time) / 1000f).coerceAtLeast(0f)
            if (age > 1.1f) return@forEach
            val alpha = (1 - age / 1.1f).pow(2)
            var x = burst.x * width; var y = burst.y * height
            // Touches inside the manuscript celebrate at its nearest rim, outside the text.
            protected.firstOrNull { it.contains(x, y) }?.let { r ->
                val distances = listOf(x - r.left, r.right - x, y - r.top, r.bottom - y)
                when (distances.indices.minBy { distances[it] }) {
                    0 -> x = r.left - 4 * density
                    1 -> x = r.right + 4 * density
                    2 -> y = r.top - 4 * density
                    else -> y = r.bottom + 4 * density
                }
            }
            val radius = age * (60 + 140 * burst.power) * density
            ink(palette[burst.seed.mod(5)], alpha, 2 * density)
            canvas.drawCircle(x, y, radius, paint)
            repeat((8 + 28 * burst.power).toInt()) { i ->
                val a = i * 2.399963f + burst.seed
                val distance = radius * (.4f + (i % 7) / 7f)
                val sx = x + cos(a) * distance; val sy = y + sin(a) * distance + age * age * 75 * density
                val arm = (2 + burst.power * 4) * density * (1 - age / 1.1f)
                ink(palette[i.mod(5)], alpha, 1.5f * density)
                canvas.drawLine(sx - arm, sy, sx + arm, sy, paint)
                canvas.drawLine(sx, sy - arm, sx, sy + arm, paint)
            }
        }

        // Arcade callouts occupy the slide area only, never the reading/clock windows.
        if (engine.running) {
            val reader = protected.maxByOrNull { it.height() }
            val split = reader != null && reader.width() < width * .75f
            val panelLeft = if (split && reader!!.centerX() < cx) reader.right else 0f
            val panelRight = if (split && reader!!.centerX() > cx) reader.left else width
            val panelWidth = panelRight - panelLeft
            val calloutX = (panelLeft + panelRight) / 2
            label(canvas, engine.level.title, calloutX, max(52 * density, height * .13f), min((20 + energy * 17) * density, panelWidth / 8), palette[(now / 600).toInt().mod(5)])
            if (engine.combo > 1) label(canvas, "${engine.combo} COMBO!", calloutX, height * .22f, min(22 * density, panelWidth / 8), Color.WHITE)
            val railY = height * .27f
            ink(0xFF0D1021.toInt(), .8f); canvas.drawRoundRect(calloutX - panelWidth * .28f, railY, calloutX + panelWidth * .28f, railY + 7 * density, 4 * density, 4 * density, paint)
            ink(palette[(now / 500).toInt().mod(5)], .95f)
            canvas.drawRoundRect(calloutX - panelWidth * .28f, railY, calloutX - panelWidth * .28f + panelWidth * .56f * energy, railY + 7 * density, 4 * density, 4 * density, paint)
        }
        canvas.restoreToCount(saved)
    }
    private fun label(canvas: Canvas, text: String, x: Float, y: Float, size: Float, color: Int) {
        paint.textSize = size; paint.typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD_ITALIC); paint.textAlign = Paint.Align.CENTER
        ink(0xFF120D24.toInt(), .95f, size * .18f); canvas.drawText(text, x, y, paint)
        ink(color); canvas.drawText(text, x, y, paint)
    }
}
