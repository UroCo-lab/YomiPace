package jp.cn22.yomipace

import android.graphics.BitmapFactory
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.res.Configuration
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.window.layout.FoldingFeature
import androidx.window.layout.WindowInfoTracker
import jp.cn22.yomipace.core.ImportedDeck
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.map
import java.io.File
import java.util.Locale

class MainActivity : ComponentActivity() {
    private val model: AppViewModel by viewModels()
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            val foldMainScreen by remember {
                WindowInfoTracker.getOrCreate(this).windowLayoutInfo(this).map { info -> info.displayFeatures.any { it is FoldingFeature } }
            }.collectAsState(initial = false)
            val dark = isSystemInDarkTheme()
            val scheme = if (dark) darkColorScheme(primary = Color(0xFF91C6FA), error = Color(0xFFFF969F)) else lightColorScheme(primary = Color(0xFF1764A6), error = Color(0xFFC52E40), background = Color(0xFFF7F8FA), surface = Color.White)
            MaterialTheme(colorScheme = scheme) {
                DisposableEffect(model.running, model.keepScreenOnWhileRunning) {
                    if (model.running && model.keepScreenOnWhileRunning) window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON) else window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                    onDispose { window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON) }
                }
                YomiPaceScreen(model, dark, foldMainScreen)
            }
        }
    }
    override fun onStop() { model.persist(); super.onStop() }
    override fun onResume() { super.onResume(); model.effectsForeground(true) }
    override fun onPause() { model.effectsForeground(false); super.onPause() }
}

