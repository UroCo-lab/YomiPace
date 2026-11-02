package jp.cn22.yomipace

import android.app.Application
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.RectF
import android.os.Looper
import android.os.VibratorManager
import android.view.View
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import jp.cn22.yomipace.core.*
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.util.ReflectionHelpers
import java.io.File
import java.time.Duration

@RunWith(AndroidJUnit4::class)
@Config(sdk = [35], qualifiers = "w360dp-h800dp-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class VibrationAndRailTest {
    private val app get() = ApplicationProvider.getApplicationContext<Application>()
    @Before fun clean() {
        app.getSharedPreferences("yomipace", 0).edit().clear().putBoolean(WelcomeSample.SETUP_COMPLETE, true).commit()
        File(app.filesDir, "decks").deleteRecursively()
    }
    private fun model(): AppViewModel {
        val vm = AppViewModel(app)
        val deadline = System.nanoTime() + 15_000_000_000L
        while (vm.busy && System.nanoTime() < deadline) {
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(50)); Thread.sleep(10)
        }
        assertFalse(vm.busy); return vm
    }
    @Test fun unsetVibrationDefaultsToEnabledMediumAndProducesMediumPulse() {
        val vm = model()
        assertTrue(vm.vibrationEnabled); assertEquals(1, vm.vibrationStrength)
        val vibrator = app.getSystemService(VibratorManager::class.java).defaultVibrator
        val output = shadowOf(vibrator)
        output.setHasVibrator(true); output.setHasAmplitudeControl(true)
        ParagraphHaptics(app).pulse(vm.vibrationEnabled, vm.vibrationStrength)
        assertEquals(24L, output.milliseconds)
    }
    @Test fun explicitOffAndSelectedStrengthSurviveUpdateAndRelaunch() {
        // A saved OFF is a user choice, even when the unsaved default changes to ON.
        app.getSharedPreferences("yomipace", 0).edit().putBoolean("vibrationEnabled", false).putInt("vibrationStrength", 0).commit()
        val vm = model()
        assertFalse(vm.vibrationEnabled); assertEquals(0, vm.vibrationStrength)
        vm.setVibration(true, 2)
        val restored = model()
        assertTrue(restored.vibrationEnabled); assertEquals(2, restored.vibrationStrength)
        restored.setVibration(false, 1)
        val off = model()
        assertFalse(off.vibrationEnabled); assertEquals(1, off.vibrationStrength)
    }
    @Test fun yellowDotsAndTheirOutlinesStayInsideTrackOnBothSidesAcrossReflow() {
        val source = (1..10).joinToString("\n") { "#$it\n" + "読み上げ原稿の表示位置を確認します。".repeat(15) }
        val script = ScriptParser.parse(source)
        val timeline = Timeline.build(script, 300.0, 2.0)
        val yellow = Color.rgb(255, 211, 55)
        val outline = Color.rgb(123, 89, 0)
        for (size in listOf(18, 32)) for (width in listOf(320, 700))
            for (dark in listOf(false, true)) for (side in ReaderScrollbarPosition.entries) {
                val reader = ScriptReaderView(app)
                reader.bind(script, timeline, size, 0.0, ReadingAnchor(), dark, 0.0, side)
                reader.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(500, View.MeasureSpec.EXACTLY))
                reader.layout(0, 0, width, 500)
                val rail = ReflectionHelpers.callInstanceMethod<RectF>(reader, "scrollbarThumb")
                val bitmap = Bitmap.createBitmap(width, 500, Bitmap.Config.ARGB_8888)
                reader.draw(Canvas(bitmap))
                val pixels = IntArray(width * 500)
                bitmap.getPixels(pixels, 0, width, 0, 0, width, 500)
                var dots = 0
                pixels.forEachIndexed { index, color ->
                    if (color == yellow || color == outline) {
                        dots++
                        val x = index % width; val y = index / width
                        assertTrue("Point escaped $side track at ($x,$y)", x >= rail.left && x < rail.right && y >= 6 && y < 494)
                    }
                }
                assertEquals(side != ReaderScrollbarPosition.NONE, dots > 0)
                if (side != ReaderScrollbarPosition.NONE) {
                    val points = ReflectionHelpers.callInstanceMethod<List<Float>>(reader, "slideMarkerPositions")
                    points.forEach { y -> assertEquals("Dot must remain visible over the blue thumb", yellow, bitmap.getPixel(rail.centerX().toInt(), y.toInt())) }
                }
                if (size == 18 && width == 320 && side != ReaderScrollbarPosition.NONE) {
                    val output = File("build/test-screenshots").apply { mkdirs() }
                    File(output, "reader-rail-${side.name.lowercase()}-${if (dark) "dark" else "light"}.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
                }
                bitmap.recycle()
            }
    }
}
