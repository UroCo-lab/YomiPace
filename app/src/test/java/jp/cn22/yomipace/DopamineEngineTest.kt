package jp.cn22.yomipace

import org.junit.Assert.*
import org.junit.Test

class DopamineEngineTest {
    @Test fun standardIsLinearAndEveryCurveReachesTheSameConfiguredMaximumAtTheDeadline() {
        for(strength in listOf(30,60,100)) {
            val start=DopamineLevel.at(0.0,100.0,strength).energy
            val end=DopamineLevel.at(100.0,100.0,strength).energy
            for(i in 0..100)assertEquals(start+(end-start)*i/100f,DopamineLevel.at(i.toDouble(),100.0,strength).energy,.0001f)
            for(curve in DopamineRise.entries) {
                assertEquals(start,DopamineLevel.at(0.0,100.0,strength,curve).energy,0f)
                assertEquals(end,DopamineLevel.at(100.0,100.0,strength,curve).energy,0f)
                assertTrue(DopamineLevel.at(99.0,100.0,strength,curve).energy<end)
                assertEquals(end,DopamineLevel.at(150.0,100.0,strength,curve).energy,0f)
            }
        }
    }
    @Test fun mascotHopUsesTheSameBeatAndStopsOnPause() {
        val e=DopamineEngine({},{});val level=DopamineLevel.at(100.0,100.0)
        e.configure(true,true,true,true,level);e.tick(1000);e.tick(1000+level.beatMillis)
        assertEquals(0f,e.beatHop(1000+level.beatMillis),.001f)
        assertTrue(e.beatHop(1000+level.beatMillis+level.beatMillis/2)>.99f)
        e.configure(true,true,true,false,level);assertEquals(0f,e.beatHop(2000),0f)
    }
    @Test fun touchCreatesPartyAtItsActualPositionAndRewindRemovesAllPartyKinds() {
        val engine=DopamineEngine({}, {})
        engine.configure(true,true,true,true,DopamineLevel.at(99.0,100.0))
        engine.touch(1000,.37f,.72f)
        for(kind in listOf(DopamineBurstKind.TOUCH,DopamineBurstKind.POPPER,DopamineBurstKind.BALLOON,DopamineBurstKind.FIREWORK)) {
            val event=engine.bursts.single { it.kind==kind }
            assertEquals(.37f,event.x,0f);assertEquals(.72f,event.y,0f)
        }
        repeat(500) { engine.touch(2000L+it*60,.4f,.6f) }
        assertTrue(engine.bursts.size<=DopamineEngine.MAX_BURSTS)
        engine.configure(true,true,true,true,DopamineLevel.at(5.0,100.0))
        assertTrue(engine.bursts.isEmpty());assertEquals(0,engine.combo)
        engine.touch(40_000,.37f,.72f)
        assertFalse(engine.bursts.any { it.kind==DopamineBurstKind.FIREWORK })
        assertTrue(engine.bursts.all { it.power==engine.level.energy })
    }
    @Test fun shortSparksExpireEvenWhenAnOlderBalloonIsStillFloating() {
        val engine=DopamineEngine({}, {})
        engine.configure(true,false,true,false,DopamineLevel.at(99.0,100.0))
        engine.bursts.add(DopamineBurst(1000,.5f,.9f,1f,0,DopamineBurstKind.BALLOON))
        engine.bursts.add(DopamineBurst(1100,.5f,.9f,1f,1))
        engine.tick(3000)
        assertEquals(1,engine.bursts.size);assertEquals(DopamineBurstKind.BALLOON,engine.bursts.single().kind)
        engine.tick(6000);assertTrue(engine.bursts.isEmpty())
    }
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
    @Test fun startsQuietlyWithContinuousBeatThenEscalates() {
        val opening = DopamineLevel.at(0.0, 420.0)
        val ending = DopamineLevel.at(415.0, 420.0)
        assertTrue(opening.energy <= .06f); assertTrue(opening.beatMillis in 800..850)
        assertTrue(ending.beatMillis < opening.beatMillis / 2)
        val pulses = mutableListOf<Float>()
        val engine = DopamineEngine({ pulses.add(it) }, {})
        engine.configure(true, true, true, true, opening)
        engine.tick(1000); engine.tick(2000)
        assertEquals(2, engine.bursts.size); assertEquals(1, pulses.size)
        assertTrue(pulses.single() <= .06f)
    }
    @Test fun rewindDropsEnergyClearsOldBurstsAndRestartsTheBeatWithoutCatchup() {
        val pulses = mutableListOf<Float>()
        val engine = DopamineEngine({ pulses.add(it) }, {})
        engine.configure(true,true,true,true,DopamineLevel.at(99.0,100.0))
        repeat(8) { engine.touch(1000L + 100 * it,.5f,.5f) }
        engine.tick(1800); engine.tick(2200)
        assertTrue(engine.bursts.isNotEmpty()); assertTrue(engine.combo > 0)
        engine.configure(true,true,true,true,DopamineLevel.at(10.0,100.0))
        assertTrue(engine.level.energy < .16f); assertEquals(0,engine.combo); assertTrue(engine.bursts.isEmpty())
        val count = pulses.size
        engine.tick(2500); assertEquals(count,pulses.size)
        engine.tick(50_000); assertEquals(count+1,pulses.size)
        assertTrue(pulses.last() < .16f)
    }
    @Test fun strengthAndRiseChangeTheCurveWithoutMakingTheOpeningLoud() {
        for (rise in DopamineRise.entries) for (strength in listOf(30,60,100)) {
            assertEquals(.05f,DopamineLevel.at(0.0,100.0,strength,rise).energy,.001f)
            assertEquals(strength/100f,DopamineLevel.at(100.0,100.0,strength,rise).energy,.001f)
        }
        assertTrue(DopamineLevel.at(50.0,100.0,100,DopamineRise.SLOW).energy < DopamineLevel.at(50.0,100.0).energy)
        assertTrue(DopamineLevel.at(50.0,100.0,100,DopamineRise.EARLY).energy > DopamineLevel.at(50.0,100.0).energy)
    }
    @Test fun beatHasFourBeatAccentsAndInteractionsDoNotInterruptIt() {
        var touches = 0; val accents = mutableListOf<Boolean>()
        val engine = DopamineEngine({ touches++ }, {}, { _, accent -> accents.add(accent) })
        val level = DopamineLevel.at(50.0,100.0)
        engine.configure(true,true,true,true,level)
        engine.tick(1000)
        repeat(8) { i ->
            val now = 1000+(i+1)*level.beatMillis
            engine.touch(now-10,.5f,.5f); engine.paragraph(now-5); engine.tick(now)
        }
        assertEquals(0,touches); assertEquals(listOf(true,false,false,false,true,false,false,false),accents)
        engine.configure(true,true,true,true,level,false)
        engine.tick(10_000); engine.tick(11_000); assertEquals(8,accents.size)
        engine.touch(12_000,.5f,.5f); assertEquals(1,touches)
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
