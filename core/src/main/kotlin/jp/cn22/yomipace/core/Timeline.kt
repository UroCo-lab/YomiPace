package jp.cn22.yomipace.core

enum class SegmentKind { SENTENCE, FIXED, SWITCH }
data class Segment(val id: String, val kind: SegmentKind, val start: Double, val end: Double, val slide: Int) {
    val duration get() = end - start
    fun fraction(elapsed: Double): Double = if (duration <= 0.0) { if (elapsed >= start) 1.0 else 0.0 } else ((elapsed - start) / duration).coerceIn(0.0, 1.0)
}
data class Timeline(val totalSeconds: Double, val segments: List<Segment>, val characterCount: Int) {
    val byId = segments.associateBy { it.id }
    companion object {
        fun build(script: Script, totalSeconds: Double?, switchSeconds: Double): Timeline {
            require(script.fatalIssues.isEmpty()) { script.fatalIssues.joinToString("\n") }
            require(totalSeconds != null && totalSeconds.isFinite() && totalSeconds > 0) { "メニューの「持ち時間」で発表時間を設定してください。" }
            require(switchSeconds.isFinite() && switchSeconds >= 0) { "切替時間は0以上の有限の秒数を入力してください。" }
            val count = script.characterCount
            require(count > 0) { "読み上げ本文が0文字です。本文を追加してください。" }
            val blocks = script.items.filterIsInstance<ScriptItem.Fixed>().sumOf { it.seconds }
            val switches = (script.slides.size - 1).coerceAtLeast(0) * switchSeconds
            val reading = totalSeconds - blocks - switches
            require(reading > 0) { "持ち時間が説明区間と切替時間の合計以下です。持ち時間を増やすか固定時間を減らしてください。" }
            val segments = mutableListOf<Segment>()
            var fixedSoFar = 0.0
            var charsSoFar = 0
            fun at() = fixedSoFar + reading * charsSoFar / count
            script.items.forEach { item ->
                val start = at()
                when (item) {
                    is ScriptItem.Comment -> Unit
                    is ScriptItem.Heading -> if (item.slide > 1) {
                        fixedSoFar += switchSeconds
                        segments += Segment("switch${item.slide}", SegmentKind.SWITCH, start, at(), item.slide)
                    }
                    is ScriptItem.Fixed -> {
                        fixedSoFar += item.seconds
                        segments += Segment(item.id, SegmentKind.FIXED, start, at(), item.slide)
                    }
                    is ScriptItem.Sentence -> {
                        charsSoFar += spokenCount(item.text)
                        segments += Segment(item.id, SegmentKind.SENTENCE, start, at(), item.slide)
                    }
                }
            }
            // Avoid accumulated floating point drift at the final endpoint.
            if (segments.isNotEmpty()) segments[segments.lastIndex] = segments.last().copy(end = totalSeconds)
            return Timeline(totalSeconds, segments, count)
        }
    }
}

/** Actual manual position is intentionally independent of the scheduled timeline. */
data class ReadingAnchor(val itemId: String = "h1", val utf16Offset: Int = 0)

/** The source is Android elapsedRealtime, injected so pause/resume can be tested. */
class PresentationClock(private val nowMillis: () -> Long) {
    var running = false
        private set
    private var accumulated = 0L
    private var startedAt = 0L
    val elapsedMillis: Long get() = accumulated + if (running) (nowMillis() - startedAt).coerceAtLeast(0) else 0L
    fun play() { if (!running) { startedAt = nowMillis(); running = true } }
    fun pause() { if (running) { accumulated = elapsedMillis; running = false } }
    fun reset() { running = false; accumulated = 0L }
    fun restorePaused(millis: Long) { running = false; accumulated = millis.coerceAtLeast(0) }
}