@Composable
fun YomiPaceScreen(vm: AppViewModel, dark: Boolean, foldMainScreen: Boolean = false) {
    var dialog by rememberSaveable { mutableStateOf<String?>(null) }
    var menuOpen by remember { mutableStateOf(false) }
    var controlsHeight by remember { mutableStateOf(220.dp) }
    val density = LocalDensity.current
    val slideListState = rememberLazyListState()
    val landscape = LocalConfiguration.current.orientation == Configuration.ORIENTATION_LANDSCAPE
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> uri?.let(vm::importZip) }
    fun openZip() {
        if (vm.running) vm.message = "ZIPを開く前に時計を停止してください。"
        else if (!vm.importing) picker.launch(arrayOf("application/zip", "application/x-zip-compressed", "application/octet-stream"))
    }
    DopamineStage(vm, dialog != null || menuOpen || vm.libraryLocation != null || vm.editorDraft != null || vm.message != null) {
    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(Modifier.fillMaxSize().safeDrawingPadding()) {
            Row(Modifier.fillMaxWidth().height(30.dp).padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("YomiPace", fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.primary)
                if (vm.dopamineMode) Text(" ⚡実験", fontSize = 10.sp, color = MaterialTheme.colorScheme.primary)
                Spacer(Modifier.width(12.dp))
                Text(vm.deck?.name ?: "資料未選択", fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f), color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            BoxWithConstraints(Modifier.weight(1f).fillMaxWidth()) {
                val expanded = maxWidth >= 600.dp && (landscape || foldMainScreen)
                // Keep the slide size tied to width; extra height belongs to the reader.
                // Only unusually short windows shrink the preview to keep the controls usable.
                val currentSlideWidth = (maxWidth - 21.dp) * 0.75f
                val collapsedSlideHeight = minOf(currentSlideWidth * (9f / 16f) + 28.dp, (maxHeight - controlsHeight - 250.dp).coerceAtLeast(0.dp))
                LaunchedEffect(vm.deck?.directory?.absolutePath, vm.currentSlide, expanded) {
                    if (expanded && vm.deck != null) slideListState.scrollToItem((vm.currentSlide - 1).coerceIn(vm.deck!!.images.indices))
                }
                if (expanded) {
                    Row(Modifier.fillMaxSize().testTag("two-column-layout")) {
                        if (!vm.swapped) SlideList(vm.deck, vm.currentSlide, slideListState, Modifier.weight(1f).fillMaxHeight()) else ReaderColumn(vm, dark, Modifier.weight(1f).fillMaxHeight(), { dialog = it }, ::openZip, onMenuChanged = { menuOpen = it })
                        Box(Modifier.width(40.dp).fillMaxHeight(), contentAlignment = Alignment.Center) {
                            Box(Modifier.width(1.dp).fillMaxHeight().background(MaterialTheme.colorScheme.outlineVariant))
                            TextButton(onClick = vm::swap, modifier = Modifier.width(40.dp).height(48.dp).background(MaterialTheme.colorScheme.surface), contentPadding = PaddingValues(0.dp)) {
                                Text("⇄", fontSize = 23.sp, modifier = Modifier.semantics { contentDescription = "左右入替" })
                            }
                        }
                        if (!vm.swapped) ReaderColumn(vm, dark, Modifier.weight(1f).fillMaxHeight(), { dialog = it }, ::openZip, onMenuChanged = { menuOpen = it }) else SlideList(vm.deck, vm.currentSlide, slideListState, Modifier.weight(1f).fillMaxHeight())
                    }
                } else {
                    Column(Modifier.fillMaxSize().testTag("stacked-layout")) {
                        CompactSlides(vm.deck, vm.currentSlide, Modifier.fillMaxWidth().height(collapsedSlideHeight))
                        ReaderColumn(vm, dark, Modifier.weight(1f).fillMaxWidth(), { dialog = it }, ::openZip, { controlsHeight = with(density) { it.toDp() } }, { menuOpen = it })
                    }
                }
            }
        }
    }
    }
    val currentDialog = dialog
    if (currentDialog != null) {
        when (currentDialog) {
            "font" -> NumberDialog("フォントサイズ", vm.fontSize.toString(), "サイズ（8～72pt）", "Androidのspで表示し、端末の文字倍率を反映します。赤線の原稿位置と時計を保持します。", { dialog = null }) { raw ->
                val n = raw.toIntOrNull()
                if (n == null || n !in 8..72) "8～72の整数を入力してください。" else { vm.setFont(n); null }
            }
            "switch" -> NumberDialog("スライド切替時間", compactNumber(vm.switchSeconds), "秒（0、小数も可）", if (vm.running) "計測中です。停止してから適用してください。" else "全スライド共通。切替はスライド枚数−1回です。経過時間は保持します。", { dialog = null }, !vm.running) { raw ->
                val n = raw.toDoubleOrNull()
                if (n == null) "0以上の秒数を入力してください。" else vm.setSwitch(n)
            }
            "time" -> TimeDialog(vm, { dialog = null })
            "help" -> HelpDialog { dialog = null }
            "display" -> DisplaySettingsDialog(vm) { dialog = null }
            "experiments" -> ExperimentsDialog(vm) { dialog = null }
            "vibration" -> VibrationSettingsDialog(vm, dark) { dialog = null }
            "clear" -> AlertDialog(onDismissRequest = { dialog = null }, title = { Text("資料をクリア") }, text = { Text("表示中の資料と時計を未選択状態に戻します。アーカイブ・元のZIP・表示設定は保持します。") }, confirmButton = { TextButton(onClick = { vm.clear(); dialog = null }) { Text("クリア") } }, dismissButton = { TextButton(onClick = { dialog = null }) { Text("閉じる") } })
            "reset" -> AlertDialog(onDismissRequest = { dialog = null }, title = { Text("時間リセット") }, text = { Text("時計を0に戻して停止します。資料・設定・赤線の原稿位置は保持します。") }, confirmButton = { TextButton(onClick = { vm.resetTime(); dialog = null }) { Text("時間リセット") } }, dismissButton = { TextButton(onClick = { dialog = null }) { Text("閉じる") } })
        }
    }
    vm.libraryLocation?.let { ArchiveDialog(vm, it, dark) }
    if (vm.editorDraft != null) ScriptEditorDialog(vm, dark)
    vm.message?.let { msg -> AlertDialog(onDismissRequest = { vm.message = null }, title = { Text("お知らせ") }, text = { Text(msg, modifier = Modifier.heightIn(max = 400.dp).verticalScroll(rememberScrollState())) }, confirmButton = { TextButton(onClick = { vm.message = null }) { Text("閉じる") } }) }
}

