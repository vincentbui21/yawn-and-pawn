package com.yawnandpawn.app.android.wake

import android.content.Context
import android.os.Build
import android.os.VibrationAttributes
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager

/**
 * The alarm vibration (AD-5): a repeating pattern with alarm usage (`VibrationAttributes.USAGE_ALARM` on API 33+, the
 * alarm [ALARM_AUDIO_ATTRIBUTES] below), so Do Not Disturb treats it like the alarm sound. [start] is idempotent.
 * Owned by [WakeRuntime].
 */
class AlarmVibrator(
    context: Context,
) {
    private val vibrator: Vibrator =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            context.getSystemService(VibratorManager::class.java).defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            context.getSystemService(Vibrator::class.java)
        }

    /** The repeating pattern is running. */
    @Volatile
    var isVibrating: Boolean = false
        private set

    /** Starts the repeating alarm pattern; nothing when it already runs. */
    @Synchronized
    fun start() {
        if (isVibrating) return
        vibrate(VibrationEffect.createWaveform(PATTERN_MILLIS, REPEAT_FROM_START))
        isVibrating = true
    }

    /**
     * One strong pulse when the grace window ends. It replaces a running pattern; when the state still wants vibration,
     * its `Vibrating` entry effect starts the pattern again right after (it starts with a pulse too).
     */
    @Synchronized
    fun strongHaptic() {
        vibrate(VibrationEffect.createOneShot(STRONG_PULSE_MILLIS, VibrationEffect.DEFAULT_AMPLITUDE))
        isVibrating = false
    }

    /** Stops any vibration. */
    @Synchronized
    fun stop() {
        vibrator.cancel()
        isVibrating = false
    }

    private fun vibrate(effect: VibrationEffect) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            vibrator.vibrate(effect, VibrationAttributes.createForUsage(VibrationAttributes.USAGE_ALARM))
        } else {
            @Suppress("DEPRECATION")
            vibrator.vibrate(effect, ALARM_AUDIO_ATTRIBUTES)
        }
    }

    private companion object {
        /** Two 0.8 s pulses, then a 1 s pause, repeating. */
        val PATTERN_MILLIS = longArrayOf(0, 800, 400, 800, 1000)
        const val REPEAT_FROM_START = 0
        const val STRONG_PULSE_MILLIS = 400L
    }
}
