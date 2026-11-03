package jp.cn22.yomipace

import android.app.AlertDialog
import android.app.Dialog
import android.content.Context
import android.graphics.Color
import android.os.Build
import android.text.Editable
import android.text.TextWatcher
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.WindowManager
import android.view.inputmethod.EditorInfo
import android.widget.*
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalContext
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import java.text.DateFormat
import java.util.Date

internal class ManagementFrame(base: Context, dark: Boolean, title: String) {
    val context = android.view.ContextThemeWrapper(base, if (dark) android.R.style.Theme_Material_NoActionBar else android.R.style.Theme_Material_Light_NoActionBar)
    val dialog = Dialog(context)
    val ink = if (dark) Color.rgb(237, 241, 245) else Color.rgb(23, 33, 43)
    val muted = if (dark) Color.LTGRAY else Color.DKGRAY
    val blue = if (dark) Color.rgb(145, 198, 250) else Color.rgb(23, 100, 166)
    val root = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL; setBackgroundColor(if (dark) Color.rgb(28, 33, 40) else Color.rgb(247, 248, 250)) }
    val toolbar = LinearLayout(context).apply { gravity = Gravity.CENTER_VERTICAL; setPadding(px(8), px(4), px(8), px(4)) }
    val titleView = TextView(context).apply { text = title; textSize = 18f; setTextColor(ink); maxLines = 2 }
    init { toolbar.addView(titleView, LinearLayout.LayoutParams(0, -2, 1f)); root.addView(toolbar); dialog.setContentView(root); dialog.setCanceledOnTouchOutside(false) }
    fun px(n: Int) = (n * context.resources.displayMetrics.density).toInt()
    fun button(label: String, onClick: () -> Unit) = Button(context).apply { text = label; isAllCaps = false; setTextColor(blue); setOnClickListener { onClick() } }
    fun label(text: String, color: Int = muted) = TextView(context).apply { this.text = text; textSize = 12f; setTextColor(color); setPadding(px(12), px(4), px(12), px(4)) }
    fun show() {
        val window = dialog.window!!
        WindowCompat.setDecorFitsSystemWindows(window, false)
        window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
        ViewCompat.setOnApplyWindowInsetsListener(root) { view, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
            val ime = insets.getInsets(WindowInsetsCompat.Type.ime())
            view.setPadding(bars.left, bars.top, bars.right, if (Build.VERSION.SDK_INT >= 30) maxOf(bars.bottom, ime.bottom) else bars.bottom)
            insets
        }
        dialog.show(); window.setLayout(-1, -1); ViewCompat.requestApplyInsets(root)
    }
}

