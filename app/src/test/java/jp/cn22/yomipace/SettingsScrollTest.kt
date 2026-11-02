package jp.cn22.yomipace

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.lifecycle.ViewModelProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

@RunWith(AndroidJUnit4::class)
@Config(sdk = [34], qualifiers = "w360dp-h800dp-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class SettingsScrollTest {
    @get:Rule(order = 0) val cleanLaunch = object : org.junit.rules.ExternalResource() {
        override fun before() {
            androidx.test.core.app.ApplicationProvider.getApplicationContext<android.app.Application>()
                .getSharedPreferences("yomipace", 0).edit().clear().commit()
        }
    }
    @get:Rule(order = 1) val rule = createAndroidComposeRule<MainActivity>()

    private fun openSettings() {
        rule.onNodeWithText("⋮").performClick()
        rule.onNodeWithText("表示・操作設定").performClick()
        rule.onNodeWithTag("settings-scrollbar").assertIsDisplayed()
        rule.onNodeWithText("閉じる").assertIsDisplayed()
    }
    private fun position() = rule.onNodeWithTag("settings-scrollbar").fetchSemanticsNode().config[SemanticsProperties.ProgressBarRangeInfo]
    private fun seek(fraction: Float) {
        val range = position().range
        rule.onNodeWithTag("settings-scrollbar").performSemanticsAction(SemanticsActions.SetProgress) { it(range.endInclusive * fraction) }
        rule.waitForIdle()
    }
    private fun capture(name: String) {
        rule.waitForIdle()
        rule.runOnUiThread {
            val root = org.robolectric.shadows.ShadowDialog.getLatestDialog().window!!.decorView
            val bitmap = android.graphics.Bitmap.createBitmap(root.width, root.height, android.graphics.Bitmap.Config.ARGB_8888)
            org.robolectric.util.ReflectionHelpers.callStaticMethod<Any?>(
                Class.forName("org.robolectric.shadows.HardwareRenderingScreenshot"), "takeScreenshot",
                org.robolectric.util.ReflectionHelpers.ClassParameter.from(android.view.View::class.java, root),
                org.robolectric.util.ReflectionHelpers.ClassParameter.from(android.graphics.Bitmap::class.java, bitmap))
            val dir = File("build/test-screenshots").apply { mkdirs() }
            File(dir, "$name.png").outputStream().use { bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
            bitmap.recycle()
        }
    }

    @Test fun thumbDragAndTrackTapShowOnlyTheDirectionsWithMoreContent() {
        openSettings()
        rule.onNodeWithTag("settings-more-above").assertDoesNotExist()
        rule.onNodeWithTag("settings-more-below").assertIsDisplayed()
        assertEquals(0f, position().current, .01f)
        capture("settings-scroll-light-top")
        rule.onNodeWithTag("settings-scrollbar").performTouchInput {
            swipe(Offset(centerX, 6f), Offset(centerX, height * .45f), 300)
        }
        assertTrue(position().current > 0 && position().current < position().range.endInclusive)
        val dragged = position()
        rule.onNodeWithTag("settings-scrollbar").performTouchInput {
            val thumb = maxOf(28f, height * height / (height + dragged.range.endInclusive))
            click(Offset(centerX, (height - thumb) * dragged.current / dragged.range.endInclusive + thumb / 2))
        }
        assertEquals("Grabbing the thumb must not jump", dragged.current, position().current, 1f)
        rule.onNodeWithTag("settings-more-above").assertIsDisplayed()
        rule.onNodeWithTag("settings-more-below").assertIsDisplayed()
        capture("settings-scroll-light-middle")
        rule.onNodeWithTag("settings-scrollbar").performTouchInput { click(Offset(centerX, height - 1f)) }
        assertEquals(position().range.endInclusive, position().current, 1f)
        rule.onNodeWithTag("settings-more-below").assertDoesNotExist()
        rule.onNodeWithTag("settings-more-above").assertIsDisplayed()
        rule.onNodeWithContentDescription("スクロールの慣性").assertIsDisplayed()
        rule.onNodeWithTag("settings-scrollbar").assertIsDisplayed()
        capture("settings-scroll-light-bottom")
        // Existing setting changes must still reach the presentation model.
        rule.onNodeWithContentDescription("スクロールの慣性").performSemanticsAction(SemanticsActions.SetProgress) { it(.8f) }
        rule.runOnIdle { assertEquals(.8, ViewModelProvider(rule.activity)[AppViewModel::class.java].scrollInertia, .01) }
        rule.onNodeWithText("閉じる").performClick()
    }

    @Test @Config(qualifiers = "w840dp-h720dp-land-night-mdpi") fun darkExpandedDialogKeepsPositionAcrossColorPickerAndCloses() {
        openSettings()
        capture("settings-scroll-dark-expanded-top")
        seek(.5f)
        rule.onNodeWithContentDescription("残り時間の色").performScrollTo()
        val before = position().current
        rule.onNodeWithContentDescription("残り時間の色").performClick()
        rule.waitForIdle()
        rule.runOnUiThread { org.robolectric.shadows.ShadowAlertDialog.getLatestAlertDialog().getButton(android.app.AlertDialog.BUTTON_NEGATIVE).performClick() }
        rule.onNodeWithTag("settings-scrollbar").assertIsDisplayed()
        assertTrue(before > 0)
        assertEquals(before, position().current, 1f)
        seek(1f)
        rule.onNodeWithTag("settings-more-below").assertDoesNotExist()
        rule.onNodeWithTag("settings-more-above").assertIsDisplayed()
        capture("settings-scroll-dark-expanded-bottom")
        rule.onNodeWithText("閉じる").assertIsDisplayed().performClick()
        rule.onNodeWithText("開始").assertIsDisplayed()
    }

    @Test @Config(qualifiers = "w800dp-h360dp-land-mdpi") fun shortLandscapeCanReachTheLastSettingWithCloseAlwaysVisible() {
        openSettings()
        rule.onNodeWithTag("settings-more-below").assertIsDisplayed()
        seek(1f)
        rule.onNodeWithContentDescription("スクロールの慣性").assertIsDisplayed()
        rule.onNodeWithText("閉じる").assertIsDisplayed()
        rule.onNodeWithTag("settings-more-below").assertDoesNotExist()
        seek(0f)
        rule.onNodeWithTag("settings-more-above").assertDoesNotExist()
        rule.onNodeWithTag("settings-more-below").assertIsDisplayed()
        rule.onNodeWithText("閉じる").performClick()
    }
}
