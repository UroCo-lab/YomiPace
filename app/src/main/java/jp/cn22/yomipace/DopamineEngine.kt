package jp.cn22.yomipace

import kotlin.math.pow

/** Presentation progress alone drives escalation; manual reading position never changes it. */
internal data class DopamineLevel(val energy: Float, val title: String, val beatMillis: Long) {
    companion object {
        fun at(elapsed: Double, total: Double?): DopamineLevel {
            val p = if (total != null && total.isFinite() && total > 0 && elapsed.isFinite())
                (elapsed / total).coerceIn(0.0, 1.0) else 0.0
            val energy = (.10 + .90 * p.pow(2.2)).toFloat()
            val title = when {
                p >= 1 -> "OVER DRIVE"
                p >= .97 -> "CLIMAX!!"
                p >= .90 -> "LIMIT BREAK"
                p >= .75 -> "FEVER!"
                p >= .50 -> "BOOST"
                else -> "SPARK"
            }
            return DopamineLevel(energy, title, (3200 - 2780 * energy).toLong())
        }
    }
}

internal data class DopamineBurst(val time: Long, val x: Float, val y: Float, val power: Float, val seed: Int)

/** Bounded transient state. All calls occur on the main thread; no saved scores or sound. */
internal class DopamineEngine(private val pulse: (Float) -> Unit, private val cancel: () -> Unit) {
    val bursts = ArrayDeque<DopamineBurst>()
    var combo = 0; private set
    var lastInteraction = Long.MIN_VALUE; private set
    private var lastPulse = Long.MIN_VALUE
    private var lastTouch = Long.MIN_VALUE
    private var nextBeat = 0L
    private var serial = 0
    var level = DopamineLevel.at(0.0, null)
    var enabled = false
    var vibration = true
    var available = false
    var running = false
    private fun age(now: Long, before: Long) = if (before == Long.MIN_VALUE) Long.MAX_VALUE else now - before

    fun configure(enabled: Boolean, vibration: Boolean, available: Boolean, running: Boolean, level: DopamineLevel) {
        if (this.enabled != enabled || this.available != available || this.running != running) reset()
        if (this.vibration && !vibration) cancel()
        this.enabled = enabled; this.vibration = vibration; this.available = available
        this.running = running; this.level = level
    }

    fun touch(now: Long, x: Float, y: Float) {
        if (!enabled || !available || age(now, lastTouch) < 90) return
        lastTouch = now
        combo = if (age(now, lastInteraction) < 1400) (combo + 1).coerceAtMost(999) else 1
        lastInteraction = now
        burst(now, x, y, if (running) level.energy else .10f)
        vibrate(now, if (running) level.energy * .7f else .06f)
    }

    fun paragraph(now: Long) {
        if (!enabled || !available || !running) return
        burst(now, .5f, .22f, (.35f + level.energy).coerceAtMost(1f))
        lastInteraction = now
        vibrate(now, level.energy)
    }

    fun tick(now: Long) {
        while (bursts.isNotEmpty() && now - bursts.first().time > 1100) bursts.removeFirst()
        if (age(now, lastInteraction) > 1400) combo = 0
        if (!enabled || !available || !running) { nextBeat = 0; return }
        if (nextBeat == 0L) { nextBeat = now + level.beatMillis; return }
        if (now >= nextBeat) {
            burst(now, if (serial % 2 == 0) .15f else .85f, .23f, level.energy)
            // Early presentation: feedback comes from touch and paragraphs. Late: a rising beat.
            if (level.energy >= .3f) vibrate(now, level.energy)
            nextBeat = now + level.beatMillis
        }
    }

    private fun burst(now: Long, x: Float, y: Float, power: Float) {
        if (bursts.size == 24) bursts.removeFirst()
        bursts.addLast(DopamineBurst(now, x.coerceIn(0f, 1f), y.coerceIn(0f, 1f), power, serial++))
    }
    private fun vibrate(now: Long, power: Float) {
        if (vibration && age(now, lastPulse) >= 150) { pulse(power); lastPulse = now }
    }
    fun reset() {
        bursts.clear(); combo = 0; nextBeat = 0; lastInteraction = Long.MIN_VALUE
        lastTouch = Long.MIN_VALUE; lastPulse = Long.MIN_VALUE
        if (enabled && vibration) cancel()
    }
}
