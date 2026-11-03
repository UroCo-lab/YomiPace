package jp.cn22.yomipace

import android.app.Application
import android.net.Uri
import androidx.compose.runtime.*
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import jp.cn22.yomipace.core.ScriptParser
import jp.cn22.yomipace.core.SlideScriptDraft
import jp.cn22.yomipace.core.ZipImporter
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import java.io.File
import java.util.UUID

internal data class SelectedSlide(val uri: Uri, val key: String = UUID.randomUUID().toString())
internal enum class CreationSection { IMAGES, HUB, TITLE, TIME, ORDER, SCRIPT }

internal class ScriptCreationModel(private val app: Application, private val scope: CoroutineScope,
    private val changed: suspend (StoredDeck, StoredDeck?) -> Unit) {
    private val prefs = app.getSharedPreferences("yomipace", 0)
    private val store = DeckStore(File(app.filesDir, "decks"))
    var visible by mutableStateOf(false); private set
    var busy by mutableStateOf(false); private set
    var error by mutableStateOf<String?>(null); private set
    var status by mutableStateOf<String?>(null); private set
    var section by mutableStateOf(CreationSection.IMAGES); private set
    var name by mutableStateOf("新しい読み上げ原稿"); private set
    var selected by mutableStateOf<List<SelectedSlide>>(emptyList()); private set
    var original by mutableStateOf<StoredDeck?>(null); private set
    var pages by mutableStateOf<List<TextFieldValue>>(emptyList()); private set
    var slideImages by mutableStateOf<List<File>>(emptyList()); private set
    var imageOrder by mutableStateOf<List<Int>>(emptyList()); private set
    var page by mutableIntStateOf(0); private set
    var timeUndecided by mutableStateOf(true); private set
    var timeMinutes by mutableStateOf(""); private set
    var timeSeconds by mutableStateOf("00"); private set
    var presentationSeconds by mutableStateOf<Double?>(null); private set
    var snippets by mutableStateOf(readSnippets()); private set
    private var format = SlideScriptDraft("", emptyList())
    private var savedName = ""
    private var savedTexts = emptyList<String>()
    private var savedOrder = emptyList<Int>()
    private var savedSeconds: Double? = null
    private var timeInputBaseline = Triple(true, "", "00")
    val timeSetupPending get() = section == CreationSection.TIME
    private val contentDirty get() = pages.map { it.text } != savedTexts || imageOrder != savedOrder || presentationSeconds != savedSeconds
    private val timeInputDirty get() = section == CreationSection.TIME && Triple(timeUndecided, timeMinutes, timeSeconds) != timeInputBaseline
    val source get() = if (!contentDirty && original != null) original!!.source else
        format.render(pages.map { it.text }, presentationSeconds, presentationSeconds != savedSeconds)
    val dirty get() = original != null && (name != savedName || contentDirty || timeInputDirty)

    fun open() {
        if (busy || visible) return
        selected = emptyList(); pages = emptyList(); slideImages = emptyList(); imageOrder = emptyList(); page = 0; original = null
        name = "新しい読み上げ原稿"; presentationSeconds = null; format = SlideScriptDraft("", emptyList())
        section = CreationSection.IMAGES; error = null; status = null; resetTimeInputs(); visible = true
    }
    fun openExisting(loaded: StoredDeck) {
        check(!busy && !visible) { "編集中の資料を閉じてください。" }
        require(loaded.entry.location == DeckLocation.ARCHIVE) { "ゴミ箱の資料は復元してから編集してください。" }
        attach(loaded); visible = true
    }
    private fun attach(loaded: StoredDeck) {
        val parsed = SlideScriptDraft.parse(loaded.source, loaded.entry.imageCount)
        original = loaded; format = parsed; name = loaded.entry.name
        pages = parsed.bodies.map { TextFieldValue(it) }; slideImages = loaded.deck.images
        imageOrder = loaded.entry.imageOrder ?: (1..pages.size).toList()
        presentationSeconds = loaded.entry.totalSeconds; page = 0; selected = emptyList()
        markSaved(); resetTimeInputs(); section = CreationSection.HUB; error = null; status = null
    }
    private fun markSaved() {
        savedName = name; savedTexts = pages.map { it.text }; savedOrder = imageOrder.toList(); savedSeconds = presentationSeconds
    }
    private fun resetTimeInputs() {
        timeUndecided = presentationSeconds == null
        timeMinutes = presentationSeconds?.let { (it.toLong() / 60).toString() } ?: ""
        timeSeconds = presentationSeconds?.let { (it.toLong() % 60).toString().padStart(2, '0') } ?: "00"
        timeInputBaseline = Triple(timeUndecided, timeMinutes, timeSeconds)
    }
    fun show(target: CreationSection) {
        if (busy || original == null || target == CreationSection.IMAGES) return
        if (section == CreationSection.TIME && !applyTimeDraft()) return
        if (target == CreationSection.TIME) resetTimeInputs()
        section = target; error = null; status = null
    }
    fun rename(value: String) { if (!busy) { name = value; status = null; error = null } }
    fun add(uris: List<Uri>) {
        if (!visible || busy || original != null || uris.isEmpty()) return
        if (selected.size + uris.size > ZipImporter.MAX_SLIDES) { error = "画像は合計200枚までです。選択を減らしてください。"; return }
        selected = selected + uris.map { SelectedSlide(it) }; error = null
    }
    fun move(index: Int, delta: Int) {
        if (busy || original != null || index !in selected.indices || index + delta !in selected.indices) return
        selected = selected.toMutableList().apply { add(index + delta, removeAt(index)) }
    }
    fun remove(index: Int) { if (!busy && original == null) selected = selected.filterIndexed { i, _ -> i != index } }
    fun moveSlide(index: Int, delta: Int) {
        if (busy || original == null || index !in pages.indices || index + delta !in pages.indices) return
        val current = imageOrder[page]
        imageOrder = imageOrder.toMutableList().apply { add(index + delta, removeAt(index)) }
        pages = pages.toMutableList().apply { add(index + delta, removeAt(index)) }
        slideImages = slideImages.toMutableList().apply { add(index + delta, removeAt(index)) }
        page = imageOrder.indexOf(current); error = null; status = null
    }
    fun selectPage(index: Int) { if (!busy && section == CreationSection.SCRIPT && index in pages.indices) page = index }
    fun edit(value: TextFieldValue) {
        if (busy || section != CreationSection.SCRIPT || page !in pages.indices) return
        pages = pages.toMutableList().apply { this[page] = value }; status = null; error = null
    }
    fun insert(text: String) {
        if (busy || page !in pages.indices) return
        val value = pages[page]
        val start = value.selection.min.coerceIn(0, value.text.length)
        val end = value.selection.max.coerceIn(start, value.text.length)
        edit(TextFieldValue(value.text.replaceRange(start, end, text), TextRange(start + text.length)))
    }
    fun generate() {
        if (busy || selected.isEmpty() || original != null) return
        busy = true; error = null; status = "画像を変換中… 0/${selected.size}"
        val images = selected.map { it.uri }; val title = name
        scope.launch {
            try {
                val created = withContext(Dispatchers.IO) {
                    SlideImageImporter.create(app, File(app.filesDir, "decks"), images, title) { count ->
                        scope.launch { status = "画像を変換中… $count/${images.size}" }
                    }
                }
                attach(created); changed(created, null)
            } catch (e: Exception) { error = e.message ?: "画像を取り込めませんでした。再選択してください。"; status = null }
            finally { busy = false }
        }
    }
    fun changeTimeUndecided(value: Boolean) { if (!busy) { timeUndecided = value; error = null } }
    fun changeTimeMinutes(value: String) { if (!busy) { timeMinutes = value; error = null } }
    fun changeTimeSeconds(value: String) { if (!busy) { timeSeconds = value; error = null } }
    private fun applyTimeDraft(): Boolean {
        if (Triple(timeUndecided, timeMinutes, timeSeconds) == timeInputBaseline) return true
        val seconds = if (timeUndecided) null else {
            val m = timeMinutes.trim().ifEmpty { "0" }.toLongOrNull()
            val s = timeSeconds.trim().ifEmpty { "0" }.toIntOrNull()
            if (m == null || m !in 0..1_000_000 || s == null || s !in 0..59 || m * 60 + s <= 0) {
                error = "0より長い発表時間を入力してください。分は0～1000000、秒は00～59です。"
                return false
            }
            (m * 60 + s).toDouble()
        }
        presentationSeconds = seconds; timeInputBaseline = Triple(timeUndecided, timeMinutes, timeSeconds)
        error = null; status = null; return true
    }
    fun beginEditing() {
        if (!busy && original != null && applyTimeDraft()) { section = CreationSection.SCRIPT; error = null; status = null }
    }
    fun save(closeAfter: Boolean = false) {
        val previous = original ?: return
        if (busy || section == CreationSection.TIME && !applyTimeDraft()) return
        val text = source; val title = name.trim(); val updateSource = contentDirty
        val order = imageOrder.toList(); val seconds = presentationSeconds
        if (title.isEmpty()) { error = "資料名を入力してください。"; return }
        busy = true; error = null; status = "保存中…"
        scope.launch {
            try {
                val saved = withContext(Dispatchers.IO) {
                    require(text.toByteArray(Charsets.UTF_8).size <= ZipImporter.MAX_SCRIPT) { "原稿はUTF-8で2MiB以下にしてください。" }
                    val parsed = ScriptParser.parse(text)
                    require(parsed.issues.isEmpty()) { parsed.issues.take(20).joinToString("\n") }
                    require(parsed.slides == (1..pages.size).toList()) { "スライド区切りは自動で入ります。入力欄の追加の##番号を取り除いてください。" }
                    require(order.sorted() == (1..pages.size).toList()) { "スライド順が不正です。" }
                    val current = store.read(previous.entry.id)
                    require(current.location == DeckLocation.ARCHIVE && current.modifiedAt == previous.entry.modifiedAt) { "資料が変更されました。閉じて開き直してください。" }
                    store.write(current.copy(name = title, scriptOverride = if (updateSource) text else current.scriptOverride,
                        totalSeconds = seconds, imageOrder = order.takeUnless { it == (1..pages.size).toList() },
                        modifiedAt = maxOf(System.currentTimeMillis(), current.modifiedAt + 1)))
                    store.load(previous.entry.id)
                }
                original = saved; name = saved.entry.name; format = SlideScriptDraft.parse(saved.source, pages.size)
                slideImages = saved.deck.images; markSaved(); status = "保存しました"
                changed(saved, previous)
                if (closeAfter) closeNow()
            } catch (e: Exception) { error = e.message ?: "保存できませんでした。"; status = null }
            finally { busy = false }
        }
    }
    fun close() { if (!busy) closeNow() }
    fun pickerFailed() { error = "画像選択画面を開けませんでした。端末のメディア・ファイルアプリを確認してください。" }
    private fun closeNow() {
        visible = false; original = null; pages = emptyList(); slideImages = emptyList(); imageOrder = emptyList(); selected = emptyList()
        presentationSeconds = null; section = CreationSection.IMAGES; status = null; error = null
    }
    fun saveSnippets(values: List<String>): Boolean {
        if (values.any { it.isBlank() || it.length > 2000 } || values.size > 50) return false
        if (!prefs.edit().putString("creationSnippets", JSONArray(values).toString()).commit()) return false
        snippets = values.toList(); return true
    }
    private fun readSnippets(): List<String> = runCatching {
        val json = prefs.getString("creationSnippets", null) ?: return@runCatching listOf("[任意の文字列(mm:ss)]", "**コメントアウト")
        val array = JSONArray(json)
        List(array.length()) { array.getString(it) }.also { require(it.size <= 50 && it.all { text -> text.isNotBlank() && text.length <= 2000 }) }
    }.getOrElse { listOf("[任意の文字列(mm:ss)]", "**コメントアウト") }
}
