package jp.cn22.yomipace

import android.graphics.Bitmap
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import androidx.activity.compose.setContent
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.MutableState
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
import org.robolectric.shadows.ShadowDialog
import org.robolectric.util.ReflectionHelpers
import org.robolectric.util.ReflectionHelpers.ClassParameter
import java.io.File
import java.time.Duration

@RunWith(AndroidJUnit4::class)
@Config(sdk = [35], qualifiers = "w360dp-h840dp-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ArchiveWorkIndicatorTest {
    @get:Rule val rule = createAndroidComposeRule<MainActivity>()
    // Hold each observable UI state deterministically. Real transfer lifecycle is covered in
    // SlideLibraryOperationsTest, including success, failure, retries and repeated taps.
    private fun <T> seed(vm: AppViewModel, field: String, value: T) {
        ReflectionHelpers.getField<MutableState<T>>(vm, field + "\$delegate").value = value
    }
    private fun descendants(view: View): List<View> = listOf(view) + if (view is ViewGroup)
        (0 until view.childCount).flatMap { descendants(view.getChildAt(it)) } else emptyList()
    private fun checkIndicator(dark: Boolean) {
        val vm = ViewModelProvider(rule.activity)[AppViewModel::class.java]
        rule.waitUntil(15_000) { shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(50)); !vm.busy }
        rule.runOnUiThread {
            vm.pause()
            seed(vm, "archives", (1..15).map { DeckSummary("deck-$it", "説明用資料 $it", 3, 0L, false) })
            rule.activity.setContent { MaterialTheme { ArchiveDialog(vm, DeckLocation.ARCHIVE, dark) } }
        }
        rule.waitForIdle()
        val root = ShadowDialog.getLatestDialog().window!!.decorView
        val work = root.findViewWithTag<View>("archive-work")
        assertEquals(View.GONE, work.visibility)
        listOf("複製を作成中…", "共有用ZIPを作成中…").forEachIndexed { index, label ->
            rule.runOnUiThread { seed(vm, "libraryWorkLabel", label); seed(vm, "libraryBusy", true) }
            rule.waitForIdle()
            rule.runOnUiThread {
                val scroll = descendants(root).filterIsInstance<ScrollView>().single()
                scroll.fullScroll(View.FOCUS_DOWN)
                assertTrue(work.isShown)
                val spinner = root.findViewWithTag<ProgressBar>("archive-work-spinner")
                assertTrue(spinner.isShown); assertTrue(spinner.isIndeterminate)
                val text = root.findViewWithTag<TextView>("archive-work-text")
                assertEquals("$label\n完了までお待ちください。", text.text.toString())
                assertEquals(View.ACCESSIBILITY_LIVE_REGION_POLITE, text.accessibilityLiveRegion)
                assertTrue(descendants(root).filterIsInstance<Button>().all { !it.isEnabled })
                val p = IntArray(2); work.getLocationOnScreen(p)
                assertTrue("Indicator must remain inside the visible dialog", p[1] >= 0 && p[1] + work.height <= root.height)
                assertTrue(work.width > 0 && work.height >= 36)
                assertFalse(work.parent is ScrollView)
                root.jumpDrawablesToCurrentState()
                val bitmap = Bitmap.createBitmap(root.width, root.height, Bitmap.Config.ARGB_8888)
                ReflectionHelpers.callStaticMethod<Any?>(Class.forName("org.robolectric.shadows.HardwareRenderingScreenshot"), "takeScreenshot",
                    ClassParameter.from(View::class.java, root), ClassParameter.from(Bitmap::class.java, bitmap))
                val folder = File("build/test-screenshots").apply { mkdirs() }
                File(folder, "archive-work-${if (dark) "dark" else "light"}-$index.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
                bitmap.recycle()
            }
            rule.runOnUiThread { seed(vm, "libraryBusy", false); seed<String?>(vm, "libraryWorkLabel", null) }
            rule.waitForIdle()
            assertEquals(View.GONE, work.visibility)
            assertTrue(descendants(root).filterIsInstance<Button>().all { it.isEnabled })
        }
    }
    @Test fun busyIndicatorStaysVisibleAndControlsRecoverOnSmallDarkScreen() = checkIndicator(true)
    @Test @Config(qualifiers = "w720dp-h840dp-mdpi")
    fun busyIndicatorStaysVisibleAndControlsRecoverOnFoldLightScreen() = checkIndicator(false)
}