@Composable
private fun ReaderColumn(vm: AppViewModel, dark: Boolean, modifier: Modifier, show: (String) -> Unit, openZip: () -> Unit, onControlsHeight: (Int) -> Unit = {}, onMenuChanged: (Boolean) -> Unit = {}) {
    var menu by remember { mutableStateOf(false) }
    DisposableEffect(menu) { onMenuChanged(menu); onDispose { onMenuChanged(false) } }
    BoxWithConstraints(modifier) {
    val clockScroll = rememberScrollState()
    val density = LocalDensity.current
    val buttonHeight = maxOf(vm.controlHeight.dp, with(density) { (vm.controlFontSize * 1.3f).sp.toDp() } + 12.dp)
    val problemHeight = if (vm.deck != null && vm.startProblem != null) 48.dp else 0.dp
    // Preserve usable controls and a manuscript viewport even in short landscape windows.
    val clockLimit = (maxHeight - buttonHeight - problemHeight - 108.dp).coerceAtLeast(36.dp)
    Column(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxWidth().onSizeChanged { onControlsHeight(it.height) }) {
        Box(Modifier.fillMaxWidth().heightIn(max = clockLimit).verticalScroll(clockScroll).testTag("clock-scroll")) { ClockPanel(vm) }
        if (clockScroll.maxValue > 0) Text("時計欄を上下にスクロール", fontSize = 10.sp, modifier = Modifier.padding(horizontal = 10.dp), color = MaterialTheme.colorScheme.onSurfaceVariant)
        Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 2.dp), horizontalArrangement = Arrangement.spacedBy(2.dp), verticalAlignment = Alignment.CenterVertically) {
            ToolButton("開始", Modifier.weight(1f), Color(0xFFC52E40), Color.White, !vm.importing && vm.startProblem == null, vm.controlFontSize, vm.controlHeight, vm::play)
            ToolButton("停止", Modifier.weight(1f), if (dark) Color(0xFF454C55) else Color(0xFFE3E5E8), if (dark) Color.White else Color(0xFF3D444E), true, vm.controlFontSize, vm.controlHeight, vm::pause)
            ToolButton("${vm.fontSize}pt", Modifier.width(46.dp), onClick = { show("font") })
            ToolButton("${compactNumber(vm.switchSeconds)}s", Modifier.width(44.dp), onClick = { show("switch") })
            Box {
                ToolButton("⋮", Modifier.width(32.dp).semantics { contentDescription = "メニュー" }, onClick = { menu = true })
                DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                    DropdownMenuItem(text = { Text("ZIPを開く") }, enabled = !vm.importing, onClick = { menu = false; openZip() })
                    DropdownMenuItem(text = { Text("読み上げ原稿の編集") }, enabled = vm.deck != null && !vm.busy, onClick = { menu = false; vm.beginScriptEdit() })
                    DropdownMenuItem(text = { Text("アーカイブ") }, enabled = !vm.busy, onClick = { menu = false; vm.showLibrary(DeckLocation.ARCHIVE) })
                    DropdownMenuItem(text = { Text("ゴミ箱") }, enabled = !vm.busy, onClick = { menu = false; vm.showLibrary(DeckLocation.TRASH) })
                    DropdownMenuItem(text = { Text("グレー表示のバイブ") }, onClick = { menu = false; show("vibration") })
                    DropdownMenuItem(text = { Text("表示・操作設定") }, onClick = { menu = false; show("display") })
                    DropdownMenuItem(text = { Text("実験") }, onClick = { menu = false; show("experiments") })
                    DropdownMenuItem(text = { Text("持ち時間") }, onClick = { menu = false; show("time") })
                    DropdownMenuItem(text = { Text("クリア") }, enabled = !vm.importing, onClick = { menu = false; show("clear") })
                    DropdownMenuItem(text = { Text("使い方") }, onClick = { menu = false; show("help") })
                    HorizontalDivider()
                    DropdownMenuItem(text = { Text("時間リセット", color = MaterialTheme.colorScheme.error) }, onClick = { menu = false; show("reset") })
                }
            }
        }
        if (vm.busy) LinearProgressIndicator(Modifier.fillMaxWidth())
        val problem = vm.startProblem
        if (vm.deck != null && problem != null && !vm.importing) {
            Text(problem, fontSize = 11.sp, lineHeight = 15.sp, color = MaterialTheme.colorScheme.error, modifier = Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 3.dp))
        }
        }
        Box(Modifier.weight(1f).fillMaxWidth().effectWindow("reader").testTag("script-viewport").border(1.dp, MaterialTheme.colorScheme.outlineVariant).background(MaterialTheme.colorScheme.surface)) {
            if (vm.deck == null) {
                Column(Modifier.align(Alignment.Center).padding(20.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("発表のペースを、手元に。", fontSize = 17.sp, color = MaterialTheme.colorScheme.primary)
                    Spacer(Modifier.height(12.dp))
                    Text("スライド画像とscript.txtをまとめたZIPを、メニューから開いてください。", fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    TextButton(onClick = openZip, enabled = !vm.importing) { Text("ZIPを開く") }
                }
            } else {
                AndroidView(factory = { context -> ScriptReaderView(context).also { it.onAnchorChanged = vm::updateAnchor } }, modifier = Modifier.fillMaxSize().clipToBounds(), update = {
                    it.onAnchorChanged = vm::updateAnchor
                    it.bindDopamine(vm.dopamine)
                    it.bind(vm.deck?.script, vm.timeline, vm.fontSize, vm.elapsed, vm.anchor, dark, vm.scrollInertia, vm.readerScrollbar)
                })
            }
        }
    }
}
}

