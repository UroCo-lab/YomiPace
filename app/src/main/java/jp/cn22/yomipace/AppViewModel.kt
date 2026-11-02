package jp.cn22.yomipace

import android.app.Application
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.SystemClock
import android.provider.OpenableColumns
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.mutableDoubleStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import jp.cn22.yomipace.core.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.UUID

class AppViewModel(application: Application) : AndroidViewModel(application) {
    private val prefs = application.getSharedPreferences("yomipace", 0)
    private val decks = File(application.filesDir, "decks").apply { mkdirs() }
    private val store = DeckStore(decks)
    val clock = PresentationClock { SystemClock.elapsedRealtime() }
    private val haptics = ParagraphHaptics(application)
    private var paragraphSchedule: ScheduledParagraphs? = null
    var deck by mutableStateOf<ImportedDeck?>(null); private set
    var timeline by mutableStateOf<Timeline?>(null); private set
    var totalSeconds by mutableStateOf<Double?>(null); private set
    var switchSeconds by mutableDoubleStateOf(prefs.getString("switchSeconds", null)?.toDoubleOrNull() ?: 2.0); private set
    var fontSize by mutableIntStateOf(prefs.getInt("font", 18)); private set
    var readerScrollbar by mutableStateOf(ReaderScrollbarPosition.fromSaved(prefs.getString("readerScrollbar", null))); private set
    var totalFontSize by mutableIntStateOf(prefs.getInt("totalFontSize", 44).coerceIn(12, 72)); private set
    var elapsedFontSize by mutableIntStateOf(prefs.getInt("elapsedFontSize", 44).coerceIn(12, 72)); private set
    var remainingFontSize by mutableIntStateOf(prefs.getInt("remainingFontSize", 44).coerceIn(12, 72)); private set
    var clockFontPriority by mutableIntStateOf(prefs.getInt("clockFontPriority",
        listOf(totalFontSize, elapsedFontSize, remainingFontSize).let { sizes -> sizes.indices.maxBy { sizes[it] } }).coerceIn(0, 2)); private set
    var totalClockColor by mutableStateOf(normalizedClockColor(prefs.getString("totalClockColor", null))); private set
    var elapsedClockColor by mutableStateOf(normalizedClockColor(prefs.getString("elapsedClockColor", null))); private set
    var remainingClockColor by mutableStateOf(normalizedClockColor(prefs.getString("remainingClockColor", null))); private set
    var keepScreenOnWhileRunning by mutableStateOf(prefs.getBoolean("keepScreenOnWhileRunning", true)); private set
    var controlFontSize by mutableIntStateOf(prefs.getInt("controlFontSize", 18).coerceIn(12, 36)); private set
    var controlHeight by mutableIntStateOf(prefs.getInt("controlHeight", 52).coerceIn(48, 112)); private set
    var scrollInertia by mutableDoubleStateOf(prefs.getString("scrollInertia", null)?.toDoubleOrNull()?.takeIf { it.isFinite() }?.coerceIn(0.0, 1.0) ?: 0.5); private set
    var swapped by mutableStateOf(prefs.getBoolean("swapped", false)); private set
    var running by mutableStateOf(false); private set
    var elapsed by mutableDoubleStateOf(0.0); private set
    var importing by mutableStateOf(true); private set
    var currentSlide by mutableIntStateOf(1); private set
    var message by mutableStateOf<String?>(null)
    var libraryLocation by mutableStateOf<DeckLocation?>(null); private set
    var archives by mutableStateOf<List<DeckSummary>>(emptyList()); private set
    var trash by mutableStateOf<List<DeckSummary>>(emptyList()); private set
    var libraryBusy by mutableStateOf(false); private set
    var editorBusy by mutableStateOf(false); private set
    var editorDraft by mutableStateOf<String?>(null); private set
    var editorError by mutableStateOf<String?>(null); private set
    private var editorOriginal: StoredDeck? = null
    val editorName get() = editorOriginal?.entry?.name.orEmpty()
    val editorDirty get() = editorDraft != null && editorDraft != editorOriginal?.source
    val busy get() = importing || libraryBusy || editorBusy
    var vibrationEnabled by mutableStateOf(prefs.getBoolean("vibrationEnabled", false)); private set
    var vibrationStrength by mutableIntStateOf(prefs.getInt("vibrationStrength", 1).coerceIn(0, 2)); private set
    var anchor by mutableStateOf(ReadingAnchor()); private set
    private var budgetProblem by mutableStateOf<String?>("メニューの「ZIPを開く」で資料を選択してください。")

