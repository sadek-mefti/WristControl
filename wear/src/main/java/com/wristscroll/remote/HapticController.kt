package com.wristscroll.remote

import android.content.Context
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager

/**
 * Haptic feedback controller for the Wear OS watch app.
 * Uses VibratorManager on API 31+ and Vibrator on API 30.
 */
class HapticController(context: Context) {

    companion object {
        // Named duration constants (in milliseconds)
        const val COMMAND_TICK_MS = 25L
        const val DOUBLE_TAP_PULSE_MS = 20L
        const val DOUBLE_TAP_GAP_MS = 40L
        const val STEALTH_TRANSITION_MS = 60L
        const val ERROR_PULSE_MS = 15L
        const val ERROR_GAP_MS = 45L

        private const val DEFAULT_AMPLITUDE = 200
        private const val STRONG_AMPLITUDE = 255
        private const val NO_REPEAT = -1
    }

    private val vibrator: Vibrator? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        val manager = context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as? VibratorManager
        manager?.defaultVibrator
    } else {
        @Suppress("DEPRECATION")
        context.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
    }

    /**
     * Command sent (scroll, swipe or single tap): one 25 ms tick.
     */
    fun playCommandSent() {
        val effect = VibrationEffect.createWaveform(
            longArrayOf(0L, COMMAND_TICK_MS),
            intArrayOf(0, DEFAULT_AMPLITUDE),
            NO_REPEAT
        )
        vibrateSafely(effect)
    }

    /**
     * Double-tap like: two 20 ms pulses with a 40 ms gap.
     */
    fun playDoubleTapLike() {
        val effect = VibrationEffect.createWaveform(
            longArrayOf(0L, DOUBLE_TAP_PULSE_MS, DOUBLE_TAP_GAP_MS, DOUBLE_TAP_PULSE_MS),
            intArrayOf(0, STRONG_AMPLITUDE, 0, STRONG_AMPLITUDE),
            NO_REPEAT
        )
        vibrateSafely(effect)
    }

    /**
     * Enter or exit Stealth Remote: one 60 ms pulse.
     */
    fun playStealthTransition() {
        val effect = VibrationEffect.createWaveform(
            longArrayOf(0L, STEALTH_TRANSITION_MS),
            intArrayOf(0, STRONG_AMPLITUDE),
            NO_REPEAT
        )
        vibrateSafely(effect)
    }

    /**
     * Error: three 15 ms buzzes.
     */
    fun playError() {
        val effect = VibrationEffect.createWaveform(
            longArrayOf(
                0L,
                ERROR_PULSE_MS,
                ERROR_GAP_MS,
                ERROR_PULSE_MS,
                ERROR_GAP_MS,
                ERROR_PULSE_MS
            ),
            intArrayOf(0, STRONG_AMPLITUDE, 0, STRONG_AMPLITUDE, 0, STRONG_AMPLITUDE),
            NO_REPEAT
        )
        vibrateSafely(effect)
    }

    private fun vibrateSafely(effect: VibrationEffect) {
        runCatching {
            if (vibrator?.hasVibrator() == true) {
                vibrator.vibrate(effect)
            }
        }
    }
}