@Composable fun ScriptEditorDialog(vm: AppViewModel, dark: Boolean) {
    val context = LocalContext.current
    val target = vm.editorName
    val holder = remember(context, target, dark) {
        val frame = ManagementFrame(context, dark, "読み上げ原稿の編集")
        val close = frame.button("閉じる") { }
        val save = frame.button("保存", vm::saveScriptEdit).apply { tag = "save-script" }
        frame.toolbar.addView(close); frame.toolbar.addView(save)
        frame.root.addView(frame.label(target))
        frame.root.addView(frame.label("##番号・time・説明区間も編集できます。行頭の**は読まないメモで、文字数・時間配分に含めません。保存で予定配分を更新し、経過時間は保持します。元ZIPは変更しません。"))
        val error = frame.label("", Color.rgb(197, 46, 64)).apply { maxLines = 5; movementMethod = android.text.method.ScrollingMovementMethod() }
        frame.root.addView(error)
        val input = EditText(frame.context).apply {
            id = android.R.id.edit; tag = "script-editor"; contentDescription = "読み上げ原稿"
            gravity = Gravity.TOP or Gravity.START; textSize = 16f
            inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_FLAG_MULTI_LINE or android.text.InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
            imeOptions = EditorInfo.IME_FLAG_NO_EXTRACT_UI
            setHorizontallyScrolling(false); isVerticalScrollBarEnabled = true
            setText(vm.editorDraft.orEmpty()); setPadding(frame.px(12), frame.px(8), frame.px(12), frame.px(8))
            addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
                override fun afterTextChanged(s: Editable?) { vm.updateEditorDraft(s.toString()) }
            })
        }
        frame.root.addView(input, LinearLayout.LayoutParams(-1, 0, 1f))
        fun requestClose() {
            if (vm.editorBusy) return
            if (!vm.editorDirty) vm.closeEditor()
            else AlertDialog.Builder(frame.context).setTitle("変更を破棄しますか？").setMessage("保存していない原稿の変更を破棄します。")
                .setNegativeButton("編集を続ける", null).setPositiveButton("破棄して閉じる") { _, _ -> vm.closeEditor() }.show()
        }
        close.setOnClickListener { requestClose() }
        frame.dialog.setCancelable(false)
        frame.dialog.setOnKeyListener { _, key, event -> if (key == KeyEvent.KEYCODE_BACK) { if (event.action == KeyEvent.ACTION_UP) requestClose(); true } else false }
        EditorHolder(frame, input, error, save, close)
    }
    SideEffect {
        holder.input.isEnabled = !vm.editorBusy; holder.save.isEnabled = !vm.editorBusy; holder.close.isEnabled = !vm.editorBusy
        holder.error.text = if (vm.editorBusy) "保存中…" else vm.editorError.orEmpty()
        holder.error.visibility = if (vm.editorBusy || vm.editorError != null) View.VISIBLE else View.GONE
    }
    DisposableEffect(holder) { holder.frame.show(); onDispose { holder.frame.dialog.dismiss() } }
}
private data class EditorHolder(val frame: ManagementFrame, val input: EditText, val error: TextView, val save: Button, val close: Button)

