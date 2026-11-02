package jp.cn22.yomipace

import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.PI

/** Presentation progress alone drives escalation; manual reading position never changes it. */
internal enum class DopamineRise(val label: String, val exponent: Double) {
    SLOW("後半型", 2.0), STANDARD("標準", 1.0), EARLY("前半型", .5);
    companion object { fun saved(value: String?) = entries.find { it.name == value } ?: STANDARD }
}

internal data class DopamineLevel(val energy: Float, val title: String, val beatMillis: Long, val progress: Double = 0.0) {
    companion object {
        fun at(elapsed: Double, total: Double?, strength: Int = 100, rise: DopamineRise = DopamineRise.STANDARD): DopamineLevel {
            val p = if (total != null && total.isFinite() && total > 0 && elapsed.isFinite())
                (elapsed / total).coerceIn(0.0, 1.0) else 0.0
            val heat = p.pow(rise.exponent)
            val energy = (.05 + (strength.coerceIn(30, 100) / 100.0 - .05) * heat).toFloat()
            val title = when {
                p >= 1 -> "OVER DRIVE"
                p >= .97 -> "CLIMAX!!"
                p >= .90 -> "LIMIT BREAK"
                p >= .75 -> "FEVER!"
                p >= .50 -> "BOOST"
                else -> "SPARK"
            }
            return DopamineLevel(energy, title, (60_000 / (72 + 108 * heat)).toLong(), p)
        }
    }
}

internal enum class DopamineBurstKind { TOUCH, BEAT, PARAGRAPH, JACKPOT, POPPER, BALLOON, FIREWORK }
internal data class DopamineRegion(val left: Float, val top: Float, val width: Float, val height: Float)
internal data class DopamineBurst(val time: Long, val x: Float, val y: Float, val power: Float, val seed: Int,
    val kind: DopamineBurstKind = DopamineBurstKind.TOUCH) {
    val lifetime: Long get() = when (kind) {
        DopamineBurstKind.BALLOON -> 4500L
        DopamineBurstKind.FIREWORK -> 2800L
        DopamineBurstKind.POPPER -> 1900L
        else -> DopamineEngine.BURST_LIFETIME
    }
}

