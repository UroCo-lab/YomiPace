package jp.cn22.yomipace

import android.app.Application
import android.net.Uri
import android.os.Looper
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
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
@Config(sdk = [35])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ArchiveSlideEditingTest {
    private val app get() = ApplicationProvider.getApplicationContext<Application>()
    private val store get() = DeckStore(File(app.filesDir, "decks"))
    private val script = "time(07:00)\n\n##1\n一枚目の本文。\n**メモ1\n\n##2\n[図の説明(00:10)]\n二枚目の本文。\n\n##3\n"
    @Before fun clean() {
        app.getSharedPreferences("yomipace", 0).edit().clear().putBoolean(WelcomeSample.SETUP_COMPLETE, true).commit()
        File(app.filesDir, "decks").deleteRecursively()
    }
    private fun waitFor(check: () -> Boolean) {
        val until = System.nanoTime() + 15_000_000_000L
        while (!check() && System.nanoTime() < until) { shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(50)); Thread.sleep(10) }
        assertTrue("Async operation timed out", check())
    }
    private fun model() = AppViewModel(app).also { waitFor { !it.busy } }
    private fun load(vm: AppViewModel): String {
        val zip = File(app.cacheDir, "archive-page-edit.zip")
        javaClass.getResourceAsStream("/sample.zip")!!.use { input -> zip.outputStream().use { input.copyTo(it) } }
        vm.importZip(Uri.fromFile(zip)); waitFor { !vm.busy }; assertNotNull(vm.deck)
        val id = vm.deck!!.directory.name
        store.write(store.read(id).copy(scriptOverride = script))
        vm.openArchived(id); waitFor { !vm.busy }; return id
    }
    private fun edit(vm: AppViewModel, id: String): ScriptCreationModel {
        vm.beginArchivedEdit(id); waitFor { !vm.busy }
        assertNull(vm.message); assertTrue(vm.creator.visible); assertEquals(CreationSection.HUB, vm.creator.section)
        return vm.creator
    }
    private fun files(id: String) = store.directory(id).listFiles()!!.filter { it.name != "entry.json" }.associate { it.name to it.readBytes() }
    private fun assertFiles(before: Map<String, ByteArray>, id: String) { before.forEach { (name, bytes) -> assertArrayEquals(name, bytes, File(store.directory(id), name).readBytes()) } }

    @Test fun titleOnlyEditPreservesExactLegacySourceAndOriginalFiles() {
        val vm = model(); val id = load(vm)
        val legacy = "\uFEFFtime(07:00)\r\n#1\r\n既存の本文。\r\n**古いメモ\r\n#2\r\n[図(00:10)]\r\n#3\r\n最後の本文。\r\n"
        store.write(store.read(id).copy(scriptOverride = legacy)); vm.openArchived(id); waitFor { !vm.busy }
        vm.seekTo(80.0); val before = files(id)
        val c = edit(vm, id); assertFalse(c.dirty); assertEquals(legacy, c.source)
        c.show(CreationSection.TITLE); c.rename("新しいタイトル"); c.save(); waitFor { !vm.busy }
        assertNull(c.error); assertEquals(legacy, store.load(id).source); assertEquals("新しいタイトル", vm.deck!!.name)
        assertEquals(80.0, vm.elapsed, 0.0); assertFalse(vm.running); assertFiles(before, id)
    }
    @Test fun slideReorderMovesTextNotesFixedIntervalsAndTheActiveAnchorTogether() {
        val vm = model(); val id = load(vm); val before = files(id)
        vm.seekTo(80.0); vm.updateAnchor(ReadingAnchor("h2"), 2)
        val c = edit(vm, id)
        c.show(CreationSection.SCRIPT); c.edit(TextFieldValue(c.pages[0].text, TextRange(2)))
        c.show(CreationSection.ORDER); c.moveSlide(2, -1); c.moveSlide(1, -1)
        assertEquals(listOf(3, 1, 2), c.imageOrder); assertEquals(TextRange(2), c.pages[1].selection)
        assertTrue(c.pages[0].text.isEmpty()); assertTrue(c.pages[1].text.contains("メモ1")); assertTrue(c.pages[2].text.contains("二枚目"))
        c.save(); waitFor { !vm.busy }; assertNull(c.error)
        val saved = store.load(id)
        assertEquals(listOf("003.png", "001.png", "002.png"), saved.deck.images.map { it.name })
        assertEquals(listOf(1, 2, 3), saved.deck.script.slides)
        assertEquals(3, saved.deck.script.items.filterIsInstance<ScriptItem.Fixed>().single().slide)
        assertEquals(2, saved.deck.script.items.filterIsInstance<ScriptItem.Comment>().single().slide)
        assertEquals(3, vm.currentSlide); assertEquals("h3", vm.anchor.itemId); assertEquals(80.0, vm.elapsed, 0.0)
        assertFalse(vm.running); assertFiles(before, id)
        c.close(); val restored = model(); assertEquals(saved.deck.images.map { it.name }, restored.deck!!.images.map { it.name })
        assertEquals(3, restored.currentSlide); assertEquals(80.0, restored.elapsed, 0.0)
    }
    @Test fun discardLeavesTitleTimeOrderScriptAndCurrentPresentationUntouched() {
        val vm = model(); val id = load(vm); val json = File(store.directory(id), "entry.json").readBytes(); val before = files(id)
        val active = vm.deck
        val c = edit(vm, id); c.rename("破棄するタイトル"); c.show(CreationSection.TIME)
        c.changeTimeUndecided(false); c.changeTimeMinutes("9"); c.changeTimeSeconds("30"); c.show(CreationSection.HUB)
        c.show(CreationSection.ORDER); c.moveSlide(2, -1); c.show(CreationSection.SCRIPT); c.edit(TextFieldValue("破棄する原稿"))
        assertTrue(c.dirty); c.close()
        assertArrayEquals(json, File(store.directory(id), "entry.json").readBytes()); assertSame(active, vm.deck); assertFiles(before, id)
    }
    @Test fun reopenedOrderCanBeChangedAgainAndIdentityOrderClearsTheMapping() {
        val vm = model(); val id = load(vm)
        var c = edit(vm, id); c.moveSlide(2, -1); c.moveSlide(1, -1); c.save(closeAfter = true); waitFor { !vm.busy }
        c = edit(vm, id); assertEquals(listOf(3, 1, 2), c.imageOrder)
        c.moveSlide(0, 1); c.moveSlide(1, 1); c.save(closeAfter = true); waitFor { !vm.busy }
        val saved = store.load(id)
        assertNull(saved.entry.imageOrder); assertEquals(listOf("001.png", "002.png", "003.png"), saved.deck.images.map { it.name })
        assertEquals("一枚目の本文。", saved.deck.script.items.filterIsInstance<ScriptItem.Sentence>().first().text)
    }
    @Test fun editingAnotherArchiveDoesNotReplaceTheCurrentPresentationOrClock() {
        val vm = model(); val first = load(vm); val second = load(vm)
        vm.seekTo(90.0); val active = vm.deck
        val c = edit(vm, first); c.rename("別の資料"); c.show(CreationSection.TIME)
        c.changeTimeUndecided(false); c.changeTimeMinutes("5"); c.changeTimeSeconds("30"); c.show(CreationSection.ORDER)
        c.moveSlide(1, -1); c.save(closeAfter = true); waitFor { !vm.busy }
        assertSame(active, vm.deck); assertEquals(second, vm.deck!!.directory.name); assertEquals(90.0, vm.elapsed, 0.0)
        vm.openArchived(first); waitFor { !vm.busy }
        assertEquals(330.0, vm.totalSeconds!!, 0.0); assertEquals("別の資料", vm.deck!!.name)
        assertEquals("002.png", vm.deck!!.images.first().name)
    }
    @Test fun invalidDraftCannotCommitOnlyTheReorderedImagesAndCanBeCorrected() {
        val vm = model(); val id = load(vm); val before = File(store.directory(id), "entry.json").readBytes()
        val c = edit(vm, id); c.moveSlide(1, -1); c.show(CreationSection.SCRIPT); c.selectPage(0)
        c.edit(TextFieldValue("[図(00:99)]")); c.save(); waitFor { !vm.busy }
        assertNotNull(c.error); assertArrayEquals(before, File(store.directory(id), "entry.json").readBytes())
        c.edit(TextFieldValue("修正した本文。")); c.save(); waitFor { !vm.busy }
        assertNull(c.error); assertEquals(listOf(2, 1, 3), store.read(id).imageOrder)
    }
    @Test fun badImageOrderDoesNotOpenAnEditorOrReplaceTheActiveDeck() {
        val vm = model(); val first = load(vm); val second = load(vm); val active = vm.deck
        store.write(store.read(first).copy(imageOrder = listOf(1, 1, 3)))
        vm.beginArchivedEdit(first); waitFor { !vm.busy }
        assertFalse(vm.creator.visible); assertNotNull(vm.message); assertSame(active, vm.deck); assertEquals(second, vm.deck!!.directory.name)
    }
}
