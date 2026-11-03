package jp.cn22.yomipace

import jp.cn22.yomipace.core.*
import org.json.JSONObject
import org.json.JSONArray
import java.io.File
import java.io.IOException
import java.io.FileOutputStream
import java.nio.file.Files
import java.nio.file.StandardCopyOption

enum class DeckLocation { ARCHIVE, TRASH }
data class DeckEntry(val id: String, val name: String, val imageCount: Int, val createdAt: Long, val modifiedAt: Long,
    val location: DeckLocation = DeckLocation.ARCHIVE, val trashedAt: Long? = null, val totalSeconds: Double? = null,
    val scriptOverride: String? = null, val imageOrder: List<Int>? = null, val imageRevision: String? = null)
data class StoredDeck(val entry: DeckEntry, val deck: ImportedDeck, val source: String)
data class DeckSummary(val id: String, val name: String, val imageCount: Int, val modifiedAt: Long, val edited: Boolean)

/** Original ZIP/images remain immutable; metadata and edited source commit in one atomic rename. */
class DeckStore(private val root: File) {
    init { root.mkdirs() }
    fun directory(id: String): File {
        require(Regex("deck-[a-f0-9-]+").matches(id)) { "資料IDが不正です。" }
        return File(root, id).also { require(it.canonicalFile.parentFile == root.canonicalFile) }
    }
    @Synchronized fun read(id: String): DeckEntry {
        val json = JSONObject(File(directory(id), "entry.json").readText(Charsets.UTF_8))
        return DeckEntry(id, json.getString("name"), json.getInt("imageCount"), json.getLong("createdAt"), json.getLong("modifiedAt"),
            DeckLocation.valueOf(json.getString("location")), if (json.isNull("trashedAt")) null else json.getLong("trashedAt"),
            if (json.isNull("totalSeconds")) null else json.getDouble("totalSeconds"), if (json.isNull("scriptOverride")) null else json.getString("scriptOverride"),
            json.optJSONArray("imageOrder")?.let { order -> List(order.length()) { order.getInt(it) } },
            if (json.isNull("imageRevision")) null else json.getString("imageRevision"))
    }
    @Synchronized fun write(entry: DeckEntry) {
        val json = JSONObject().put("name", entry.name).put("imageCount", entry.imageCount).put("createdAt", entry.createdAt)
            .put("modifiedAt", entry.modifiedAt).put("location", entry.location.name).put("trashedAt", entry.trashedAt ?: JSONObject.NULL)
            .put("totalSeconds", entry.totalSeconds ?: JSONObject.NULL).put("scriptOverride", entry.scriptOverride ?: JSONObject.NULL)
            .put("imageOrder", entry.imageOrder?.let { JSONArray(it) } ?: JSONObject.NULL)
            .put("imageRevision", entry.imageRevision ?: JSONObject.NULL)
        val target = File(directory(entry.id), "entry.json")
        val pending = File(directory(entry.id), "entry.json.pending")
        try {
            FileOutputStream(pending).use { output -> output.write(json.toString().toByteArray(Charsets.UTF_8)); output.fd.sync() }
            Files.move(pending.toPath(), target.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        } catch (e: Exception) { pending.delete(); throw e }
    }
    @Synchronized fun migrateActive(id: String, name: String, total: Double?) {
        val directory = directory(id)
        if (File(directory, "entry.json").exists()) return
        require(File(directory, "script.txt").isFile)
        val count = images(directory).size
        val now = System.currentTimeMillis()
        write(DeckEntry(id, name, count, now, now, totalSeconds = total))
    }
    private fun images(dir: File) = dir.listFiles().orEmpty().filter { Regex("\\d{3}\\.(png|jpg|jpeg)").matches(it.name) }.sortedBy { it.name }
    fun revisionDirectory(id: String, revision: String): File {
        require(Regex("deck-[a-f0-9-]+").matches(revision)) { "保存資料の画像セットが不正です。" }
        val parent = File(directory(id), "revisions")
        return File(parent, revision).also { require(it.canonicalFile.parentFile == parent.canonicalFile) }
    }
    @Synchronized fun load(id: String): StoredDeck {
        val entry = read(id)
        val dir = directory(id)
        val source = entry.scriptOverride ?: File(dir, "script.txt").readText(Charsets.UTF_8)
        val script = ScriptParser.parse(source)
        val images = images(entry.imageRevision?.let { revisionDirectory(id, it) } ?: dir)
        require(images.map { it.name.substringBefore('.').toInt() } == (1..images.size).toList()) {
            "保存資料の画像番号が001から連続していません。元のZIPから読み込み直してください。"
        }
        require(script.fatalIssues.isEmpty() && images.size == entry.imageCount && script.slides == (1..images.size).toList()) { "保存資料を読み取れません。" }
        val order = entry.imageOrder ?: (1..images.size).toList()
        require(order.sorted() == (1..images.size).toList()) { "保存資料のスライド順が不正です。" }
        return StoredDeck(entry, ImportedDeck(dir, order.map { images[it - 1] }, script, entry.name), source)
    }
    @Synchronized fun summaries(location: DeckLocation): List<DeckSummary> = root.listFiles().orEmpty()
        .filter { it.isDirectory && Regex("deck-[a-f0-9-]+").matches(it.name) }
        .mapNotNull { dir -> runCatching { read(dir.name) }.getOrNull() }
        .filter { it.location == location }.sortedByDescending { it.modifiedAt }
        .map { DeckSummary(it.id, it.name, it.imageCount, it.modifiedAt, it.scriptOverride != null || it.imageOrder != null || it.imageRevision != null) }
    @Synchronized fun move(id: String, location: DeckLocation) {
        val now = System.currentTimeMillis()
        write(read(id).copy(location = location, modifiedAt = now, trashedAt = if (location == DeckLocation.TRASH) now else null))
    }
    @Synchronized fun deletePermanently(id: String) {
        require(read(id).location == DeckLocation.TRASH) { "完全削除できるのはゴミ箱の資料だけです。" }
        val target = directory(id) // Validated direct child of the app-owned store.
        val marker = File(target, "entry.json")
        // Keep the trash record available for retry if any content cannot be removed.
        target.listFiles().orEmpty().filter { it != marker }.sortedBy { it.name }.forEach { file ->
            if (!file.deleteRecursively()) throw IOException("資料の削除を完了できませんでした。もう一度お試しください。")
        }
        if (!marker.delete() || !target.delete()) throw IOException("資料の削除を完了できませんでした。")
    }
}
