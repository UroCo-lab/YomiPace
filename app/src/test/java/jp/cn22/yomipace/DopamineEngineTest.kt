package jp.cn22.yomipace

import org.junit.Assert.*
import org.junit.Test

class DopamineEngineTest {
    @Test fun escalationDependsOnProportionAndCapsInOvertime() {
        val levels = (0..100).map { DopamineLevel.at(it.toDouble(), 100.0) }
        levels.zipWithNext().forEach { (a, b) -> assertTrue(a.energy <= b.energy); assertTrue(a.beatMillis >= b.beatMillis) }
        assertEquals("SPARK", levels[0].title); assertEquals("BOOST", levels[50].title)
        assertEquals("FEVER!", levels[75].title); assertEquals("LIMIT BREAK", levels[90].title)
        assertEquals("CLIMAX!!", levels[97].title); assertEquals("OVER DRIVE", levels[100].title)
        assertEquals(levels[100], DopamineLevel.at(500.0, 100.0))
        assertEquals(levels[0], DopamineLevel.at(Double.NaN, null))
        assertEquals(levels[90], DopamineLevel.at(540.0, 600.0))
    }
    @Test fun touchComboAndVibrationAreThrottledAndParticlesBounded() {
        val pulses = mutableListOf<Float>()
        val engine = DopamineEngine({ pulses.add(it) }, {})
        engine.configure(true, true, true, true, DopamineLevel.at(98.0, 100.0))
        repeat(1000) { engine.touch(1000L + it, .5f, .6f) }
        assertEquals(17, engine.combo); assertTrue(pulses.size <= 7)
        assertTrue(engine.bursts.size <= DopamineEngine.MAX_BURSTS)
        repeat(100) { engine.touch(3000L + 200 * it, .5f, .6f) }
        assertEquals(DopamineEngine.MAX_BURSTS, engine.bursts.size)
        engine.tick(100_000); assertEquals(0, engine.combo); assertTrue(engine.bursts.size <= 1)
    }
    @Test fun startsWithVisibleEnergyAndContinuousBeatThenEscalates() {
        val opening = DopamineLevel.at(0.0, 420.0)
        val ending = DopamineLevel.at(415.0, 420.0)
        assertTrue(opening.energy >= .4f); assertTrue(opening.beatMillis <= 900)
        assertTrue(ending.beatMillis < opening.beatMillis / 2)
        val pulses = mutableListOf<Float>()
        val engine = DopamineEngine({ pulses.add(it) }, {})
        engine.configure(true, true, true, true, opening)
        engine.tick(1000); engine.tick(2000)
        assertEquals(2, engine.bursts.size); assertEquals(1, pulses.size)
        assertTrue(pulses.single() >= .4f)
    }
    @Test fun comboJackpotAndParagraphCreateMultipleBurstsAndResetQuietly() {
        val engine = DopamineEngine({}, {})
        engine.configure(true, true, true, true, DopamineLevel.at(0.0, 420.0))
        repeat(8) { engine.touch(1000L + 100 * it, .5f, .6f) }
        assertEquals(8, engine.combo)
        assertTrue(engine.bursts.count { it.kind == DopamineBurstKind.JACKPOT } >= 2)
        engine.paragraph(1900)
        assertEquals(3, engine.bursts.count { it.kind == DopamineBurstKind.PARAGRAPH })
        engine.configure(true, true, true, false, engine.level)
        assertTrue(engine.bursts.isEmpty()); assertEquals(0, engine.combo)
        engine.touch(2000, .5f, .6f)
        assertEquals(1, engine.bursts.size); assertTrue(engine.bursts.single().power < .2f)
    }
    @Test fun disabledBackgroundPausedAndMutedModesDoNotProduceAutomaticHaptics() {
        var pulses = 0; var cancellations = 0
        val engine = DopamineEngine({ pulses++ }, { cancellations++ })
        val level = DopamineLevel.at(100.0, 100.0)
        engine.configure(false, true, true, true, level)
        engine.touch(1000, .5f, .5f); engine.tick(5000); engine.paragraph(6000)
        assertEquals(0, pulses); assertTrue(engine.bursts.isEmpty())
        engine.configure(true, true, true, true, level)
        engine.tick(10_000); engine.tick(11_000); assertEquals(1, pulses)
        engine.configure(true, true, false, true, level)
        engine.tick(20_000); engine.touch(20_100, 0f, 0f); engine.paragraph(21_000)
        assertEquals(1, pulses); assertTrue(engine.bursts.isEmpty())
        engine.configure(true, true, true, false, level)
        engine.tick(22_000); engine.tick(25_000); engine.paragraph(26_000)
        assertEquals(1, pulses)
        engine.touch(27_000, .5f, .5f); assertEquals(2, pulses) // tiny stopped touch feedback
        engine.configure(true, false, true, true, level)
        engine.tick(30_000); engine.tick(31_000); engine.paragraph(32_000)
        assertEquals(2, pulses); assertTrue(engine.bursts.isNotEmpty()); assertTrue(cancellations > 0)
    }
}
