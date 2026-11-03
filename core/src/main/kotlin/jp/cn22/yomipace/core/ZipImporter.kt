package jp.cn22.yomipace.core

import java.io.File
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.util.zip.ZipInputStream

data class ImportedDeck(val directory: File, val images: List<File>, val script: Script, val name: String)

/** Transactional: caller only activates a fully validated staging directory. Never extracts entry paths. */
object ZipImporter {
    const val MAX_TOTAL = 256L * 1024 * 1024
    const val MAX_IMAGE = 24L * 1024 * 1024
    const val MAX_SCRIPT = 2L * 1024 * 1024
    const val MAX_SLIDES = 200
    private val imageName = Regex("(\\d{3})\\.(png|jpg|jpeg)", RegexOption.IGNORE_CASE)

    fun read(input: InputStream, staging: File, displayName: String): ImportedDeck {
        require(!staging.exists() || staging.listFiles().isNullOrEmpty()) { "取り込み先が空ではありません。" }
        staging.mkdirs()
        try {
            val imageFiles = sortedMapOf<Int, File>()
            var scriptFile: File? = null
            var commonParent: String? = null
            var total = 0L
            var entries = 0
            val names = mutableSetOf<String>()
            ZipInputStream(input.buffered()).use { zip ->
                while (true) {
                    val entry = zip.nextEntry ?: break
                    entries++
                    require(entries <= 1000) { "ZIP内の項目が多すぎます（上限1000）。" }
                    val path = entry.name
                    require(!path.startsWith('/') && !path.contains('\\') && !path.contains(':') && !path.contains('\u0000') && path.split('/').none { it == ".." || it == "." }) { "危険なZIP内パスです：$path" }
                    val parts = path.trimEnd('/').split('/')
                    // Ignore only known metadata; read it too so expansion remains bounded.
                    val metadata = parts.firstOrNull() == "__MACOSX" || parts.lastOrNull() == ".DS_Store"
                    require(entry.isDirectory || metadata || parts.size <= 2) { "資料はZIP直下、または一段の親フォルダにまとめてください：$path" }
                    var target: File? = null
                    var limit = MAX_IMAGE
                    if (!entry.isDirectory && !metadata) {
                        val parent = parts.dropLast(1).joinToString("/")
                        require(commonParent == null || commonParent == parent) { "複数の資料フォルダが混在しています。1つの資料だけにしてください。" }
                        commonParent = parent
                        val leaf = parts.last()
                        require(names.add(leaf.lowercase())) { "同名ファイルが重複しています：$leaf" }
                        val image = imageName.matchEntire(leaf)
                        if (leaf.equals("script.txt", true)) {
                            require(scriptFile == null) { "script.txtが重複しています。" }
                            target = File(staging, "script.txt"); scriptFile = target; limit = MAX_SCRIPT
                        } else if (image != null) {
                            val number = image.groupValues[1].toInt()
                            require(number in 1..MAX_SLIDES) { "画像番号は001～200にしてください：$leaf" }
                            require(number !in imageFiles) { "同じ番号の画像が重複しています：$number" }
                            target = File(staging, leaf.lowercase()); imageFiles[number] = target
                        } else throw IllegalArgumentException("未対応のファイルです：$leaf。画像は001.png等の3桁連番にしてください。")
                    }
                    var size = 0L
                    val out = target?.outputStream()
                    try {
                        val buffer = ByteArray(32 * 1024)
                        while (true) {
                            val count = zip.read(buffer)
                            if (count < 0) break
                            size += count; total += count
                            require(size <= limit && total <= MAX_TOTAL) { "展開サイズが上限を超えました（画像24MiB、原稿2MiB、合計256MiB）。" }
                            out?.write(buffer, 0, count)
                        }
                    } finally { out?.close() }
                    zip.closeEntry()
                }
            }
            require(imageFiles.isNotEmpty()) { "001.pngから始まるスライド画像がありません。" }
            require(imageFiles.keys.toList() == (1..imageFiles.size).toList()) { "画像を001から欠番のない3桁連番にしてください。" }
            require(scriptFile != null) { "UTF-8のscript.txtがありません。" }
            val decoder = Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT)
            val source = try { decoder.decode(ByteBuffer.wrap(scriptFile!!.readBytes())).toString() } catch (_: Exception) { throw IllegalArgumentException("script.txtはUTF-8で保存してください（BOM可）。") }
            val script = ScriptParser.parse(source)
            require(script.fatalIssues.isEmpty()) { script.fatalIssues.joinToString("\n") }
            require(script.slides == imageFiles.keys.toList()) { "原稿の##番号と画像番号・枚数が一致しません。説明のない画像にも##番号を置いてください。" }
            return ImportedDeck(staging, imageFiles.values.toList(), script, displayName)
        } catch (e: Exception) {
            // staging belongs to this call and contains only fixed flat filenames generated above.
            staging.deleteRecursively()
            throw e
        }
    }
}
