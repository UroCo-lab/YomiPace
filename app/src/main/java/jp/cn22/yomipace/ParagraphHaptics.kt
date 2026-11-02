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
        val duration = (8 + 25 * power).toLong()
        val amplitude = if (target.hasAmplitudeControl()) (35 + 200 * power).toInt() else VibrationEffect.DEFAULT_AMPLITUDE
        if (power >= .65f) target.vibrate(VibrationEffect.createWaveform(
            longArrayOf(0, duration, 28, duration / 2), intArrayOf(0, amplitude, 0, amplitude), -1))
        else target.vibrate(VibrationEffect.createOneShot(duration, amplitude))
    }
    fun cancel() { vibrator?.cancel() }
}
