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
    @Test fun effectsGrowWithTimeAndARewindRestoresTheQuietRendering() {
        val image = Bitmap.createBitmap(360, 800, Bitmap.Config.ARGB_8888)
        val engine = DopamineEngine({}, {})
        val renderer = DopamineRenderer()
        fun pixels(seconds: Double): IntArray {
            image.eraseColor(Color.rgb(12,10,24))
            engine.configure(true,false,true,true,DopamineLevel.at(seconds,100.0))
            renderer.draw(Canvas(image),360f,800f,1f,10_000,engine,
                listOf(RectF(10f,205f,350f,315f),RectF(0f,423f,360f,800f)))
            return IntArray(360*800).also { image.getPixels(it,0,360,0,0,360,800) }
        }
        fun bright(p: IntArray) = p.count { maxOf(Color.red(it),Color.green(it),Color.blue(it)) > 50 }
        val start=pixels(0.0); val middle=pixels(60.0); val end=pixels(99.0)
        assertTrue(bright(start)<bright(middle)); assertTrue(bright(middle)<bright(end))
        assertArrayEquals(start,pixels(0.0)); image.recycle()
    }
    @Test fun slideDecorationHasBoundedContrastAndNeverOverpaintsTheProgressPanel() {
        val bitmap=Bitmap.createBitmap(360,800,Bitmap.Config.ARGB_8888)
        val base=Color.rgb(120,130,140); bitmap.eraseColor(base)
        val engine=DopamineEngine({},{}).apply { configure(true,true,true,true,DopamineLevel.at(99.0,100.0)); paragraph(1000) }
        val progress=RectF(10f,205f,350f,315f)
        DopamineRenderer().draw(Canvas(bitmap),360f,800f,1f,1100,engine,listOf(progress),listOf(RectF(5f,30f,355f,220f)))
        for(y in 32..200) for(x in 7..352) {
            val pixel=bitmap.getPixel(x,y)
            for(channel in listOf<(Int)->Int>(Color::red,Color::green,Color::blue))
                assertTrue("Slide obscured at $x,$y",kotlin.math.abs(channel(pixel)-channel(base))<=32)
        }
        for(y in 206..313) for(x in 11..348) assertEquals(base,bitmap.getPixel(x,y))
        bitmap.recycle()
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