/** Bounded transient state. All calls occur on the main thread; no saved scores or sound. */
internal class DopamineEngine(private val pulse: (Float) -> Unit, private val cancel: () -> Unit,
    private val beatPulse: (Float, Boolean) -> Unit = { power, _ -> pulse(power) }) {
    val bursts = ArrayDeque<DopamineBurst>()
    var combo = 0; private set
    var lastInteraction = Long.MIN_VALUE; private set
    private var lastPulse = Long.MIN_VALUE
    private var lastTouch = Long.MIN_VALUE
    private var lastPartyTouch = Long.MIN_VALUE
    private var nextBeat = 0L
    private var beatIndex = 0
    private var lastBeatAt = Long.MIN_VALUE
    private var serial = 0
    var level = DopamineLevel.at(0.0, null)
    var enabled = false
    var vibration = true
    var vibrationBeat = true
    var shade = 0
    var readerRegion: DopamineRegion? = null
    var available = false
    var running = false
    var slideNumber = 1
    var slideCount = 0
    companion object { const val MAX_BURSTS = 32; const val BURST_LIFETIME = 1400L }
    private fun age(now: Long, before: Long) = if (before == Long.MIN_VALUE) Long.MAX_VALUE else now - before
    fun beatHop(now: Long): Float = if (!running || lastBeatAt == Long.MIN_VALUE) 0f else
        sin(PI * (age(now,lastBeatAt).toDouble()/level.beatMillis).coerceIn(0.0,1.0)).toFloat().coerceAtLeast(0f)

    fun configure(enabled: Boolean, vibration: Boolean, available: Boolean, running: Boolean, level: DopamineLevel,
                  vibrationBeat: Boolean = true) {
        if (this.enabled != enabled || this.available != available || this.running != running ||
            this.vibrationBeat != vibrationBeat || level.progress < this.level.progress || level.energy < this.level.energy) reset()
        if (this.vibration && !vibration) cancel()
        this.enabled = enabled; this.vibration = vibration; this.available = available
        this.running = running; this.level = level
        this.vibrationBeat = vibrationBeat
    }

    fun touch(now: Long, x: Float, y: Float) {
        if (!enabled || !available || age(now, lastTouch) < 60) return
        combo = if (age(now, lastTouch) < 1800) (combo + 1).coerceAtMost(999) else 1
        lastTouch = now
        lastInteraction = now
        burst(now, x, y, if (running) level.energy else .10f)
        if (running && age(now, lastPartyTouch) >= 360) {
            burst(now, x, y, level.energy, DopamineBurstKind.POPPER)
            burst(now, x, y, level.energy, DopamineBurstKind.BALLOON)
            if (level.energy >= .55f) burst(now, x, y, level.energy, DopamineBurstKind.FIREWORK)
            lastPartyTouch = now
        }
        if (running && combo % 8 == 0) {
            burst(now, 1 - x, .17f, level.energy, DopamineBurstKind.JACKPOT)
            burst(now, .5f, .45f, level.energy, DopamineBurstKind.JACKPOT)
        }
        if (!running || !vibrationBeat) vibrate(now, if (running) level.energy else .06f)
    }

    fun paragraph(now: Long) {
        if (!enabled || !available || !running) return
        burst(now, .5f, .18f, level.energy, DopamineBurstKind.PARAGRAPH)
        burst(now, .10f, .40f, level.energy, DopamineBurstKind.PARAGRAPH)
        burst(now, .90f, .40f, level.energy, DopamineBurstKind.PARAGRAPH)
        burst(now, .08f, .92f, level.energy, DopamineBurstKind.POPPER)
        burst(now, .92f, .92f, level.energy, DopamineBurstKind.POPPER)
        lastInteraction = now
        if (!vibrationBeat) vibrate(now, level.energy)
    }

    fun tick(now: Long) {
        bursts.removeAll { now - it.time > it.lifetime }
        if (age(now, lastTouch) > 1800) combo = 0
        if (!enabled || !available || !running) { nextBeat = 0; return }
        if (nextBeat == 0L) { nextBeat = now + level.beatMillis; return }
        if (now >= nextBeat) {
            burst(now, .10f, .18f, level.energy, DopamineBurstKind.BEAT)
            burst(now, .90f, .32f, level.energy, DopamineBurstKind.BEAT)
            if (level.energy >= .8f) burst(now, .5f, .08f, level.energy, DopamineBurstKind.BEAT)
            lastBeatAt = now
            if (vibration && vibrationBeat) { beatPulse(level.energy, beatIndex % 4 == 0); lastPulse = now }
            if (beatIndex % 4 == 0 && level.energy >= .2f)
                burst(now, if (beatIndex % 8 == 0) .18f else .82f, .98f, level.energy, DopamineBurstKind.BALLOON)
            if (beatIndex % 4 == 0 && level.energy >= .45f)
                burst(now, .18f + (beatIndex % 12) / 12f * .64f, .96f, level.energy, DopamineBurstKind.FIREWORK)
            beatIndex++
            // Keep the beat on its grid; after a stalled frame skip missed beats without a catch-up buzz.
            nextBeat += ((now - nextBeat) / level.beatMillis + 1) * level.beatMillis
        }
    }

    private fun burst(now: Long, x: Float, y: Float, power: Float, kind: DopamineBurstKind = DopamineBurstKind.TOUCH) {
        if (bursts.size == MAX_BURSTS) bursts.removeFirst()
        bursts.addLast(DopamineBurst(now, x.coerceIn(0f, 1f), y.coerceIn(0f, 1f), power, serial++, kind))
    }
    private fun vibrate(now: Long, power: Float) {
        if (vibration && age(now, lastPulse) >= 150) { pulse(power); lastPulse = now }
    }
    fun reset() {
        bursts.clear(); combo = 0; nextBeat = 0; beatIndex = 0; lastBeatAt = Long.MIN_VALUE; lastPartyTouch = Long.MIN_VALUE; lastInteraction = Long.MIN_VALUE
        lastTouch = Long.MIN_VALUE; lastPulse = Long.MIN_VALUE
        if (enabled && vibration) cancel()
    }
}
