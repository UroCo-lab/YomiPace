package jp.cn22.yomipace

import android.graphics.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class DopamineRenderingTest {
    @Test fun openingIsSubstantiallyBrighterThanTheFrozen050Reference() {
        val before = BitmapFactory.decodeStream(javaClass.getResourceAsStream("/dopamine-050-start.png"))!!
        val after = Bitmap.createBitmap(360, 800, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.rgb(12,10,24)) }
        val engine = DopamineEngine({}, {})
        engine.configure(true, false, true, true, DopamineLevel.at(0.0, 420.0))
        DopamineRenderer().draw(Canvas(after), 360f,800f,1f,10_000,engine,
            listOf(RectF(10f,205f,350f,275f),RectF(0f,423f,360f,800f)))
        fun bright(bitmap: Bitmap): Int {
            val p = IntArray(bitmap.width*bitmap.height); bitmap.getPixels(p,0,bitmap.width,0,0,bitmap.width,bitmap.height)
            return p.count { maxOf(Color.red(it),Color.green(it),Color.blue(it)) > 150 }
        }
        val old = bright(before); val new = bright(after)
        println("Opening bright pixels: 0.5.0=$old, 0.5.1=$new")
        assertTrue("Opening did not become visibly stronger: $old -> $new", new > old * 2)
        before.recycle(); after.recycle()
    }
    @Test fun allEffectsLeaveClockAndReaderPixelsUntouchedIncludingOverlaps() {
        for ((w, h) in listOf(360 to 800, 720 to 840, 840 to 360)) {
            val windows = listOf(RectF(10f, h * .30f, w - 10f, h * .45f), RectF(0f, h * .55f, w.toFloat(), h.toFloat()),
                RectF(25f, h * .38f, w - 25f, h * .6f))
            val engine = DopamineEngine({}, {})
            engine.configure(true, true, true, true, DopamineLevel.at(99.0, 100.0))
            engine.touch(1000, .5f, .7f); engine.paragraph(1200)
            val bitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
            val base = Color.rgb(18, 25, 38); bitmap.eraseColor(base)
            DopamineRenderer().draw(Canvas(bitmap), w.toFloat(), h.toFloat(), 1f, 1400, engine, windows)
            var changes = 0
            for (y in 0 until h) for (x in 0 until w) {
                if (windows.any { it.contains(x + .5f, y + .5f) } && windows.any { it.contains(x - 1f, y - 1f) && it.contains(x + 1f, y + 1f) })
                    assertEquals("Overlay painted protected pixel at $x,$y ($w x $h)", base, bitmap.getPixel(x, y))
                if (bitmap.getPixel(x, y) != base) changes++
            }
            assertTrue(changes > 1000); bitmap.recycle()
        }
    }
    @Test fun noPaintWhenModeOffOrBackgroundAndNoAmbientPaintWhilePaused() {
        val engine = DopamineEngine({}, {})
        val bitmap = Bitmap.createBitmap(360, 800, Bitmap.Config.ARGB_8888)
        for ((enabled, available, running) in listOf(Triple(false, true, true), Triple(true, false, true), Triple(true, true, false))) {
            engine.configure(enabled, true, available, running, DopamineLevel.at(99.0, 100.0))
            bitmap.eraseColor(Color.TRANSPARENT)
            DopamineRenderer().draw(Canvas(bitmap), 360f, 800f, 1f, 1000, engine, emptyList())
            val pixels = IntArray(360 * 800); bitmap.getPixels(pixels, 0, 360, 0, 0, 360, 800)
            assertTrue(pixels.all { it == Color.TRANSPARENT })
        }
        bitmap.recycle()
    }
}
