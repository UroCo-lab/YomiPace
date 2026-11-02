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
}
