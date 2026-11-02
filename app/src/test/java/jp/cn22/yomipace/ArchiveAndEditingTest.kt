package jp.cn22.yomipace

import android.app.Application
import android.net.Uri
import android.os.Looper
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
class ArchiveAndEditingTest {
    private val app get() = ApplicationProvider.getApplicationContext<Application>()
    @Before fun clean() { app.getSharedPreferences("yomipace", 0).edit().clear().commit(); File(app.filesDir, "decks").deleteRecursively() }
    private fun waitFor(check: () -> Boolean) {
        val until = System.nanoTime() + 15_000_000_000L
        while (!check() && System.nanoTime() < until) { shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(50)); Thread.sleep(10) }
        assertTrue("Async operation timed out", check())
    }
    private fun freshModel() = AppViewModel(app).also { waitFor { !it.busy } }
    private fun sample(): File = File(app.cacheDir, "sample.zip").also { f -> javaClass.getResourceAsStream("/sample.zip")!!.use { input -> f.outputStream().use { input.copyTo(it) } } }
    private fun load(vm: AppViewModel, file: File = sample()) { vm.importZip(Uri.fromFile(file)); waitFor { !vm.busy }; assertNotNull(vm.deck) }
    @Test fun importKeepsOriginalZipAndEarlierDecksAndClearKeepsArchive() {
        val vm = freshModel(); val source = sample(); val bytes = source.readBytes()
        load(vm, source); val first = vm.deck!!.directory
        assertArrayEquals(bytes, File(first, "source.zip").readBytes())
        load(vm, source); assertEquals(2, vm.archives.size); assertTrue(first.isDirectory)
        vm.clear(); assertNull(vm.deck); assertEquals(2, vm.archives.size)
        vm.openArchived(first.name); waitFor { !vm.busy }
        assertEquals(first, vm.deck!!.directory)
        assertArrayEquals(bytes, source.readBytes())
    }
    @Test fun trashRestoreAndPermanentDeletionAreSeparateAndDoNotDeleteSource() {
        val vm = freshModel(); val source = sample(); load(vm, source)
        val directory = vm.deck!!.directory; val id = directory.name
        vm.moveToTrash(id); waitFor { !vm.busy }
        assertNull(vm.deck); assertEquals(0, vm.archives.size); assertEquals(1, vm.trash.size); assertTrue(directory.exists())
        vm.restoreFromTrash(id); waitFor { !vm.busy }
        assertEquals(1, vm.archives.size); assertEquals(0, vm.trash.size)
        vm.deletePermanently(id); waitFor { !vm.busy }; assertTrue(directory.exists()) // archive entries cannot be purged
        vm.message = null; vm.moveToTrash(id); waitFor { !vm.busy }
        vm.deletePermanently(id); waitFor { !vm.busy }
        assertFalse("Delete result: ${vm.message}; remaining=${directory.listFiles()?.map { it.name }}", directory.exists()); assertTrue(source.exists()); assertTrue(vm.trash.isEmpty())
    }
    @Test fun invalidEditAndCancelKeepOriginalValidEditPersistsWithoutChangingOriginalZip() {
        val vm = freshModel(); load(vm); vm.setTotal(480.0)
        val before = vm.deck!!; val originalZip = File(before.directory, "source.zip").readBytes()
        vm.beginScriptEdit(); waitFor { !vm.busy }
        val source = vm.editorDraft!!
        vm.updateEditorDraft("#1\n[図(00:99)]"); vm.saveScriptEdit(); waitFor { !vm.busy }
        assertTrue(vm.editorError!!.contains("2行目")); assertSame(before, vm.deck)
        vm.closeEditor(); vm.beginScriptEdit(); waitFor { !vm.busy }; assertEquals(source, vm.editorDraft)
        vm.updateEditorDraft(source.replace("本日は", "今回は")); vm.saveScriptEdit(); waitFor { !vm.busy }
        assertNull(vm.editorDraft); assertTrue(vm.deck!!.script.items.filterIsInstance<ScriptItem.Sentence>().any { it.text.startsWith("今回は") })
        assertEquals(480.0, vm.totalSeconds!!, 0.0); assertArrayEquals(originalZip, File(before.directory, "source.zip").readBytes())
        val restored = freshModel(); assertTrue(restored.deck!!.script.items.filterIsInstance<ScriptItem.Sentence>().any { it.text.startsWith("今回は") })
        assertEquals(480.0, restored.totalSeconds!!, 0.0)
    }
    @Test fun editingArchivedDeckDoesNotSwitchCurrentPresentationAndRunningBlocksEditing() {
        val vm = freshModel(); load(vm); val first = vm.deck!!.directory.name
        load(vm); val second = vm.deck!!.directory.name
        vm.beginScriptEdit(first); waitFor { !vm.busy }
        vm.updateEditorDraft(vm.editorDraft!!.replace("07:00", "08:00")); vm.saveScriptEdit(); waitFor { !vm.busy }
        assertEquals(second, vm.deck!!.directory.name); assertEquals(420.0, vm.totalSeconds!!, 0.0)
        vm.openArchived(first); waitFor { !vm.busy }; assertEquals(480.0, vm.totalSeconds!!, 0.0)
        vm.play(); vm.beginScriptEdit(); assertNull(vm.editorDraft); assertTrue(vm.running)
        vm.pause(); vm.setVibration(true, 2)
        val restored = freshModel(); assertTrue(restored.vibrationEnabled); assertEquals(2, restored.vibrationStrength)
    }
    @Test fun scheduledGreyCompletionUsesVibrationSettingAndIgnoresManualPosition() {
        val vm = freshModel(); load(vm)
        org.robolectric.shadows.ShadowVibrator.reset()
        val vibrator = requireNotNull(app.getSystemService(android.os.VibratorManager::class.java)).defaultVibrator
        val shadow = shadowOf(vibrator)
        shadow.setHasVibrator(true); shadow.setHasAmplitudeControl(true)
        val baseline = shadow.milliseconds
        val ends = vm.deck!!.script.items.filterIsInstance<ScriptItem.Sentence>().groupBy { it.line }.values.map { group ->
            group.maxOf { vm.timeline!!.byId.getValue(it.id).end }
        }
        vm.setVibration(false, 1); vm.updateAnchor(ReadingAnchor("h3"), 3); vm.setFont(24); vm.swap()
        assertEquals(baseline, shadow.milliseconds)
        vm.play(); shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis((ends[0] * 1000).toLong() + 100))
        assertEquals(baseline, shadow.milliseconds)
        vm.pause(); vm.setVibration(true, 2); vm.play()
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(((ends[1] - vm.elapsed) * 1000).toLong().coerceAtLeast(1) + 100))
        assertTrue("Scheduled paragraph completion did not reach the vibrator", shadow.milliseconds > 0)
        vm.pause()
    }
    @Test fun legacyActiveDeckIsMigratedWithoutRequiringOriginalZip() {
        val vm = freshModel(); load(vm); val dir = vm.deck!!.directory
        File(dir, "entry.json").delete(); File(dir, "source.zip").delete()
        val migrated = freshModel(); assertNotNull(migrated.deck); assertEquals(1, migrated.archives.size)
        assertTrue(File(dir, "entry.json").isFile)
    }

    @Test fun archivedImageNumberMismatchMustNotReplaceTheCurrentDeck() {
        val vm = freshModel(); load(vm)
        val archived = vm.deck!!.directory
        load(vm)
        val active = vm.deck
        assertTrue(File(archived, "002.png").renameTo(File(archived, "009.png")))
        vm.openArchived(archived.name); waitFor { !vm.busy }
        assertSame("A broken image sequence must not replace the active presentation", active, vm.deck)
        assertNotNull(vm.message)
    }
}

