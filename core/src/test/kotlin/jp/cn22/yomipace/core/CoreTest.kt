package jp.cn22.yomipace.core

import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.nio.file.Files
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class CoreTest {
    private fun script(text: String) = ScriptParser.parse(text.trimIndent())
    private fun fails(block: () -> Unit): String = try { block(); error("Expected an input error") } catch (e: IllegalArgumentException) { e.message.orEmpty() }
    @Test fun unicodeControlLinesAndSentenceBoundaries() {
        val s = script("\uFEFFtime(07:00)\r\n#1\r\nあ 😀。Ａ?\r\n改行の文\r\n[図😀(00:30)]\r\n#2\r\n")
        assertEquals(420.0, s.declaredSeconds!!, 0.0)
        assertEquals(9, s.characterCount)
        assertEquals(3, s.items.filterIsInstance<ScriptItem.Sentence>().size)
        assertTrue(s.issues.isEmpty())
        assertEquals(2, spokenCount(" \t\n\u3000😀A"))
    }
    @Test fun malformedTimeIsRecoverableButNeverSilentlyInherited() {
        val s = script("time(01:60)\n#1\n本文。\ntime(02:00)")
        assertNull(s.declaredSeconds)
        assertEquals(listOf(1, 4), s.issues.map { it.line })
        assertTrue(s.fatalIssues.isEmpty())
        assertEquals(180.0, Timeline.build(s, 180.0, 2.0).totalSeconds, 0.0)
        assertTrue(fails { Timeline.build(s, null, 2.0) }.contains("持ち時間"))
    }
    @Test fun malformedBlockHasLineAndRepair() {
        val s = script("#1\n本文。\n[図(00:99)]")
        assertEquals(3, s.fatalIssues.single().line)
        assertTrue(s.fatalIssues.single().message.contains("00:30"))
        assertTrue(script("#1\nmovie(00:30)").fatalIssues.isNotEmpty())
        assertTrue(script("#2\n本文").fatalIssues.isNotEmpty())
    }
    @Test fun timelineHasExactBudgetAndFixedProgress() {
        val s = script("time(02:00)\n#1\n甲乙。\n[図(00:30)]\n次。\n#2\n終。\n#3")
        for (switch in listOf(0.0, 0.5, 2.0)) {
            val t = Timeline.build(s, 120.0, switch)
            assertEquals(120.0, t.segments.sumOf { it.duration }, 1e-10)
            assertEquals(2, t.segments.count { it.kind == SegmentKind.SWITCH })
            assertEquals(switch * 2, t.segments.filter { it.kind == SegmentKind.SWITCH }.sumOf { it.duration }, 1e-10)
            val b = t.segments.single { it.kind == SegmentKind.FIXED }
            assertEquals(0.0, b.fraction(b.start), 0.0)
            assertEquals(0.5, b.fraction(b.start + 15), 1e-10)
            assertEquals(1.0, b.fraction(b.start + 30), 0.0)
            assertEquals(0.0, b.fraction(-1.0), 0.0)
            assertEquals(1.0, b.fraction(1000.0), 0.0)
            assertEquals(b.start, t.segments[t.segments.indexOf(b) - 1].end, 0.0)
        }
    }
    @Test fun zeroSecondBlockIsInstantAndInvalidBudgetsFail() {
        val s = script("#1\n本文\n[瞬間(00:00)]")
        val b = Timeline.build(s, 60.0, 0.0).segments.last()
        assertEquals(1.0, b.fraction(60.0), 0.0)
        assertFalse(b.fraction(60.0).isNaN())
        assertTrue(fails { Timeline.build(script("#1\n[図(00:30)]"), 60.0, 0.0) }.contains("0文字"))
        assertTrue(fails { Timeline.build(script("#1\n本文\n[図(00:30)]"), 30.0, 0.0) }.contains("合計以下"))
        assertTrue(fails { Timeline.build(s, Double.NaN, 0.0) }.isNotEmpty())
    }
    @Test fun manualPositionCannotMutateTimeline() {
        val t = Timeline.build(script("#1\n長い原稿。\n#2\n結論。"), 60.0, 0.5)
        val before = t.segments.toList()
        var anchor = ReadingAnchor()
        anchor = anchor.copy(itemId = "s4-0", utf16Offset = 2)
        assertEquals("s4-0", anchor.itemId)
        assertEquals(before, t.segments)
    }
    @Test fun monotonicClockIsIdempotentExcludesPausesAndContinuesOvertime() {
        var now = 1000L
        val c = PresentationClock { now }
        c.play(); now += 1500; c.play(); assertEquals(1500L, c.elapsedMillis)
        c.pause(); c.pause(); now += 99_000; assertEquals(1500L, c.elapsedMillis)
        c.play(); now += 420_000; assertEquals(421500L, c.elapsedMillis)
        c.reset(); assertFalse(c.running); assertEquals(0L, c.elapsedMillis)
        c.restorePaused(1500L); assertEquals(1500L, c.elapsedMillis)
    }
    private fun zip(vararg entries: Pair<String, ByteArray>): ByteArray {
        val output = ByteArrayOutputStream()
        ZipOutputStream(output).use { z -> entries.forEach { (name, bytes) -> z.putNextEntry(ZipEntry(name)); z.write(bytes); z.closeEntry() } }
        return output.toByteArray()
    }
    private fun read(bytes: ByteArray): ImportedDeck {
        val parent = Files.createTempDirectory("yomipace-test").toFile()
        return try { ZipImporter.read(ByteArrayInputStream(bytes), parent.resolve("stage"), "test.zip") } finally { parent.deleteOnExit() }
    }
    private fun txt(s: String) = s.toByteArray(Charsets.UTF_8)
    @Test fun zipRootParentCaseAndBomAreSupported() {
        for (prefix in listOf("", "資料/")) {
            val d = read(zip("${prefix}001.PNG" to byteArrayOf(1), "${prefix}002.JpEg" to byteArrayOf(2), "${prefix}script.txt" to txt("\uFEFFtime(01:00)\r\n#1\r\n本文。\r\n#2")))
            assertEquals(2, d.images.size)
            assertEquals(60.0, d.script.declaredSeconds!!, 0.0)
            d.directory.parentFile.deleteRecursively()
        }
    }
    @Test fun zipRejectsPathsMixedDecksDuplicateNumbersAndInvalidUtf8() {
        val cases = listOf(
            zip("../001.png" to byteArrayOf(1)),
            zip("C:/001.png" to byteArrayOf(1)),
            zip("a/001.png" to byteArrayOf(1), "b/script.txt" to txt("#1\n本文")),
            zip("001.png" to byteArrayOf(1), "001.jpg" to byteArrayOf(1)),
            zip("002.png" to byteArrayOf(1), "script.txt" to txt("#1\n本文")),
            zip("001.png" to byteArrayOf(1), "script.txt" to byteArrayOf(0xff.toByte())),
            zip("001.png" to byteArrayOf(1), "script.txt" to txt("#1\n本文\n#2")),
            zip("001.png" to byteArrayOf(1), "script.txt" to ByteArray((ZipImporter.MAX_SCRIPT + 1).toInt()) { 65 }),
        )
        cases.forEach { bytes -> assertTrue(fails { read(bytes) }.isNotEmpty()) }
    }
    @Test fun failedReplacementDoesNotTouchExistingDeck() {
        val d = read(zip("001.png" to byteArrayOf(1), "script.txt" to txt("#1\n既存本文")))
        val staging = d.directory.parentFile.resolve("replacement")
        fails { ZipImporter.read(ByteArrayInputStream(zip("../bad" to txt("bad"))), staging, "bad.zip") }
        assertTrue(d.images.single().exists())
        assertTrue(d.directory.resolve("script.txt").readText().contains("既存本文"))
        assertFalse(staging.exists())
        d.directory.parentFile.deleteRecursively()
    }
    @Test fun suppliedSampleZipBuildsSevenMinuteTimeline() {
        val parent = Files.createTempDirectory("yomipace-sample").toFile()
        try {
            val d = ZipImporter.read(javaClass.getResourceAsStream("/sample.zip")!!, parent.resolve("stage"), "sample.zip")
            assertEquals(3, d.images.size)
            assertEquals(420.0, d.script.declaredSeconds!!, 0.0)
            val timeline = Timeline.build(d.script, d.script.declaredSeconds, 2.0)
            assertEquals(420.0, timeline.segments.sumOf { it.duration }, 1e-10)
            assertEquals(50.0, timeline.segments.filter { it.kind == SegmentKind.FIXED }.sumOf { it.duration }, 1e-10)
            assertEquals(4.0, timeline.segments.filter { it.kind == SegmentKind.SWITCH }.sumOf { it.duration }, 1e-10)
        } finally { parent.deleteRecursively() }
    }
}