    init {
        // Capture first-install state before lifecycle callbacks can save ordinary defaults.
        runCatching { WelcomeSample.prepare(application) } // IO initialization below retries and reports storage failures.
        viewModelScope.launch {
            withContext(Dispatchers.IO) {
                try { WelcomeSample.installIfNew(application) }
                catch (_: Exception) { withContext(Dispatchers.Main) { message = "サンプル資料を準備できませんでした。次回起動時に再試行します。メニューの「ZIPを開く」も使用できます。" } }
                val active = prefs.getString("active", null)
                if (active != null && Regex("deck-[a-f0-9-]+").matches(active)) {
                    val dir = File(decks, active)
                    try {
                        store.migrateActive(active, prefs.getString("name", "資料").orEmpty(), prefs.getString("total", null)?.toDoubleOrNull())
                        val stored = store.load(active)
                        require(stored.entry.location == DeckLocation.ARCHIVE)
                        val loaded = stored.deck
                        withContext(Dispatchers.Main) {
                            deck = loaded
                            totalSeconds = stored.entry.totalSeconds
                            anchor = ReadingAnchor(prefs.getString("anchor", "h1").orEmpty(), prefs.getInt("offset", 0))
                            currentSlide = loaded.script.items.find { it.id == anchor.itemId }?.slide ?: 1
                            clock.restorePaused(prefs.getLong("elapsed", 0))
                            elapsed = clock.elapsedMillis / 1000.0
                            rebuild()
                        }
                    } catch (_: Exception) { withContext(Dispatchers.Main) { message = "保存資料を復元できませんでした。ZIPを開き直してください。" } }
                }
            }
            refreshLibrary()
            importing = false
            while (isActive) {
                if (clock.running) { updateElapsed(true) }
                delay(50)
            }
        }
    }
    val startProblem: String? get() {
        if (busy) return "資料を処理中です。"
        if (editorDraft != null) return "原稿の編集を閉じてから再生してください。"
        return budgetProblem
    }
    private fun rebuild() {
        val script = deck?.script
        if (script == null) { timeline = null; paragraphSchedule = null; budgetProblem = "メニューの「ZIPを開く」で資料を選択してください。"; return }
        val result = runCatching { Timeline.build(script, totalSeconds, switchSeconds) }
        timeline = result.getOrNull(); budgetProblem = result.exceptionOrNull()?.message
        paragraphSchedule = timeline?.let { ScheduledParagraphs(script, it).apply { seek(elapsed) } }
    }
    fun play() {
        if (running) return
        val problem = startProblem
        if (problem != null) { message = problem; return }
        if (timeline == null) rebuild()
        clock.play(); running = true
    }
    private fun updateElapsed(allowVibration: Boolean) {
        elapsed = clock.elapsedMillis / 1000.0
        if (paragraphSchedule?.advance(elapsed) == true && allowVibration) haptics.pulse(vibrationEnabled, vibrationStrength)
    }
    fun pause() { val wasRunning = clock.running; clock.pause(); running = false; updateElapsed(wasRunning); persist() }
    fun resetTime() { clock.reset(); running = false; elapsed = 0.0; paragraphSchedule?.seek(0.0); persist() }
    fun seekTo(seconds: Double) {
        if (!seconds.isFinite() || timeline == null || busy) return
        clock.restorePaused((seconds.coerceAtLeast(0.0) * 1000).toLong())
        running = false; elapsed = clock.elapsedMillis / 1000.0
        paragraphSchedule?.seek(elapsed)
        persist()
    }
    fun setFont(value: Int) { require(value in 8..72); fontSize = value; persist() }
    fun changeReaderScrollbar(value: ReaderScrollbarPosition) { readerScrollbar = value; persist() }
    fun setTotalFont(value: Int) { totalFontSize = value.coerceIn(12, 72); clockFontPriority = 0; persist() }
    fun setElapsedFont(value: Int) { elapsedFontSize = value.coerceIn(12, 72); clockFontPriority = 1; persist() }
    fun setRemainingFont(value: Int) { remainingFontSize = value.coerceIn(12, 72); clockFontPriority = 2; persist() }
    fun changeTotalClockColor(value: String?) { totalClockColor = normalizedClockColor(value); persist() }
    fun changeElapsedClockColor(value: String?) { elapsedClockColor = normalizedClockColor(value); persist() }
    fun changeRemainingClockColor(value: String?) { remainingClockColor = normalizedClockColor(value); persist() }
    fun changeKeepScreenOn(value: Boolean) { keepScreenOnWhileRunning = value; persist() }
    fun setControlFont(value: Int) { controlFontSize = value.coerceIn(12, 36); persist() }
    fun changeControlHeight(value: Int) { controlHeight = value.coerceIn(48, 112); persist() }
    fun changeScrollInertia(value: Double) { if (value.isFinite()) { scrollInertia = value.coerceIn(0.0, 1.0); persist() } }
    fun swap() { swapped = !swapped; persist() }
    fun setSwitch(value: Double): String? {
        if (running) return "停止してから切替時間を適用してください。"
        if (!value.isFinite() || value < 0) return "0以上の有限の秒数を入力してください。"
        switchSeconds = value; rebuild(); persist(); return null
    }
    fun setTotal(value: Double): String? {
        if (running) return "停止してから持ち時間を変更してください。"
        if (busy || editorDraft != null) return "資料の処理・編集を終えてから変更してください。"
        if (!value.isFinite() || value <= 0) return "0より長い持ち時間を入力してください。"
        try { deck?.let { store.write(store.read(it.directory.name).copy(totalSeconds = value)) } }
        catch (_: Exception) { return "持ち時間を保存できませんでした。" }
        totalSeconds = value; rebuild(); persist(); return null
    }
    fun updateAnchor(value: ReadingAnchor, slide: Int) { anchor = value; currentSlide = slide }
    fun clear() {
        if (busy) return
        resetTime(); deck = null; timeline = null; totalSeconds = null; anchor = ReadingAnchor(); currentSlide = 1; rebuild()
        persist()
        // Clearing the presentation no longer deletes archived material.
    }
    fun persist() {
        prefs.edit().putInt("font", fontSize).putString("switchSeconds", switchSeconds.toString()).putBoolean("swapped", swapped)
            .putString("readerScrollbar", readerScrollbar.name)
            .putInt("totalFontSize", totalFontSize).putInt("elapsedFontSize", elapsedFontSize)
            .putInt("remainingFontSize", remainingFontSize).putString("totalClockColor", totalClockColor)
            .putInt("clockFontPriority", clockFontPriority)
            .putString("elapsedClockColor", elapsedClockColor).putString("remainingClockColor", remainingClockColor)
            .putBoolean("keepScreenOnWhileRunning", keepScreenOnWhileRunning)
            .putInt("controlFontSize", controlFontSize).putInt("controlHeight", controlHeight).putString("scrollInertia", scrollInertia.toString())
            .putBoolean("vibrationEnabled", vibrationEnabled).putInt("vibrationStrength", vibrationStrength)
            .putString("active", deck?.directory?.name).putString("name", deck?.name).putString("total", totalSeconds?.toString())
            .putString("anchor", anchor.itemId).putInt("offset", anchor.utf16Offset).putLong("elapsed", clock.elapsedMillis).apply()
    }
    fun importZip(uri: Uri) {
        if (running) { message = "ZIPを開く前に時計を停止してください。"; return }
        if (busy) return
        importing = true
        viewModelScope.launch {
            var staging: File? = null
            var incoming: File? = null
            try {
                val loaded = withContext(Dispatchers.IO) {
                    val resolver = getApplication<Application>().contentResolver
                    val name = runCatching { resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { if (it.moveToFirst()) it.getString(0) else null } }.getOrNull() ?: "資料.zip"
                    val dir = File(decks, "deck-${UUID.randomUUID()}"); staging = dir
                    val originalZip = File(decks, ".incoming-${UUID.randomUUID()}.zip"); incoming = originalZip
                    val input = resolver.openInputStream(uri) ?: error("ZIPを読み取れません。")
                    input.use { source -> originalZip.outputStream().use { output ->
                        val buffer = ByteArray(32 * 1024); var copied = 0L
                        while (true) { val count = source.read(buffer); if (count < 0) break; copied += count; require(copied <= 128L * 1024 * 1024) { "ZIPは128MiB以下にしてください。" }; output.write(buffer, 0, count) }
                    } }
                    val imported = ZipImporter.read(originalZip.inputStream(), dir, name)
                    imported.images.forEach { image ->
                        val encoded = image.readBytes()
                        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                        BitmapFactory.decodeByteArray(encoded, 0, encoded.size, bounds)
                        require(bounds.outMimeType in listOf("image/png", "image/jpeg") && bounds.outWidth > 0 && bounds.outHeight > 0 && bounds.outWidth.toLong() * bounds.outHeight <= 40_000_000) { "PNG/JPEG画像を読み取れないか40メガピクセルを超えています：${image.name}" }
                        var sample = 1
                        while (bounds.outWidth / sample > 512 || bounds.outHeight / sample > 512) sample *= 2
                        val bitmap = BitmapFactory.decodeByteArray(encoded, 0, encoded.size, BitmapFactory.Options().apply { inSampleSize = sample })
                        require(bitmap != null) { "画像データが壊れています：${image.name}" }
                        bitmap.recycle()
                    }
                    check(originalZip.renameTo(File(dir, "source.zip"))) { "元ZIPを保存できませんでした。" }
                    val now = System.currentTimeMillis()
                    store.write(DeckEntry(dir.name, name, imported.images.size, now, now, totalSeconds = imported.script.declaredSeconds))
                    imported
                }
                // Commit the active pointer before old private data can be removed.
                val committed = prefs.edit().putString("active", loaded.directory.name).putString("name", loaded.name)
                    .putString("total", loaded.script.declaredSeconds?.toString()).putString("anchor", "h1").putInt("offset", 0).putLong("elapsed", 0).commit()
                check(committed) { "取り込み状態を保存できません。以前の資料を保持します。" }
                clock.reset(); running = false; elapsed = 0.0
                deck = loaded; totalSeconds = loaded.script.declaredSeconds; anchor = ReadingAnchor(); currentSlide = 1
                rebuild()
                message = if (loaded.script.issues.isNotEmpty()) loaded.script.issues.joinToString("\n") else if (totalSeconds == null) "ZIPを読み込みました。メニューの「持ち時間」で発表時間を設定してください。" else null
                refreshLibrary()
            } catch (e: Exception) {
                val failedStage = staging
                if (failedStage != null && failedStage != deck?.directory) withContext(Dispatchers.IO) { failedStage.deleteRecursively() }
                message = "ZIPを読み込めませんでした。\n${e.message ?: "読み取りエラー"}\n\n現在の資料は保持しています。"
            } finally { withContext(Dispatchers.IO) { incoming?.delete() }; importing = false }
        }
    }

