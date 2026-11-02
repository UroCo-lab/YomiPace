package jp.cn22.yomipace

import android.app.Application
import android.graphics.Bitmap
import android.graphics.Canvas
import android.net.Uri
import android.view.MotionEvent
import android.view.View
import androidx.lifecycle.ViewModelProvider
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import android.os.Looper
import java.io.File
import java.time.Duration
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import jp.cn22.yomipace.core.*

@RunWith(AndroidJUnit4::class)
@Config(sdk = [35], qualifiers = "w360dp-h800dp-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class AndroidBehaviorTest {
    private val app get() = ApplicationProvider.getApplicationContext<Application>()
    @Before fun cleanPreferences() { app.getSharedPreferences("yomipace", 0).edit().clear().commit() }
    private fun pumpUntil(check: () -> Boolean) {
        val deadline = System.nanoTime() + 15_000_000_000L
        while (!check() && System.nanoTime() < deadline) { shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(50)); Thread.sleep(20) }
        assertTrue("Async operation did not complete", check())
    }
    private fun zip(name: String, script: String): File {
        val f = File(app.cacheDir, name)
        val png = Bitmap.createBitmap(64, 36, Bitmap.Config.ARGB_8888)
        png.eraseColor(android.graphics.Color.WHITE)
        ZipOutputStream(f.outputStream()).use { z ->
            z.putNextEntry(ZipEntry("001.png")); png.compress(Bitmap.CompressFormat.PNG, 100, z); z.closeEntry()
            z.putNextEntry(ZipEntry("script.txt")); z.write(script.toByteArray()); z.closeEntry()
        }
        png.recycle(); return f
    }
    @Test fun importManualOverrideResetClearAndFailedReplacement() {
        val vm = AppViewModel(app)
        pumpUntil { !vm.importing }
        assertNotNull(vm.startProblem)
        vm.importZip(Uri.fromFile(zip("good.zip", "time(01:00)\n#1\n本文です。\n[図(00:10)]\n終わります。")))
        pumpUntil { !vm.importing }
        assertEquals(60.0, vm.totalSeconds!!, 0.0)
        assertNull(vm.startProblem)
        val original = vm.deck
        vm.importZip(Uri.fromFile(zip("bad.zip", "#1\n[図(00:99)]")))
        pumpUntil { !vm.importing }
        assertSame(original, vm.deck)
        assertTrue(vm.message!!.contains("2行目"))
        vm.message = null
        assertNull(vm.setTotal(90.0)); vm.play(); vm.play()
        assertTrue(vm.running)
        assertNotNull(vm.setSwitch(0.5))
        vm.pause(); vm.pause(); assertNull(vm.setSwitch(0.5))
        val position = ReadingAnchor("s3-0", 2)
        vm.updateAnchor(position, 1); vm.setFont(24); vm.swap()
        vm.resetTime()
        assertEquals(position, vm.anchor)
        assertEquals(90.0, vm.totalSeconds!!, 0.0)
        assertNotNull(vm.deck)
        vm.persist()
        val restored = AppViewModel(app)
        pumpUntil { !restored.importing }
        assertNotNull(restored.deck)
        assertEquals(24, restored.fontSize); assertTrue(restored.swapped)
        assertEquals(position, restored.anchor)
        vm.clear()
        assertNull(vm.deck); assertNull(vm.totalSeconds)
        assertEquals(24, vm.fontSize); assertTrue(vm.swapped); assertEquals(0.5, vm.switchSeconds, 0.0)
        vm.importZip(Uri.fromFile(zip("no-time.zip", "#1\n時間指定なし。")))
        pumpUntil { !vm.importing }
        assertNull(vm.totalSeconds); assertNotNull(vm.startProblem)
    }
    @Test fun readerKeepsCharacterAnchorAcrossFontWidthAndRecreation() {
        val source = "#1\n" + "あいうえおかきくけこさしすせそたちつてとなにぬねの。".repeat(20) + "\n#2\n結論です。"
        val script = ScriptParser.parse(source)
        val timeline = Timeline.build(script, 120.0, 0.5)
        var actual = ReadingAnchor("s2-0", 12)
        val reader = ScriptReaderView(app)
        reader.onAnchorChanged = { anchor, _ -> actual = anchor }
        reader.bind(script, timeline, 18, 12.0, actual, false)
        reader.measure(View.MeasureSpec.makeMeasureSpec(340, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(500, View.MeasureSpec.EXACTLY))
        reader.layout(0, 0, 340, 500)
        assertEquals(ReadingAnchor("s2-0", 12), actual)
        reader.bind(script, timeline, 32, 12.0, actual, false)
        reader.layout(0, 0, 300, 600)
        assertEquals(ReadingAnchor("s2-0", 12), actual)
        val recreated = ScriptReaderView(app)
        recreated.bind(script, timeline, 32, 12.0, actual, false)
        recreated.measure(View.MeasureSpec.makeMeasureSpec(320, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(700, View.MeasureSpec.EXACTLY))
        recreated.layout(0, 0, 320, 700)
        assertEquals(actual, recreated.logicalAnchorForTest())
        val before = timeline.segments.toList()
        reader.onTouchEvent(MotionEvent.obtain(0, 0, MotionEvent.ACTION_DOWN, 100f, 400f, 0))
        reader.onTouchEvent(MotionEvent.obtain(0, 100, MotionEvent.ACTION_MOVE, 100f, 150f, 0))
        reader.onTouchEvent(MotionEvent.obtain(0, 200, MotionEvent.ACTION_UP, 100f, 150f, 0))
        assertEquals(before, timeline.segments)
        assertNotEquals(ReadingAnchor("s2-0", 12), actual)
        val bitmap = Bitmap.createBitmap(300, 600, Bitmap.Config.ARGB_8888)
        reader.draw(Canvas(bitmap))
        assertEquals(android.graphics.Color.rgb(197, 46, 64), bitmap.getPixel(150, 200))
        bitmap.recycle()
    }
    @Test fun readerMustNotPaintOverSurroundingSlidesClockOrControls() {
        val script = ScriptParser.parse("#1\n本文です。\n[図(00:10)]\n続きです。")
        for (dark in listOf(false, true)) {
            val reader = ScriptReaderView(app)
            reader.bind(script, Timeline.build(script, 60.0, 2.0), 18, 0.0, ReadingAnchor(), dark)
            reader.measure(View.MeasureSpec.makeMeasureSpec(300, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(400, View.MeasureSpec.EXACTLY))
            reader.layout(0, 0, 300, 400)
            val bitmap = Bitmap.createBitmap(600, 800, Bitmap.Config.ARGB_8888)
            val surrounding = android.graphics.Color.MAGENTA
            bitmap.eraseColor(surrounding)
            val canvas = Canvas(bitmap)
            canvas.save(); canvas.translate(100f, 200f); reader.draw(canvas); canvas.restore()
            for ((x, y) in listOf(150 to 50, 50 to 300, 450 to 300, 150 to 700)) {
                assertEquals("Reader painted outside its viewport ($x,$y), dark=$dark", surrounding, bitmap.getPixel(x, y))
            }
            assertNotEquals(surrounding, bitmap.getPixel(110, 210))
            bitmap.recycle()
        }
    }
    @Test fun seekPausesAtTouchDownRewindsAllScheduledStateAndResumesFromSavedTime() {
        val vm = AppViewModel(app)
        pumpUntil { !vm.importing }
        vm.importZip(Uri.fromFile(zip("seek.zip", "time(01:00)\n#1\n本文です。\n[図(00:10)]\n終わります。")))
        pumpUntil { !vm.importing }
        val before = ReadingAnchor("b4", 0)
        vm.updateAnchor(before, 1)
        vm.seekTo(60.0)
        assertTrue(vm.timeline!!.segments.all { it.fraction(vm.elapsed) == 1.0 })
        vm.play()
        val seek = TimeSeekView(app, vm)
        seek.measure(View.MeasureSpec.makeMeasureSpec(320, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(36, View.MeasureSpec.EXACTLY))
        seek.layout(0, 0, 320, 36); seek.refresh()
        seek.onTouchEvent(MotionEvent.obtain(0, 0, MotionEvent.ACTION_DOWN, 160f, 18f, 0))
        assertFalse("Touching the seek bar must pause immediately", vm.running)
        vm.seekTo(1.0)
        seek.onTouchEvent(MotionEvent.obtain(0, 100, MotionEvent.ACTION_CANCEL, 160f, 18f, 0))
        assertFalse(vm.running)
        val block = vm.timeline!!.byId.getValue("b4")
        assertEquals(0.0, block.fraction(vm.elapsed), 0.0)
        assertTrue(vm.timeline!!.segments.any { it.fraction(vm.elapsed) < 1.0 })
        assertEquals(before, vm.anchor)
        vm.setTotalFont(64); vm.setElapsedFont(60); vm.setControlFont(30); vm.changeControlHeight(96); vm.changeScrollInertia(0.87)
        val restored = AppViewModel(app)
        pumpUntil { !restored.importing }
        assertFalse(restored.running); assertEquals(1.0, restored.elapsed, .001)
        assertEquals(before, restored.anchor)
        assertEquals(64, restored.totalFontSize); assertEquals(60, restored.elapsedFontSize)
        assertEquals(30, restored.controlFontSize); assertEquals(96, restored.controlHeight); assertEquals(.87, restored.scrollInertia, .00001)
        vm.play()
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(2))
        vm.pause()
        assertEquals("Resume must continue from the sought time", 3.0, vm.elapsed, .1)
        vm.seekTo(Double.NaN); assertEquals(3.0, vm.elapsed, .1)
    }

    @Test fun readerRewindRestoresInkAndFixedBlockFill() {
        val script = ScriptParser.parse("#1\n本文です。\n[図(00:10)]\n終わります。")
        val timeline = Timeline.build(script, 60.0, 2.0)
        val reader = ScriptReaderView(app)
        reader.bind(script, timeline, 18, 60.0, ReadingAnchor(), false)
        reader.measure(View.MeasureSpec.makeMeasureSpec(320, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(500, View.MeasureSpec.EXACTLY))
        reader.layout(0, 0, 320, 500)
        fun mutedPixels(): Int {
            val bitmap = Bitmap.createBitmap(320, 500, Bitmap.Config.ARGB_8888)
            reader.draw(Canvas(bitmap))
            var count = 0
            for (y in 200 until 495) for (x in 26 until 300) if (bitmap.getPixel(x, y) == android.graphics.Color.rgb(131, 141, 151)) count++
            bitmap.recycle(); return count
        }
        val complete = mutedPixels()
        reader.bind(script, timeline, 18, 0.0, ReadingAnchor(), false)
        assertTrue("Rewinding must restore completed text to normal ink", mutedPixels() < complete)
        fun blockPixel(seconds: Double): Int {
            reader.bind(script, timeline, 18, seconds, ReadingAnchor("b3", 0), false)
            val bitmap = Bitmap.createBitmap(320, 500, Bitmap.Config.ARGB_8888)
            reader.draw(Canvas(bitmap)); val color = bitmap.getPixel(280, 183); bitmap.recycle(); return color
        }
        val completedBlock = blockPixel(60.0)
        val emptyBlock = blockPixel(0.0)
        assertNotEquals("Rewinding must clear the fixed block fill", completedBlock, emptyBlock)
        assertEquals(android.graphics.Color.rgb(229, 234, 240), emptyBlock)
    }

    @Test fun slideDotsReflowAndThumbTracksViewport() {
        val script = ScriptParser.parse("#1\n" + "あいうえお。".repeat(50) + "\n#2\n結論です。\n#3\nありがとうございました。")
        val timeline = Timeline.build(script, 180.0, 2.0)
        val reader = ScriptReaderView(app)
        reader.bind(script, timeline, 18, 0.0, ReadingAnchor(), false)
        reader.measure(View.MeasureSpec.makeMeasureSpec(320, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(500, View.MeasureSpec.EXACTLY))
        reader.layout(0, 0, 320, 500)
        fun dots() = org.robolectric.util.ReflectionHelpers.callInstanceMethod<List<Float>>(reader, "slideMarkerPositions")
        val initial = dots()
        assertEquals(3, initial.size); assertTrue(initial.zipWithNext().all { (a, b) -> b > a })
        val bitmap = Bitmap.createBitmap(320, 500, Bitmap.Config.ARGB_8888)
        reader.draw(Canvas(bitmap))
        initial.forEach { assertEquals(android.graphics.Color.rgb(255, 211, 55), bitmap.getPixel(16, it.toInt())) }
        bitmap.recycle()
        reader.bind(script, timeline, 32, 0.0, ReadingAnchor("h2"), false)
        reader.layout(0, 0, 280, 500)
        assertNotEquals(initial, dots())
        assertEquals(ReadingAnchor("h2"), reader.logicalAnchorForTest())
        val thumb = org.robolectric.util.ReflectionHelpers.callInstanceMethod<android.graphics.RectF>(reader, "scrollbarThumb")
        assertTrue("Thumb must move down with the displayed manuscript", thumb.top > 6f)
    }

    @Test fun inertiaChangesReleaseTravelWithoutChangingDirectDrag() {
        val script = ScriptParser.parse("#1\n" + "長い原稿を読みます。".repeat(500))
        val timeline = Timeline.build(script, 600.0, 0.0)
        val positions = mutableListOf<ReadingAnchor>()
        val travel = listOf(0.0, 0.1, 0.5, 1.0).map { inertia ->
            val reader = ScriptReaderView(app)
            reader.bind(script, timeline, 18, 0.0, ReadingAnchor(), false, inertia)
            reader.measure(View.MeasureSpec.makeMeasureSpec(320, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(500, View.MeasureSpec.EXACTLY))
            reader.layout(0, 0, 320, 500)
            val start = android.os.SystemClock.uptimeMillis()
            reader.onTouchEvent(MotionEvent.obtain(start, start, MotionEvent.ACTION_DOWN, 100f, 460f, 0))
            for (step in 1..5) reader.onTouchEvent(MotionEvent.obtain(start, start + step * 16L, MotionEvent.ACTION_MOVE, 100f, 460f - step * 60, 0))
            positions += reader.logicalAnchorForTest()
            reader.onTouchEvent(MotionEvent.obtain(start, start + 96, MotionEvent.ACTION_UP, 100f, 100f, 0))
            val scroller = org.robolectric.util.ReflectionHelpers.getField<android.widget.OverScroller>(reader, "scroller")
            if (inertia == 0.0) assertTrue("Zero inertia must stop immediately", scroller.isFinished)
            scroller.finalY
        }
        assertEquals(1, positions.toSet().size)
        assertTrue("Inertia must continuously increase release travel: $travel", travel.zipWithNext().all { (a, b) -> b > a })
    }

    @Test fun grabbingScrollbarThumbMustNotJumpTheReadingPosition() {
        val script = ScriptParser.parse("#1\n" + "最初の原稿です。".repeat(30) + "\n#2\n" + "次の原稿です。".repeat(30))
        val reader = ScriptReaderView(app)
        val before = ReadingAnchor("h2", 0)
        reader.bind(script, Timeline.build(script, 180.0, 2.0), 18, 0.0, before, false)
        reader.measure(View.MeasureSpec.makeMeasureSpec(320, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(500, View.MeasureSpec.EXACTLY))
        reader.layout(0, 0, 320, 500)
        val thumb = org.robolectric.util.ReflectionHelpers.callInstanceMethod<android.graphics.RectF>(reader, "scrollbarThumb")
        val start = android.os.SystemClock.uptimeMillis()
        reader.onTouchEvent(MotionEvent.obtain(start, start, MotionEvent.ACTION_DOWN, thumb.centerX(), thumb.top + 2f, 0))
        assertEquals("Grabbing an existing thumb must keep the reading position", before, reader.logicalAnchorForTest())
        reader.onTouchEvent(MotionEvent.obtain(start, start + 100, MotionEvent.ACTION_UP, thumb.centerX(), thumb.top + 2f, 0))
        assertEquals(before, reader.logicalAnchorForTest())
    }
}
