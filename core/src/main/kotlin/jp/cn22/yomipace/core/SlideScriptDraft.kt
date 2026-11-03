package jp.cn22.yomipace.core

/** Editable slide blocks retain all text, including comments, blank slides and fixed intervals. */
data class SlideScriptDraft(val preamble: String, val bodies: List<String>) {
    fun render(bodies: List<String> = this.bodies, seconds: Double? = null, replaceTime: Boolean = false): String {
        val prefix = if (!replaceTime) preamble else {
            val other = preamble.split('\n').filterNot { Regex("^time\\s*[（(]", RegexOption.IGNORE_CASE).containsMatchIn(it.trim()) }
                .joinToString("\n").trim('\n')
            val time = seconds?.let { "time(${it.toLong() / 60}:${(it.toLong() % 60).toString().padStart(2, '0')})" }
            listOfNotNull(time, other.takeIf { it.isNotEmpty() }).joinToString("\n")
        }
        val head = if (prefix.isEmpty()) "" else prefix.trimEnd('\n') + "\n\n"
        return head + bodies.mapIndexed { i, body -> "##${i + 1}\n$body" }.joinToString("\n\n") + "\n"
    }

    companion object {
        fun parse(source: String, imageCount: Int): SlideScriptDraft {
            require(imageCount in 1..ZipImporter.MAX_SLIDES) { "画像は1～200枚が必要です。" }
            val lines = source.removePrefix("\uFEFF").replace("\r\n", "\n").replace('\r', '\n').split('\n')
            val modern = lines.any { it.trim().startsWith("##") }
            val heading = if (modern) Regex("##([1-9]\\d*)") else Regex("#([1-9]\\d*)")
            val boundaries = lines.indices.filter { heading.matches(lines[it].trim()) }
            require(boundaries.map { heading.matchEntire(lines[it].trim())!!.groupValues[1].toInt() } == (1..imageCount).toList()) {
                "スライド番号と画像の枚数が一致しません。"
            }
            val bodies = boundaries.mapIndexed { index, start ->
                val end = boundaries.getOrNull(index + 1) ?: lines.size
                // Separators are recreated; keep internal blank lines and all actual text unchanged.
                lines.subList(start + 1, end).joinToString("\n").trimEnd('\n')
            }
            return SlideScriptDraft(lines.take(boundaries.first()).joinToString("\n"), bodies)
        }
    }
}