    private suspend fun refreshLibrary() {
        val lists = withContext(Dispatchers.IO) { store.summaries(DeckLocation.ARCHIVE) to store.summaries(DeckLocation.TRASH) }
        archives = lists.first; trash = lists.second
    }
    fun showLibrary(location: DeckLocation) {
        if (running) { message = "停止してからアーカイブを開いてください。"; return }
        if (busy) return
        libraryLocation = location
        viewModelScope.launch { refreshLibrary() }
    }
    fun closeLibrary() { if (!busy) libraryLocation = null }
    fun openArchived(id: String) = libraryAction {
        val loaded = withContext(Dispatchers.IO) { store.load(id).also { require(it.entry.location == DeckLocation.ARCHIVE) } }
        check(prefs.edit().putString("active", id).putString("name", loaded.entry.name).putString("total", loaded.entry.totalSeconds?.toString())
            .putString("anchor", "h1").putInt("offset", 0).putLong("elapsed", 0).commit()) { "資料の状態を保存できません。" }
        clock.reset(); running = false; elapsed = 0.0; deck = loaded.deck; totalSeconds = loaded.entry.totalSeconds
        anchor = ReadingAnchor(); currentSlide = 1; rebuild(); libraryLocation = null
    }
    fun moveToTrash(id: String) = libraryAction {
        withContext(Dispatchers.IO) { store.move(id, DeckLocation.TRASH) }
        if (deck?.directory?.name == id) { clock.reset(); running = false; elapsed = 0.0; deck = null; totalSeconds = null; anchor = ReadingAnchor(); currentSlide = 1; rebuild(); persist() }
    }
    fun restoreFromTrash(id: String) = libraryAction { withContext(Dispatchers.IO) { store.move(id, DeckLocation.ARCHIVE) } }
    fun deletePermanently(id: String) = libraryAction { withContext(Dispatchers.IO) { store.deletePermanently(id) } }
    private fun libraryAction(action: suspend () -> Unit) {
        if (running) { message = "停止してから資料を操作してください。"; return }
        if (busy || editorDraft != null) return
        libraryBusy = true
        viewModelScope.launch {
            try { action(); refreshLibrary() } catch (e: Exception) { message = e.message ?: "資料を操作できませんでした。" }
            finally { libraryBusy = false }
        }
    }
    fun beginScriptEdit(id: String? = deck?.directory?.name) {
        if (running) { message = "停止してから読み上げ原稿を編集してください。"; return }
        if (busy || editorDraft != null) return
        if (id == null) { message = "先にZIPを開いてください。"; return }
        editorBusy = true
        viewModelScope.launch {
            try {
                val loaded = withContext(Dispatchers.IO) { store.load(id).also { require(it.entry.location == DeckLocation.ARCHIVE) { "ゴミ箱の資料は復元してから編集してください。" } } }
                editorOriginal = loaded; editorError = null; editorDraft = loaded.source
            } catch (e: Exception) { message = e.message ?: "原稿を開けませんでした。" }
            finally { editorBusy = false }
        }
    }
    fun updateEditorDraft(text: String) { if (!editorBusy) { editorDraft = text; editorError = null } }
    fun closeEditor() { if (!editorBusy) { editorDraft = null; editorOriginal = null; editorError = null } }
    fun saveScriptEdit() {
        val original = editorOriginal ?: return
        val source = editorDraft ?: return
        if (editorBusy || running) return
        editorBusy = true; editorError = null
        viewModelScope.launch {
            try {
                val active = deck?.directory?.name == original.entry.id
                val result = withContext(Dispatchers.IO) {
                    val current = store.read(original.entry.id)
                    require(current.location == DeckLocation.ARCHIVE && current.modifiedAt == original.entry.modifiedAt) { "資料が変更されています。一度閉じて開き直してください。" }
                    val prepared = ScriptEditing.prepare(source, original.deck.script, original.entry.imageCount, if (active) totalSeconds else original.entry.totalSeconds, switchSeconds)
                    store.write(current.copy(scriptOverride = source, totalSeconds = prepared.totalSeconds, modifiedAt = System.currentTimeMillis()))
                    prepared
                }
                if (active) {
                    anchor = ScriptEditing.relocate(original.deck.script, result.script, anchor, currentSlide)
                    deck = original.deck.copy(script = result.script); totalSeconds = result.totalSeconds; rebuild(); persist()
                }
                editorDraft = null; editorOriginal = null; refreshLibrary()
            } catch (e: Exception) { editorError = e.message ?: "保存できませんでした。元の原稿を保持しています。" }
            finally { editorBusy = false }
        }
    }
    fun setVibration(enabled: Boolean, strength: Int) { vibrationEnabled = enabled; vibrationStrength = strength.coerceIn(0, 2); persist() }
}
