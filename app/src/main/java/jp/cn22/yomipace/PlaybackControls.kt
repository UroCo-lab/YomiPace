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
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.Alignment
import androidx.compose.ui.graphics.Color as ComposeColor
import androidx.compose.ui.platform.LocalContext
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
    var colorField by remember { mutableStateOf<String?>(null) }
    val settingsScroll = rememberScrollState()
    if (colorField != null) {
        when (colorField) {
            "total" -> ClockColorDialog("持ち時間の色", vm.totalClockColor, { colorField = null }, vm::changeTotalClockColor)
            "elapsed" -> ClockColorDialog("経過時間の色", vm.elapsedClockColor, { colorField = null }, vm::changeElapsedClockColor)
            "remaining" -> ClockColorDialog("残り時間の色", vm.remainingClockColor, { colorField = null }, vm::changeRemainingClockColor)
        }
        return
    }
    AlertDialog(onDismissRequest = dismiss, title = { Text("表示・操作設定") }, text = {
        Column(Modifier.fillMaxWidth().heightIn(max = 480.dp).verticalScroll(settingsScroll)) {
            Text("原稿スクロールバー")
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                ReaderScrollbarPosition.entries.forEach { position ->
                    Row(Modifier.selectable(selected = vm.readerScrollbar == position, role = Role.RadioButton,
                        onClick = { vm.changeReaderScrollbar(position) }).padding(vertical = 8.dp, horizontal = 4.dp),
                        verticalAlignment = Alignment.CenterVertically) {
                        RadioButton(selected = vm.readerScrollbar == position, onClick = null)
                        Text(position.label)
                    }
                }
            }
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text("計測中のスリープ禁止", modifier = Modifier.weight(1f))
                Switch(checked = vm.keepScreenOnWhileRunning, onCheckedChange = vm::changeKeepScreenOn,
                    modifier = Modifier.semantics { contentDescription = "計測中のスリープ禁止" })
            }
            Text("ONで開始後の計測中は画面を点灯したままにします。停止中は端末の設定に従います。")
            ClockSetting("持ち時間", vm.totalFontSize, vm.totalClockColor, vm::setTotalFont) { colorField = "total" }
            ClockSetting("経過時間", vm.elapsedFontSize, vm.elapsedClockColor, vm::setElapsedFont) { colorField = "elapsed" }
            ClockSetting("残り時間", vm.remainingFontSize, vm.remainingClockColor, vm::setRemainingFont) { colorField = "remaining" }
            Text("横幅全体を設定した文字サイズの比率で配分し、各領域の中央に表示します（12・18・30なら2:3:5）。領域に収まらない文字は自動縮小します。設定値は上限として保存し、画面が広がれば元のサイズへ戻ります。")
            SettingSlider("開始・停止の文字サイズ", vm.controlFontSize.toFloat(), 12f..36f, "${vm.controlFontSize}sp") { vm.setControlFont(it.roundToInt()) }
            SettingSlider("開始・停止のボタンの高さ", vm.controlHeight.toFloat(), 48f..112f, "${vm.controlHeight}dp") { vm.changeControlHeight(it.roundToInt()) }
            SettingSlider("スクロールの慣性", vm.scrollInertia.toFloat(), 0f..1f, "${(vm.scrollInertia * 100).roundToInt()}%") { vm.changeScrollInertia(it.toDouble()) }
            Text("慣性0%は指を離すと停止、100%は長く滑らかに流れます。原稿欄だけに適用します。設定は自動保存します。")
        }
    }, confirmButton = { TextButton(onClick = dismiss) { Text("閉じる") } })
}

@Composable private fun ClockSetting(label: String, size: Int, color: String?, changeSize: (Int) -> Unit, chooseColor: () -> Unit) {
    SettingSlider("${label}の文字サイズ", size.toFloat(), 12f..72f, "${size}sp") { changeSize(it.roundToInt()) }
    TextButton(onClick = chooseColor, modifier = Modifier.semantics { contentDescription = "${label}の色" }) {
        val swatch = color?.let { ComposeColor(Color.parseColor(it)) } ?: MaterialTheme.colorScheme.onSurface
        Box(Modifier.size(18.dp).background(swatch).border(1.dp, MaterialTheme.colorScheme.outline))
        Spacer(Modifier.width(8.dp))
        Text("${label}の色：${color ?: "自動"}")
    }
}

@Composable private fun ClockColorDialog(title: String, current: String?, dismiss: () -> Unit, apply: (String?) -> Unit) {
    val base = LocalContext.current
    val dark = isSystemInDarkTheme()
    // Match the app's standard native font/time input dialogs, including IME behavior.
    DisposableEffect(title, dark) {
        val context = android.view.ContextThemeWrapper(base, if (dark) android.R.style.Theme_Material_Dialog_Alert else android.R.style.Theme_Material_Light_Dialog_Alert)
        fun px(value: Int) = (value * context.resources.displayMetrics.density).roundToInt()
        val body = android.widget.LinearLayout(context).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            setPadding(px(12), px(4), px(12), px(8))
        }
        lateinit var picker: android.app.AlertDialog
        fun choose(value: String?) { apply(value); picker.dismiss() }
        body.addView(android.widget.Button(context).apply {
            text = "自動（テーマに合わせる）"; isAllCaps = false; tag = "clock-color-auto"
            setOnClickListener { choose(null) }
        })
        listOf("赤" to "#C52E40", "青" to "#1764A6", "緑" to "#16804A", "オレンジ" to "#B85C00", "黒" to "#000000", "白" to "#FFFFFF").chunked(3).forEach { colors ->
            val row = android.widget.LinearLayout(context)
            colors.forEach { (label, value) ->
                row.addView(android.widget.Button(context).apply {
                    text = label; isAllCaps = false; tag = "clock-color-$label"
                    setOnClickListener { choose(value) }
                }, android.widget.LinearLayout.LayoutParams(0, -2, 1f))
            }
            body.addView(row)
        }
        body.addView(android.widget.TextView(context).apply { text = "カラーコード（#RRGGBB）"; textSize = 13f })
        val input = android.widget.EditText(context).apply {
            id = android.R.id.edit; contentDescription = "カラーコード"; setSingleLine(true)
            inputType = android.text.InputType.TYPE_CLASS_TEXT
            setText(current ?: "#1764A6")
        }
        body.addView(input)
        picker = android.app.AlertDialog.Builder(context).setTitle(title)
            .setView(android.widget.ScrollView(context).apply { addView(body) })
            .setPositiveButton("適用", null).setNegativeButton("キャンセル") { _, _ -> }.create()
        picker.setOnDismissListener { dismiss() }
        picker.show()
        picker.getButton(android.app.AlertDialog.BUTTON_POSITIVE).setOnClickListener {
            val value = normalizedClockColor(input.text.toString())
            if (value == null) input.error = "#と6桁の16進数で指定してください。" else choose(value)
        }
        onDispose { picker.setOnDismissListener(null); picker.dismiss() }
    }
}

@Composable private fun SettingSlider(label: String, value: Float, range: ClosedFloatingPointRange<Float>, formatted: String, change: (Float) -> Unit) {
    Text("$label：$formatted")
    Slider(value = value, onValueChange = change, valueRange = range,
        modifier = Modifier.fillMaxWidth().semantics { contentDescription = label })
}
