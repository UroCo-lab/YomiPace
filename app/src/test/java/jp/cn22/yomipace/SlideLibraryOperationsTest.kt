package jp.cn22.yomipace

import android.app.Application
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Color
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
import java.util.zip.ZipFile

@RunWith(AndroidJUnit4::class)
@Config(sdk = [35], shadows = [HostFileProviderPaths::class])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class SlideLibraryOperationsTest {
    private val app get() = ApplicationProvider.getApplicationContext<Application>()
    private val store get() = DeckStore(File(app.filesDir, "decks"))
    @Before fun clean() {
        // AndroidX caches paths per process; Robolectric creates a new private cache directory per test.
        org.robolectric.util.ReflectionHelpers.getStaticField<MutableMap<*, *>>(androidx.core.content.FileProvider::class.java, "sCache").clear()
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
        val zip = File(app.cacheDir, "library-operations.zip")
        javaClass.getResourceAsStream("/sample.zip")!!.use { input -> zip.outputStream().use { input.copyTo(it) } }
        vm.importZip(Uri.fromFile(zip)); waitFor { !vm.busy }; val id = vm.deck!!.directory.name
        store.write(store.read(id).copy(scriptOverride = "time(07:00)\n##1\n一枚目。\n**メモ1\n##2\n[図(00:10)]\n二枚目。\n##3\n三枚目。\n"))
        vm.openArchived(id); waitFor { !vm.busy }; return id
    }
    private fun edit(vm: AppViewModel, id: String): ScriptCreationModel {
        vm.beginArchivedEdit(id); waitFor { !vm.busy }; assertNull(vm.message); assertTrue(vm.creator.visible); return vm.creator
    }
    private fun image(): Uri {
        val file = File(app.cacheDir, "new-slide.png")
        val bitmap = Bitmap.createBitmap(320, 180, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.MAGENTA) }
        file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }; bitmap.recycle(); return Uri.fromFile(file)
    }
    private fun originals(id: String) = store.directory(id).listFiles()!!.filter { it.isFile }.associate { it.name to it.readBytes() }
    private fun unchanged(id: String, before: Map<String, ByteArray>, metadata: Boolean = false) {
        before.filterKeys { metadata || it != "entry.json" }.forEach { (name, bytes) -> assertArrayEquals(name, bytes, File(store.directory(id), name).readBytes()) }
    }

    @Test fun addRemoveAndReorderCommitTogetherAndKeepActiveSlideAndOriginals() {
        val vm = model(); val id = load(vm); val before = originals(id); val uri = image(); val media = File(uri.path!!).readBytes()
        vm.seekTo(88.0); vm.updateAnchor(ReadingAnchor("h2"), 2)
        val c = edit(vm, id); c.addSlides(listOf(uri)); waitFor { !vm.busy }; assertEquals(4, c.pages.size)
        c.show(CreationSection.SCRIPT); c.selectPage(3); c.edit(TextFieldValue("追加した原稿。\n**追加メモ", TextRange(3)))
        c.moveSlide(3, -1); c.moveSlide(2, -1); c.removeSlide(0)
        assertTrue(c.pages[0].text.contains("追加した")); c.save(closeAfter = true); waitFor { !vm.busy }; assertNull(c.error)
        val saved = store.load(id); assertNotNull(saved.entry.imageRevision); assertEquals(3, saved.entry.imageCount)
        assertEquals((1..3).toList(), saved.deck.script.slides); assertTrue(saved.source.contains("追加した原稿")); assertFalse(saved.source.contains("一枚目"))
        assertEquals(2, saved.deck.script.items.filterIsInstance<ScriptItem.Fixed>().single().slide)
        assertEquals(2, vm.currentSlide); assertEquals(88.0, vm.elapsed, 0.0); assertFalse(vm.running)
        unchanged(id, before); assertArrayEquals(media, File(uri.path!!).readBytes())
        val restored = model(); assertEquals(saved.source, restored.deck!!.script.let { store.load(id).source }); assertEquals(2, restored.currentSlide)
        val oldRevision = saved.deck.images.associate { it to it.readBytes() }
        val again = edit(restored, id); again.removeSlide(1); again.save(closeAfter = true); waitFor { !restored.busy }
        assertEquals(2, store.load(id).deck.images.size); oldRevision.forEach { (file, bytes) -> assertArrayEquals(bytes, file.readBytes()) }
    }
    @Test fun discardingAddedAndDeletedSlidesLeavesSavedDeckByteForByte() {
        val vm = model(); val id = load(vm); val before = originals(id)
        val c = edit(vm, id); c.addSlides(listOf(image())); waitFor { !vm.busy }; val draftImage = c.slideImages.last()
        c.removeSlide(0); assertTrue(c.dirty); c.close(); unchanged(id, before, metadata = true)
        assertFalse(draftImage.exists()); assertEquals(3, store.load(id).deck.images.size)
    }
    @Test fun invalidTextAndMissingAddedImageCannotPartiallyCommitSlideChanges() {
        val vm = model(); val id = load(vm); val before = originals(id)
        val c = edit(vm, id); c.addSlides(listOf(image())); waitFor { !vm.busy }; c.show(CreationSection.SCRIPT); c.selectPage(3)
        c.edit(TextFieldValue("[図(00:99)]")); c.save(); waitFor { !vm.busy }; assertNotNull(c.error); unchanged(id, before, true)
        c.edit(TextFieldValue("正常な本文。")); assertTrue(c.slideImages.last().delete())
        c.save(); waitFor { !vm.busy }; assertNotNull(c.error); unchanged(id, before, true); assertEquals(3, store.load(id).deck.images.size)
        c.close()
    }
    @Test fun lastSlideIsKeptAndDeletingActiveSlideChoosesValidNeighbour() {
        val vm = model(); val id = load(vm); vm.seekTo(50.0); vm.updateAnchor(ReadingAnchor("h3"), 3)
        val c = edit(vm, id); c.removeSlide(2); c.removeSlide(1); c.removeSlide(0); assertEquals(1, c.pages.size)
        c.save(closeAfter = true); waitFor { !vm.busy }; assertNull(c.error); assertEquals(1, vm.currentSlide); assertEquals("h1", vm.anchor.itemId)
        assertEquals(50.0, vm.elapsed, 0.0); assertEquals(1, store.load(id).entry.imageCount)
    }
    @Test fun portableZipUsesEditedSlidesTextAndCurrentTimeAndRoundTrips() {
        val vm = model(); val id = load(vm); val before = originals(id)
        val c = edit(vm, id); c.removeSlide(0); c.moveSlide(1, -1); c.save(closeAfter = true); waitFor { !vm.busy }
        store.write(store.read(id).copy(totalSeconds = 480.0)) // Time metadata can differ from an older script header.
        val loaded = store.load(id); val zip = DeckTransfer.export(app, loaded)
        ZipFile(zip).use { z -> assertEquals(setOf("001.png", "002.png", "script.txt"), z.entries().toList().map { it.name }.toSet()) }
        val imported = zip.inputStream().use { ZipImporter.read(it, File(app.cacheDir, "roundtrip-${System.nanoTime()}"), "共有した資料") }
        assertEquals(2, imported.images.size); assertEquals(480.0, imported.script.declaredSeconds!!, 0.0)
        assertEquals(listOf("三枚目。", "二枚目。"), imported.script.items.filterIsInstance<ScriptItem.Sentence>().map { it.text })
        assertEquals(2, imported.script.items.filterIsInstance<ScriptItem.Fixed>().single().slide); unchanged(id, before)
    }
    @Test fun duplicateIsIndependentRetainsSavedEditsAndNeverSwitchesActivePresentation() {
        val vm = model(); val id = load(vm); val c = edit(vm, id); c.removeSlide(0); c.save(closeAfter = true); waitFor { !vm.busy }
        val before = originals(id); val source = store.load(id); val active = vm.deck; vm.seekTo(66.0)
        vm.duplicateArchived(id); assertTrue(vm.libraryBusy); assertEquals("複製を作成中…", vm.libraryWorkLabel)
        waitFor { !vm.busy }; assertNull(vm.libraryWorkLabel); assertNull(vm.message); assertSame(active, vm.deck); assertEquals(66.0, vm.elapsed, 0.0)
        val copyId = vm.archives.single { it.id != id }.id; val copy = store.load(copyId)
        assertEquals(source.source, copy.source); assertEquals(source.entry.totalSeconds, copy.entry.totalSeconds)
        assertEquals(source.entry.imageCount, copy.entry.imageCount); assertTrue(copy.entry.name.endsWith("（コピー）"))
        assertTrue(copy.deck.images.none { it in source.deck.images })
        val editor = edit(vm, copyId); editor.removeSlide(0); editor.save(closeAfter = true); waitFor { !vm.busy }
        unchanged(id, before, true); assertEquals(2, store.load(id).entry.imageCount); assertEquals(1, store.load(copyId).entry.imageCount)
        vm.duplicateArchived(id); waitFor { !vm.busy }; assertTrue(vm.archives.any { it.name.endsWith("（コピー2）") })
    }
    @Test fun shareIntentGrantsReadAccessToOnlyThePortableZipAndTrashCannotBeShared() {
        val vm = model(); val id = load(vm); var zip: File? = null
        vm.shareArchived(id) { assertTrue(vm.libraryBusy); zip = it }
        assertTrue(vm.libraryBusy); assertEquals("共有用ZIPを作成中…", vm.libraryWorkLabel)
        waitFor { !vm.busy }; assertNull(vm.libraryWorkLabel); assertNotNull(zip)
        val send = DeckTransfer.shareIntent(app, zip!!); assertEquals(Intent.ACTION_SEND, send.action); assertEquals("application/zip", send.type)
        val uri = send.getParcelableExtra<Uri>(Intent.EXTRA_STREAM)!!
        assertEquals("content", uri.scheme); assertEquals("${app.packageName}.files", uri.authority)
        assertEquals(uri, send.clipData!!.getItemAt(0).uri); assertTrue(send.flags and Intent.FLAG_GRANT_READ_URI_PERMISSION != 0)
        assertEquals(0, send.flags and Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
        app.contentResolver.openInputStream(uri)!!.use { assertArrayEquals(zip!!.readBytes(), it.readBytes()) }
        try {
            DeckTransfer.shareIntent(app, File(app.filesDir, "private-not-shared.zip"))
            fail("A private file outside shared-zips must not get a URI")
        } catch (_: IllegalArgumentException) { }
        vm.moveToTrash(id); waitFor { !vm.busy }; var shared = false
        vm.shareArchived(id) { shared = true }; waitFor { !vm.busy }; assertFalse(shared); assertNotNull(vm.message)
        assertNull(vm.libraryWorkLabel)
    }
    @Test fun failedTransferClearsWorkStateAndAllowsRetryWithoutStartingDuplicateWork() {
        val vm = model(); val id = load(vm); val count = vm.archives.size
        vm.duplicateArchived("invalid-id")
        assertTrue(vm.libraryBusy); assertEquals("複製を作成中…", vm.libraryWorkLabel)
        var called = false
        vm.shareArchived(id) { called = true } // A second tap cannot change the active operation.
        assertEquals("複製を作成中…", vm.libraryWorkLabel)
        waitFor { !vm.busy }; assertFalse(called); assertNotNull(vm.message); assertNull(vm.libraryWorkLabel)
        assertEquals(count, vm.archives.size)
        vm.message = null
        vm.shareArchived(id) { throw IllegalStateException("共有先を開けません。") }
        assertEquals("共有用ZIPを作成中…", vm.libraryWorkLabel)
        waitFor { !vm.busy }; assertEquals("共有先を開けません。", vm.message); assertNull(vm.libraryWorkLabel)
        vm.message = null
        vm.duplicateArchived(id); waitFor { !vm.busy }
        assertNull(vm.message); assertNull(vm.libraryWorkLabel); assertEquals(count + 1, vm.archives.size)
    }
}
