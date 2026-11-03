package jp.cn22.yomipace

import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.selection.selectable
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@Composable internal fun ScriptCreationScreen(model: ScriptCreationModel) {
    var confirmClose by rememberSaveable { mutableStateOf(false) }
    var editSnippets by rememberSaveable { mutableStateOf(false) }
    var deleteSlideKey by rememberSaveable { mutableStateOf<Int?>(null) }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.PickMultipleVisualMedia()) { uris ->
        if (model.original == null) model.add(uris) else model.addSlides(uris)
    }
    fun choose() {
        try {
            picker.launch(PickVisualMediaRequest.Builder().setMediaType(ActivityResultContracts.PickVisualMedia.ImageOnly)
                .setMaxItems((200 - if (model.original == null) model.selected.size else model.pages.size).coerceAtLeast(2)).setOrderedSelection(true).build())
        } catch (_: android.content.ActivityNotFoundException) { model.pickerFailed() }
    }
    fun close() { if (!model.busy) { if (model.dirty) confirmClose = true else model.close() } }
    Dialog(onDismissRequest = ::close, properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
        BackHandler { if (model.section in listOf(CreationSection.IMAGES, CreationSection.HUB)) close() else model.show(CreationSection.HUB) }
        Surface(Modifier.fillMaxSize()) {
            BoxWithConstraints(Modifier.fillMaxSize().safeDrawingPadding().imePadding().padding(horizontal = 12.dp)) {
                val keyboard = WindowInsets.ime.getBottom(LocalDensity.current) > 0
                val previewHeight = (maxHeight * if (keyboard) .18f else .28f).coerceIn(40.dp, 240.dp)
                Column(Modifier.fillMaxSize()) {
                    if (!keyboard) Text(if (model.original == null) "読み上げ原稿作成" else "読み上げ原稿の編集", style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(vertical = 8.dp))
                    if (model.original == null) {
                        OutlinedTextField(model.name, model::rename, label = { Text("資料名") }, enabled = !model.busy, singleLine = true, modifier = Modifier.fillMaxWidth().testTag("creation-name"))
                        Text("選択順で追加します。生成前に順番を確認してください。元画像は変更しません。", style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(vertical = 6.dp))
                        Button(onClick = ::choose, enabled = !model.busy && model.selected.size < 200) { Text(if (model.selected.isEmpty()) "画像選択" else "画像を追加") }
                        Text("${model.selected.size}枚 / 最大200枚", style = MaterialTheme.typography.labelMedium)
                        LazyColumn(Modifier.weight(1f).fillMaxWidth().testTag("creation-image-list")) {
                            itemsIndexed(model.selected, key = { _, slide -> slide.key }) { index, slide ->
                                Column(Modifier.fillMaxWidth().padding(vertical = 6.dp).testTag("selected-slide-$index")) {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        CreationImage(slide.uri, "選択画像 ${index + 1}", Modifier.width(112.dp).height(72.dp))
                                        Text("${index + 1}", Modifier.padding(start = 8.dp).weight(1f))
                                        TextButton(onClick = { model.move(index, -1) }, enabled = !model.busy && index > 0, modifier = Modifier.semantics { contentDescription = "画像${index + 1}を前へ" }) { Text("↑") }
                                        TextButton(onClick = { model.move(index, 1) }, enabled = !model.busy && index < model.selected.lastIndex, modifier = Modifier.semantics { contentDescription = "画像${index + 1}を後へ" }) { Text("↓") }
                                    }
                                    TextButton(onClick = { model.remove(index) }, enabled = !model.busy, modifier = Modifier.align(Alignment.End).semantics { contentDescription = "画像${index + 1}を削除" }) { Text("削除") }
                                    HorizontalDivider()
                                }
                            }
                        }
                    } else if (model.section == CreationSection.HUB) {
                        Column(Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()).testTag("creation-hub"), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                            Text(model.name.ifBlank { "タイトル未入力" }, style = MaterialTheme.typography.headlineSmall, modifier = Modifier.padding(top = 20.dp))
                            Text("${model.pages.size}枚  ·  " + (model.presentationSeconds?.let { "${it.toLong() / 60}分${it.toLong() % 60}秒" } ?: "時間未定"), color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Text("編集する項目を選んでください。変更は「保存」で確定します。", style = MaterialTheme.typography.bodyMedium)
                            OutlinedButton(onClick = { model.show(CreationSection.TITLE) }, enabled = !model.busy, modifier = Modifier.fillMaxWidth().heightIn(min = 54.dp).testTag("creation-hub-title")) { Text("タイトル") }
                            OutlinedButton(onClick = { model.show(CreationSection.TIME) }, enabled = !model.busy, modifier = Modifier.fillMaxWidth().heightIn(min = 54.dp).testTag("creation-hub-time")) { Text("時間指定") }
                            OutlinedButton(onClick = { model.show(CreationSection.ORDER) }, enabled = !model.busy, modifier = Modifier.fillMaxWidth().heightIn(min = 54.dp).testTag("creation-hub-order")) { Text("スライド編集") }
                            Button(onClick = { model.show(CreationSection.SCRIPT) }, enabled = !model.busy, modifier = Modifier.fillMaxWidth().heightIn(min = 54.dp).testTag("creation-hub-script")) { Text("原稿編集") }
                        }
                    } else if (model.section == CreationSection.TITLE) {
                        Column(Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState())) {
                            Text("タイトル", style = MaterialTheme.typography.headlineSmall, modifier = Modifier.padding(vertical = 20.dp))
                            OutlinedTextField(model.name, model::rename, label = { Text("資料名") }, enabled = !model.busy, singleLine = true, modifier = Modifier.fillMaxWidth().testTag("creation-name"))
                            Text("変更は「保存」で確定します。", style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 16.dp))
                        }
                    } else if (model.section == CreationSection.ORDER) {
                        Text("スライド編集", style = MaterialTheme.typography.headlineSmall, modifier = Modifier.padding(vertical = 12.dp))
                        Text("追加・削除・並び替えができます。画像と原稿は一緒に移動し、番号を振り直します。変更は保存で確定します。", style = MaterialTheme.typography.bodySmall)
                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                            OutlinedButton(onClick = ::choose, enabled = !model.busy && model.pages.size < 200, modifier = Modifier.testTag("creation-add-slides")) { Text("スライドを追加") }
                            Text("${model.pages.size}枚 / 最大200枚", style = MaterialTheme.typography.labelMedium, modifier = Modifier.padding(start = 12.dp))
                        }
                        LazyColumn(Modifier.weight(1f).fillMaxWidth().testTag("creation-slide-order")) {
                            itemsIndexed(model.slideImages, key = { index, _ -> model.imageOrder[index] }) { index, file ->
                                Row(Modifier.fillMaxWidth().padding(vertical = 12.dp).testTag("ordered-slide-$index"), verticalAlignment = Alignment.CenterVertically) {
                                    CreationImage(Uri.fromFile(file), "並び替えスライド ${index + 1}", Modifier.width(96.dp).height(64.dp))
                                    Column(Modifier.weight(1f).padding(start = 8.dp)) {
                                        Text("スライド ${index + 1}", style = MaterialTheme.typography.labelLarge)
                                        Text(model.pages[index].text.lineSequence().firstOrNull { it.isNotBlank() } ?: "原稿は未入力", style = MaterialTheme.typography.bodySmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
                                    }
                                    Column {
                                        Row {
                                            TextButton(onClick = { model.moveSlide(index, -1) }, enabled = !model.busy && index > 0, modifier = Modifier.semantics { contentDescription = "スライド${index + 1}を前へ" }) { Text("↑") }
                                            TextButton(onClick = { model.moveSlide(index, 1) }, enabled = !model.busy && index < model.pages.lastIndex, modifier = Modifier.semantics { contentDescription = "スライド${index + 1}を後へ" }) { Text("↓") }
                                        }
                                        TextButton(onClick = { deleteSlideKey = model.imageOrder[index] }, enabled = !model.busy && model.pages.size > 1,
                                            modifier = Modifier.align(Alignment.End).semantics { contentDescription = "スライド${index + 1}を削除" }) { Text("削除") }
                                    }
                                }
                                HorizontalDivider()
                            }
                        }
                    } else if (model.timeSetupPending) {
                        Column(Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()).testTag("creation-time-setup")) {
                            Text("時間指定", style = MaterialTheme.typography.headlineSmall, modifier = Modifier.padding(top = 28.dp, bottom = 12.dp))
                            Text("発表の持ち時間を設定します。未定のまま原稿を書き、あとから設定・変更することもできます。", style = MaterialTheme.typography.bodyMedium)
                            Row(Modifier.fillMaxWidth().padding(top = 20.dp).selectable(!model.timeUndecided, enabled = !model.busy, role = Role.RadioButton, onClick = { model.changeTimeUndecided(false) }).testTag("creation-time-fixed"), verticalAlignment = Alignment.CenterVertically) {
                                RadioButton(selected = !model.timeUndecided, onClick = null, enabled = !model.busy)
                                Text("発表時間を設定する", Modifier.padding(start = 8.dp))
                            }
                            Row(Modifier.fillMaxWidth().padding(start = 12.dp, top = 8.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                OutlinedTextField(model.timeMinutes, model::changeTimeMinutes, label = { Text("分") }, placeholder = { Text("0") },
                                    singleLine = true, enabled = !model.busy && !model.timeUndecided, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), modifier = Modifier.weight(1f).testTag("creation-time-minutes"))
                                OutlinedTextField(model.timeSeconds, model::changeTimeSeconds, label = { Text("秒（00～59）") },
                                    singleLine = true, enabled = !model.busy && !model.timeUndecided, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), modifier = Modifier.weight(1f).testTag("creation-time-seconds"))
                            }
                            Row(Modifier.fillMaxWidth().padding(top = 16.dp).selectable(model.timeUndecided, enabled = !model.busy, role = Role.RadioButton, onClick = { model.changeTimeUndecided(true) }).testTag("creation-time-undecided"), verticalAlignment = Alignment.CenterVertically) {
                                RadioButton(selected = model.timeUndecided, onClick = null, enabled = !model.busy)
                                Text("未定（あとで設定）", Modifier.padding(start = 8.dp))
                            }
                            Text("「管理へ戻る」で入力を反映し、「保存」で確定します。", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 16.dp))
                        }
                    } else {
                        Text(model.presentationSeconds?.let { "発表時間 ${it.toLong() / 60}分${it.toLong() % 60}秒" } ?: "発表時間 未定（あとで設定できます）", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
                            TextButton(onClick = { model.selectPage(model.page - 1) }, enabled = !model.busy && model.page > 0) { Text("前へ") }
                            Text("スライド ${model.page + 1} / ${model.pages.size}")
                            TextButton(onClick = { model.selectPage(model.page + 1) }, enabled = !model.busy && model.page < model.pages.lastIndex) { Text("次へ") }
                        }
                        CreationImage(Uri.fromFile(model.slideImages[model.page]), "作成中のスライド ${model.page + 1}", Modifier.fillMaxWidth().height(previewHeight))
                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                            Row(Modifier.weight(1f).horizontalScroll(rememberScrollState()).testTag("creation-snippets")) {
                                model.snippets.forEachIndexed { index, text ->
                                    TextButton(onClick = { model.insert(text) }, enabled = !model.busy, modifier = Modifier.testTag("insert-snippet-$index")) { Text(text) }
                                }
                            }
                            TextButton(onClick = { editSnippets = true }, enabled = !model.busy, modifier = Modifier.semantics { contentDescription = "文字列ボタンのメニュー" }.testTag("snippet-menu")) { Text("⋮") }
                        }
                        Text("##${model.page + 1}（自動）", style = MaterialTheme.typography.labelMedium)
                        OutlinedTextField(value = model.pages[model.page], onValueChange = model::edit,
                            enabled = !model.busy, label = { Text("スライド${model.page + 1}の原稿") },
                            placeholder = { Text("ここに読み上げ原稿を入力") },
                            modifier = Modifier.weight(1f).fillMaxWidth().testTag("creation-script"))
                    }
                    if (model.busy) LinearProgressIndicator(Modifier.fillMaxWidth())
                    (model.error ?: model.status)?.let {
                        Text(it, color = if (model.error != null) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary,
                            style = MaterialTheme.typography.bodySmall, modifier = Modifier.fillMaxWidth().heightIn(max = 80.dp).verticalScroll(rememberScrollState()).testTag("creation-status"))
                    }
                    Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(12.dp, Alignment.End)) {
                        if (model.original != null && model.section != CreationSection.HUB) {
                            TextButton(onClick = { model.show(CreationSection.HUB) }, enabled = !model.busy, modifier = Modifier.testTag("creation-back-hub")) { Text("管理へ戻る") }
                            Spacer(Modifier.weight(1f))
                        }
                        TextButton(onClick = ::close, enabled = !model.busy) { Text("閉じる") }
                        if (model.original == null) Button(onClick = model::generate, enabled = !model.busy && model.selected.isNotEmpty()) { Text("生成") }
                        else Button(onClick = { model.save() }, enabled = !model.busy, modifier = Modifier.testTag("creation-save")) { Text("保存") }
                    }
                }
            }
        }
        if (confirmClose) AlertDialog(onDismissRequest = { confirmClose = false }, title = { Text("原稿を保存しますか？") },
            text = { Text("未保存の変更があります。画像と最後に保存した原稿はアーカイブに残ります。") },
            confirmButton = { TextButton(onClick = { confirmClose = false; model.save(closeAfter = true) }) { Text("保存する") } },
            dismissButton = {
                Row {
                    TextButton(onClick = { confirmClose = false; model.close() }) { Text("保存しない") }
                    TextButton(onClick = { confirmClose = false }) { Text("キャンセル") }
                }
            })
    val deletionIndex = deleteSlideKey?.let { model.imageOrder.indexOf(it) } ?: -1
    if (deletionIndex >= 0) AlertDialog(onDismissRequest = { deleteSlideKey = null },
        title = { Text("スライド${deletionIndex + 1}を削除しますか？") },
        text = { Text("このスライドと対応する原稿を編集内容から取り除きます。変更は「保存」で確定します。元の画像やZIPは変更しません。") },
        confirmButton = { TextButton(onClick = { model.removeSlide(deletionIndex); deleteSlideKey = null }, enabled = !model.busy) { Text("削除する") } },
        dismissButton = { TextButton(onClick = { deleteSlideKey = null }) { Text("キャンセル") } })
    if (editSnippets) SnippetEditor(model) { editSnippets = false }
    }
}

