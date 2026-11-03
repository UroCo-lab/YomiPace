package jp.cn22.yomipace.core

data class PreparedScriptEdit(val script: Script, val totalSeconds: Double?)

object ScriptEditing {
    fun prepare(source: String, oldScript: Script, imageCount: Int, currentTotal: Double?, switchSeconds: Double): PreparedScriptEdit {
        require(source.toByteArray(Charsets.UTF_8).size <= ZipImporter.MAX_SCRIPT) { "原稿はUTF-8で2MiB以下にしてください。" }
        val script = ScriptParser.parse(source)
        require(script.issues.isEmpty()) { script.issues.take(20).joinToString("\n") }
        require(script.slides == (1..imageCount).toList()) { "##番号は画像に合わせて ##1～##$imageCount を順に置いてください。画像の枚数は編集できません。" }
        require(script.characterCount > 0) { "読み上げ本文が0文字です。本文を追加してください。" }
        val total = if (script.declaredSeconds != null && script.declaredSeconds != oldScript.declaredSeconds) script.declaredSeconds else currentTotal
        if (total != null) Timeline.build(script, total, switchSeconds)
        return PreparedScriptEdit(script, total)
    }
    fun relocate(old: Script, edited: Script, anchor: ReadingAnchor, slide: Int): ReadingAnchor {
        val previous = old.items.find { it.id == anchor.itemId }
        fun same(item: ScriptItem) = item.slide == slide && when {
            previous is ScriptItem.Sentence && item is ScriptItem.Sentence -> previous.text == item.text
            previous is ScriptItem.Comment && item is ScriptItem.Comment -> previous.text == item.text
            previous is ScriptItem.Fixed && item is ScriptItem.Fixed -> previous.label == item.label && previous.seconds == item.seconds
            previous is ScriptItem.Heading && item is ScriptItem.Heading -> true
            else -> false
        }
        val item = edited.items.find { it.id == anchor.itemId && same(it) } ?: edited.items.find(::same)
        val length = when (item) {
            is ScriptItem.Sentence -> item.text.length
            is ScriptItem.Comment -> item.text.length
            else -> 0
        }
        return if (item == null) ReadingAnchor("h$slide") else ReadingAnchor(item.id, anchor.utf16Offset.coerceIn(0, length))
    }
}

