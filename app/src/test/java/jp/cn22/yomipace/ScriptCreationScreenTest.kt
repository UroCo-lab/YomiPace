package jp.cn22.yomipace

import android.app.Application
import android.graphics.Bitmap
import android.graphics.Color
import android.net.Uri
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import androidx.lifecycle.ViewModelProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.shadows.ShadowDialog
import org.robolectric.util.ReflectionHelpers
import org.robolectric.util.ReflectionHelpers.ClassParameter
import java.io.File
import java.time.Duration

@RunWith(AndroidJUnit4::class)
@Config(sdk = [35], qualifiers = "w360dp-h840dp-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ScriptCreationScreenTest {
    @get:Rule val rule = createAndroidComposeRule<MainActivity>()
    private fun waitFor(check: () -> Boolean) = rule.waitUntil(15_000) { shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(50)); check() }
    private fun start(): AppViewModel {
        val vm = ViewModelProvider(rule.activity)[AppViewModel::class.java]
        waitFor { !vm.busy }
        rule.runOnIdle { vm.clear(); vm.message = null; vm.creator.saveSnippets(listOf("[任意の文字列(mm:ss)]", "**コメントアウト")) }
        rule.onNodeWithText("⋮").performClick(); rule.onNodeWithText("読み上げ原稿作成").performClick()
        rule.onNodeWithText("画像選択").assertIsDisplayed()
        return vm
    }
    private fun add(vm: AppViewModel) {
        val uris = (1..2).map { index ->
            val file = File(rule.activity.cacheDir, "creation-ui-$index.png")
            val bitmap = Bitmap.createBitmap(640, 360, Bitmap.Config.ARGB_8888).apply { eraseColor(if (index == 1) Color.rgb(32, 90, 160) else Color.rgb(32, 140, 110)) }
            file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }; bitmap.recycle(); Uri.fromFile(file)
        }
        rule.runOnIdle { vm.creator.add(uris) }
    }
    private fun generate(vm: AppViewModel) {
        rule.onNodeWithText("生成").performClick(); waitFor { !vm.creator.busy }; rule.waitForIdle()
        assertNotNull("Import failed: ${vm.creator.error}", vm.creator.original)
        rule.onNodeWithTag("creation-hub").assertIsDisplayed()
        rule.onNodeWithTag("creation-hub-script").performClick()
        waitFor { !vm.creator.busy && !vm.creator.timeSetupPending }; rule.waitForIdle()
        waitFor { rule.onAllNodesWithContentDescription("作成中のスライド 1").fetchSemanticsNodes().isNotEmpty() }
    }
    private fun capture(name: String) {
        rule.waitForIdle()
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(300))
        rule.runOnUiThread {
            val root = ShadowDialog.getLatestDialog().window!!.decorView
            fun settle(view: View) { view.jumpDrawablesToCurrentState(); if (view is ViewGroup) (0 until view.childCount).forEach { settle(view.getChildAt(it)) } }
            settle(root)
            val bitmap = Bitmap.createBitmap(root.width, root.height, Bitmap.Config.ARGB_8888)
            ReflectionHelpers.callStaticMethod<Any?>(Class.forName("org.robolectric.shadows.HardwareRenderingScreenshot"), "takeScreenshot", ClassParameter.from(View::class.java, root), ClassParameter.from(Bitmap::class.java, bitmap))
            val folder = File("build/test-screenshots").apply { mkdirs() }
            File(folder, "$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }; bitmap.recycle()
        }
    }
    @Test fun menuSelectionReorderGenerationInsertSaveAndThreeWayClose() {
        val vm = start(); add(vm)
        val initial = vm.creator.selected.map { it.uri }
        rule.onNodeWithContentDescription("画像2を前へ").performClick()
        assertEquals(initial.reversed(), vm.creator.selected.map { it.uri })
        capture("creation-selection-360x840")
        generate(vm)
        rule.onNodeWithTag("creation-script").performTextInput("本文です。")
        rule.onNodeWithText("次へ").performClick()
        rule.onNodeWithTag("creation-script").performTextInput("二枚目。")
        rule.onNodeWithText("前へ").performClick()
        assertEquals("本文です。", vm.creator.pages[0].text)
        rule.runOnIdle { vm.creator.edit(TextFieldValue("前後", TextRange(1))) }
        rule.onNodeWithTag("insert-snippet-1").performScrollTo().performClick()
        assertEquals("前**コメントアウト後", vm.creator.pages[0].text)
        rule.runOnIdle { vm.creator.edit(TextFieldValue("本文です。\n**読まないメモ")) }
        capture("creation-editor-360x840")
        rule.onNodeWithText("閉じる").performClick()
        rule.onNodeWithText("保存する").assertIsDisplayed()
        rule.onNodeWithText("保存しない").assertIsDisplayed()
        rule.onNodeWithText("キャンセル").performClick(); assertTrue(vm.creator.visible)
        rule.onNodeWithTag("creation-save").performClick(); waitFor { !vm.creator.busy && !vm.creator.dirty }
        val saved = vm.creator.original!!.source
        val savedDirectory = vm.creator.original!!.deck.directory
        rule.onNodeWithTag("creation-script").performTextInput("未保存")
        rule.onNodeWithText("閉じる").performClick(); rule.onNodeWithText("保存しない").performClick()
        assertFalse(vm.creator.visible)
        val store = DeckStore(savedDirectory.parentFile!!)
        assertEquals(saved, store.load(savedDirectory.name).source)
        assertTrue(vm.archives.any { it.id == savedDirectory.name })
    }
    @Test fun closeSaveFailureKeepsEditorThenValidSaveCloses() {
        val vm = start(); add(vm); generate(vm)
        rule.onNodeWithTag("creation-script").performTextInput("[任意の文字列(mm:ss)]")
        rule.onNodeWithText("閉じる").performClick(); rule.onNodeWithText("保存する").performClick()
        waitFor { !vm.creator.busy && vm.creator.error != null }; assertTrue(vm.creator.visible)
        rule.onNodeWithTag("creation-script").performTextReplacement("本文です。")
        rule.onNodeWithText("閉じる").performClick(); rule.onNodeWithText("保存する").performClick()
        waitFor { !vm.creator.busy && !vm.creator.visible }
    }
    @Test fun snippetMenuCanEditAddReorderDeleteAndPersist() {
        val vm = start(); add(vm); generate(vm)
        rule.onNodeWithTag("snippet-menu").performClick()
        rule.waitForIdle()
        val dialog = ShadowDialog.getLatestDialog()
        rule.runOnUiThread {
            val root = dialog.window!!.decorView
            root.findViewWithTag<android.widget.EditText>("snippet-value-0").setText("[説明(00:15)]")
            root.findViewWithTag<android.widget.Button>("snippet-down-0").performClick()
            root.findViewWithTag<android.widget.Button>("snippet-add").performClick()
            root.findViewWithTag<android.widget.EditText>("snippet-value-2").setText("追加文字列")
            root.findViewWithTag<android.widget.Button>("snippet-delete-0").performClick()
        }
        capture("creation-snippet-settings-360x840")
        rule.runOnUiThread { dialog.window!!.decorView.findViewWithTag<android.widget.Button>("snippet-apply").performClick() }
        assertEquals(listOf("[説明(00:15)]", "追加文字列"), vm.creator.snippets)
        val restored = AppViewModel(vm.getApplication<Application>())
        assertEquals(vm.creator.snippets, restored.creator.snippets)
        rule.runOnIdle { vm.creator.close() }
    }
    @Test @Config(qualifiers = "w720dp-h840dp-mdpi") fun foldSizedCreationKeepsSlideAboveEditorAndSaveVisible() {
        val vm = start(); add(vm); generate(vm)
        val image = rule.onNodeWithContentDescription("作成中のスライド 1").fetchSemanticsNode().boundsInRoot
        val editor = rule.onNodeWithTag("creation-script").fetchSemanticsNode().boundsInRoot
        assertTrue(image.bottom < editor.top)
        rule.onNodeWithTag("creation-save").assertIsDisplayed()
        capture("creation-editor-720x840")
        rule.runOnIdle { vm.creator.close() }
    }
    @Test fun presentationTimeIsChosenBeforeFirstPageAndInvalidTimeCanBeCorrected() {
        val vm = start(); add(vm)
        rule.onNodeWithText("生成").performClick(); waitFor { !vm.creator.busy }; rule.waitForIdle()
        rule.onNodeWithTag("creation-script").assertDoesNotExist()
        rule.onNodeWithTag("creation-hub-time").performClick()
        rule.onNodeWithTag("creation-time-undecided").assertIsSelected()
        rule.onNodeWithTag("creation-time-minutes").assertIsNotEnabled()
        capture("creation-time-setup-360x840")
        rule.onNodeWithTag("creation-time-fixed").performClick()
        rule.onNodeWithTag("creation-time-minutes").performTextReplacement("0")
        rule.onNodeWithTag("creation-time-seconds").performTextReplacement("00")
        rule.onNodeWithTag("creation-back-hub").performClick()
        assertTrue(vm.creator.timeSetupPending); assertNotNull(vm.creator.error)
        rule.onNodeWithTag("creation-time-minutes").performTextReplacement("7")
        rule.onNodeWithTag("creation-time-seconds").performTextReplacement("30")
        rule.onNodeWithTag("creation-back-hub").performClick()
        rule.onNodeWithTag("creation-hub-script").performClick()
        waitFor { !vm.creator.busy && !vm.creator.timeSetupPending }
        rule.onNodeWithTag("creation-script").assertIsDisplayed()
        rule.onNodeWithText("発表時間 7分30秒").assertIsDisplayed()
        rule.onNodeWithTag("creation-save").performClick(); waitFor { !vm.creator.busy }
        assertEquals(450.0, vm.creator.original!!.entry.totalSeconds!!, 0.0)
        rule.runOnIdle { vm.creator.close() }
    }
    @Test fun fourManagementButtonsEditTitleTimeAndOrderAndReopenIntoTheHub() {
        val vm = start(); add(vm); generate(vm)
        rule.onNodeWithTag("creation-script").performTextInput("一枚目の本文。")
        rule.onNodeWithText("次へ").performClick()
        rule.onNodeWithTag("creation-script").performTextInput("二枚目の本文。")
        rule.onNodeWithTag("creation-back-hub").performClick()
        listOf("タイトル", "時間指定", "スライド順", "原稿編集").forEach { rule.onNodeWithText(it).assertIsDisplayed() }
        capture("creation-hub-360x840")
        rule.onNodeWithTag("creation-hub-title").performClick()
        rule.onNodeWithTag("creation-name").performTextReplacement("管理ページの資料")
        rule.onNodeWithTag("creation-back-hub").performClick()
        rule.onNodeWithTag("creation-hub-time").performClick()
        rule.onNodeWithTag("creation-time-fixed").performClick()
        rule.onNodeWithTag("creation-time-minutes").performTextReplacement("8")
        rule.onNodeWithTag("creation-time-seconds").performTextReplacement("15")
        rule.onNodeWithTag("creation-back-hub").performClick()
        rule.onNodeWithTag("creation-hub-order").performClick()
        rule.onNodeWithContentDescription("スライド2を前へ").performClick()
        capture("creation-slide-order-360x840")
        rule.onNodeWithTag("creation-back-hub").performClick()
        rule.onNodeWithTag("creation-hub-script").performClick()
        rule.onNodeWithTag("creation-name").assertDoesNotExist()
        rule.onNodeWithTag("creation-script").assertTextContains("二枚目の本文。")
        rule.onNodeWithText("##1（自動）").assertIsDisplayed()
        val id = vm.creator.original!!.entry.id
        rule.onNodeWithTag("creation-save").performClick(); waitFor { !vm.creator.busy }
        rule.onNodeWithText("閉じる").performClick()
        rule.runOnIdle { vm.beginArchivedEdit(id) }; waitFor { !vm.busy }
        rule.onNodeWithTag("creation-hub").assertIsDisplayed()
        assertEquals("管理ページの資料", vm.creator.name); assertEquals(495.0, vm.creator.presentationSeconds!!, 0.0)
        assertEquals(listOf(2, 1), vm.creator.imageOrder)
        rule.runOnIdle { vm.creator.close() }
    }
    @Test fun unsavedSlideOrderAndTimeCanBeCancelledOrDiscardedTogether() {
        val vm = start(); add(vm); generate(vm)
        val directory = vm.creator.original!!.deck.directory
        val entry = File(directory, "entry.json").readBytes()
        rule.onNodeWithTag("creation-back-hub").performClick()
        rule.onNodeWithTag("creation-hub-time").performClick()
        rule.onNodeWithTag("creation-time-fixed").performClick()
        rule.onNodeWithTag("creation-time-minutes").performTextReplacement("5")
        rule.onNodeWithTag("creation-back-hub").performClick()
        rule.onNodeWithTag("creation-hub-order").performClick()
        rule.onNodeWithContentDescription("スライド2を前へ").performClick()
        rule.onNodeWithText("閉じる").performClick()
        rule.onNodeWithText("キャンセル").performClick()
        assertEquals(listOf(2, 1), vm.creator.imageOrder)
        rule.onNodeWithText("閉じる").performClick()
        rule.onNodeWithText("保存しない").performClick()
        assertFalse(vm.creator.visible); assertArrayEquals(entry, File(directory, "entry.json").readBytes())
    }
}