@Composable
private fun ClockPanel(vm: AppViewModel) {
    val total = vm.totalSeconds
    val overtime = total != null && vm.elapsed > total
    Column(Modifier.fillMaxWidth().effectWindow("progress-panel").padding(horizontal = 10.dp, vertical = 4.dp)) {
        ClockTimes(vm)
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(if (vm.deck == null) "スライド — / —" else "スライド ${vm.currentSlide} / ${vm.deck!!.images.size}", fontSize = 11.sp)
            if (total == null || overtime) Text(if (total == null) "時間未設定" else "超過 +${formatTime(vm.elapsed - total)}", fontSize = 11.sp, color = if (overtime) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant)
        }
        TimeSeekBar(vm)
        Text(if (vm.importing) "資料を読み込み中" else if (vm.running) "計測中" else "停止中", fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

private data class ClockItem(val label: String, val text: String, val size: Int, val color: Color, val tag: String)

@Composable private fun ClockTimes(vm: AppViewModel) {
    val measurer = rememberTextMeasurer()
    val density = LocalDensity.current
    val remaining = vm.totalSeconds?.minus(vm.elapsed)
    val overtime = remaining != null && remaining < 0
    val normal = MaterialTheme.colorScheme.onSurface
    val automatic = if (overtime) MaterialTheme.colorScheme.error else normal
    fun chosen(saved: String?, fallback: Color) = saved?.let { Color(android.graphics.Color.parseColor(it)) } ?: fallback
    val items = listOf(
        ClockItem("持ち時間", vm.totalSeconds?.let(::formatTime) ?: "—:—", vm.totalFontSize, chosen(vm.totalClockColor, normal), "clock-total"),
        ClockItem("経過時間", formatTime(vm.elapsed), vm.elapsedFontSize, chosen(vm.elapsedClockColor, automatic), "clock-elapsed"),
        ClockItem("残り時間", remaining?.let { (if (it < 0) "−" else "") + formatTime(if (it > 0) kotlin.math.ceil(it) else it) } ?: "—:—", vm.remainingFontSize, chosen(vm.remainingClockColor, automatic), "clock-remaining")
    )
    val labelStyle = MaterialTheme.typography.bodySmall.copy(fontSize = 11.sp, lineHeight = 14.sp, letterSpacing = 0.sp)
    val valueStyle = MaterialTheme.typography.bodyLarge.copy(fontWeight = FontWeight.Medium, letterSpacing = 0.sp)
    BoxWithConstraints(Modifier.fillMaxWidth().effectWindow("clocks").testTag("clock-times")) {
        if (maxWidth <= 0.dp) return@BoxWithConstraints
        val gap = minOf(8.dp, maxWidth / 12)
        fun measuredWidth(index: Int, size: Float, labels: Float): Float {
            val labelWidth = measurer.measure(AnnotatedString(items[index].label), labelStyle.copy(fontSize = (11f * labels).sp)).size.width
            val valueWidth = measurer.measure(AnnotatedString(items[index].text), valueStyle.copy(fontSize = size.sp)).size.width
            return maxOf(labelWidth, valueWidth) + 2f
        }
        val fitting = remember(items.map { it.text to it.size }, maxWidth, density, labelStyle, valueStyle, measurer) {
            val available = with(density) { maxWidth.toPx() - 2 * gap.roundToPx() - 3f }.coerceAtLeast(1f)
            fitClockFonts(items.map { it.size.toFloat() }, available, ::measuredWidth)
        }
        val fittedLabels = labelStyle.copy(fontSize = (11f * fitting.labelScale).sp, lineHeight = (14f * fitting.labelScale).sp)
        val styles = fitting.sizes.map { valueStyle.copy(fontSize = it.sp, lineHeight = (it * 1.15f).sp) }
        Column(Modifier.fillMaxWidth()) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(gap)) {
                items.forEach { item -> Text(item.label, Modifier.weight(item.size.toFloat()).testTag("${item.tag}-label"),
                    textAlign = androidx.compose.ui.text.style.TextAlign.Center, style = fittedLabels, maxLines = 1, softWrap = false) }
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(gap)) {
                items.forEachIndexed { index, item ->
                    Text(item.text, Modifier.weight(item.size.toFloat()).alignByBaseline().testTag(item.tag),
                        textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                        style = styles[index], color = item.color, maxLines = 1, softWrap = false)
                }
            }
        }
    }
}

@Composable private fun ToolButton(label: String, modifier: Modifier = Modifier, background: Color = MaterialTheme.colorScheme.surface, foreground: Color = MaterialTheme.colorScheme.onSurface, enabled: Boolean = true, fontSize: Int = 12, height: Int = 48, onClick: () -> Unit) {
    val density = LocalDensity.current
    val measurer = rememberTextMeasurer()
    val textHeight = with(density) { (fontSize * 1.3f).sp.toDp() } + 12.dp
    Button(onClick = onClick, enabled = enabled, modifier = modifier.height(maxOf(height.dp, textHeight)), shape = RoundedCornerShape(7.dp), contentPadding = PaddingValues(horizontal = 1.dp, vertical = 0.dp), colors = ButtonDefaults.buttonColors(containerColor = background, contentColor = foreground, disabledContainerColor = background.copy(alpha = .45f), disabledContentColor = foreground.copy(alpha = .65f))) {
        BoxWithConstraints(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
            val measured = measurer.measure(AnnotatedString(label), MaterialTheme.typography.labelLarge.copy(fontSize = fontSize.sp, letterSpacing = 0.sp)).size.width
            val size = fontSize * minOf(1f, (with(density) { maxWidth.toPx() } - 2f).coerceAtLeast(1f) / measured.coerceAtLeast(1))
            Text(label, modifier = Modifier.fillMaxWidth(), textAlign = androidx.compose.ui.text.style.TextAlign.Center, fontSize = size.sp, lineHeight = (size * 1.3f).sp, letterSpacing = 0.sp, softWrap = false, maxLines = 1)
        }
    }
}

