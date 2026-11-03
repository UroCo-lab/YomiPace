package jp.cn22.yomipace

import android.app.Activity
import android.app.Application
import android.content.ClipData
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.net.Uri
import android.os.Looper
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
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
class ScriptCreationTest {
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
    private fun model() = AppViewModel(app).also { waitFor { !it.busy }; it.beginScriptCreation() }
    private fun image(name: String, color: Int, jpeg: Boolean = false): Uri {
        val file = File(app.cacheDir, name)
        val bitmap = Bitmap.createBitmap(64, 36, Bitmap.Config.ARGB_8888).apply { eraseColor(color) }
        file.outputStream().use { bitmap.compress(if (jpeg) Bitmap.CompressFormat.JPEG else Bitmap.CompressFormat.PNG, 100, it) }; bitmap.recycle()
        return Uri.fromFile(file)
    }
    @Test fun selectionOrderAddMoveRemoveAndPngCopiesKeepOriginalBytes() {
        val vm = model(); val c = vm.creator
        val a = image("c-red.jpg", Color.RED, true); val b = image("c-blue.png", Color.BLUE); val d = image("c-green.png", Color.GREEN)
        val bytes = listOf(a, b, d).associateWith { File(it.path!!).readBytes() }
        c.add(listOf(b, a)); c.add(listOf(d)); c.move(2, -1); c.remove(2)
        assertEquals(listOf(b, d), c.selected.map { it.uri })
        c.add(listOf(a)); c.generate(); waitFor { !c.busy }
        assertNull(c.error); val stored = c.original!!
        assertEquals(listOf("001.png", "002.png", "003.png"), stored.deck.images.map { it.name })
        assertEquals(listOf(1, 2, 3), stored.deck.script.slides)
        val first = BitmapFactory.decodeFile(stored.deck.images.first().path)
        assertEquals(Color.BLUE, first.getPixel(10, 10)); first.recycle()
        stored.deck.images.forEach { assertArrayEquals(byteArrayOf(-119, 80, 78, 71, 13, 10, 26, 10), it.readBytes().take(8).toByteArray()) }
        bytes.forEach { (uri, before) -> assertArrayEquals(before, File(uri.path!!).readBytes()) }
        assertEquals(1, vm.archives.size); assertNull(vm.deck)
    }
    @Test fun brokenImageRollsBackWholeImportAndCanRetry() {
        val vm = model(); val c = vm.creator; val good = image("good.png", Color.RED)
        val bad = File(app.cacheDir, "broken.jpg").apply { writeText("not an image") }
        c.add(listOf(good, Uri.fromFile(bad))); c.generate(); waitFor { !c.busy }
        assertNotNull(c.error); assertNull(c.original); assertTrue(vm.archives.isEmpty())
        assertTrue(File(app.filesDir, "decks").listFiles().orEmpty().isEmpty())
        c.remove(1); c.generate(); waitFor { !c.busy }; assertNotNull(c.original)
    }
    @Test fun cursorInsertionPagesSaveDiscardAndRelaunchPersistOnlySavedText() {
        val vm = model(); val c = vm.creator
        c.add(listOf(image("one.png", Color.RED), image("two.png", Color.BLUE))); c.generate(); waitFor { !c.busy }
        c.beginEditing(); waitFor { !c.busy }
        val id = c.original!!.entry.id
        c.edit(TextFieldValue("前後", TextRange(1))); c.insert("本文")
        assertEquals("前本文後", c.pages[0].text); assertEquals(TextRange(3), c.pages[0].selection)
        c.selectPage(1); c.edit(TextFieldValue("二枚目。\n")); c.insert("**コメントアウト")
        // Selection ranges replace only the selected text, including reversed selections.
        c.selectPage(0); c.edit(TextFieldValue("前本文後", TextRange(3, 1))); c.insert("差替")
        assertEquals("前差替後", c.pages[0].text)
        c.rename("作成テスト"); c.save(); waitFor { !c.busy }; assertNull(c.error); assertFalse(c.dirty)
        val store = DeckStore(File(app.filesDir, "decks")); val saved = store.load(id)
        assertTrue(saved.source.startsWith("##1\n前差替後\n\n##2\n")); assertEquals("作成テスト", saved.entry.name)
        c.edit(TextFieldValue("保存しない内容")); assertTrue(c.dirty); c.close()
        assertEquals(saved.source, store.load(id).source)
        vm.openArchived(id); waitFor { !vm.busy }; assertEquals(listOf(1, 2), vm.deck!!.script.slides)
        val restored = AppViewModel(app); waitFor { !restored.busy }; assertEquals(saved.source, store.load(restored.deck!!.directory.name).source)
    }
    @Test fun incompleteDraftCanSaveButInvalidSyntaxKeepsLastSavedDraft() {
        val vm = model(); val c = vm.creator
        c.add(listOf(image("draft.png", Color.RED))); c.generate(); waitFor { !c.busy }
        c.beginEditing(); waitFor { !c.busy }
        c.save(); waitFor { !c.busy }; assertNull(c.error)
        val saved = c.original!!.source
        c.edit(TextFieldValue("[任意の文字列(mm:ss)]")); c.save(closeAfter = true); waitFor { !c.busy }
        assertTrue(c.visible); assertNotNull(c.error); assertEquals(saved, DeckStore(File(app.filesDir, "decks")).load(c.original!!.entry.id).source)
        c.edit(TextFieldValue("本文です。\n[図の説明(00:10)]\n**メモ")); c.save(closeAfter = true); waitFor { !c.busy }
        assertFalse(c.visible)
    }
    @Test fun snippetsPersistEditedOrderEmptyListAndDefaultIsOnlyUsedWhenUnset() {
        val c = model().creator
        assertEquals(listOf("[任意の文字列(mm:ss)]", "**コメントアウト"), c.snippets)
        assertTrue(c.saveSnippets(listOf("**注意", "[説明(00:30)]", "追加")))
        assertEquals(listOf("**注意", "[説明(00:30)]", "追加"), model().creator.snippets)
        assertFalse(c.saveSnippets(listOf(""))); assertTrue(c.saveSnippets(emptyList()))
        assertTrue(model().creator.snippets.isEmpty())
    }
    @Test fun mediaContractRequestsImagesAndPreservesReturnedClipOrderAndCancel() {
        val contract = ActivityResultContracts.PickMultipleVisualMedia()
        val request = PickVisualMediaRequest.Builder().setMediaType(ActivityResultContracts.PickVisualMedia.ImageOnly).setOrderedSelection(true).setMaxItems(2).build()
        val intent = contract.createIntent(app, request)
        assertEquals("image/*", intent.type)
        val a = Uri.parse("content://test/9"); val b = Uri.parse("content://test/1")
        val clip = ClipData.newRawUri("images", a).apply { addItem(ClipData.Item(b)) }
        assertEquals(listOf(a, b), contract.parseResult(Activity.RESULT_OK, Intent().apply { clipData = clip }))
        assertTrue(contract.parseResult(Activity.RESULT_CANCELED, Intent().apply { clipData = clip }).isEmpty())
    }
    @Test @Config(sdk = [26, 35]) fun jpegOrientationIsAppliedWithoutChangingExternalFile() {
        val uri = image("orientation.jpg", Color.RED, true)
        val file = File(uri.path!!)
        val jpeg = file.readBytes()
        // JPEG APP1: EXIF little-endian TIFF, one SHORT orientation=6 (90 degrees).
        val exif = byteArrayOf(0xff.toByte(), 0xe1.toByte(), 0, 34,
            69, 120, 105, 102, 0, 0, 73, 73, 42, 0, 8, 0, 0, 0,
            1, 0, 18, 1, 3, 0, 1, 0, 0, 0, 6, 0, 0, 0, 0, 0, 0, 0)
        val original = jpeg.copyOfRange(0, 2) + exif + jpeg.copyOfRange(2, jpeg.size)
        file.writeBytes(original)
        val c = model().creator; c.add(listOf(uri)); c.generate(); waitFor { !c.busy }
        assertNull(c.error)
        val encoded = c.original!!.deck.images.single().readBytes()
        val bitmap = BitmapFactory.decodeByteArray(encoded, 0, encoded.size)
        assertEquals(36, bitmap.width); assertEquals(64, bitmap.height); bitmap.recycle()
        assertArrayEquals(original, file.readBytes())
    }
    @Test fun selectedTimeIsSavedInMetadataAndScriptAndSurvivesReopen() {
        val vm = model(); val c = vm.creator
        c.add(listOf(image("timed.png", Color.BLUE))); c.generate(); waitFor { !c.busy }
        assertEquals(CreationSection.HUB, c.section)
        c.show(CreationSection.TIME)
        c.edit(TextFieldValue("まだ書けない")); assertEquals("", c.pages[0].text)
        c.changeTimeUndecided(false); c.changeTimeMinutes("7"); c.changeTimeSeconds("30")
        c.beginEditing(); waitFor { !c.busy }
        assertNull(c.error); assertFalse(c.timeSetupPending)
        assertEquals(450.0, c.presentationSeconds!!, 0.0)
        assertTrue(c.source.startsWith("time(7:30)\n\n##1\n"))
        val id = c.original!!.entry.id
        c.edit(TextFieldValue("時間を設定した原稿です。")); c.save(closeAfter = true); waitFor { !c.busy }
        vm.openArchived(id); waitFor { !vm.busy }
        assertEquals(450.0, vm.totalSeconds!!, 0.0); assertEquals(450.0, vm.deck!!.script.declaredSeconds!!, 0.0)
        val restored = AppViewModel(app); waitFor { !restored.busy }
        assertEquals(450.0, restored.totalSeconds!!, 0.0)
    }
    @Test fun undecidedTimeRemainsUnsetAndCanBeConfiguredLater() {
        val vm = model(); val c = vm.creator
        c.add(listOf(image("undecided.png", Color.RED))); c.generate(); waitFor { !c.busy }
        c.show(CreationSection.TIME)
        c.changeTimeUndecided(false); c.changeTimeMinutes("8"); c.changeTimeSeconds("00")
        c.changeTimeUndecided(true); c.beginEditing(); waitFor { !c.busy }
        assertFalse(c.timeSetupPending); assertNull(c.presentationSeconds); assertNull(c.original!!.entry.totalSeconds)
        assertFalse(c.source.startsWith("time("))
        val id = c.original!!.entry.id
        c.edit(TextFieldValue("持ち時間はあとで設定します。")); c.save(closeAfter = true); waitFor { !c.busy }
        vm.openArchived(id); waitFor { !vm.busy }; assertNull(vm.totalSeconds)
        assertNull(vm.setTotal(300.0))
        val restored = AppViewModel(app); waitFor { !restored.busy }; assertEquals(300.0, restored.totalSeconds!!, 0.0)
    }
    @Test fun invalidTimeStaysOnSetupAndDoesNotWriteAnInvalidDuration() {
        val vm = model(); val c = vm.creator
        c.add(listOf(image("invalid-time.png", Color.RED))); c.generate(); waitFor { !c.busy }
        c.show(CreationSection.TIME)
        c.changeTimeUndecided(false)
        for ((m, s) in listOf("0" to "00", "-1" to "30", "1" to "60", "abc" to "20", "1000001" to "00")) {
            c.changeTimeMinutes(m); c.changeTimeSeconds(s); c.beginEditing()
            assertTrue(c.timeSetupPending); assertFalse(c.busy); assertNotNull(c.error)
            assertNull(DeckStore(File(app.filesDir, "decks")).load(c.original!!.entry.id).entry.totalSeconds)
        }
        c.changeTimeMinutes("0"); c.changeTimeSeconds("30"); c.beginEditing(); waitFor { !c.busy }
        assertFalse(c.timeSetupPending); assertEquals(30.0, c.presentationSeconds!!, 0.0)
    }
}
