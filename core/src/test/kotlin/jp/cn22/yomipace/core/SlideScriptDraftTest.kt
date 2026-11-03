package jp.cn22.yomipace.core

import org.junit.Assert.*
import org.junit.Test

class SlideScriptDraftTest {
    @Test fun splitsModernPagesWithoutLosingNotesFixedIntervalsOrEmptySlides() {
        val source = "**全体のメモ\ntime(7:00)\n\n##1\n本文#1です。\n\n次の段落。\n** ##99 はメモ\n\n##2\n[図(00:20)]\n二枚目です。\n\n##3\n"
        val draft = SlideScriptDraft.parse(source, 3)
        assertEquals("**全体のメモ\ntime(7:00)\n", draft.preamble)
        assertTrue(draft.bodies[0].contains("本文#1です。\n\n次の段落。"))
        assertTrue(draft.bodies[0].contains("** ##99 はメモ")); assertEquals("", draft.bodies[2])
        assertEquals(ScriptParser.parse(source).characterCount, ScriptParser.parse(draft.render()).characterCount)
    }
    @Test fun reorderingBodiesRenumbersSlidesAndMovesTheirFixedIntervalsAndComments() {
        val draft = SlideScriptDraft.parse("time(7:00)\n##1\n一枚目。\n**メモ1\n##2\n[図(00:20)]\n二枚目。\n##3\n", 3)
        val result = ScriptParser.parse(draft.render(listOf(draft.bodies[2], draft.bodies[0], draft.bodies[1])))
        assertTrue(result.issues.isEmpty()); assertEquals(listOf(1, 2, 3), result.slides)
        assertEquals(2, result.items.filterIsInstance<ScriptItem.Comment>().single().slide)
        assertEquals(3, result.items.filterIsInstance<ScriptItem.Fixed>().single().slide)
        assertEquals(420.0, result.declaredSeconds!!, 0.0)
        assertFalse(result.items.any { it.slide == 1 && it is ScriptItem.Sentence })
    }
    @Test fun legacyBomCrLfScriptsKeepBodyTextAndCanBeEditedInModernPageFormat() {
        val source = "\uFEFFtime(04:00)\r\n#1\r\n既存の本文です。\r\n** 読まないメモ\r\n#2\r\n[動画(00:10)]\r\n"
        val draft = SlideScriptDraft.parse(source, 2)
        val result = ScriptParser.parse(draft.render())
        assertTrue(result.issues.isEmpty()); assertEquals(240.0, result.declaredSeconds!!, 0.0)
        assertEquals("既存の本文です。\n** 読まないメモ", draft.bodies[0])
        assertEquals(ScriptParser.parse(source).characterCount, result.characterCount)
    }
    @Test fun replacingOrClearingTimePreservesPreambleCommentsAndAllPageBodies() {
        val draft = SlideScriptDraft.parse("**time(99:00) はメモ\ntime(4:00)\n##1\n本文です。", 1)
        val set = ScriptParser.parse(draft.render(seconds = 450.0, replaceTime = true))
        assertTrue(set.issues.isEmpty()); assertEquals(450.0, set.declaredSeconds!!, 0.0)
        val cleared = ScriptParser.parse(draft.render(seconds = null, replaceTime = true))
        assertTrue(cleared.issues.isEmpty()); assertNull(cleared.declaredSeconds)
        assertTrue(cleared.items.filterIsInstance<ScriptItem.Comment>().single().text.contains("time(99:00)"))
    }
    @Test fun mismatchBetweenHeadingsAndImagesIsRejectedBeforeAnEditorIsOpened() {
        assertThrows(IllegalArgumentException::class.java) { SlideScriptDraft.parse("##1\n本文\n##3\n別の本文", 2) }
    }
}
