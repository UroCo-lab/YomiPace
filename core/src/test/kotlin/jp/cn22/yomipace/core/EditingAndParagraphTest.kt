package jp.cn22.yomipace.core

import org.junit.Assert.*
import org.junit.Test

class EditingAndParagraphTest {
    private val old = ScriptParser.parse("time(01:00)\n#1\n最初です。次の文です。\n次の段落です。\n#2\n結論。")
    private fun fails(block: () -> Unit): String = try { block(); error("Expected rejection") } catch (e: IllegalArgumentException) { e.message.orEmpty() }
    @Test fun textOnlyEditKeepsScreenOverrideAndExplicitTimeChangeUpdatesIt() {
        val source = "time(01:00)\n#1\n編集後です。\n#2\n結論。"
        assertEquals(90.0, ScriptEditing.prepare(source, old, 2, 90.0, 2.0).totalSeconds!!, 0.0)
        assertEquals(120.0, ScriptEditing.prepare(source.replace("01:00", "02:00"), old, 2, 90.0, 2.0).totalSeconds!!, 0.0)
        assertEquals(90.0, ScriptEditing.prepare(source.substringAfter('\n'), old, 2, 90.0, 2.0).totalSeconds!!, 0.0)
        assertNull(ScriptEditing.prepare(source.substringAfter('\n'), old, 2, null, 2.0).totalSeconds)
    }
    @Test fun editedScriptRejectsBadSyntaxSlideMismatchAndImpossibleBudget() {
        assertTrue(fails { ScriptEditing.prepare("#1\n本文。\n[図(00:99)]\n#2", old, 2, 60.0, 2.0) }.contains("3行目"))
        assertTrue(fails { ScriptEditing.prepare("#1\n本文。", old, 2, 60.0, 2.0) }.contains("画像"))
        assertTrue(fails { ScriptEditing.prepare("#1\n本文。\n[図(02:00)]\n#2", old, 2, 60.0, 2.0) }.contains("合計以下"))
        assertTrue(fails { ScriptEditing.prepare("#1\n#2", old, 2, 60.0, 2.0) }.contains("0文字"))
    }
    @Test fun anchorFollowsUnchangedTextAfterInsertedLinesAndFallsBackOnChangedText() {
        val first = old.items.filterIsInstance<ScriptItem.Sentence>().first()
        val inserted = ScriptParser.parse("\n\ntime(01:00)\n#1\n最初です。次の文です。\n次の段落です。\n#2\n結論。")
        val relocated = ScriptEditing.relocate(old, inserted, ReadingAnchor(first.id, 2), 1)
        assertEquals(2, relocated.utf16Offset)
        assertEquals(first.text, (inserted.items.find { it.id == relocated.itemId } as ScriptItem.Sentence).text)
        assertEquals(ReadingAnchor("h1"), ScriptEditing.relocate(old, ScriptParser.parse("#1\n別の内容。\n#2\n結論。"), ReadingAnchor(first.id, 2), 1))
    }
    @Test fun vibrationWaitsForEntireParagraphNotItsFirstSentence() {
        val timeline = Timeline.build(old, 60.0, 2.0)
        val tracker = ScheduledParagraphs(old, timeline)
        val sentences = old.items.filterIsInstance<ScriptItem.Sentence>()
        val firstEnd = timeline.byId.getValue(sentences[0].id).end
        val paragraphEnd = timeline.byId.getValue(sentences[1].id).end
        assertFalse(tracker.advance(firstEnd))
        assertTrue(tracker.advance(paragraphEnd))
        assertFalse(tracker.advance(paragraphEnd))
    }
    @Test fun fixedIntervalsAndManualPositionsCannotTriggerParagraphVibration() {
        val script = ScriptParser.parse("#1\n本文。\n[図(00:30)]\n次の段落。")
        val t = Timeline.build(script, 60.0, 0.0)
        val tracker = ScheduledParagraphs(script, t)
        val block = t.segments.single { it.kind == SegmentKind.FIXED }
        assertTrue(tracker.advance(block.start))
        assertFalse(tracker.advance(block.start + 15))
        assertFalse(tracker.advance(block.end))
        val anchor = ReadingAnchor("s4-0", 3)
        assertEquals("s4-0", anchor.itemId)
        assertFalse(tracker.advance(block.end))
        assertTrue(tracker.advance(t.totalSeconds))
    }
    @Test fun restoredOrRebuiltTimelineSkipsPastParagraphsWithoutVibrating() {
        val t = Timeline.build(old, 60.0, 2.0)
        val tracker = ScheduledParagraphs(old, t)
        tracker.seek(59.0)
        assertFalse(tracker.advance(59.0))
        assertTrue(tracker.advance(60.0))
        tracker.seek(0.0)
        assertFalse(tracker.advance(0.0))
        assertTrue(tracker.advance(60.0)) // one pulse for a frame which crosses several ends
    }
}
