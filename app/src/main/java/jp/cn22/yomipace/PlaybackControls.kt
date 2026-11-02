package jp.cn22.yomipace

import android.content.Context
import android.annotation.SuppressLint
import android.content.res.ColorStateList
import android.graphics.Color
import android.view.MotionEvent
import android.widget.SeekBar
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import kotlin.math.roundToInt

/** Pause on DOWN, including a tap on the track or a gesture that is later cancelled. */
// Constructed only by the Compose factory, with the active presentation model.
@SuppressLint("ViewConstructor")
internal class TimeSeekView(context: Context, private val vm: AppViewModel) : SeekBar(context) {
    private var tracking = false
    private var rangeSeconds = 1.0
    init {
        tag = "time-seek"
        contentDescription = "経過時間のシーク。操作すると停止します"
        max = 100_000
        progressTintList = ColorStateList.valueOf(Color.rgb(23, 100, 166))
        thumbTintList = progressTintList
        setOnSeekBarChangeListener(object : OnSeekBarChangeListener {
            override fun onStartTrackingTouch(bar: SeekBar) { beginSeek() }
            override fun onProgressChanged(bar: SeekBar, value: Int, fromUser: Boolean) {
                if (fromUser) vm.seekTo(value.toDouble() / max * rangeSeconds)
            }
            override fun onStopTrackingTouch(bar: SeekBar) { tracking = false; vm.persist() }
        })
    }
    private fun beginSeek() {
        if (tracking) return
        vm.pause()
        rangeSeconds = maxOf(vm.totalSeconds ?: 0.0, vm.elapsed, 1.0)
        tracking = true
    }
    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (isEnabled && event.actionMasked == MotionEvent.ACTION_DOWN) beginSeek()
        val result = super.onTouchEvent(event)
        if (event.actionMasked == MotionEvent.ACTION_CANCEL || event.actionMasked == MotionEvent.ACTION_UP) { tracking = false; vm.persist() }
        if (event.actionMasked == MotionEvent.ACTION_UP) performClick()
        return result
    }
    override fun performClick(): Boolean { super.performClick(); return true }
    fun refresh() {
        isEnabled = vm.timeline != null && !vm.busy && vm.editorDraft == null
        if (!tracking) {
            rangeSeconds = maxOf(vm.totalSeconds ?: 0.0, vm.elapsed, 1.0)
            progress = (vm.elapsed / rangeSeconds * max).roundToInt().coerceIn(0, max)
        }
    }
}

@Composable internal fun TimeSeekBar(vm: AppViewModel) {
    // Read observable values here so the native progress refreshes on every clock tick.
    val seconds = vm.elapsed
    val enabled = vm.timeline != null && !vm.busy
    AndroidView(factory = { TimeSeekView(it, vm) },
        modifier = Modifier.fillMaxWidth().height(36.dp).semantics { contentDescription = "経過時間のシーク ${formatTime(seconds)}${if (enabled) "" else "（無効）"}" },
        update = { it.refresh() })
}

@Composable internal fun DisplaySettingsDialog(vm: AppViewModel, dismiss: () -> Unit) {
    AlertDialog(onDismissRequest = dismiss, title = { Text("表示・操作設定") }, text = {
        Column(Modifier.fillMaxWidth().heightIn(max = 480.dp).verticalScroll(rememberScrollState())) {
            SettingSlider("持ち時間の文字サイズ", vm.totalFontSize.toFloat(), 24f..72f, "${vm.totalFontSize}sp") { vm.setTotalFont(it.roundToInt()) }
            SettingSlider("経過時間の文字サイズ", vm.elapsedFontSize.toFloat(), 24f..72f, "${vm.elapsedFontSize}sp") { vm.setElapsedFont(it.roundToInt()) }
            SettingSlider("開始・停止の文字サイズ", vm.controlFontSize.toFloat(), 12f..36f, "${vm.controlFontSize}sp") { vm.setControlFont(it.roundToInt()) }
            SettingSlider("開始・停止のボタンの高さ", vm.controlHeight.toFloat(), 48f..112f, "${vm.controlHeight}dp") { vm.changeControlHeight(it.roundToInt()) }
            SettingSlider("スクロールの慣性", vm.scrollInertia.toFloat(), 0f..1f, "${(vm.scrollInertia * 100).roundToInt()}%") { vm.changeScrollInertia(it.toDouble()) }
            Text("慣性0%は指を離すと停止、100%は長く滑らかに流れます。原稿欄だけに適用します。設定は自動保存します。")
        }
    }, confirmButton = { TextButton(onClick = dismiss) { Text("閉じる") } })
}

@Composable private fun SettingSlider(label: String, value: Float, range: ClosedFloatingPointRange<Float>, formatted: String, change: (Float) -> Unit) {
    Text("$label：$formatted")
    Slider(value = value, onValueChange = change, valueRange = range,
        modifier = Modifier.fillMaxWidth().semantics { contentDescription = label })
}