@Composable private fun CompactSlides(deck: ImportedDeck?, current: Int, modifier: Modifier) {
    Row(modifier.padding(horizontal = 8.dp, vertical = 3.dp), horizontalArrangement = Arrangement.spacedBy(5.dp)) {
        Slide(deck?.images?.getOrNull(current - 1), "現在のスライド", current, Modifier.weight(3f).fillMaxHeight())
        Column(Modifier.weight(1f).fillMaxHeight(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Slide(deck?.images?.getOrNull(current), "次", current + 1, Modifier.weight(1f).fillMaxWidth(), true)
            Slide(deck?.images?.getOrNull(current + 1), "その次", current + 2, Modifier.weight(1f).fillMaxWidth(), true)
        }
    }
}

@Composable private fun SlideList(deck: ImportedDeck?, current: Int, state: androidx.compose.foundation.lazy.LazyListState, modifier: Modifier) {
    BoxWithConstraints(modifier) {
        val labelHeight = with(LocalDensity.current) { 13.sp.toDp() }
        val cardHeight = (maxWidth - 26.dp) * (9f / 16f) + labelHeight + 10.dp
        // Allow even the last slide to align at the top when the script advances.
        val bottom = (maxHeight - cardHeight - 8.dp).coerceAtLeast(8.dp)
        LazyColumn(Modifier.fillMaxSize().testTag("slide-list"), state = state, contentPadding = PaddingValues(start = 8.dp, top = 8.dp, end = 8.dp, bottom = bottom), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            if (deck == null) item { Slide(null, "現在のスライド", 1, Modifier.fillMaxWidth(), fullWidth = true) }
            else itemsIndexed(deck.images, key = { index, _ -> "${deck.directory.name}-${index + 1}" }) { index, file ->
                val number = index + 1
                Slide(file, if (number == current) "現在のスライド" else "スライド", number, Modifier.fillMaxWidth().testTag("slide-card-$number"), fullWidth = true)
            }
        }
    }
}

private data class SlideImageLoad(val image: ImageBitmap? = null, val failed: Boolean = false)

@Composable private fun Slide(file: File?, label: String, number: Int, modifier: Modifier, tiny: Boolean = false, fullWidth: Boolean = false) {
    var retry by remember(file?.absolutePath) { mutableIntStateOf(0) }
    // A distinct state per file prevents a cancelled previous load from clearing a new image.
    val loading = remember(file?.absolutePath, retry) { mutableStateOf(SlideImageLoad()) }
    LaunchedEffect(file?.absolutePath, retry) {
            if (file == null) return@LaunchedEffect
            repeat(2) { attempt ->
                val decoded = withContext(Dispatchers.IO) {
                    runCatching {
                        val encoded = file.readBytes()
                        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                        BitmapFactory.decodeByteArray(encoded, 0, encoded.size, bounds)
                        var sample = 1
                        while (bounds.outWidth / sample > 1600 || bounds.outHeight / sample > 1600) sample *= 2
                        BitmapFactory.decodeByteArray(encoded, 0, encoded.size, BitmapFactory.Options().apply { inSampleSize = sample })?.asImageBitmap()
                    }.getOrNull()
                }
                if (decoded != null) { loading.value = SlideImageLoad(decoded); return@LaunchedEffect }
                if (attempt == 0) kotlinx.coroutines.delay(100)
            }
            loading.value = SlideImageLoad(failed = true)
    }
    val loaded by loading
    Column(modifier.effectWindow("slide-$label-$number").background(MaterialTheme.colorScheme.surface, RoundedCornerShape(5.dp)).border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(5.dp)).padding(if (tiny) 2.dp else 5.dp)) {
        Text(if (file == null) "$label —" else "$label · $number", fontSize = if (tiny) 8.sp else 10.sp, lineHeight = if (tiny) 10.sp else 13.sp, color = MaterialTheme.colorScheme.primary, maxLines = 1)
        val imageModifier = if (fullWidth) Modifier.fillMaxWidth().aspectRatio(16f / 9f) else Modifier.weight(1f).fillMaxWidth()
        Box(imageModifier, contentAlignment = Alignment.Center) {
            if (loaded.image != null) Image(loaded.image!!, "$label $number", Modifier.fillMaxSize(), contentScale = ContentScale.Fit)
            else if (loaded.failed) Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text("画像を読み込めません", fontSize = if (tiny) 8.sp else 11.sp, color = MaterialTheme.colorScheme.error)
                TextButton(onClick = { retry++ }, contentPadding = PaddingValues(0.dp)) { Text("再読込", fontSize = 11.sp) }
            }
            else Text(if (file == null) "—" else "…", color = MaterialTheme.colorScheme.outline, fontSize = 12.sp)
        }
    }
}

