package jp.cn22.yomipace

import android.content.Context
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager

class ParagraphHaptics(context: Context) {
    @Suppress("DEPRECATION")
    private val vibrator = if (Build.VERSION.SDK_INT >= 31) context.getSystemService(VibratorManager::class.java)?.defaultVibrator else context.getSystemService(Vibrator::class.java)
    fun pulse(enabled: Boolean, strength: Int) {
        val target = vibrator ?: return
        if (!enabled || !target.hasVibrator()) return
        val level = strength.coerceIn(0, 2)
        val duration = longArrayOf(12, 24, 40)[level]
        val amplitude = if (target.hasAmplitudeControl()) intArrayOf(50, 135, 255)[level] else VibrationEffect.DEFAULT_AMPLITUDE
        target.vibrate(VibrationEffect.createOneShot(duration, amplitude))
    }
    fun dopaminePulse(energy: Float) {
        val target = vibrator ?: return
        if (!target.hasVibrator()) return
        val power = energy.coerceIn(0f, 1f)
        val amplitude = if (target.hasAmplitudeControl()) (50 + 205 * power).toInt() else VibrationEffect.DEFAULT_AMPLITUDE
        when {
            power >= .8f -> target.vibrate(VibrationEffect.createWaveform(
                longArrayOf(0, 42, 18, 26, 16, 18), intArrayOf(0, amplitude, 0, amplitude, 0, amplitude), -1))
            power >= .35f -> target.vibrate(VibrationEffect.createWaveform(
                longArrayOf(0, (20 + 24 * power).toLong(), 22, 18), intArrayOf(0, amplitude, 0, amplitude), -1))
            else -> target.vibrate(VibrationEffect.createOneShot((8 + 22 * power).toLong(), amplitude))
        }
    }
    fun dopamineBeat(energy: Float, accent: Boolean) {
        val target = vibrator ?: return
        if (!target.hasVibrator()) return
        val power = energy.coerceIn(0f, 1f)
        val amplitude = if (target.hasAmplitudeControl())
            ((45 + 180 * power) * if (accent) 1f else .65f).toInt().coerceIn(1, 255)
            else VibrationEffect.DEFAULT_AMPLITUDE
        target.vibrate(VibrationEffect.createOneShot((12 + 16 * power + if (accent) 6 else 0).toLong(), amplitude))
    }
    fun cancel() { vibrator?.cancel() }
}
