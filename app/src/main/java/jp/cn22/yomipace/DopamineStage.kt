package jp.cn22.yomipace

import android.graphics.RectF
import android.os.SystemClock
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay

private val LocalEffectWindows = compositionLocalOf<MutableMap<String, Rect>?> { null }

internal fun Modifier.effectWindow(key: String): Modifier = composed {
    val regions = LocalEffectWindows.current
    DisposableEffect(regions, key) { onDispose { regions?.remove(key) } }
    onGloballyPositioned { coordinates -> regions?.set(key, coordinates.boundsInRoot()) }
}

@Composable internal fun DopamineStage(vm: AppViewModel, suspended: Boolean, content: @Composable () -> Unit) {
    val windows = remember { mutableStateMapOf<String, Rect>() }
    var root by remember { mutableStateOf(Rect.Zero) }
    val renderer = remember { DopamineRenderer() }
    val engine = vm.dopamine
    val available = vm.effectsForeground && !suspended && !vm.busy
    val enabled = vm.dopamineMode
    SideEffect {
        engine.configure(enabled, vm.dopamineVibration, available, vm.running, DopamineLevel.at(vm.elapsed, vm.totalSeconds))
        engine.slideNumber = vm.currentSlide; engine.slideCount = vm.deck?.images?.size ?: 0
    }
    DisposableEffect(engine) { onDispose { engine.reset(); engine.available = false } }
    val frameTime = remember { mutableLongStateOf(SystemClock.elapsedRealtime()) }
    LaunchedEffect(enabled, available, vm.running) {
        if (enabled && available) while (true) {
            val now = SystemClock.elapsedRealtime()
            val hadBursts = engine.bursts.isNotEmpty()
            engine.tick(now)
            if (vm.running || hadBursts || engine.bursts.isNotEmpty()) frameTime.longValue = now
            delay(33) // Bound animation to about 30 fps, including on high refresh-rate phones.
        }
    }
    CompositionLocalProvider(LocalEffectWindows provides windows) {
        Box(Modifier.fillMaxSize().onGloballyPositioned { root = it.boundsInRoot() }
            .pointerInput(enabled, available) {
                if (enabled && available) awaitPointerEventScope {
                    while (true) {
                        val event = awaitPointerEvent(PointerEventPass.Initial)
                        event.changes.firstOrNull { it.pressed && (!it.previousPressed || it.position != it.previousPosition) }?.let {
                            engine.touch(SystemClock.elapsedRealtime(), it.position.x / size.width.coerceAtLeast(1), it.position.y / size.height.coerceAtLeast(1))
                        }
                        // Observe only. Do not consume events belonging to the reader or controls.
                    }
                }
            }) {
            content()
            if (enabled && available) Canvas(Modifier.matchParentSize().testTag("dopamine-effects")) {
                val protected = windows.values.filter { it.width > 0 && it.height > 0 }.map {
                    RectF(it.left - root.left, it.top - root.top, it.right - root.left, it.bottom - root.top)
                }
                renderer.draw(drawContext.canvas.nativeCanvas, size.width, size.height, density, frameTime.longValue, engine, protected)
            }
        }
    }
}

@Composable internal fun ExperimentsDialog(vm: AppViewModel, dismiss: () -> Unit) {
    AlertDialog(onDismissRequest = dismiss, title = { Text("実験") }, text = {
        SettingsScrollPane(androidx.compose.foundation.rememberScrollState()) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("ドパガキモード")
                    Text("無音・振動とエフェクトで盛り上げる")
                }
                Switch(vm.dopamineMode, vm::changeDopamineMode, Modifier.semantics { contentDescription = "ドパガキモード" })
            }
            Text("開始直後から原色全開！ 虹色WELCOME、星柄の壁紙、流れる電光テロップ、熱量カウンターが古き良きインターネットを召喚。触ると連続花火、8コンボごとに大爆発、段落完了で三方向バースト。終盤は虹の渦と稲妻がさらに加速します。")
            Spacer(Modifier.height(12.dp))
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text("演出の振動", Modifier.weight(1f))
                Switch(vm.dopamineVibration, vm::changeDopamineVibration, Modifier.semantics { contentDescription = "演出の振動" }, enabled = vm.dopamineMode)
            }
            Text("このモード専用の振動設定です。「グレー表示のバイブ」とは別に保存します。原稿の背景も動きますが、文字・赤線・段落の進捗は暗い下地と縁取りを付けて最前面へ。時計は保護し、スライド番号は別の大きな表示でも確認できます。音は鳴りません。停止中は小さなタッチ反応だけ、設定画面・バックグラウンドでは休止します。")
        }
    }, confirmButton = { TextButton(onClick = dismiss) { Text("閉じる") } })
}
