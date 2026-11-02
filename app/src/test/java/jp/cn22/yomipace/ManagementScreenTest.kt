package jp.cn22.yomipace

import android.app.AlertDialog
import android.app.Dialog
import android.graphics.Bitmap
import android.net.Uri
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.widget.*
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.lifecycle.ViewModelProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.shadows.ShadowAlertDialog
import org.robolectric.shadows.ShadowDialog
import org.robolectric.util.ReflectionHelpers
import org.robolectric.util.ReflectionHelpers.ClassParameter
import java.io.File
import java.time.Duration

@RunWith(AndroidJUnit4::class)
@Config(sdk = [34], qualifiers = "w360dp-h840dp-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ManagementScreenTest {
    @get:Rule val rule = createAndroidComposeRule<MainActivity>()
    private fun waitFor(check: () -> Boolean) = rule.waitUntil(15_000) { shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(50)); check() }
    private fun load(): AppViewModel {
        val vm = ViewModelProvider(rule.activity)[AppViewModel::class.java]
        waitFor { !vm.busy }
        rule.runOnUiThread {
            vm.clear()
            val file = File(rule.activity.cacheDir, "management.zip")
            javaClass.getResourceAsStream("/sample.zip")!!.use { input -> file.outputStream().use { input.copyTo(it) } }
            vm.importZip(Uri.fromFile(file))
        }
        waitFor { !vm.busy }
        rule.runOnIdle { assertNotNull(vm.deck); vm.message = null }
        return vm
    }
    private fun menu(label: String) { rule.onNodeWithText("⋮").performClick(); rule.onNodeWithText(label).performClick(); rule.waitForIdle() }
    private fun views(root: View): List<View> = listOf(root) + if (root is ViewGroup) (0 until root.childCount).flatMap { views(root.getChildAt(it)) } else emptyList()
    private fun button(dialog: Dialog, text: String) = views(dialog.window!!.decorView).filterIsInstance<Button>().first { it.text.toString() == text }
    private fun action(dialog: Dialog, description: String, id: String) = views(dialog.window!!.decorView.findViewWithTag<View>("archive-entry-$id")).first { it.contentDescription?.toString() == description }
    private fun capture(dialog: Dialog, name: String) {
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(400))
        rule.runOnUiThread {
            val root = dialog.window!!.decorView
            views(root).forEach { it.jumpDrawablesToCurrentState() }
            val image = Bitmap.createBitmap(root.width, root.height, Bitmap.Config.ARGB_8888)
            ReflectionHelpers.callStaticMethod<Any?>(Class.forName("org.robolectric.shadows.HardwareRenderingScreenshot"), "takeScreenshot", ClassParameter.from(View::class.java, root), ClassParameter.from(Bitmap::class.java, image))
            val dir = File("build/test-screenshots").apply { mkdirs() }
            File(dir, "$name.png").outputStream().use { image.compress(Bitmap.CompressFormat.PNG, 100, it) }; image.recycle()
        }
    }
    @Test fun editorShowsWholeScriptValidatesBeforeSaveAndCanDiscard() {
        val vm = load()
        val before = vm.deck!!.script
        menu("読み上げ原稿の編集"); waitFor { vm.editorDraft != null && !vm.busy }; rule.waitForIdle()
        val editor = ShadowDialog.getLatestDialog()
        val source = vm.editorDraft!!
        capture(editor, "script-editor-360x840")
        rule.runOnUiThread {
            editor.findViewById<EditText>(android.R.id.edit).setText("#1\n[図(00:99)]")
            editor.window!!.decorView.findViewWithTag<Button>("save-script").performClick()
        }
        waitFor { !vm.busy && vm.editorError != null }; assertSame(before, vm.deck!!.script)
        rule.runOnUiThread {
            editor.findViewById<EditText>(android.R.id.edit).setText(source.replace("本日は", "新しい原稿です。本日は"))
            editor.window!!.decorView.findViewWithTag<Button>("save-script").performClick()
        }
        waitFor { !vm.busy && vm.editorDraft == null }
        assertTrue(vm.deck!!.script.items.filterIsInstance<jp.cn22.yomipace.core.ScriptItem.Sentence>().any { it.text == "新しい原稿です。" })
        menu("読み上げ原稿の編集"); waitFor { vm.editorDraft != null && !vm.busy }; rule.waitForIdle()
        val reopened = ShadowDialog.getLatestDialog(); val saved = vm.editorDraft!!
        rule.runOnUiThread { reopened.findViewById<EditText>(android.R.id.edit).setText("破棄する内容"); button(reopened, "閉じる").performClick() }
        rule.runOnUiThread { ShadowAlertDialog.getLatestAlertDialog().getButton(AlertDialog.BUTTON_POSITIVE).performClick() }
        waitFor { vm.editorDraft == null }
        rule.runOnUiThread { vm.beginScriptEdit() }; waitFor { vm.editorDraft != null && !vm.busy }
        assertEquals(saved, vm.editorDraft)
        rule.runOnUiThread { vm.closeEditor() }
    }
    @Test fun archivePencilTrashRestoreAndConfirmedPurgeAreUsable() {
        val vm = load(); val id = vm.deck!!.directory.name; val name = vm.deck!!.name
        menu("アーカイブ"); rule.waitForIdle()
        val archive = ShadowDialog.getLatestDialog()
        capture(archive, "archive-360x840")
        rule.runOnUiThread { action(archive, "原稿を編集：$name", id).performClick() }
        waitFor { vm.editorDraft != null && !vm.busy }; rule.waitForIdle()
        assertNotNull(ShadowDialog.getLatestDialog().findViewById<EditText>(android.R.id.edit))
        rule.runOnUiThread { vm.closeEditor() }; rule.waitForIdle()
        rule.runOnUiThread { action(archive, "ゴミ箱へ：$name", id).performClick() }
        waitFor { !vm.busy && vm.trash.any { it.id == id } }; rule.waitForIdle()
        rule.runOnUiThread { button(archive, "ゴミ箱").performClick() }; rule.waitForIdle()
        val trash = ShadowDialog.getLatestDialog()
        capture(trash, "trash-360x840")
        rule.runOnUiThread { action(trash, "復元：$name", id).performClick() }
        waitFor { !vm.busy && vm.archives.any { it.id == id } }; rule.waitForIdle()
        rule.runOnUiThread { vm.moveToTrash(id) }; waitFor { !vm.busy && vm.trash.any { it.id == id } }; rule.waitForIdle()
        rule.runOnUiThread { action(trash, "完全削除：$name", id).performClick() }
        rule.runOnUiThread { ShadowAlertDialog.getLatestAlertDialog().getButton(AlertDialog.BUTTON_NEGATIVE).performClick() }
        assertTrue(vm.trash.any { it.id == id })
        rule.runOnUiThread { action(trash, "完全削除：$name", id).performClick(); ShadowAlertDialog.getLatestAlertDialog().getButton(AlertDialog.BUTTON_POSITIVE).performClick() }
        waitFor { !vm.busy && vm.trash.none { it.id == id } }
        rule.runOnUiThread { vm.closeLibrary() }
    }
    @Test fun vibrationOptionsAndLargerSlideViewport() {
        val vm = load()
        val image = rule.onNodeWithContentDescription("現在のスライド 1").fetchSemanticsNode().boundsInRoot
        assertTrue("16:9 slide viewport should be taller than the old 140dp panel", image.height >= 135f)
        menu("グレー表示のバイブ")
        val dialog = ShadowAlertDialog.getLatestAlertDialog()
        rule.runOnUiThread {
            dialog.window!!.decorView.findViewWithTag<Switch>("vibration-enabled").isChecked = true
            dialog.window!!.decorView.findViewWithTag<RadioButton>("vibration-strength-2").performClick()
            assertTrue(dialog.window!!.decorView.findViewWithTag<RadioButton>("vibration-strength-2").isChecked)
            assertFalse(dialog.window!!.decorView.findViewWithTag<RadioButton>("vibration-strength-1").isChecked)
        }
        capture(dialog, "vibration-settings")
        rule.runOnUiThread { dialog.getButton(AlertDialog.BUTTON_POSITIVE).performClick() }
        rule.runOnIdle { assertTrue(vm.vibrationEnabled); assertEquals(2, vm.vibrationStrength) }
    }
}
