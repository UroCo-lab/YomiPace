package jp.cn22.yomipace.core

/** A paragraph completes when the last scheduled sentence of its source line turns grey. */
class ScheduledParagraphs(script: Script, timeline: Timeline) {
    private val ends = script.items.filterIsInstance<ScriptItem.Sentence>().groupBy { it.line }.values.map { sentences ->
        sentences.maxOf { timeline.byId.getValue(it.id).end }
    }
    private var observed = 0.0
    fun seek(elapsed: Double) { observed = elapsed }
    fun advance(elapsed: Double): Boolean {
        val crossed = elapsed > observed && ends.any { it > observed && it <= elapsed }
        observed = elapsed
        return crossed
    }
}
