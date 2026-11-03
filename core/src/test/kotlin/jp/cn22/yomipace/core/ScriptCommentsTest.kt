package jp.cn22.yomipace.core

import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.nio.file.Files
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class ScriptCommentsTest {
    @Test fun modernBoundariesLeaveSingleHashesAndInlineStarsInTheBody() {
        val script = ScriptParser.parse("\uFEFFtime(01:00)\r\n##1\r\n#1\r\n#1の文章と本文途中の**記号。\r\n##2\r\n終わり。")
        assertTrue(script.issues.toString(), script.issues.isEmpty())
        assertEquals(listOf(1, 2), script.slides)
        assertEquals(listOf("#1", "#1の文章と本文途中の**記号。", "終わり。"), script.items.filterIsInstance<ScriptItem.Sentence>().map { it.text })
        assertEquals(60.0, script.declaredSeconds!!, 0.0)
    }

    @Test fun commentsCannotExecuteControlsOrChangeTimeAndKeepTheirSourceLines() {
        val script = ScriptParser.parse("\uFEFF  ** ##99\r\ntime(01:00)\r\n##1\r\n\t**time(99:99)\r\n** [図(99:99)]\r\n** #2\r\n**\r\n本文。\r\n##2")
        assertTrue(script.issues.toString(), script.issues.isEmpty())
        assertEquals(listOf(1, 2), script.slides)
        assertEquals(60.0, script.declaredSeconds!!, 0.0)
        assertEquals(spokenCount("本文。"), script.characterCount)
        val comments = script.items.filterIsInstance<ScriptItem.Comment>()
        assertEquals(listOf(1, 4, 5, 6, 7), comments.map { it.line })
        assertEquals(listOf("##99", "time(99:99)", "[図(99:99)]", "#2", ""), comments.map { it.text })
        assertTrue(comments.all { it.slide == 1 })
        assertEquals(8, script.items.filterIsInstance<ScriptItem.Sentence>().single().line)
    }

    @Test fun commentsDoNotChangeScheduledDurationsOrAddVibrationEvents() {
        val source = "##1\n本文。\n[図(00:10)]\n##2\n結論。"
        val plain = ScriptParser.parse(source)
        val noted = ScriptParser.parse(source.replace("[図(00:10)]", "** 質問対策。\n[図(00:10)]\n** time(90:00)") + "\n** 末尾メモ。")
        val expected = Timeline.build(plain, 60.0, 2.0)
        val actual = Timeline.build(noted, 60.0, 2.0)
        assertEquals(expected.characterCount, actual.characterCount)
        assertEquals(expected.segments.map { Triple(it.kind, it.start, it.end) }, actual.segments.map { Triple(it.kind, it.start, it.end) })
        assertTrue(noted.items.filterIsInstance<ScriptItem.Comment>().none { it.id in actual.byId })
        val baseline = ScheduledParagraphs(plain, expected)
        val tracker = ScheduledParagraphs(noted, actual)
        for (elapsed in (0..120).map { it / 2.0 }) assertEquals("at $elapsed", baseline.advance(elapsed), tracker.advance(elapsed))
    }

    @Test fun legacyDecksRemainReadableAndCommentsDoNotEnableModernMode() {
        val old = ScriptParser.parse("** ##99\n#1\n本文。\n#2\n結論。")
        assertTrue(old.issues.toString(), old.issues.isEmpty())
        assertEquals(listOf(1, 2), old.slides)
        assertTrue(ScriptParser.parse("##1\n本文。\n##3").fatalIssues.single().message.contains("##2"))
        assertTrue(ScriptParser.parse("##1 本文").fatalIssues.isNotEmpty())
        assertTrue(ScriptParser.parse("##0\n本文").fatalIssues.isNotEmpty())
        assertTrue(ScriptParser.parse("#1\n旧本文\n##2\n新本文").fatalIssues.isNotEmpty())
    }

    @Test fun editsPreserveCommentAnchorsAndRejectCommentsOnlyScripts() {
        val source = "time(01:00)\n##1\n本文。\n** 質問への回答\n##2\n結論。"
        val old = ScriptParser.parse(source)
        val anchor = ReadingAnchor(old.items.filterIsInstance<ScriptItem.Comment>().single().id, 3)
        val prepared = ScriptEditing.prepare(source.replace("本文。", "本文。\n補足。"), old, 2, 90.0, 2.0)
        val moved = ScriptEditing.relocate(old, prepared.script, anchor, 1)
        assertEquals(prepared.script.items.filterIsInstance<ScriptItem.Comment>().single().id, moved.itemId)
        assertEquals(3, moved.utf16Offset)
        assertEquals(90.0, prepared.totalSeconds!!, 0.0)
        val notesOnly = ScriptParser.parse("##1\n** 読まない。\n##2\n** [図(00:30)]")
        assertEquals(0, notesOnly.characterCount)
        try {
            Timeline.build(notesOnly, 60.0, 2.0)
            fail("Comments alone cannot be used as spoken text")
        } catch (e: IllegalArgumentException) { assertTrue(e.message.orEmpty().contains("0文字")) }
    }

    @Test fun zipImportAcceptsNewScriptAndRetainsTheUnmodifiedSourceText() {
        val source = "time(01:00)\n##1\n本文の#1。\n** [図(00:99)]\n##2\n結論。"
        val bytes = ByteArrayOutputStream().also { out ->
            ZipOutputStream(out).use { zip ->
                listOf("001.png" to byteArrayOf(1), "002.png" to byteArrayOf(2), "script.txt" to source.toByteArray()).forEach { (name, data) ->
                    zip.putNextEntry(ZipEntry(name)); zip.write(data); zip.closeEntry()
                }
            }
        }.toByteArray()
        val parent = Files.createTempDirectory("script-comments").toFile()
        try {
            val imported = ZipImporter.read(ByteArrayInputStream(bytes), parent.resolve("deck"), "comments.zip")
            assertEquals(source, imported.directory.resolve("script.txt").readText())
            assertEquals(1, imported.script.items.filterIsInstance<ScriptItem.Comment>().size)
            assertEquals(listOf(1, 2), imported.script.slides)
        } finally { parent.deleteRecursively() }
    }
}
