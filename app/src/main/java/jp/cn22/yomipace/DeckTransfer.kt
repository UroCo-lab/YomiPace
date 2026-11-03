package jp.cn22.yomipace

import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.core.content.FileProvider
import jp.cn22.yomipace.core.SlideScriptDraft
import jp.cn22.yomipace.core.ZipImporter
import java.io.File
import java.util.Locale
import java.util.UUID
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/** A portable snapshot of the saved presentation; never export original/stale ZIPs or app metadata. */
internal object DeckTransfer {
    fun export(context: Context, loaded: StoredDeck): File {
        require(loaded.entry.location == DeckLocation.ARCHIVE) { "ゴミ箱の資料は復元してから共有してください。" }
        val parent = File(context.cacheDir, "shared-zips/${UUID.randomUUID()}").apply { check(mkdirs()) }
        val name = loaded.entry.name.replace(Regex("[\\p{Cntrl}/\\\\:*?\"<>|]"), "_").trim().take(60).ifBlank { "YomiPace" }
        val target = File(parent, name.replace(Regex("(?i)\\.zip$"), "") + ".zip")
        var converted: StoredDeck? = null
        try {
            converted = SlideImageImporter.create(context, File(context.cacheDir, "export-work"), loaded.deck.images.map { Uri.fromFile(it) }, loaded.entry.name)
            val source = SlideScriptDraft.parse(loaded.source, loaded.entry.imageCount)
                .render(seconds = loaded.entry.totalSeconds, replaceTime = true).toByteArray(Charsets.UTF_8)
            require(source.size <= ZipImporter.MAX_SCRIPT) { "原稿は2MiB以下にしてください。" }
            require(converted.deck.images.sumOf { it.length() } + source.size <= ZipImporter.MAX_TOTAL) { "共有資料は合計256MiB以下にしてください。" }
            ZipOutputStream(target.outputStream().buffered()).use { zip ->
                converted.deck.images.forEachIndexed { i, file ->
                    zip.putNextEntry(ZipEntry(String.format(Locale.ROOT, "%03d.png", i + 1)))
                    file.inputStream().use { it.copyTo(zip) }; zip.closeEntry()
                }
                zip.putNextEntry(ZipEntry("script.txt")); zip.write(source); zip.closeEntry()
            }
            return target
        } catch (e: Exception) { parent.deleteRecursively(); throw e }
        finally { converted?.deck?.directory?.deleteRecursively() }
    }

    fun duplicate(context: Context, store: DeckStore, loaded: StoredDeck): StoredDeck {
        val zip = export(context, loaded)
        val directory = store.directory("deck-${UUID.randomUUID()}")
        try {
            val names = store.summaries(DeckLocation.ARCHIVE).map { it.name }.toSet()
            var name = "${loaded.entry.name}（コピー）"; var number = 2
            while (name in names) name = "${loaded.entry.name}（コピー${number++}）"
            val imported = zip.inputStream().use { ZipImporter.read(it, directory, name) }
            zip.copyTo(File(directory, "source.zip"))
            val now = System.currentTimeMillis()
            store.write(DeckEntry(directory.name, name, imported.images.size, now, now, totalSeconds = loaded.entry.totalSeconds, scriptOverride = loaded.source))
            return store.load(directory.name)
        } catch (e: Exception) { directory.deleteRecursively(); throw e }
        finally { zip.parentFile?.deleteRecursively() }
    }

    fun shareIntent(context: Context, file: File): Intent {
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.files", file)
        return Intent(Intent.ACTION_SEND).apply {
            type = "application/zip"; putExtra(Intent.EXTRA_STREAM, uri)
            clipData = ClipData.newUri(context.contentResolver, file.name, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
    }
    fun share(context: Context, file: File) {
        context.startActivity(Intent.createChooser(shareIntent(context, file), "ZIPを共有"))
    }
}
