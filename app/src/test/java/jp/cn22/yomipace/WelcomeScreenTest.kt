package jp.cn22.yomipace

import android.app.AlertDialog
import android.app.Application
import android.graphics.Bitmap
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import androidx.activity.compose.setContent
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModel
import androidx.test.ext.junit.runners.AndroidJUnit4
import jp.cn22.yomipace.core.ReadingAnchor
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

abstract class WelcomeScreenFixture {
    @get:Rule val rule = createAndroidComposeRule<MainActivity>()
    protected fun ready(): AppViewModel {
        val previous = ViewModelProvider(rule.activity)[AppViewModel::class.java]
        waitFor { !previous.busy }
        lateinit var vm: AppViewModel
        rule.runOnUiThread {
            // Use a lifecycle-owned host so this UI fixture has no previously saved presentation.
            previous.pause()
            rule.activity.viewModelStore.clear()
            val app = rule.activity.application
            app.getSharedPreferences("yomipace", 0).edit().clear().commit()
            File(app.filesDir, "decks").deleteRecursively()
            // Bind this test's Application explicitly; Robolectric can retain a factory from a prior sandbox.
            vm = ViewModelProvider(rule.activity, object : ViewModelProvider.Factory {
                @Suppress("UNCHECKED_CAST")
                override fun <T : ViewModel> create(modelClass: Class<T>): T = AppViewModel(app) as T
            })[AppViewModel::class.java]
            assertSame(app, vm.getApplication<Application>())
            rule.activity.setContent { MaterialTheme { YomiPaceScreen(vm, false) } }
        }
        waitFor { !vm.busy }
        rule.runOnIdle {
            val app = rule.activity.application
            assertNotNull("Initial sample: ${vm.message}; prefs=${app.getSharedPreferences("yomipace", 0).all}; files=${File(app.filesDir, "decks").list()?.toList()}", vm.deck)
            assertNull(vm.startProblem)
        }
        return vm
    }
    protected fun waitFor(check: () -> Boolean) = rule.waitUntil(15_000) {
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(50)); check()
    }
    protected fun capture(name: String) {
        waitFor {
            androidx.compose.runtime.snapshots.Snapshot.sendApplyNotifications(); rule.waitForIdle()
            rule.onAllNodesWithText("…").fetchSemanticsNodes().isEmpty()
        }
        rule.onAllNodesWithText("画像を読み込めません").assertCountEquals(0)
        rule.waitForIdle()
        rule.runOnUiThread {
            // The native screenshot renderer can otherwise reuse a preceding screen's RenderNode.
            views(rule.activity.window.decorView).forEach { it.invalidate() }
        }
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(400))
        rule.waitForIdle()
        rule.runOnUiThread {
            val root = rule.activity.window.decorView
            val image = Bitmap.createBitmap(root.width, root.height, Bitmap.Config.ARGB_8888)
            ReflectionHelpers.callStaticMethod<Any?>(Class.forName("org.robolectric.shadows.HardwareRenderingScreenshot"), "takeScreenshot",
                ClassParameter.from(View::class.java, root), ClassParameter.from(Bitmap::class.java, image))
            val output = File("build/test-screenshots").apply { mkdirs() }
            File(output, "$name.png").outputStream().use { image.compress(Bitmap.CompressFormat.PNG, 100, it) }; image.recycle()
        }
    }
    protected fun views(root: View): List<View> = listOf(root) + if (root is ViewGroup) (0 until root.childCount).flatMap { views(root.getChildAt(it)) } else emptyList()
    protected fun menu(label: String) { rule.onNodeWithText("⋮").performClick(); rule.onNodeWithText(label).performClick(); rule.waitForIdle() }
}

@RunWith(AndroidJUnit4::class)
@Config(sdk = [34], qualifiers = "w360dp-h800dp-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class WelcomeScreenTest : WelcomeScreenFixture() {
    @Test fun firstInstallShowsGuideAndLastSlideCanBeDeletedThroughArchiveAndTrash() {
        val vm = ready()
        assertEquals(1, vm.currentSlide)
        capture("welcome-first-launch-phone")
        rule.onNodeWithText("現在のスライド · 1").assertIsDisplayed()
        rule.onNodeWithText(WelcomeSample.NAME).assertIsDisplayed()
        rule.onNodeWithText("開始").assertIsEnabled()
        rule.onNodeWithTag("stacked-layout").assertExists()
        assertFalse(vm.running)
        rule.runOnIdle { vm.updateAnchor(ReadingAnchor("h10"), 10) }
        capture("welcome-last-slide-phone")
        menu("アーカイブ")
        val archive = ShadowDialog.getLatestDialog()
        rule.runOnUiThread {
            val row = archive.window!!.decorView.findViewWithTag<View>("archive-entry-${WelcomeSample.ID}")
            views(row).first { it.contentDescription == "ゴミ箱へ：${WelcomeSample.NAME}" }.performClick()
        }
        waitFor { !vm.busy && vm.trash.size == 1 }; assertNull(vm.deck)
        rule.runOnUiThread { views(archive.window!!.decorView).filterIsInstance<Button>().first { it.text == "ゴミ箱" }.performClick() }
        rule.waitForIdle()
        val trash = ShadowDialog.getLatestDialog()
        rule.runOnUiThread {
            views(trash.window!!.decorView).first { it.contentDescription == "完全削除：${WelcomeSample.NAME}" }.performClick()
        }
        rule.runOnUiThread { ShadowAlertDialog.getLatestAlertDialog().getButton(AlertDialog.BUTTON_POSITIVE).performClick() }
        waitFor { !vm.busy && vm.trash.isEmpty() }; assertTrue(vm.archives.isEmpty())
    }

}

@RunWith(AndroidJUnit4::class)
@Config(sdk = [34], qualifiers = "w700dp-h900dp-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class WelcomeFoldScreenTest : WelcomeScreenFixture() {
    @Test
    fun welcomeRemainsUsableOnFoldMainScreenInPortrait() {
        val vm = ready()
        rule.runOnUiThread { rule.activity.setContent { MaterialTheme { YomiPaceScreen(vm, false, foldMainScreen = true) } } }
        capture("welcome-first-launch-fold")
        rule.onNodeWithTag("two-column-layout").assertExists()
        rule.onNodeWithText("開始").assertIsEnabled()
        rule.runOnIdle { vm.updateAnchor(ReadingAnchor("h10"), 10); vm.swap() }
        capture("welcome-last-slide-fold-swapped")
        assertEquals(10, vm.currentSlide); assertEquals(300.0, vm.totalSeconds!!, 0.0)
    }
}
