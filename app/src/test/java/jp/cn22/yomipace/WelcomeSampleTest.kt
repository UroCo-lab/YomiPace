package jp.cn22.yomipace

import android.app.Application
import android.graphics.BitmapFactory
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
import java.io.IOException
import java.time.Duration

@RunWith(AndroidJUnit4::class)
@Config(sdk = [35])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class WelcomeSampleTest {
    private val app get() = ApplicationProvider.getApplicationContext<Application>()
    private val prefs get() = app.getSharedPreferences("yomipace", 0)
    private val root get() = File(app.filesDir, "decks")
    @Before fun clean() { prefs.edit().clear().commit(); root.deleteRecursively() }
    private fun waitFor(check: () -> Boolean) {
        val until = System.nanoTime() + 15_000_000_000L
        while (!check() && System.nanoTime() < until) { shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(50)); Thread.sleep(10) }
        assertTrue("Async operation timed out", check())
    }
    private fun model() = AppViewModel(app).also { waitFor { !it.busy } }

    @Test fun firstLaunchOpensSixteenValidSlidesWithEditingGuideAndPausedEightMinuteTimeline() {
        val vm = model()
        assertEquals(WelcomeSample.NAME, vm.deck!!.name)
        assertEquals(16, vm.deck!!.images.size)
        assertEquals((1..16).toList(), vm.deck!!.script.slides)
        assertTrue(vm.deck!!.script.issues.isEmpty())
        assertEquals(480.0, vm.totalSeconds!!, 0.0)
        assertNotNull(vm.timeline); assertNull(vm.startProblem)
        assertFalse(vm.running); assertEquals(1, vm.currentSlide); assertEquals(0.0, vm.elapsed, 0.0)
        assertEquals(1, vm.archives.size)
        vm.deck!!.images.forEach { image ->
            val bitmap = BitmapFactory.decodeFile(image.path)
            assertNotNull(image.name, bitmap); assertEquals(1280, bitmap.width); assertEquals(720, bitmap.height); bitmap.recycle()
        }
        val source = File(vm.deck!!.directory, "script.txt").readText()
        assertTrue(source.substringAfter("##16\n").contains("完全に削除"))
        assertTrue(source.substringAfter("##16\n").contains("アーカイブ"))
        val editing = source.substringAfter("##11\n").substringBefore("##16\n")
        listOf("タイトル", "時間指定", "スライド順", "原稿編集", "未定", "カーソル", "保存する", "保存しない", "キャンセル").forEach { assertTrue(it, editing.contains(it)) }
        assertTrue(source.substringAfter("##12\n").substringBefore("##13\n").contains("読まないメモ、固定時間"))
        assertArrayEquals(app.assets.open(WelcomeSample.ASSET).use { it.readBytes() }, File(vm.deck!!.directory, "source.zip").readBytes())
    }
    @Test fun relaunchRestoresEditedSamplePositionAndDoesNotDuplicateIt() {
        val vm = model()
        vm.beginScriptEdit(); waitFor { !vm.busy }
        vm.updateEditorDraft(vm.editorDraft!!.replace("ようこそ", "いらっしゃいませ")); vm.saveScriptEdit(); waitFor { !vm.busy }
        vm.seekTo(75.0); vm.updateAnchor(ReadingAnchor("h16"), 16); vm.setFont(24); vm.persist()
        val restored = model()
        assertEquals(1, restored.archives.size); assertEquals(24, restored.fontSize)
        assertEquals(16, restored.currentSlide); assertEquals(75.0, restored.elapsed, .001)
        assertFalse(restored.running)
        assertTrue(File(restored.deck!!.directory, "entry.json").readText().contains("いらっしゃいませ"))
    }
    @Test fun clearKeepsArchiveButNeverAutomaticallyReopensSample() {
        val vm = model(); vm.clear()
        val restored = model()
        assertNull(restored.deck); assertEquals(1, restored.archives.size)
    }
    @Test fun trashRestoreAndPermanentDeletionNeverReinstallSample() {
        val vm = model(); vm.moveToTrash(WelcomeSample.ID); waitFor { !vm.busy }
        var restored = model()
        assertNull(restored.deck); assertTrue(restored.archives.isEmpty()); assertEquals(1, restored.trash.size)
        restored.restoreFromTrash(WelcomeSample.ID); waitFor { !restored.busy }
        restored.openArchived(WelcomeSample.ID); waitFor { !restored.busy }; assertEquals(16, restored.deck!!.images.size)
        restored.moveToTrash(WelcomeSample.ID); waitFor { !restored.busy }
        restored.deletePermanently(WelcomeSample.ID); waitFor { !restored.busy }
        restored = model()
        assertNull(restored.deck); assertTrue(restored.archives.isEmpty()); assertTrue(restored.trash.isEmpty())
        assertFalse(File(root, WelcomeSample.ID).exists())
    }
    @Test fun upgradeWithClearedPresentationPreservesSettingsAndStaysEmpty() {
        prefs.edit().putInt("font", 29).putString("anchor", "h1").putBoolean("keepScreenOnWhileRunning", false).commit()
        val vm = model()
        assertNull(vm.deck); assertTrue(vm.archives.isEmpty()); assertEquals(29, vm.fontSize); assertFalse(vm.keepScreenOnWhileRunning)
        assertTrue(prefs.getBoolean(WelcomeSample.SETUP_COMPLETE, false))
    }
    @Test fun upgradePreservesExistingActiveDeckAndArchiveWithoutAddingSample() {
        val id = "deck-11111111-1111-4111-8111-111111111111"
        val store = DeckStore(root)
        val imported = ZipImporter.read(javaClass.getResourceAsStream("/sample.zip")!!, store.directory(id), "既存の資料")
        store.write(DeckEntry(id, "既存の資料", imported.images.size, 1, 1, totalSeconds = 420.0))
        prefs.edit().putString("active", id).putString("anchor", "h2").putLong("elapsed", 12000).commit()
        val vm = model()
        assertEquals(id, vm.deck!!.directory.name); assertEquals(12.0, vm.elapsed, .001); assertEquals(2, vm.currentSlide)
        assertEquals(1, vm.archives.size); assertFalse(File(root, WelcomeSample.ID).exists())
    }
    @Test fun existingArchiveWithoutPreferencesAlsoSkipsSample() {
        File(root, "deck-22222222-2222-4222-8222-222222222222").mkdirs()
        WelcomeSample.installIfNew(app) { throw AssertionError("Must not read asset for an existing installation") }
        assertNull(prefs.getString("active", null)); assertFalse(File(root, WelcomeSample.ID).exists())
    }
    @Test fun failedInstallLeavesNoCompletionFlagAndNextLaunchRetries() {
        var reads = 0
        try {
            WelcomeSample.installIfNew(app) {
                reads++
                if (reads == 2) throw IOException("Simulated source ZIP copy failure")
                app.assets.open(WelcomeSample.ASSET)
            }
            fail("Expected installation to fail")
        } catch (_: IOException) { }
        assertFalse(prefs.contains(WelcomeSample.SETUP_COMPLETE)); assertFalse(File(root, WelcomeSample.ID).exists())
        // MainActivity.onStop can save ordinary display settings after a failed first launch.
        prefs.edit().putInt("font", 29).putLong("elapsed", 0).commit()
        assertEquals(16, model().deck!!.images.size)
    }
    @Test fun interruptedInstallReusesSameArchiveEntry() {
        WelcomeSample.installIfNew(app)
        prefs.edit().clear().commit() // Simulate complete files before the initial preferences transaction.
        WelcomeSample.installIfNew(app) { throw AssertionError("Completed sample files must be reused") }
        val vm = model(); assertEquals(1, vm.archives.size); assertEquals(WelcomeSample.ID, vm.deck!!.directory.name)
    }
    @Test fun lifecycleSavingDefaultsBeforeAssetExtractionDoesNotSuppressFirstInstall() {
        WelcomeSample.prepare(app)
        // Simulate onStop between ViewModel construction and its IO coroutine starting.
        prefs.edit().putInt("font", 24).putLong("elapsed", 0).putString("anchor", "h1").commit()
        val vm = model()
        assertEquals(16, vm.deck!!.images.size); assertEquals(24, vm.fontSize); assertEquals(1, vm.archives.size)
    }
    @Test fun manualImportAfterDeletingSampleWorksNormally() {
        val vm = model(); vm.moveToTrash(WelcomeSample.ID); waitFor { !vm.busy }
        vm.deletePermanently(WelcomeSample.ID); waitFor { !vm.busy }
        val file = File(app.cacheDir, "welcome.zip")
        app.assets.open(WelcomeSample.ASSET).use { input -> file.outputStream().use { input.copyTo(it) } }
        vm.importZip(Uri.fromFile(file)); waitFor { !vm.busy }
        assertEquals(16, vm.deck!!.images.size); assertEquals(1, vm.archives.size)
        assertNotEquals(WelcomeSample.ID, vm.deck!!.directory.name)
    }
}
