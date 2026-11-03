package jp.cn22.yomipace

import android.app.Application
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.net.Uri
import android.os.Looper
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
import java.io.File
import java.time.Duration

@RunWith(AndroidJUnit4::class)
@Config(sdk = [35], qualifiers = "w360dp-h800dp-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ScriptCommentsAppTest {
    private val app get() = ApplicationProvider.getApplicationContext<Application>()
    @Before fun clean() {
        app.getSharedPreferences("yomipace", 0).edit().clear().putBoolean(WelcomeSample.SETUP_COMPLETE, true).commit()
        File(app.filesDir, "decks").deleteRecursively()
    }
    private fun waitFor(check: () -> Boolean) {
        val until = System.nanoTime() + 15_000_000_000L
        while (!check() && System.nanoTime() < until) { shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(50)); Thread.sleep(10) }
        assertTrue("Async operation timed out", check())
    }

    @Test fun noteEditsPersistWithManualPositionWithoutChangingZipOrTimeBudget() {
        val vm = AppViewModel(app).also { waitFor { !it.busy } }
        val zip = File(app.cacheDir, "comments.zip")
        javaClass.getResourceAsStream("/sample.zip")!!.use { input -> zip.outputStream().use { input.copyTo(it) } }
        vm.importZip(Uri.fromFile(zip)); waitFor { !vm.busy }; assertNotNull(vm.deck)
        val before = vm.deck!!
        val original = File(before.directory, "source.zip").readBytes()
        val scheduled = vm.timeline!!.segments.map { Triple(it.kind, it.start, it.end) }
        val count = vm.timeline!!.characterCount
        vm.seekTo(20.0)
        vm.beginScriptEdit(); waitFor { !vm.busy }
        val source = vm.editorDraft!!
        val comment = before.script.items.filterIsInstance<ScriptItem.Comment>().first()
        vm.updateAnchor(ReadingAnchor(comment.id, 2), 1)
        vm.updateEditorDraft(source.replace("** 質問対策メモ：", "** [図(99:99)] 質問対策メモ："))
        vm.saveScriptEdit(); waitFor { !vm.busy }
        assertNull(vm.editorError); assertNull(vm.editorDraft)
        assertEquals(count, vm.timeline!!.characterCount)
        assertEquals(scheduled, vm.timeline!!.segments.map { Triple(it.kind, it.start, it.end) })
        assertEquals(20.0, vm.elapsed, 0.001)
        val savedComment = vm.deck!!.script.items.filterIsInstance<ScriptItem.Comment>().first()
        vm.updateAnchor(ReadingAnchor(savedComment.id, 2), 1)
        vm.persist()
        val restored = AppViewModel(app).also { waitFor { !it.busy } }
        assertTrue(restored.deck!!.script.items.filterIsInstance<ScriptItem.Comment>().first().text.startsWith("[図(99:99)]"))
        assertEquals(ReadingAnchor(savedComment.id, 2), restored.anchor)
        assertEquals(20.0, restored.elapsed, 0.001)
        assertArrayEquals(original, File(before.directory, "source.zip").readBytes())
    }

    @Test fun readerKeepsNotesVisibleAcrossScheduledCompletionThemesEffectsAndReflow() {
        val script = ScriptParser.parse("##1\n本文を読みます。\n** 質問対策：対象期間は追加資料。\n##2\n結論です。")
        val timeline = Timeline.build(script, 60.0, 2.0)
        val note = script.items.filterIsInstance<ScriptItem.Comment>().single()
        val reader = ScriptReaderView(app)
        var observed = ReadingAnchor()
        var observedSlide = 0
        reader.onAnchorChanged = { anchor, slide -> observed = anchor; observedSlide = slide }
        val anchor = ReadingAnchor(note.id)
        fun bind(seconds: Double, dark: Boolean = false, font: Int = 18) = reader.bind(script, timeline, font, seconds, anchor, dark)
        fun size(width: Int, height: Int) {
            reader.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY))
            reader.layout(0, 0, width, height)
        }
        fun capture(name: String): Bitmap {
            val bitmap = Bitmap.createBitmap(reader.width, reader.height, Bitmap.Config.ARGB_8888)
            reader.draw(Canvas(bitmap))
            val folder = File("build/test-screenshots").apply { mkdirs() }
            File(folder, name + ".png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
            return bitmap
        }
        bind(0.0); size(360, 480)
        val before = capture("comments-light-before")
        bind(60.0)
        val after = capture("comments-light-after")
        var noteInk = 0
        for (y in 164..183) for (x in 28..350) {
            assertEquals("Note colour changed at $x,$y", before.getPixel(x, y), after.getPixel(x, y))
            if (before.getPixel(x, y) == Color.rgb(38, 86, 120)) noteInk++
        }
        assertTrue("The note must actually be rendered", noteInk > 50)
        assertEquals(anchor, observed); assertEquals(1, observedSlide)
        before.recycle(); after.recycle()
        bind(60.0, true); capture("comments-dark").recycle()
        val engine = DopamineEngine({}, {}).apply { configure(true, false, true, true, DopamineLevel.at(60.0, 60.0)) }
        reader.bindDopamine(engine) { 10000L }
        capture("comments-dopagaki").recycle()
        bind(60.0, true, 28); size(480, 360)
        assertEquals(anchor, observed); assertEquals(1, observedSlide)
        capture("comments-large-landscape").recycle()
    }

    @Test fun copiedPromptAndHelpDescribeTheSameSupportedSyntax() {
        assertTrue(ZIP_CREATION_PROMPT.contains("##1、##2"))
        assertTrue(ZIP_CREATION_PROMPT.contains("各行の先頭に半角の**"))
        assertTrue(ZIP_CREATION_PROMPT.contains("time(mm:ss)"))
        assertTrue(ZIP_CREATION_PROMPT.contains("[説明文(mm:ss)]"))
        assertTrue(ZIP_CREATION_PROMPT.contains("メモを創作したりしない"))
        assertTrue(HELP.contains("##1\n"))
        assertTrue(HELP.contains("** 質問対策："))
    }
}
