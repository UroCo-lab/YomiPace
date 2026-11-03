package jp.cn22.yomipace

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageDecoder
import android.graphics.Matrix
import androidx.exifinterface.media.ExifInterface
import android.net.Uri
import android.os.Build
import jp.cn22.yomipace.core.ZipImporter
import java.io.File
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.util.Locale
import java.util.UUID

/** Reads external media only. A deck becomes visible only after every PNG is complete. */
internal object SlideImageImporter {
    fun create(context: Context, root: File, uris: List<Uri>, name: String, progress: (Int) -> Unit = {}): StoredDeck {
        require(uris.size in 1..ZipImporter.MAX_SLIDES) { "画像は1～200枚を選んでください。" }
        val store = DeckStore(root)
        val directory = store.directory("deck-${UUID.randomUUID()}")
        check(directory.mkdirs()) { "画像の保存先を作れませんでした。" }
        try {
            var total = 0L
            uris.forEachIndexed { index, uri ->
                // Work from a bounded private copy; never rename, write, or delete a media URI.
                val copy = File(directory, ".image-input")
                (context.contentResolver.openInputStream(uri) ?: error("画像${index + 1}を開けません。再選択してください。")).use { input ->
                    copy.outputStream().use { output ->
                        val buffer = ByteArray(32 * 1024)
                        var size = 0L
                        while (true) {
                            val count = input.read(buffer)
                            if (count < 0) break
                            size += count
                            require(size <= ZipImporter.MAX_IMAGE) { "画像${index + 1}は24MiBを超えています。" }
                            output.write(buffer, 0, count)
                        }
                    }
                }
                val encoded = copy.readBytes()
                check(copy.delete()) { "画像の一時コピーを片付けられませんでした。" }
                val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                BitmapFactory.decodeByteArray(encoded, 0, encoded.size, bounds)
                require(bounds.outWidth > 0 && bounds.outHeight > 0 && bounds.outWidth.toLong() * bounds.outHeight <= 40_000_000) {
                    "画像${index + 1}を読み取れないか、40メガピクセルを超えています。"
                }
                val bitmap = decode(encoded)
                try {
                    val target = File(directory, String.format(Locale.ROOT, "%03d.png", index + 1))
                    target.outputStream().use { check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) { "PNG変換に失敗しました。" } }
                    total += target.length()
                    require(target.length() <= ZipImporter.MAX_IMAGE && total <= ZipImporter.MAX_TOTAL) { "保存画像は1枚24MiB、合計256MiB以下にしてください。" }
                } finally { bitmap.recycle() }
                progress(index + 1)
            }
            File(directory, "script.txt").writeText((1..uris.size).joinToString("\n\n") { "##$it\n" } + "\n", Charsets.UTF_8)
            val now = System.currentTimeMillis()
            store.write(DeckEntry(directory.name, name.trim().ifEmpty { "新しい読み上げ原稿" }, uris.size, now, now))
            return store.load(directory.name)
        } catch (e: Throwable) {
            // This is exclusively the new, app-owned directory, never an input URI.
            directory.deleteRecursively()
            if (e is OutOfMemoryError) throw IllegalArgumentException("画像が大きすぎて変換できません。小さな画像を選び直してください。")
            throw e
        }
    }

    fun thumbnail(context: Context, uri: Uri): Bitmap {
        val encoded = (context.contentResolver.openInputStream(uri) ?: error("画像を開けません。")).use { input ->
            ByteArrayOutputStream().use { output ->
                val buffer = ByteArray(32 * 1024)
                while (true) {
                    val count = input.read(buffer)
                    if (count < 0) break
                    require(output.size().toLong() + count <= ZipImporter.MAX_IMAGE) { "画像は24MiB以下にしてください。" }
                    output.write(buffer, 0, count)
                }
                output.toByteArray()
            }
        }
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(encoded, 0, encoded.size, bounds)
        require(bounds.outWidth > 0 && bounds.outHeight > 0 && bounds.outWidth.toLong() * bounds.outHeight <= 40_000_000)
        var sample = 1
        while (bounds.outWidth / sample > 1200 || bounds.outHeight / sample > 1200) sample *= 2
        return decode(encoded, sample)
    }

    private fun decode(encoded: ByteArray, sample: Int = 1): Bitmap {
        if (Build.VERSION.SDK_INT >= 28) return ImageDecoder.decodeBitmap(ImageDecoder.createSource(ByteBuffer.wrap(encoded))) { decoder, _, _ ->
            decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
            decoder.setTargetSampleSize(sample)
            decoder.setOnPartialImageListener { false }
        }
        val bitmap = BitmapFactory.decodeByteArray(encoded, 0, encoded.size, BitmapFactory.Options().apply { inSampleSize = sample }) ?: error("画像データが壊れています。")
        val orientation = runCatching { encoded.inputStream().use { ExifInterface(it).getAttributeInt(ExifInterface.TAG_ORIENTATION, 1) } }.getOrDefault(1)
        val matrix = Matrix().apply {
            when (orientation) {
                2 -> setScale(-1f, 1f)
                3 -> setRotate(180f)
                4 -> setScale(1f, -1f)
                5 -> { setRotate(90f); postScale(-1f, 1f) }
                6 -> setRotate(90f)
                7 -> { setRotate(-90f); postScale(-1f, 1f) }
                8 -> setRotate(-90f)
            }
        }
        if (matrix.isIdentity) return bitmap
        return try { Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true) }
        finally { bitmap.recycle() }
    }
}