@Composable fun ArchiveDialog(vm: AppViewModel, location: DeckLocation, dark: Boolean) {
    val context = LocalContext.current
    val holder = remember(context, location, dark) {
        val isTrash = location == DeckLocation.TRASH
        val frame = ManagementFrame(context, dark, if (isTrash) "ゴミ箱" else "アーカイブ")
        frame.toolbar.addView(frame.button(if (isTrash) "アーカイブ" else "ゴミ箱") { vm.showLibrary(if (isTrash) DeckLocation.ARCHIVE else DeckLocation.TRASH) })
        frame.toolbar.addView(frame.button("閉じる", vm::closeLibrary))
        frame.root.addView(frame.label(if (isTrash) "復元するとアーカイブへ戻ります。ここからの削除は完全削除です。" else "読み込んだZIPを端末内に保存しています。鉛筆で原稿を編集できます。"))
        val status = frame.label(""); frame.root.addView(status)
        val list = LinearLayout(frame.context).apply { orientation = LinearLayout.VERTICAL }
        frame.root.addView(ScrollView(frame.context).apply { addView(list) }, LinearLayout.LayoutParams(-1, 0, 1f))
        frame.dialog.setOnCancelListener { vm.closeLibrary() }
        ArchiveHolder(frame, list, status)
    }
    val entries = if (location == DeckLocation.TRASH) vm.trash else vm.archives
    val busy = vm.busy
    SideEffect {
        holder.status.text = if (busy) "処理中…" else if (entries.isEmpty()) "資料はありません。" else "${entries.size}件"
        if (holder.rendered != entries || holder.busy != busy) {
            holder.rendered = entries; holder.busy = busy; holder.list.removeAllViews()
            entries.forEach { entry ->
                val frame = holder.frame
                val card = LinearLayout(frame.context).apply { tag = "archive-entry-${entry.id}"; orientation = LinearLayout.VERTICAL; setPadding(frame.px(12), frame.px(12), frame.px(12), frame.px(10)) }
                val title = TextView(frame.context).apply { text = entry.name; textSize = 16f; setTextColor(frame.ink); maxLines = 3 }
                card.addView(title)
                card.addView(TextView(frame.context).apply { text = "${entry.imageCount}枚  ${if (entry.edited) "原稿編集済み  " else ""}${DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(Date(entry.modifiedAt))}"; textSize = 11f; setTextColor(frame.muted) })
                val actions = LinearLayout(frame.context).apply { gravity = Gravity.END or Gravity.CENTER_VERTICAL }
                fun icon(resource: Int, description: String, action: () -> Unit) = ImageButton(frame.context).apply {
                    setImageResource(resource); imageTintList = android.content.res.ColorStateList.valueOf(frame.blue)
                    contentDescription = description; isEnabled = !busy; setOnClickListener { action() }
                    layoutParams = LinearLayout.LayoutParams(frame.px(48), frame.px(48))
                }
                if (location == DeckLocation.ARCHIVE) {
                    actions.addView(frame.button("開く") { vm.openArchived(entry.id) }.apply { isEnabled = !busy; contentDescription = "開く：${entry.name}" })
                    actions.addView(icon(android.R.drawable.ic_menu_edit, "原稿を編集：${entry.name}") { vm.beginArchivedEdit(entry.id) })
                    actions.addView(icon(android.R.drawable.ic_menu_delete, "ゴミ箱へ：${entry.name}") { vm.moveToTrash(entry.id) })
                } else {
                    actions.addView(frame.button("復元") { vm.restoreFromTrash(entry.id) }.apply { isEnabled = !busy; contentDescription = "復元：${entry.name}" })
                    actions.addView(icon(android.R.drawable.ic_menu_delete, "完全削除：${entry.name}") {
                        AlertDialog.Builder(frame.context).setTitle("完全に削除しますか？").setMessage("「${entry.name}」のアプリ内のZIP・スライド・編集済み原稿を削除します。復元できません。")
                            .setNegativeButton("閉じる", null).setPositiveButton("完全に削除") { _, _ -> vm.deletePermanently(entry.id) }.show()
                    })
                }
                card.addView(actions); holder.list.addView(card)
                holder.list.addView(View(frame.context).apply { setBackgroundColor(Color.GRAY) }, LinearLayout.LayoutParams(-1, frame.px(1)))
            }
        }
        holder.frame.dialog.setCancelable(!busy)
    }
    DisposableEffect(holder) { holder.frame.show(); onDispose { holder.frame.dialog.setOnCancelListener(null); holder.frame.dialog.dismiss() } }
}
private class ArchiveHolder(val frame: ManagementFrame, val list: LinearLayout, val status: TextView) {
    var rendered: List<DeckSummary>? = null
    var busy = false
}

@Composable fun VibrationSettingsDialog(vm: AppViewModel, dark: Boolean, dismiss: () -> Unit) {
    val context = LocalContext.current
    DisposableEffect(context) {
        val frame = ManagementFrame(context, dark, "グレー表示のバイブ")
        val body = LinearLayout(frame.context).apply { orientation = LinearLayout.VERTICAL; setPadding(frame.px(20), frame.px(8), frame.px(20), frame.px(8)) }
        val enabled = Switch(frame.context).apply { text = "段落がグレーになったら振動"; isChecked = vm.vibrationEnabled; tag = "vibration-enabled" }
        body.addView(enabled)
        val strengths = RadioGroup(frame.context)
        listOf("弱", "中", "強").forEachIndexed { index, label -> strengths.addView(RadioButton(frame.context).apply { id = 100 + index; text = label; tag = "vibration-strength-$index" }) }
        strengths.check(100 + vm.vibrationStrength); body.addView(strengths)
        body.addView(frame.label("予定ペースで本文段落全体がグレーになると振動します。赤線・スクロール・開閉・文字サイズ変更では振動しません。"))
        val dialog = AlertDialog.Builder(frame.context).setTitle("グレー表示のバイブ").setView(body).setNegativeButton("閉じる", null)
            .setPositiveButton("適用") { _, _ -> vm.setVibration(enabled.isChecked, strengths.checkedRadioButtonId - 100) }.create()
        dialog.setOnDismissListener { dismiss() }; dialog.show()
        onDispose { dialog.setOnDismissListener(null); dialog.dismiss() }
    }
}
