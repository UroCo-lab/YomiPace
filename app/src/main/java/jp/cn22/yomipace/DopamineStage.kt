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
import kotlin.math.roundToInt

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
    // Read snapshot state in composition. Reads inside SideEffect do not subscribe this
    // composable, so child clock redraws alone previously left the engine at its old level.
    val running = vm.running
    val level = DopamineLevel.at(vm.elapsed, vm.totalSeconds, vm.dopamineStrength, vm.dopamineRise)
    val vibration = vm.dopamineVibration
    val beat = vm.dopamineBeat
    val shade = vm.dopamineShade
    val slideNumber = vm.currentSlide
    val slideCount = vm.deck?.images?.size ?: 0
    val readerRegion = windows["reader"]?.takeIf { root.width > 0 && root.height > 0 && it.width > 0 && it.height > 0 }?.let {
        DopamineRegion((it.left - root.left) / root.width, (it.top - root.top) / root.height, it.width / root.width, it.height / root.height)
    }
    SideEffect {
        engine.configure(enabled, vibration, available, running, level, beat)
        engine.slideNumber = slideNumber; engine.slideCount = slideCount
        engine.shade = shade; engine.readerRegion = readerRegion
    }
    DisposableEffect(engine) { onDispose { engine.reset(); engine.available = false } }
    val frameTime = remember { mutableLongStateOf(SystemClock.elapsedRealtime()) }
    LaunchedEffect(enabled, available, running) {
        if (enabled && available) while (true) {
            val now = SystemClock.elapsedRealtime()
            val hadBursts = engine.bursts.isNotEmpty()
            engine.tick(now)
            if (running || hadBursts || engine.bursts.isNotEmpty()) frameTime.longValue = now
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
                fun regions(slides: Boolean) = windows.filter { (key, rect) -> key.startsWith("slide-") == slides && rect.width > 0 && rect.height > 0 }.values.map {
                    RectF(it.left - root.left, it.top - root.top, it.right - root.left, it.bottom - root.top)
                }
                renderer.draw(drawContext.canvas.nativeCanvas, size.width, size.height, density, frameTime.longValue, engine, regions(false), regions(true))
            }
        }
    }
}

@Composable internal fun ExperimentsDialog(vm: AppViewModel, dismiss: () -> Unit) {
    AlertDialog(onDismissRequest = dismiss, title = { Text("実験") }, text = {
        SettingsScrollPane(androidx.compose.foundation.rememberScrollState()) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("DOPAGAKIモード")
                    Text("無音・振動とエフェクトで盛り上げる")
                }
                Switch(vm.dopamineMode, vm::changeDopamineMode, Modifier.semantics { contentDescription = "DOPAGAKIモード" })
            }
            Text("触った場所が弾け、クラッカーの紙吹雪と風船が飛び出します。後半は打ち上げ花火も追加。序盤は少なく、終盤ほど数と勢いが増えます。時間を巻き戻すと風船・花火・コンボをリセット。その時間の強さへ戻ります。原稿を手でスクロールしても演出の段階は変わりません。")
            Spacer(Modifier.height(12.dp))
            Text("演出の強さ：${vm.dopamineStrength}%")
            Slider(value = vm.dopamineStrength.toFloat(), onValueChange = { vm.changeDopamineStrength(it.roundToInt()) },
                valueRange = 30f..100f, steps = 6, enabled = vm.dopamineMode,
                modifier = Modifier.semantics { contentDescription = "演出の強さ" })
            Text("終盤の最大の派手さを調整します。序盤はどの設定でも控えめです。")
            Text("演出のシェード：${vm.dopamineShade}%")
            Slider(value = vm.dopamineShade.toFloat(), onValueChange = { vm.changeDopamineShade(it.roundToInt()) },
                valueRange = 0f..100f, steps = 9, enabled = vm.dopamineMode,
                modifier = Modifier.semantics { contentDescription = "演出のシェード" })
            Text("0%は原色くっきり、100%は暗め。色の暗さだけを調整し、盛り上がる速さは変えません。原稿の文字下地と時計・進捗バーの保護は維持します。")
            Text("盛り上がりカーブ：${vm.dopamineRise.label}${if(vm.dopamineRise==DopamineRise.STANDARD) "（直線）" else ""}")
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                DopamineRise.entries.forEach { rise ->
                    FilterChip(selected = vm.dopamineRise == rise, onClick = { vm.changeDopamineRise(rise) },
                        label = { Text(rise.label) }, enabled = vm.dopamineMode)
                }
            }
            Canvas(Modifier.fillMaxWidth().height(86.dp).semantics { contentDescription = "盛り上がりカーブ。標準は直線。全て予定時間で設定した強さに到達" }) {
                val pad=12.dp.toPx();val w=size.width-2*pad;val h=size.height-2*pad
                drawLine(androidx.compose.ui.graphics.Color.Gray,androidx.compose.ui.geometry.Offset(pad,pad),androidx.compose.ui.geometry.Offset(pad,pad+h))
                drawLine(androidx.compose.ui.graphics.Color.Gray,androidx.compose.ui.geometry.Offset(pad,pad+h),androidx.compose.ui.geometry.Offset(pad+w,pad+h))
                val path=androidx.compose.ui.graphics.Path()
                for(i in 0..100) {
                    val y=pad+h*(1-DopamineLevel.at(i.toDouble(),100.0,vm.dopamineStrength,vm.dopamineRise).energy)
                    if(i==0)path.moveTo(pad,y) else path.lineTo(pad+w*i/100f,y)
                }
                drawPath(path,androidx.compose.ui.graphics.Color(0xFFFF17C8),style=androidx.compose.ui.graphics.drawscope.Stroke(3.dp.toPx()))
            }
            Text("横軸は経過時間、縦軸は演出の強さ。標準は直線、前半型は早め、後半型は終盤で伸びます。どれも予定時間で設定した強さへ到達します。100%なら予定時間で最大、超過後は最大を維持します。")
            Spacer(Modifier.height(12.dp))
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text("演出の振動", Modifier.weight(1f))
                Switch(vm.dopamineVibration, vm::changeDopamineVibration, Modifier.semantics { contentDescription = "演出の振動" }, enabled = vm.dopamineMode)
            }
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text("振動ビート", Modifier.weight(1f))
                Switch(vm.dopamineBeat, vm::changeDopamineBeat, Modifier.semantics { contentDescription = "振動ビート" },
                    enabled = vm.dopamineMode && vm.dopamineVibration)
            }
            Text("振動ビートON：72→180 BPMで加速し、4拍ごとに強拍を刻みます。タッチや段落完了の振動を重ねず、リズムを保ちます。OFF：タッチ・段落完了だけ振動します。「演出の振動」をOFFにすると両方止まります。")
            Text("招き猫、キリ番カウンター、工事中、相互リンク、WebRing、BBS、Sorry, Japanese only!で個人ホームページの時代へ。カウンターやリンクは遊びの表示です。原稿の文字・赤線・段落の進捗は従来どおり最前面へ。時計・進捗バーには演出を重ねず、スライド上は薄い装飾だけ。アプリは無音。停止中は小さなタッチ反応だけ、設定画面・バックグラウンドでは休止します。強さ・シェード・速さ・ビート設定は再起動後も保持します。")
        }
    }, confirmButton = { TextButton(onClick = dismiss) { Text("閉じる") } })
}