fun compactNumber(n: Double): String = java.math.BigDecimal.valueOf(n).stripTrailingZeros().toPlainString()

@Composable private fun NumberDialog(title: String, initial: String, label: String, note: String, dismiss: () -> Unit, canApply: Boolean = true, apply: (String) -> String?) {
    NativeInputDialog(title, listOf(label to initial), note, dismiss, canApply, true) { apply(it.single().trim()) }
}

@Composable private fun TimeDialog(vm: AppViewModel, dismiss: () -> Unit) {
    val minutes = vm.totalSeconds?.let { (it.toLong() / 60).toString() } ?: ""
    val seconds = vm.totalSeconds?.let { (it.toLong() % 60).toString().padStart(2, '0') } ?: "00"
    NativeInputDialog("持ち時間", listOf("分" to minutes, "秒" to seconds), if (vm.running) "停止してから適用してください。" else "画面の設定を今回の計測に使います。経過時間は保持します。", dismiss, !vm.running, false) { values ->
        val m = values[0].trim().toLongOrNull(); val s = values[1].trim().toIntOrNull()
        if (m == null || m !in 0..1_000_000 || s == null || s !in 0..59) "分は0以上、秒は00～59で入力してください。" else vm.setTotal(m * 60.0 + s)
    }
}

/** Android's standard input widgets provide the popup's keyboard, cursor and error behavior. */
@Composable private fun NativeInputDialog(title: String, values: List<Pair<String, String>>, note: String, dismiss: () -> Unit, canApply: Boolean, decimal: Boolean, apply: (List<String>) -> String?) {
    val baseContext = LocalContext.current
    val dark = isSystemInDarkTheme()
    DisposableEffect(title) {
        val context = android.view.ContextThemeWrapper(baseContext, if (dark) android.R.style.Theme_Material_Dialog_Alert else android.R.style.Theme_Material_Light_Dialog_Alert)
        fun px(value: Int) = (value * context.resources.displayMetrics.density).toInt()
        val body = android.widget.LinearLayout(context).apply { orientation = android.widget.LinearLayout.VERTICAL; setPadding(px(20), px(8), px(20), px(4)) }
        val row = android.widget.LinearLayout(context).apply { orientation = android.widget.LinearLayout.HORIZONTAL }
        val fields = values.mapIndexed { index, (label, value) ->
            val column = android.widget.LinearLayout(context).apply { orientation = android.widget.LinearLayout.VERTICAL; setPadding(0, 0, px(8), 0) }
            column.addView(android.widget.TextView(context).apply { text = label; textSize = 13f })
            val field = android.widget.EditText(context).apply {
                id = if (index == 0) android.R.id.edit else android.R.id.text2
                setText(value); setSingleLine(true); textSize = 20f
                inputType = android.text.InputType.TYPE_CLASS_NUMBER or if (decimal) android.text.InputType.TYPE_NUMBER_FLAG_DECIMAL else 0
                setSelectAllOnFocus(true)
                contentDescription = label
            }
            column.addView(field, android.widget.LinearLayout.LayoutParams(-1, -2))
            row.addView(column, android.widget.LinearLayout.LayoutParams(0, -2, 1f))
            field
        }
        body.addView(row)
        body.addView(android.widget.TextView(context).apply { text = note; textSize = 12f; setPadding(0, px(12), 0, 0) })
        val scroll = android.widget.ScrollView(context).apply { addView(body) }
        val dialog = android.app.AlertDialog.Builder(context).setTitle(title).setView(scroll)
            .setPositiveButton("適用", null).setNegativeButton("閉じる") { _, _ -> }.create()
        dialog.setOnDismissListener { dismiss() }
        dialog.show()
        dialog.getButton(android.app.AlertDialog.BUTTON_POSITIVE).apply {
            isEnabled = canApply
            setOnClickListener {
                val error = apply(fields.map { it.text.toString() })
                if (error == null) dialog.dismiss() else fields.first().error = error
            }
        }
        onDispose { dialog.setOnDismissListener(null); dialog.dismiss() }
    }
}

