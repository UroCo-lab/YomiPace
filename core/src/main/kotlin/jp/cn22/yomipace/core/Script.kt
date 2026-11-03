package jp.cn22.yomipace.core

sealed interface ScriptItem {
    val id: String
    val slide: Int
    data class Heading(override val id: String, override val slide: Int) : ScriptItem
    data class Sentence(override val id: String, override val slide: Int, val text: String, val line: Int) : ScriptItem
    data class Comment(override val id: String, override val slide: Int, val text: String, val line: Int) : ScriptItem
    data class Fixed(override val id: String, override val slide: Int, val label: String, val seconds: Double) : ScriptItem
}

data class InputIssue(val line: Int, val message: String, val timeOnly: Boolean = false) {
    override fun toString() = "${line}行目：$message"
}
data class Script(val items: List<ScriptItem>, val declaredSeconds: Double?, val issues: List<InputIssue>) {
    val slides get() = items.filterIsInstance<ScriptItem.Heading>().map { it.slide }
    val fatalIssues get() = issues.filterNot { it.timeOnly }
    val characterCount get() = items.filterIsInstance<ScriptItem.Sentence>().sumOf { spokenCount(it.text) }
}

fun spokenCount(text: String): Int = text.codePoints().filter { !Character.isWhitespace(it) && !Character.isSpaceChar(it) }.count().toInt()

object ScriptParser {
    private val time = Regex("time\\((\\d+):([0-5]\\d)\\)")
    private val heading = Regex("##([1-9]\\d*)")
    private val legacyHeading = Regex("#([1-9]\\d*)")
    private val fixed = Regex("\\[(.+)\\((\\d+):([0-5]\\d)\\)\\]")
    private fun seconds(m: String, s: String): Double? = m.toLongOrNull()?.takeIf { it <= 1_000_000 }?.let { it * 60.0 + s.toInt() }

    fun parse(source: String): Script {
        val items = mutableListOf<ScriptItem>()
        val issues = mutableListOf<InputIssue>()
        var slide = 0
        var expected = 1
        var timeCount = 0
        var declared: Double? = null
        var firstContent = true
        val lines = source.removePrefix("\uFEFF").replace("\r\n", "\n").replace('\r', '\n').split('\n')
        // Keep saved decks readable. A new-format script never interprets single # as a boundary.
        val modern = lines.any { it.trim().startsWith("##") }
        lines.forEachIndexed { index, raw ->
            val line = index + 1
            val text = raw.trim()
            if (text.isEmpty()) return@forEachIndexed
            if (text.startsWith("**")) {
                items += ScriptItem.Comment("c$line", slide.coerceAtLeast(1), text.drop(2).trimStart(), line)
                return@forEachIndexed
            }
            val isFirst = firstContent
            firstContent = false
            if (Regex("^time\\s*[（(]", RegexOption.IGNORE_CASE).containsMatchIn(text)) {
                timeCount++
                val match = time.matchEntire(text)
                val value = match?.let { seconds(it.groupValues[1], it.groupValues[2]) }
                if (match == null || value == null || value <= 0) issues += InputIssue(line, "time(07:00) の形式で、0より長い時間を指定してください（秒は00～59）。持ち時間は画面でも設定できます。", true)
                else if (!isFirst || timeCount > 1) issues += InputIssue(line, "time指定は原稿先頭に1回だけ置いてください。持ち時間を画面で確定してください。", true)
                else declared = value
                return@forEachIndexed
            }
            if (text.startsWith("##") || !modern && legacyHeading.matches(text)) {
                val number = (if (modern) heading else legacyHeading).matchEntire(text)?.groupValues?.get(1)?.toIntOrNull()
                if (number != expected) { issues += InputIssue(line, "スライド境界は ##$expected を単独行に書いてください。"); return@forEachIndexed }
                slide = number
                expected++
                items += ScriptItem.Heading("h$slide", slide)
                return@forEachIndexed
            }
            if (slide == 0) { issues += InputIssue(line, "本文・説明区間の前に ##1 を単独行に置いてください。"); return@forEachIndexed }
            val block = fixed.matchEntire(text)
            if (block != null) {
                val label = block.groupValues[1].trim()
                val duration = seconds(block.groupValues[2], block.groupValues[3])
                if (label.isEmpty() || duration == null) issues += InputIssue(line, "[図を説明(00:30)] の形式で、表示名と有効な時間を指定してください。")
                else items += ScriptItem.Fixed("b$line", slide, label, duration)
                return@forEachIndexed
            }
            val controlLike = text.startsWith("[") || text.startsWith("［") || Regex("^(movie|pause|table|figure)\\s*\\(", RegexOption.IGNORE_CASE).containsMatchIn(text) || Regex(".*[（(]\\d+[:：].*").matches(text)
            if (controlLike) { issues += InputIssue(line, "時間区間は [任意の表示名(00:30)] を単独行に書いてください。半角括弧・コロン、秒00～59を使います。"); return@forEachIndexed }
            var start = 0
            var part = 0
            var i = 0
            while (i < text.length) {
                if (text[i] in "。！？!?．.") {
                    // A decimal point or initials are still ordinary characters, not mandatory boundaries.
                    val decimal = text[i] == '.' && i > 0 && i + 1 < text.length && text[i - 1].isDigit() && text[i + 1].isDigit()
                    if (!decimal) {
                        while (i + 1 < text.length && text[i + 1] in "。！？!?．.」』）)\"”") i++
                        val sentence = text.substring(start, i + 1)
                        if (spokenCount(sentence) > 0) items += ScriptItem.Sentence("s$line-${part++}", slide, sentence, line)
                        start = i + 1
                    }
                }
                i++
            }
            if (start < text.length && spokenCount(text.substring(start)) > 0) items += ScriptItem.Sentence("s$line-$part", slide, text.substring(start), line)
        }
        if (timeCount > 1 || issues.any { it.timeOnly }) declared = null
        if (slide == 0) issues += InputIssue(1, "##1 から始まるスライド境界がありません。")
        return Script(items, declared, issues)
    }
}