@Composable private fun CreationImage(uri: Uri, label: String, modifier: Modifier) {
    val context = LocalContext.current
    var failed by remember(uri) { mutableStateOf(false) }
    val bitmap by produceState<ImageBitmap?>(null, uri) {
        value = withContext(Dispatchers.IO) {
            try { SlideImageImporter.thumbnail(context, uri).asImageBitmap() }
            catch (_: Exception) { null }
            catch (_: OutOfMemoryError) { null }
        }
        failed = value == null
    }
    Box(modifier, contentAlignment = Alignment.Center) {
        bitmap?.let { Image(it, label, Modifier.fillMaxSize(), contentScale = ContentScale.Fit) }
            ?: Text(if (failed) "画像を読み込めません。再選択してください。" else "読み込み中…", style = MaterialTheme.typography.bodySmall)
    }
}

@Composable private fun SnippetEditor(model: ScriptCreationModel, close: () -> Unit) {
    val context = LocalContext.current
    val dark = isSystemInDarkTheme()
    DisposableEffect(context, dark) {
        val frame = ManagementFrame(context, dark, "文字列ボタンの編集")
        val values = model.snippets.toMutableList()
        frame.root.addView(frame.label("最大50個・各2000文字。前へ／後へで表示順を変更します。"))
        val scroll = android.widget.ScrollView(frame.context)
        val list = android.widget.LinearLayout(frame.context).apply { orientation = android.widget.LinearLayout.VERTICAL; setPadding(frame.px(12), 0, frame.px(12), 0) }
        scroll.addView(list)
        frame.root.addView(scroll, android.widget.LinearLayout.LayoutParams(-1, 0, 1f))
        val error = frame.label("", android.graphics.Color.rgb(197, 46, 64))
        frame.root.addView(error)
        fun render() {
            val offset = scroll.scrollY
            list.removeAllViews()
            values.forEachIndexed { index, value ->
                list.addView(frame.label("文字列 ${index + 1}"))
                val input = android.widget.EditText(frame.context).apply {
                    tag = "snippet-value-$index"; contentDescription = "文字列 ${index + 1}"
                    setText(value); textSize = 16f; setTextColor(frame.ink)
                    inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_FLAG_MULTI_LINE
                    imeOptions = android.view.inputmethod.EditorInfo.IME_FLAG_NO_EXTRACT_UI
                    filters = arrayOf(android.text.InputFilter.LengthFilter(2000))
                    addTextChangedListener(object : android.text.TextWatcher {
                        override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
                        override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
                        override fun afterTextChanged(s: android.text.Editable?) { values[index] = s.toString(); error.text = "" }
                    })
                }
                list.addView(input, android.widget.LinearLayout.LayoutParams(-1, -2))
                val row = android.widget.LinearLayout(frame.context)
                row.addView(frame.button("前へ") { values.add(index - 1, values.removeAt(index)); render() }.apply { isEnabled = index > 0; tag = "snippet-up-$index" })
                row.addView(frame.button("後へ") { values.add(index + 1, values.removeAt(index)); render() }.apply { isEnabled = index < values.lastIndex; tag = "snippet-down-$index" })
                row.addView(frame.button("削除") { values.removeAt(index); render() }.apply { tag = "snippet-delete-$index" })
                list.addView(row)
            }
            list.addView(frame.button("追加") { values.add(""); render(); scroll.post { scroll.fullScroll(android.view.View.FOCUS_DOWN) } }.apply { isEnabled = values.size < 50; tag = "snippet-add" })
            scroll.post { scroll.scrollTo(0, offset) }
        }
        render()
        val footer = android.widget.LinearLayout(frame.context).apply { gravity = android.view.Gravity.END }
        footer.addView(frame.button("キャンセル", close))
        footer.addView(frame.button("適用") {
            if (model.saveSnippets(values)) close() else error.text = "空欄・個数・文字数を確認してください。保存できない場合は再試行してください。"
        }.apply { tag = "snippet-apply" })
        frame.root.addView(footer)
        frame.dialog.setOnCancelListener { close() }
        frame.show()
        onDispose { frame.dialog.setOnCancelListener(null); frame.dialog.dismiss() }
    }
}