@Composable private fun HelpDialog(dismiss: () -> Unit) {
    val context = LocalContext.current
    var copies by remember { mutableIntStateOf(0) }
    var copied by remember { mutableStateOf(false) }
    LaunchedEffect(copies) { if (copies > 0) { copied = true; kotlinx.coroutines.delay(2000); copied = false } }
    AlertDialog(onDismissRequest = dismiss, title = { Text("YomiPaceの使い方") }, text = {
        Column(Modifier.heightIn(max = 480.dp).verticalScroll(rememberScrollState())) {
            Text("PPTXから読み込み用ZIPを作る", fontWeight = FontWeight.SemiBold)
            Text("PowerPointを添付し、次の例文を貼り付けて依頼できます。", fontSize = 13.sp)
            TextButton(onClick = {
                (context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager).setPrimaryClip(ClipData.newPlainText("YomiPace ZIP作成依頼", ZIP_CREATION_PROMPT))
                copies++
            }) { Text(if (copied) "コピーしました" else "コピー") }
            Text(ZIP_CREATION_PROMPT, fontSize = 13.sp)
            HorizontalDivider(Modifier.padding(vertical = 12.dp))
            Text(HELP, fontSize = 13.sp)
        }
    }, confirmButton = { TextButton(onClick = dismiss) { Text("閉じる") } })
}

const val ZIP_CREATION_PROMPT = "添付のPowerPointから、YomiPace用ZIPを作成してください。指定範囲、指定がなければ全スライドを対象に、元の見た目と縦横比を維持してPNG画像にし、001.pngから連番にしてください。発表者ノートはUTF-8のscript.txtにまとめ、各画像に対応する原稿の前に#1、#2…を独立した行で記載してください。ノートがない場合は創作せず知らせてください。発表時間を指定した場合のみ、先頭にtime(mm)を記載してください。説明・動画などの明示された時間は[説明文(mm)]とし、時間を推測しないでください。ZIP直下には画像とscript.txtだけを配置してください。文字化け・見切れ・番号の対応・ZIPの正常性を確認し、ZIPと単独のscript.txtをダウンロードできる形で提示してください。"

