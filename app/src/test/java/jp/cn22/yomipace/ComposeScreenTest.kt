package jp.cn22.yomipace

import android.graphics.Bitmap
import android.graphics.Canvas
import android.net.Uri
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
import android.os.Looper
import org.robolectric.Shadows.shadowOf
import java.time.Duration
import com.github.takahirom.roborazzi.captureRoboImage
import org.robolectric.shadows.ShadowAlertDialog
import android.widget.EditText
import android.app.AlertDialog
import org.robolectric.util.ReflectionHelpers
import org.robolectric.util.ReflectionHelpers.ClassParameter
import androidx.window.layout.FoldingFeature
import androidx.window.testing.layout.TestWindowLayoutInfo
import androidx.window.testing.layout.WindowLayoutInfoPublisherRule
import androidx.window.testing.layout.FoldingFeature as TestFoldingFeature

@RunWith(AndroidJUnit4::class)
@Config(sdk = [34], qualifiers = "w360dp-h800dp-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ComposeScreenTest {
    @get:Rule(order = 0) val cleanLaunch = object : org.junit.rules.ExternalResource() {
        override fun before() {
            androidx.test.core.app.ApplicationProvider.getApplicationContext<android.app.Application>()
                .getSharedPreferences("yomipace", 0).edit().clear().putBoolean(WelcomeSample.SETUP_COMPLETE, true).commit()
        }
    }
    @get:Rule(order = 1) val foldInfo = WindowLayoutInfoPublisherRule()
    @get:Rule(order = 2) val rule = createAndroidComposeRule<MainActivity>()
    @org.junit.After fun stopPresentationBeforeClosingActivity() {
        rule.runOnIdle { ViewModelProvider(rule.activity)[AppViewModel::class.java].let {
            it.pause(); it.clear(); it.changeDopamineMode(false); it.changeDopamineVibration(true)
            it.changeDopamineShade(0); it.changeDopamineStrength(100); it.changeDopamineRise(DopamineRise.STANDARD); it.changeDopamineBeat(true)
        } }
    }
    private fun loadSample(): AppViewModel {
        val vm = ViewModelProvider(rule.activity)[AppViewModel::class.java]
        rule.waitUntil(15_000) { shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(50)); !vm.importing }
        rule.runOnUiThread {
            vm.clear()
            vm.setFont(18); vm.setSwitch(2.0); if (vm.swapped) vm.swap()
            vm.setTotalFont(44); vm.setElapsedFont(44); vm.setControlFont(18); vm.changeControlHeight(52); vm.changeScrollInertia(.5)
            vm.setRemainingFont(44); vm.changeTotalClockColor(null); vm.changeElapsedClockColor(null); vm.changeRemainingClockColor(null); vm.changeKeepScreenOn(true)
            val sample = File(rule.activity.cacheDir, "YomiPace_Sample_001.zip")
            javaClass.getResourceAsStream("/sample.zip")!!.use { input -> sample.outputStream().use { input.copyTo(it) } }
            vm.importZip(Uri.fromFile(sample))
        }
        rule.waitUntil(15_000) { shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(50)); !vm.importing }
        rule.runOnIdle { assertNotNull(vm.deck); assertNull(vm.startProblem); vm.message = null }
        waitForImages()
        return vm
    }
    private fun waitForImages() {
        try { rule.waitUntil(15_000) {
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(50))
            // IO completion can precede snapshot notifications in the paused Robolectric looper.
            androidx.compose.runtime.snapshots.Snapshot.sendApplyNotifications()
            rule.waitForIdle()
            rule.onAllNodesWithText("…").fetchSemanticsNodes().isEmpty()
        } } catch (e: androidx.compose.ui.test.ComposeTimeoutException) {
            println(rule.onRoot(useUnmergedTree = true).printToString())
            throw e
        }
        rule.onAllNodesWithText("画像を読み込めません").assertCountEquals(0)
    }
    private fun screenshot(name: String) {
        waitForImages()
        rule.waitForIdle()
        rule.runOnUiThread {
            fun invalidateViews(view: android.view.View) {
                view.invalidate()
                if (view is android.view.ViewGroup) (0 until view.childCount).forEach { invalidateViews(view.getChildAt(it)) }
            }
            invalidateViews(rule.activity.window.decorView)
        }
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(400))
        rule.waitForIdle()
        val playBounds = rule.onNodeWithText("開始").fetchSemanticsNode().boundsInRoot
        val dir = File("build/test-screenshots").apply { mkdirs() }
        rule.runOnUiThread {
            val root = rule.activity.window.decorView
            val bitmap = Bitmap.createBitmap(root.width, root.height, Bitmap.Config.ARGB_8888)
            // Use the pinned Robolectric hardware renderer so Compose RenderNodes are included.
            ReflectionHelpers.callStaticMethod<Any?>(
                Class.forName("org.robolectric.shadows.HardwareRenderingScreenshot"), "takeScreenshot",
                ClassParameter.from(android.view.View::class.java, root), ClassParameter.from(Bitmap::class.java, bitmap)
            )
            File(dir, "$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
            var redPixels = 0
            for (y in playBounds.top.toInt().coerceAtLeast(0) until playBounds.bottom.toInt().coerceAtMost(bitmap.height)) {
                for (x in playBounds.left.toInt().coerceAtLeast(0) until playBounds.right.toInt().coerceAtMost(bitmap.width)) {
                    val color = bitmap.getPixel(x, y)
                    if (android.graphics.Color.red(color) > 130 && android.graphics.Color.green(color) < 100 && android.graphics.Color.blue(color) < 120) redPixels++
                }
            }
            // Experimental decoration may cover buttons; normal mode must retain their original appearance.
            if (!ViewModelProvider(rule.activity)[AppViewModel::class.java].dopamineMode)
                assertTrue("Play control exists in semantics but is covered in the rendered image", redPixels > 100)
            bitmap.recycle()
        }
    }
    private fun assertControlsAndReader(font: String = "18pt", switch: String = "2s") {
        val controls = listOf("開始", "停止", font, switch, "⋮").map { label ->
            try { rule.onNodeWithText(label).assertIsDisplayed().fetchSemanticsNode().boundsInRoot }
            catch (e: AssertionError) { throw AssertionError("Control not displayed: $label\n" + rule.onRoot(useUnmergedTree = true).printToString(), e) }
        }
        controls.zipWithNext().forEach { (a, b) -> assertTrue("Controls overlap: $a / $b", a.right <= b.left + 1f) }
        rule.runOnUiThread {
            fun readers(view: android.view.View): List<ScriptReaderView> = when (view) {
                is ScriptReaderView -> listOf(view)
                is android.view.ViewGroup -> (0 until view.childCount).flatMap { readers(view.getChildAt(it)) }
                else -> emptyList()
            }
            val reader = readers(rule.activity.window.decorView).single()
            assertTrue("Reader has no usable height: ${reader.width}x${reader.height}", reader.height >= 250)
            assertTrue("Reader has no usable width", reader.width >= 250)
        }
    }
    @Test fun experimentsToggleObserveReaderTouchAndStopOnPause() {
        val vm = loadSample()
        rule.runOnIdle { assertFalse(vm.dopamineMode) }
        rule.onNodeWithText("⋮").performClick()
        rule.onNodeWithText("実験").performClick()
        rule.onNodeWithContentDescription("DOPAGAKIモード").performClick()
        rule.onNodeWithText("閉じる").performClick()
        rule.onNodeWithTag("dopamine-effects").assertExists()
        rule.runOnIdle { assertTrue(vm.dopamineMode); assertTrue(vm.dopamineVibration) }
        rule.onNodeWithTag("script-viewport").performTouchInput { swipeUp(durationMillis = 500) }
        rule.runOnIdle { assertTrue("Reader touch did not reach effects", vm.dopamine.lastInteraction != Long.MIN_VALUE) }
        rule.onNodeWithText("開始").performClick()
        rule.runOnIdle { assertTrue(vm.running) }
        rule.onNodeWithText("停止").performClick()
        rule.runOnIdle { assertFalse(vm.dopamine.running); assertTrue(vm.dopamine.bursts.isEmpty()) }
        rule.runOnIdle { vm.changeDopamineVibration(false); vm.persist() }
        // Robolectric can retain an AndroidViewModel factory from another Application sandbox.
        val prefs = vm.getApplication<android.app.Application>().getSharedPreferences("yomipace", 0)
        assertTrue(prefs.getBoolean("dopamineMode", false)); assertFalse(prefs.getBoolean("dopamineVibration", true))
        rule.runOnIdle { vm.effectsForeground(false) }
        rule.onNodeWithTag("dopamine-effects").assertDoesNotExist()
    }

    @Test fun singleProgressTrackCanJumpBackwardAndForwardWithoutMovingReader() {
        val vm = loadSample()
        rule.onAllNodesWithTag("presentation-progress").assertCountEquals(1)
        rule.runOnIdle { vm.changeDopamineMode(true); vm.seekTo(410.0); vm.play() }
        val anchor = vm.anchor
        rule.runOnUiThread {
            fun seek(view: android.view.View): TimeSeekView? = when (view) {
                is TimeSeekView -> view
                is android.view.ViewGroup -> (0 until view.childCount).firstNotNullOfOrNull { seek(view.getChildAt(it)) }
                else -> null
            }
            val bar = requireNotNull(seek(rule.activity.window.decorView))
            fun tap(fraction: Float) {
                val now = android.os.SystemClock.uptimeMillis()
                val x = bar.paddingLeft + (bar.width - bar.paddingLeft - bar.paddingRight) * fraction
                bar.onTouchEvent(android.view.MotionEvent.obtain(now, now, 0, x, bar.height / 2f, 0))
                assertFalse(vm.running)
                bar.onTouchEvent(android.view.MotionEvent.obtain(now, now + 20, 1, x, bar.height / 2f, 0))
            }
            tap(.2f); assertEquals(84.0, vm.elapsed, 2.0); assertFalse(vm.running)
            assertTrue(vm.dopamine.level.energy < .26f); assertEquals(0,vm.dopamine.combo); assertTrue(vm.dopamine.bursts.isEmpty())
            tap(.8f); assertEquals(336.0, vm.elapsed, 2.0); assertFalse(vm.running)
            assertEquals(anchor, vm.anchor)
        }
        rule.onNodeWithText("開始").performClick()
        rule.runOnIdle { assertTrue(vm.running) }
    }

    @Test fun experimentStrengthRiseAndBeatCanBeChangedInSettingsAndPersist() {
        val vm=loadSample()
        rule.onNodeWithText("⋮").performClick(); rule.onNodeWithText("実験").performClick()
        rule.onNodeWithContentDescription("DOPAGAKIモード").performClick()
        rule.onNodeWithContentDescription("演出の強さ").performScrollTo()
            .performSemanticsAction(androidx.compose.ui.semantics.SemanticsActions.SetProgress) { it(60f) }
        rule.onNodeWithContentDescription("演出のシェード").performScrollTo()
            .performSemanticsAction(androidx.compose.ui.semantics.SemanticsActions.SetProgress) { it(30f) }
        rule.onNodeWithText("後半型").performScrollTo().performClick()
        rule.onNodeWithContentDescription("振動ビート").performScrollTo().performClick()
        rule.onNodeWithText("閉じる").performClick()
        rule.runOnIdle { assertEquals(30,vm.dopamineShade); assertEquals(60,vm.dopamineStrength); assertEquals(DopamineRise.SLOW,vm.dopamineRise); assertFalse(vm.dopamineBeat) }
        rule.activityRule.scenario.recreate()
        rule.runOnIdle {
            val restored=ViewModelProvider(rule.activity)[AppViewModel::class.java]
            assertEquals(30,restored.dopamineShade); assertEquals(60,restored.dopamineStrength); assertEquals(DopamineRise.SLOW,restored.dopamineRise); assertFalse(restored.dopamineBeat)
        }
    }

    private fun dopamineSnapshots(name: String, fold: Boolean) {
        val vm = loadSample()
        if (fold) {
            foldInfo.overrideWindowLayoutInfo(TestWindowLayoutInfo(listOf(TestFoldingFeature(rule.activity, state = FoldingFeature.State.FLAT, orientation = FoldingFeature.Orientation.VERTICAL))))
            rule.onNodeWithTag("two-column-layout").assertIsDisplayed(); waitForImages()
        }
        rule.runOnIdle { vm.changeDopamineMode(true); vm.changeDopamineVibration(false); vm.seekTo(0.0); vm.play() }
        rule.onNodeWithTag("dopamine-effects").assertIsDisplayed()
        screenshot("dopamine-$name-spark")
        rule.runOnIdle { vm.seekTo(410.0); vm.play() }
        screenshot("dopamine-$name-climax")
        rule.runOnIdle {
            val now=android.os.SystemClock.elapsedRealtime()
            vm.dopamine.touch(now-1100,.20f,.82f)
            vm.dopamine.touch(now-650,.50f,.80f)
            vm.dopamine.touch(now-250,.80f,.85f)
        }
        screenshot("dopamine-$name-party")
        rule.runOnIdle { vm.changeDopamineShade(70) }
        screenshot("dopamine-$name-shade70")
        rule.runOnIdle { vm.changeDopamineShade(0); assertTrue(vm.dopamine.level.energy > .9f); vm.pause() }
        if (fold) {
            rule.onNodeWithText("⇄").performClick(); waitForImages()
            rule.runOnIdle { vm.seekTo(410.0); vm.play() }
            screenshot("dopamine-$name-swapped")
            rule.runOnIdle { vm.pause(); vm.setFont(24); assertTrue(vm.dopamineMode) }
        }
        rule.runOnIdle { vm.changeDopamineMode(false) }
        rule.onNodeWithTag("dopamine-effects").assertDoesNotExist()
    }
    @Test fun dopamineCoverVisuals() = dopamineSnapshots("cover", false)
    @Test @Config(qualifiers = "w720dp-h840dp-port-night-mdpi") fun dopamineFoldDarkVisualsAndSwap() = dopamineSnapshots("fold-dark", true)

    @Test fun compactScreenMenuAndSettings() {
        val vm = loadSample()
        rule.onNodeWithTag("clock-total").assertIsDisplayed().assertTextEquals("7:00")
        assertControlsAndReader()
        rule.onNodeWithText("開始").assertIsEnabled().performClick()
        rule.runOnIdle { assertTrue(vm.running) }
        rule.onNodeWithText("開始").performClick()
        rule.runOnIdle { assertTrue(vm.running) }
        rule.onNodeWithText("停止").performClick()
        rule.runOnIdle { assertFalse(vm.running) }
        rule.onNodeWithText("18pt").performClick()
        rule.waitForIdle()
        rule.runOnUiThread {
            val dialog = ShadowAlertDialog.getLatestAlertDialog()
            assertEquals("フォントサイズ", shadowOf(dialog).title.toString())
            dialog.findViewById<EditText>(android.R.id.edit).setText("24")
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).performClick()
        }
        rule.onNodeWithText("24pt").assertIsDisplayed()
        rule.onNodeWithText("2s").performClick()
        rule.waitForIdle()
        rule.runOnUiThread {
            val dialog = ShadowAlertDialog.getLatestAlertDialog()
            assertEquals("スライド切替時間", shadowOf(dialog).title.toString())
            dialog.findViewById<EditText>(android.R.id.edit).setText("0.5")
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).performClick()
        }
        rule.runOnIdle { assertEquals(0.5, vm.switchSeconds, 0.0) }
        rule.onNodeWithText("0.5s").assertIsDisplayed()
        rule.onNodeWithText("⋮").performClick()
        rule.onNodeWithText("ZIPを開く").assertIsDisplayed()
        rule.onNodeWithText("クリア").assertIsDisplayed()
        rule.onNodeWithText("時間リセット").assertIsDisplayed()
        rule.onNodeWithText("使い方").performClick()
        rule.onNodeWithText("YomiPaceの使い方").assertIsDisplayed()
        rule.onNodeWithText("閉じる").performClick()
        rule.runOnIdle { vm.setFont(18); vm.setSwitch(2.0) }
        screenshot("compact-360x800")
    }
    @Test @Config(qualifiers = "w800dp-h720dp-land-mdpi") fun expandedScreenSwapsWholeColumnsAndKeepsClockAndPosition() {
        val vm = loadSample()
        assertControlsAndReader()
        val before = jp.cn22.yomipace.core.ReadingAnchor("s11-0", 3)
        rule.runOnIdle { vm.updateAnchor(before, 2); vm.play() }
        rule.onNodeWithText("⇄").assertIsDisplayed().performClick()
        rule.runOnIdle { assertTrue(vm.swapped); assertTrue(vm.running); assertEquals(before, vm.anchor) }
        rule.onNodeWithText("⇄").performClick()
        rule.runOnIdle { assertFalse(vm.swapped); vm.pause() }
        screenshot("expanded-800x720")
    }

    private fun stackedScreen(name: String) {
        loadSample()
        rule.onNodeWithTag("stacked-layout").assertIsDisplayed()
        rule.onNodeWithTag("two-column-layout").assertDoesNotExist()
        assertControlsAndReader()
        val slide = rule.onNodeWithContentDescription("現在のスライド 1").fetchSemanticsNode().boundsInRoot
        val play = rule.onNodeWithText("開始").fetchSemanticsNode().boundsInRoot
        val reader = rule.onNodeWithTag("script-viewport").fetchSemanticsNode().boundsInRoot
        assertTrue("Slides must stay above the controls", slide.bottom < play.top)
        assertTrue("Reader must use the remaining height", reader.top >= play.bottom && reader.bottom > play.bottom + 250f)
        screenshot(name)
    }
    @Test @Config(qualifiers = "w320dp-h640dp-port-mdpi") fun smallPhone() = stackedScreen("phone-320x640")
    @Test @Config(qualifiers = "w360dp-h960dp-port-mdpi") fun tallPhone() = stackedScreen("phone-360x960")
    @Test @Config(qualifiers = "w360dp-h800dp-port-xhdpi") fun highDensityPhone() = stackedScreen("phone-360x800-xhdpi")
    @Test @Config(qualifiers = "w600dp-h960dp-port-mdpi") fun smallTabletPortrait() = stackedScreen("tablet-600x960-portrait")
    @Test @Config(qualifiers = "w800dp-h1280dp-port-mdpi") fun tabletPortrait() = stackedScreen("tablet-800x1280-portrait")
    @Test @Config(qualifiers = "w800dp-h840dp-port-mdpi") fun nearlySquareTabletPortrait() = stackedScreen("tablet-800x840-portrait")

    @Test @Config(qualifiers = "w800dp-h1280dp-port-mdpi") fun rotationKeepsClockAndLogicalPosition() {
        val vm = loadSample()
        val before = jp.cn22.yomipace.core.ReadingAnchor("h2", 0)
        rule.runOnIdle { vm.updateAnchor(before, 2); vm.play() }
        waitForImages()
        val slideHeight = rule.onNodeWithContentDescription("現在のスライド 2").fetchSemanticsNode().boundsInRoot.height
        val readerHeight = rule.onNodeWithTag("script-viewport").fetchSemanticsNode().boundsInRoot.height
        org.robolectric.RuntimeEnvironment.setQualifiers("w800dp-h1440dp-port-mdpi")
        rule.activityRule.scenario.recreate()
        waitForImages()
        assertEquals("Extra screen height must not enlarge the slide", slideHeight, rule.onNodeWithContentDescription("現在のスライド 2").fetchSemanticsNode().boundsInRoot.height, 1f)
        assertEquals("Extra screen height must go to the reader", readerHeight + 160f, rule.onNodeWithTag("script-viewport").fetchSemanticsNode().boundsInRoot.height, 2f)
        org.robolectric.RuntimeEnvironment.setQualifiers("w1280dp-h800dp-land-mdpi")
        rule.activityRule.scenario.recreate()
        rule.onNodeWithTag("two-column-layout").assertIsDisplayed()
        rule.runOnIdle { assertSame(vm, ViewModelProvider(rule.activity)[AppViewModel::class.java]); assertTrue(vm.running); assertEquals(before, vm.anchor); assertEquals(2, vm.currentSlide) }
        org.robolectric.RuntimeEnvironment.setQualifiers("w800dp-h1280dp-port-mdpi")
        rule.activityRule.scenario.recreate()
        rule.onNodeWithTag("stacked-layout").assertIsDisplayed()
        assertControlsAndReader()
        rule.runOnIdle { assertTrue(vm.running); assertEquals(before, vm.anchor); vm.pause() }
    }

    private fun scrollingSlides(name: String) {
        val vm = loadSample()
        rule.onNodeWithTag("two-column-layout").assertIsDisplayed()
        assertControlsAndReader()
        val list = rule.onNodeWithTag("slide-list").fetchSemanticsNode().boundsInRoot
        val image = rule.onNodeWithContentDescription("現在のスライド 1").fetchSemanticsNode().boundsInRoot
        assertEquals("The slide must fill the column width", list.width - 26f, image.width, 2f)
        assertEquals("The slide must retain its 16:9 viewport", 16f / 9f, image.width / image.height, 0.01f)
        val before = vm.anchor
        rule.onNodeWithTag("slide-list").performScrollToIndex(2)
        waitForImages()
        rule.onNodeWithContentDescription("スライド 3").assertIsDisplayed()
        rule.runOnIdle { assertEquals("Preview scrolling must not move the script or current slide", before, vm.anchor); assertEquals(1, vm.currentSlide) }
        rule.runOnIdle { vm.updateAnchor(jp.cn22.yomipace.core.ReadingAnchor("h2", 0), 2) }
        rule.waitForIdle()
        val current = rule.onNodeWithTag("slide-card-2").fetchSemanticsNode().boundsInRoot
        assertEquals("A new script slide must be aligned to the top", list.top + 8f, current.top, 2f)
        rule.runOnIdle { vm.updateAnchor(jp.cn22.yomipace.core.ReadingAnchor("h3", 0), 3) }
        rule.waitForIdle()
        val last = rule.onNodeWithTag("slide-card-3").fetchSemanticsNode().boundsInRoot
        assertEquals("The final slide must also align to the top", list.top + 8f, last.top, 2f)
        rule.onNodeWithText("⇄").performClick()
        rule.runOnIdle { assertEquals(3, vm.currentSlide); assertEquals("h3", vm.anchor.itemId) }
        rule.onNodeWithText("⇄").performClick()
        rule.runOnIdle { vm.updateAnchor(jp.cn22.yomipace.core.ReadingAnchor("h1", 0), 1) }
        rule.waitForIdle()
        rule.runOnUiThread {
            fun findReader(view: android.view.View): ScriptReaderView? = when (view) {
                is ScriptReaderView -> view
                is android.view.ViewGroup -> (0 until view.childCount).firstNotNullOfOrNull { findReader(view.getChildAt(it)) }
                else -> null
            }
            assertEquals("The native reader must match the current slide", vm.anchor, ReflectionHelpers.getField<jp.cn22.yomipace.core.ReadingAnchor>(findReader(rule.activity.window.decorView)!!, "anchor"))
        }
        screenshot(name)
    }
    @Test @Config(qualifiers = "w1024dp-h600dp-land-mdpi") fun tabletLandscapeWithScrollableFullWidthSlides() = scrollingSlides("tablet-1024x600-landscape")
    @Test @Config(qualifiers = "w1280dp-h800dp-land-mdpi") fun largeTabletLandscapeWithScrollableFullWidthSlides() = scrollingSlides("tablet-1280x800-landscape")

    @Test @Config(qualifiers = "w720dp-h840dp-port-mdpi") fun unfoldedPortraitKeepsTwoColumnsAndClockAcrossFoldUpdates() {
        val vm = loadSample()
        rule.onNodeWithTag("stacked-layout").assertIsDisplayed()
        val before = jp.cn22.yomipace.core.ReadingAnchor("h2", 0)
        rule.runOnIdle { vm.updateAnchor(before, 2); vm.play() }
        foldInfo.overrideWindowLayoutInfo(TestWindowLayoutInfo(listOf(TestFoldingFeature(rule.activity, state = FoldingFeature.State.FLAT, orientation = FoldingFeature.Orientation.VERTICAL))))
        rule.onNodeWithTag("two-column-layout").assertIsDisplayed()
        assertControlsAndReader()
        rule.runOnIdle { assertTrue(vm.running); assertEquals(before, vm.anchor); assertEquals(2, vm.currentSlide) }
        screenshot("fold-main-720x840-portrait")
        foldInfo.overrideWindowLayoutInfo(TestWindowLayoutInfo(emptyList()))
        rule.onNodeWithTag("stacked-layout").assertIsDisplayed()
        rule.runOnIdle { assertTrue(vm.running); assertEquals(before, vm.anchor); vm.pause() }
    }
    @Test @Config(qualifiers = "w360dp-h840dp-night-mdpi") fun actualNineSlideZipInDarkCoverLayout() {
        val path = System.getProperty("yomipace.regressionZip")
        org.junit.Assume.assumeTrue("Local nine-slide regression fixture is not present", path != null)
        val vm = ViewModelProvider(rule.activity)[AppViewModel::class.java]
        rule.waitUntil(15_000) { shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(50)); !vm.importing }
        rule.runOnUiThread { vm.importZip(Uri.fromFile(File(path!!))) }
        rule.waitUntil(15_000) { shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(50)); !vm.importing }
        rule.runOnIdle {
            assertEquals(9, vm.deck?.images?.size)
            vm.message = null
            assertNull(vm.setTotal(240.0))
            assertNull(vm.startProblem)
        }
        assertControlsAndReader()
        rule.onNodeWithText("開始").performClick()
        rule.onNodeWithText("停止").performClick()
        rule.runOnIdle { assertFalse(vm.running) }
        screenshot("nine-slide-dark-cover-360x840")
    }

    @Test fun settingsSlidersAndPromptCopyWorkFromMenu() {
        val vm = loadSample()
        rule.onNodeWithText("⋮").performClick()
        rule.onNodeWithText("表示・操作設定").performClick()
        rule.onNodeWithContentDescription("持ち時間の文字サイズ").performScrollTo().performSemanticsAction(androidx.compose.ui.semantics.SemanticsActions.SetProgress) { it(60f) }
        rule.onNodeWithContentDescription("経過時間の文字サイズ").performScrollTo().performSemanticsAction(androidx.compose.ui.semantics.SemanticsActions.SetProgress) { it(64f) }
        rule.onNodeWithContentDescription("開始・停止の文字サイズ").performScrollTo().performSemanticsAction(androidx.compose.ui.semantics.SemanticsActions.SetProgress) { it(28f) }
        rule.onNodeWithContentDescription("開始・停止のボタンの高さ").performScrollTo().performSemanticsAction(androidx.compose.ui.semantics.SemanticsActions.SetProgress) { it(88f) }
        rule.onNodeWithContentDescription("スクロールの慣性").performScrollTo().performSemanticsAction(androidx.compose.ui.semantics.SemanticsActions.SetProgress) { it(0f) }
        rule.onNodeWithText("閉じる").performClick()
        rule.runOnIdle {
            assertEquals(60, vm.totalFontSize); assertEquals(64, vm.elapsedFontSize)
            assertEquals(28, vm.controlFontSize); assertEquals(88, vm.controlHeight); assertEquals(0.0, vm.scrollInertia, .0001)
        }
        rule.onNodeWithText("⋮").performClick()
        rule.onNodeWithText("使い方").performClick()
        rule.onNodeWithText("PPTXから読み込み用ZIPを作る").assertIsDisplayed()
        rule.onNodeWithText("コピー").performClick()
        rule.onNodeWithText("コピーしました").assertIsDisplayed()
        rule.runOnIdle {
            val clipboard = rule.activity.getSystemService(android.content.Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
            assertEquals(ZIP_CREATION_PROMPT, clipboard.primaryClip!!.getItemAt(0).text.toString())
        }
        rule.onNodeWithText("閉じる").performClick()
    }

    private fun largeSettingsAndSeek(name: String, fold: Boolean) {
        val vm = loadSample()
        val position = jp.cn22.yomipace.core.ReadingAnchor("h2", 0)
        rule.runOnIdle {
            vm.updateAnchor(position, 2)
            vm.setTotalFont(72); vm.setElapsedFont(72); vm.setControlFont(36); vm.changeControlHeight(112)
            vm.seekTo(250.0); vm.play()
        }
        rule.waitForIdle()
        rule.runOnUiThread {
            fun findSeek(view: android.view.View): TimeSeekView? = when (view) {
                is TimeSeekView -> view
                is android.view.ViewGroup -> (0 until view.childCount).firstNotNullOfOrNull { findSeek(view.getChildAt(it)) }
                else -> null
            }
            val seek = findSeek(rule.activity.window.decorView)!!
            val start = android.os.SystemClock.uptimeMillis()
            seek.onTouchEvent(android.view.MotionEvent.obtain(start, start, android.view.MotionEvent.ACTION_DOWN, seek.width / 4f, seek.height / 2f, 0))
            assertFalse(vm.running)
            seek.onTouchEvent(android.view.MotionEvent.obtain(start, start + 80, android.view.MotionEvent.ACTION_MOVE, seek.width / 5f, seek.height / 2f, 0))
            seek.onTouchEvent(android.view.MotionEvent.obtain(start, start + 100, android.view.MotionEvent.ACTION_UP, seek.width / 5f, seek.height / 2f, 0))
            assertFalse(vm.running); assertTrue(vm.elapsed < 250.0)
            assertEquals(position, vm.anchor)
        }
        val elapsed = vm.elapsed
        val bounds = rule.onNodeWithText("開始").assertIsDisplayed().fetchSemanticsNode().boundsInRoot
        val stop = rule.onNodeWithText("停止").assertIsDisplayed().fetchSemanticsNode().boundsInRoot
        assertTrue(bounds.right <= stop.left)
        screenshot(name)
        val textTargets = listOf(rule.onNodeWithText("開始"), rule.onNodeWithText("停止")) +
            listOf("clock-total", "clock-elapsed", "clock-remaining").map { rule.onNodeWithTag(it) }
        for (target in textTargets) {
            val layouts = mutableListOf<androidx.compose.ui.text.TextLayoutResult>()
            target.performSemanticsAction(androidx.compose.ui.semantics.SemanticsActions.GetTextLayoutResult) { it(layouts) }
            assertFalse("Text clipped: ${layouts.single().layoutInput.text} ${layouts.single().size}", layouts.single().hasVisualOverflow)
        }
        assertTrue(rule.onNodeWithTag("script-viewport").fetchSemanticsNode().boundsInRoot.height >= 100f)
        if (fold) {
            foldInfo.overrideWindowLayoutInfo(TestWindowLayoutInfo(listOf(TestFoldingFeature(rule.activity, state = FoldingFeature.State.FLAT, orientation = FoldingFeature.Orientation.VERTICAL))))
            rule.onNodeWithTag("two-column-layout").assertIsDisplayed()
            rule.onNodeWithText("⇄").performClick()
            rule.runOnIdle { assertEquals(position, vm.anchor); assertEquals(elapsed, vm.elapsed, .01); assertFalse(vm.running); vm.setFont(32) }
            screenshot("large-settings-fold-swapped")
            foldInfo.overrideWindowLayoutInfo(TestWindowLayoutInfo(emptyList()))
            rule.onNodeWithTag("stacked-layout").assertIsDisplayed()
        }
        rule.onNodeWithText("開始").performClick()
        rule.runOnIdle { assertTrue(vm.running); assertTrue(vm.elapsed >= elapsed) }
        rule.onNodeWithText("停止").performClick()
    }
    @Test @Config(qualifiers = "w320dp-h640dp-port-mdpi") fun maximumSettingsOnSmallCover() = largeSettingsAndSeek("large-settings-cover-320x640", false)
    @Test @Config(qualifiers = "w720dp-h840dp-port-mdpi") fun seekSurvivesFoldSwapAndFontChange() = largeSettingsAndSeek("large-settings-stacked-720x840", true)

    @Test @Config(qualifiers = "w800dp-h360dp-land-mdpi") fun shortLandscapeKeepsControlsAndReaderWithMaximumSettings() {
        val vm = loadSample()
        rule.runOnIdle { vm.setTotalFont(72); vm.setElapsedFont(72); vm.setControlFont(36); vm.changeControlHeight(112) }
        screenshot("short-landscape-maximum-settings")
        rule.onNodeWithText("開始").assertIsDisplayed()
        rule.onNodeWithText("停止").assertIsDisplayed()
        val viewport = rule.onNodeWithTag("script-viewport").fetchSemanticsNode().boundsInRoot
        assertTrue("Short landscape lost the manuscript: $viewport", viewport.height >= 80f)
        rule.onNodeWithText("時計欄を上下にスクロール").assertIsDisplayed()
        rule.onNodeWithTag("presentation-progress").performScrollTo().assertIsDisplayed()
        rule.onNodeWithText("開始").assertIsDisplayed()
        rule.onNodeWithText("停止").assertIsDisplayed()
        screenshot("short-landscape-clock-scrolled")
    }

    @Test fun failedSlideLoadShowsAnErrorAndCanRetryWithoutChangingPresentationState() {
        val vm = loadSample()
        val image = vm.deck!!.images[2]
        val original = image.readBytes()
        rule.runOnIdle { image.writeText("invalid image"); vm.updateAnchor(jp.cn22.yomipace.core.ReadingAnchor("h3"), 3) }
        rule.waitUntil(15_000) {
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(50))
            rule.onAllNodesWithText("画像を読み込めません").fetchSemanticsNodes().isNotEmpty()
        }
        rule.onNodeWithText("画像を読み込めません").assertIsDisplayed()
        rule.runOnIdle { image.writeBytes(original) }
        rule.onNodeWithText("再読込").performClick()
        waitForImages()
        rule.onNodeWithContentDescription("現在のスライド 3").assertIsDisplayed()
        rule.runOnIdle { assertEquals("h3", vm.anchor.itemId); assertEquals(0.0, vm.elapsed, .001); assertFalse(vm.running) }
    }

    @Test @Config(qualifiers = "w720dp-h840dp-port-mdpi") fun scrollbarSettingWorksAcrossFoldSwapAndReaderFontChanges() {
        val vm = loadSample()
        val anchor = jp.cn22.yomipace.core.ReadingAnchor("h2")
        rule.runOnIdle { vm.updateAnchor(anchor, 2); vm.seekTo(45.0); vm.play() }
        fun choose(label: String, side: ReaderScrollbarPosition) {
            rule.onNodeWithText("⋮").performClick()
            rule.onNodeWithText("表示・操作設定").performClick()
            rule.onNodeWithText("原稿スクロールバー").assertIsDisplayed()
            rule.onNodeWithText(label).performClick()
            rule.onNodeWithText(label).assertIsSelected()
            rule.onNodeWithText("閉じる").performClick()
            rule.runOnIdle { assertEquals(side, vm.readerScrollbar); assertEquals(anchor, vm.anchor); assertTrue(vm.running) }
        }
        choose("右", ReaderScrollbarPosition.RIGHT)
        rule.runOnIdle { vm.pause() }
        val elapsed = vm.elapsed
        screenshot("scrollbar-right-stacked")
        rule.runOnIdle { vm.play() }
        foldInfo.overrideWindowLayoutInfo(TestWindowLayoutInfo(listOf(TestFoldingFeature(rule.activity, state = FoldingFeature.State.FLAT, orientation = FoldingFeature.Orientation.VERTICAL))))
        rule.onNodeWithText("⇄").performClick()
        rule.runOnIdle { vm.setFont(32) }
        choose("なし", ReaderScrollbarPosition.NONE)
        rule.runOnIdle { vm.pause() }
        screenshot("scrollbar-hidden-fold-swapped")
        rule.runOnIdle { vm.play() }
        choose("左", ReaderScrollbarPosition.LEFT)
        rule.runOnIdle { assertTrue(vm.elapsed >= elapsed); vm.pause() }
        screenshot("scrollbar-left-fold-swapped")
        foldInfo.overrideWindowLayoutInfo(TestWindowLayoutInfo(emptyList()))
        rule.onNodeWithTag("stacked-layout").assertIsDisplayed()
        rule.runOnIdle { assertEquals(ReaderScrollbarPosition.LEFT, vm.readerScrollbar); assertEquals(anchor, vm.anchor) }
    }

    @Test fun threeClockFieldsFillTheRowInSavedFontRatiosWithoutScrolling() {
        val vm = loadSample()
        rule.runOnIdle {
            vm.seekTo(65.0)
            vm.setTotalFont(24); vm.setElapsedFont(32); vm.setRemainingFont(40)
            vm.changeTotalClockColor("#123ABC"); vm.changeElapsedClockColor("#C52E40"); vm.changeRemainingClockColor("#16804A")
        }
        val tags = listOf("clock-total", "clock-elapsed", "clock-remaining")
        fun layout(tag: String): androidx.compose.ui.text.TextLayoutResult {
            val results = mutableListOf<androidx.compose.ui.text.TextLayoutResult>()
            rule.onNodeWithTag(tag).performSemanticsAction(androidx.compose.ui.semantics.SemanticsActions.GetTextLayoutResult) { it(results) }
            return results.single()
        }
        rule.onNodeWithTag("clock-total").assertTextEquals("7:00").assertIsDisplayed()
        rule.onNodeWithTag("clock-elapsed").assertTextEquals("1:05").assertIsDisplayed()
        rule.onNodeWithTag("clock-remaining").assertTextEquals("5:55").assertIsDisplayed()
        rule.runOnIdle { vm.seekTo(65.25) }
        rule.onNodeWithTag("clock-elapsed").assertTextEquals("1:05")
        rule.onNodeWithTag("clock-remaining").assertTextEquals("5:55")
        val before = tags.map(::layout)
        assertTrue(before.zipWithNext().all { (a, b) -> a.size.width < b.size.width })
        val expectedColors = listOf("#123ABC", "#C52E40", "#16804A")
        val baselines = tags.mapIndexed { index, tag ->
            assertFalse(before[index].hasVisualOverflow)
            assertEquals(androidx.compose.ui.graphics.Color(android.graphics.Color.parseColor(expectedColors[index])), before[index].layoutInput.style.color)
            rule.onNodeWithTag(tag).fetchSemanticsNode().boundsInRoot.top + before[index].firstBaseline
        }
        assertTrue("Clocks must share a horizontal baseline: $baselines", baselines.max() - baselines.min() <= 1f)
        screenshot("clock-three-fields-custom")
        rule.runOnIdle { vm.setTotalFont(72) }
        assertTrue(layout("clock-total").size.width > before[0].size.width)
        assertTrue(layout("clock-elapsed").size.width < before[1].size.width)
        assertTrue(layout("clock-remaining").size.width < before[2].size.width)
        rule.runOnIdle { vm.setElapsedFont(72); vm.setRemainingFont(72) }
        rule.onNodeWithText("時計欄を左右にスクロール").assertDoesNotExist()
        assertFalse(rule.onNodeWithTag("clock-times").fetchSemanticsNode().config.contains(androidx.compose.ui.semantics.SemanticsProperties.HorizontalScrollAxisRange))
        val viewport = rule.onNodeWithTag("clock-times").fetchSemanticsNode().boundsInRoot
        val boxes = tags.map { rule.onNodeWithTag(it).assertIsDisplayed().fetchSemanticsNode().boundsInRoot }
        assertEquals(viewport.left, boxes.first().left, 1f)
        assertEquals(viewport.right, boxes.last().right, 1f)
        assertTrue(boxes.zipWithNext().all { (a, b) -> a.right <= b.left })
        assertTrue(layout("clock-remaining").layoutInput.style.fontSize.value <= 72f)
        assertTrue(layout("clock-total").layoutInput.style.fontSize.value < 72f)
        assertTrue(layout("clock-elapsed").layoutInput.style.fontSize.value < 72f)
        tags.forEach { tag ->
            assertFalse(layout(tag).hasVisualOverflow)
        }
        screenshot("clock-priority-remaining")
        rule.runOnIdle { vm.setTotalFont(72) }
        assertTrue(layout("clock-total").layoutInput.style.fontSize.value <= 72f)
        assertTrue(layout("clock-elapsed").layoutInput.style.fontSize.value < 72f)
        assertTrue(layout("clock-remaining").layoutInput.style.fontSize.value < 72f)
        screenshot("clock-priority-total")
        rule.runOnIdle { vm.setElapsedFont(72) }
        assertTrue(layout("clock-elapsed").layoutInput.style.fontSize.value <= 72f)
        assertTrue(layout("clock-total").layoutInput.style.fontSize.value < 72f)
        assertTrue(layout("clock-remaining").layoutInput.style.fontSize.value < 72f)
        screenshot("clock-priority-elapsed")
        rule.runOnIdle { vm.seekTo(421.0) }
        rule.onNodeWithTag("clock-remaining").assertTextEquals("−0:01")
        assertEquals(androidx.compose.ui.graphics.Color(android.graphics.Color.parseColor("#16804A")), layout("clock-remaining").layoutInput.style.color)
        rule.runOnIdle { vm.seekTo(60.0) }
        rule.onNodeWithTag("clock-remaining").assertTextEquals("6:00")
        rule.runOnIdle { vm.setTotalFont(12); vm.setElapsedFont(18); vm.setRemainingFont(30) }
        val ratios = listOf(2f, 3f, 5f)
        val proportional = tags.map { rule.onNodeWithTag(it).fetchSemanticsNode().boundsInRoot }
        val unit = proportional.sumOf { it.width.toDouble() }.toFloat() / ratios.sum()
        tags.forEachIndexed { index, tag ->
            val box = proportional[index]
            val heading = rule.onNodeWithTag("$tag-label").fetchSemanticsNode().boundsInRoot
            assertEquals(unit * ratios[index], box.width, 1f)
            assertEquals(box.center.x, heading.center.x, 1f)
            assertEquals(androidx.compose.ui.text.style.TextAlign.Center, layout(tag).layoutInput.style.textAlign)
            assertEquals(listOf(12f, 18f, 30f)[index], layout(tag).layoutInput.style.fontSize.value, .01f)
            assertFalse(layout(tag).hasVisualOverflow)
        }
        assertEquals(viewport.left, proportional.first().left, 1f)
        assertEquals(viewport.right, proportional.last().right, 1f)
        screenshot("clock-ratio-2-3-5")
    }

    @Test @Config(qualifiers = "w720dp-h840dp-port-mdpi") fun proportionalClocksSurviveFoldSwapAndWidthChanges() {
        val vm = loadSample()
        rule.runOnIdle { vm.setTotalFont(12); vm.setElapsedFont(18); vm.setRemainingFont(30); vm.seekTo(65.0) }
        fun check() {
            val weights = listOf(12f, 18f, 30f)
            val tags = listOf("clock-total", "clock-elapsed", "clock-remaining")
            val boxes = tags.map { rule.onNodeWithTag(it).assertIsDisplayed().fetchSemanticsNode().boundsInRoot }
            val viewport = rule.onNodeWithTag("clock-times").fetchSemanticsNode().boundsInRoot
            val width = boxes.sumOf { it.width.toDouble() }.toFloat()
            assertEquals(viewport.left, boxes.first().left, 1f)
            assertEquals(viewport.right, boxes.last().right, 1f)
            assertTrue(boxes.zipWithNext().all { (a, b) -> a.right <= b.left })
            tags.forEachIndexed { index, tag ->
                assertEquals(width * weights[index] / weights.sum(), boxes[index].width, 1f)
                val results = mutableListOf<androidx.compose.ui.text.TextLayoutResult>()
                rule.onNodeWithTag(tag).performSemanticsAction(androidx.compose.ui.semantics.SemanticsActions.GetTextLayoutResult) { it(results) }
                assertFalse(results.single().hasVisualOverflow)
            }
            rule.runOnIdle { assertEquals(65.0, vm.elapsed, .001); assertEquals(12, vm.totalFontSize); assertEquals(18, vm.elapsedFontSize); assertEquals(30, vm.remainingFontSize) }
        }
        check()
        foldInfo.overrideWindowLayoutInfo(TestWindowLayoutInfo(listOf(TestFoldingFeature(rule.activity, state = FoldingFeature.State.FLAT, orientation = FoldingFeature.Orientation.VERTICAL))))
        rule.onNodeWithTag("two-column-layout").assertIsDisplayed()
        check()
        waitForImages()
        rule.onNodeWithText("⇄").performClick()
        check()
        screenshot("clock-ratio-fold-swapped")
        foldInfo.overrideWindowLayoutInfo(TestWindowLayoutInfo(emptyList()))
        org.robolectric.RuntimeEnvironment.setQualifiers("w320dp-h800dp-port-mdpi")
        rule.activityRule.scenario.recreate()
        check()
        screenshot("clock-ratio-cover-320")
    }

    @Test fun clockColorPickersAndSleepSwitchApplyWhileRunning() {
        val vm = loadSample()
        fun screenHeld() = (rule.activity.window.attributes.flags and android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON) != 0
        rule.runOnIdle { assertTrue(vm.keepScreenOnWhileRunning); assertFalse(screenHeld()) }
        rule.onNodeWithText("開始").performClick()
        rule.runOnIdle { assertTrue(screenHeld()) }
        rule.onNodeWithText("⋮").performClick()
        rule.onNodeWithText("表示・操作設定").performClick()
        rule.onNodeWithContentDescription("計測中のスリープ禁止").assertIsOn().performClick().assertIsOff()
        rule.runOnIdle { assertTrue(vm.running); assertFalse(screenHeld()) }
        rule.onNodeWithContentDescription("持ち時間の色").performScrollTo().performClick()
        rule.waitForIdle()
        rule.runOnUiThread { ShadowAlertDialog.getLatestAlertDialog().window!!.decorView.findViewWithTag<android.widget.Button>("clock-color-赤").performClick() }
        rule.onNodeWithContentDescription("経過時間の色").performScrollTo().performClick()
        rule.waitForIdle()
        rule.runOnUiThread {
            val picker = ShadowAlertDialog.getLatestAlertDialog()
            val input = picker.findViewById<EditText>(android.R.id.edit)
            input.setText("#XXYYZZ"); picker.getButton(AlertDialog.BUTTON_POSITIVE).performClick()
            assertEquals("#と6桁の16進数で指定してください。", input.error.toString())
            input.setText("#123ABC"); picker.getButton(AlertDialog.BUTTON_POSITIVE).performClick()
        }
        rule.onNodeWithContentDescription("残り時間の色").performScrollTo().performClick()
        rule.waitForIdle()
        rule.runOnUiThread { ShadowAlertDialog.getLatestAlertDialog().window!!.decorView.findViewWithTag<android.widget.Button>("clock-color-緑").performClick() }
        rule.onNodeWithContentDescription("残り時間の文字サイズ").performScrollTo().performSemanticsAction(androidx.compose.ui.semantics.SemanticsActions.SetProgress) { it(56f) }
        rule.onNodeWithText("閉じる").performClick()
        rule.runOnIdle {
            assertTrue(vm.running); assertFalse(vm.keepScreenOnWhileRunning); assertFalse(screenHeld())
            assertEquals("#C52E40", vm.totalClockColor); assertEquals("#123ABC", vm.elapsedClockColor)
            assertEquals("#16804A", vm.remainingClockColor); assertEquals(56, vm.remainingFontSize)
            vm.changeKeepScreenOn(true)
        }
        rule.runOnIdle { assertTrue(screenHeld()); vm.seekTo(80.0) }
        rule.runOnIdle { assertFalse(screenHeld()); assertFalse(vm.running) }
        screenshot("clock-colors-and-sleep-setting")
        rule.onNodeWithText("開始").performClick()
        rule.runOnIdle { assertTrue(screenHeld()) }
        rule.onNodeWithText("停止").performClick()
        rule.runOnIdle { assertFalse(screenHeld()) }
    }
}