val HELP = """
1. ZIPを開く
メニューからAndroidのファイル選択画面を開き、ZIPを選びます。ZIP直下、または一段の親フォルダに次をまとめてください。
001.png / 002.jpg / 003.jpeg / script.txt
画像は001からの3桁連番。PNG・JPG・JPEG対応（拡張子の大小不問）。同番号の重複と欠番は使えません。

2. 原稿の書き方
script.txtはUTF-8（BOM可、LF/CRLF可）。#番号を単独行に書き、画像と対応させます。説明のないスライドにも#番号を置きます。

time(07:00)
#1
本日は結果を説明します。
#2
こちらが結果です。
[Fig.1を説明(00:30)]
結果を補足します。
[手術動画を再生(00:20)]
[Table.2の要点を説明(00:45)]
#3
最後にまとめます。

timeは先頭に1回。省略時・不正時はメニューの「持ち時間」で分・秒を入力してください。
固定時間区間はすべて [任意の表示名(mm:ss)]。半角括弧・コロン、分は0以上、秒は00～59です。0秒も使用可能です。実際の動画再生機能はありません。

3. 発表する
「開始」で開始／続行、「停止」で一時停止。停止時間は経過に含みません。持ち時間を過ぎても時計は進み、超過を表示します。持ち時間と切替秒数は停止中に変更できます。
時計下の1本の進捗バーを直接タップ・ドラッグして、任意の経過時間へ移動できます。触れた時点で停止し、離した後も停止を保ちます。「開始」で変更後の時間から再開します。巻き戻した本文・時間指定ブロック・切替バーは未完了へ戻ります。赤線の手動位置は保持します。

4. 赤線とグレー
原稿を手動で上下にスクロールし、実際に読む箇所を赤線に合わせます。上下配置では、その#番号のスライドと次の2枚を表示します。2カラムでは、そのスライドを列の先頭に表示し、続きの画像を下へスクロールできます。スライド欄だけのスクロールでは原稿位置と時計は変わりません。原稿の自動スクロールはしません。
グレーは予定上読み終えた文です。実際の読了検出ではありません。グレーの境界が赤線より下なら遅れ、赤線より上に通常色が残れば先行の目安です。下線は現在の予定文です。
青いつまみが現在の表示範囲、黄色の点が各スライドの原稿開始位置です。メニュー「表示・操作設定」の「原稿スクロールバー」で右・左・なしを選べます（初期は左）。なしではつまみと黄色の点を隠し、原稿を直接スクロールできます。表示中のバーを動かして位置を変えることもできます。文字サイズや幅の変更で目印も再計算します。
バーは固定の予定時刻で進みます。スクロールで開始しません。バー区間中も全体時計は進み、本文の予定位置は進みません。

5. 表示と設定
18ptボタンで8～72の文字サイズを入力。内部はspで端末の文字倍率を反映します。2sボタンで切替時間を入力（0秒・0.5秒等も可）。切替回数は枚数−1です。
持ち時間・経過時間・残り時間は横スクロールなしの横一列表示です。メニュー「表示・操作設定」で各文字サイズと色（自動・基本色・#RRGGBB）を設定できます。横幅全体を設定した文字サイズの比率で配分し、各領域の中央に表示します。領域に収まらない文字は自動縮小します。設定値は上限として保存し、広い画面では元のサイズに戻ります。超過時の残り時間はマイナスで表示します。設定は次回起動にも保持します。
「計測中のスリープ禁止」は初期ONです。ONでは開始後の計測中に画面が自動消灯しないようにし、OFFまたは停止中は端末の設定に従います。開始・停止の共通文字サイズと高さ、原稿スクロールの慣性も調整できます。慣性0%では指を離すと停止、100%では長く流れます。直接ドラッグ中の速さは変えません。
横向きなどで高さが足りない場合は、開始・停止と原稿欄を残し、時計欄を上下にスクロールできます。案内が表示されたら時計欄を動かして進捗やシークバーを表示してください。
通常の縦画面は上下配置で、横幅に合わせたスライドの下に時計・操作ボタンを置き、残りの高さを原稿へ割り当てます。幅600dp以上の横画面は2カラムです。折りたたみのメイン画面を端末が通知した場合は、幅600dp以上なら縦向きでも2カラムを維持します。
2カラムではスライドを列幅いっぱいの16:9枠で表示し、下へ続く画像をスクロールできます。画像自体の縦横比は保ち、全体を表示します。仕切り線の⇄で左右列を交換できます。開閉・回転・入替・文字サイズ変更は赤線の文字位置と時計を保持します。

6. リセットと保存
「時間リセット」は時計と予定進捗を0に戻して停止し、資料・設定・手動位置は保持します。
「クリア」は表示中の資料と時計を解除します。アーカイブと表示設定は保持します。次のZIPの時間は新しく設定します。
資料は端末内にコピーします。ネット通信やマイクは使いません。通常の画面再生成では時計を保持し、OSによるプロセス終了後は最後の保存時点の経過時間で停止状態に復元します。
上限：画像200枚、1画像24MiB・40MP、原稿2MiB、展開合計256MiB。

7. 原稿編集とアーカイブ
停止中にメニュー「読み上げ原稿の編集」で全文を編集し、保存します。書式・画像番号・時間配分に誤りがあれば保存せず行番号等を知らせます。timeを明示的に変更した場合は持ち時間に反映し、本文だけの編集では画面の持ち時間を保ちます。経過時間を保ち、編集で同じ箇所を特定できない場合は現在スライドの先頭へ戻します。元ZIPは変更しません。
読み込んだZIPはアプリ内アーカイブに保存します。各資料の「開く」、鉛筆の編集、ゴミ箱ボタンを使えます。ゴミ箱からは復元、または確認後に完全削除できます。旧版の資料は残っている表示用データを引き継ぎます。

8. グレー表示のバイブ
メニューからオン／オフと弱・中・強を設定します。初期はオン・中です。0.5.0への更新時は一度オン・中に設定し、その後の選択を保存します。予定ペースで、改行で区切った本文段落の全体がグレーになった時点で振動します。赤線や手動スクロールでは振動しません。停止中・開閉・文字サイズ変更だけでも振動しません。

9. 実験：DOPAGAKIモード
メニュー「実験」でオンにすると、序盤の小さな火花から終盤の虹色WELCOME・招き猫・花火・稲妻へ徐々に盛り上がります。「演出の強さ」（30～100%）と「盛り上がりカーブ」（後半型・標準（直線）・前半型）を調整できます。時間を巻き戻すとコンボ・花火を消し、その時間の強さへ戻ります。原稿をスクロールしても演出段階は変わりません。時計と進捗バーには演出を重ねず、スライドは薄い装飾だけ。原稿は従来どおり動く背景＋最前面の文字・赤線です。「振動ビート」は72→180 BPMで加速し4拍ごとに強拍を刻みます。ON中はタッチや段落の振動を重ねません。「演出の振動」がOFFならビートも止まります。音は鳴りません。停止中は小さなタッチ反応だけ、設定画面・バックグラウンドでは休止。モードは初期OFF、振動とビートは初期ONで、全設定を保存します。 招き猫・キリ番カウンター・工事中・相互リンク・WebRing・BBSで個人ホームページ風に。「演出のシェード」は0～100%で色の暗さを調整します。初期0%は原色くっきりで、文字下地の暗さは変えません。 タッチ位置からクラッカーの紙吹雪と風船が飛び出し、後半は打ち上げ花火も加わります。原稿欄でも指の位置に反応し、文字の後ろへ描きます。風船・花火も停止や巻き戻しで消えます。 標準は直線的に強くなり、全てのカーブが予定時間で設定した強さへ到達します。猫は下側の両端でビートに合わせて跳ねて手招きし、終盤は目からビームを伸ばします。
""".trimIndent()
