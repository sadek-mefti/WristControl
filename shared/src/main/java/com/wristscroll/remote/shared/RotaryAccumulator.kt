package com.wristscroll.remote.shared

import kotlin.math.abs

/**
 * Pure Kotlin rotary scroll accumulator with an injectable clock and zero Android imports.
 *
 * Rules enforced:
 * - Adds verticalScrollPixels to a running total.
 * - Resets the running total whenever the scroll direction reverses.
 * - When the total passes [thresholdPx], returns the command for the current rotary mode
 *   (positive = NEXT_ITEM / SWIPE_LEFT, negative = PREV_ITEM / SWIPE_RIGHT) and resets total to 0.
 * - After a command fires, ignores rotary events for [cooldownMs] (300 ms) and does not add
 *   them to the running total.
 */
class RotaryAccumulator(
    private val cooldownMs: Long = DEFAULT_COOLDOWN_MS,
    private val clock: () -> Long = { System.currentTimeMillis() }
) {
    companion object {
        const val DEFAULT_COOLDOWN_MS: Long = 300L
    }

    var runningTotal: Float = 0f
        private set

    private var lastCommandTimeMs: Long? = null

    fun onScroll(
        deltaPixels: Float,
        thresholdPx: Float,
        isCarouselMode: Boolean,
        timeMs: Long = clock()
    ): String? {
        val lastFired = lastCommandTimeMs
        if (lastFired != null && (timeMs - lastFired) < cooldownMs) {
            return null
        }

        if (deltaPixels == 0f || thresholdPx <= 0f) {
            return null
        }

        // Reset running total whenever the direction reverses
        if (runningTotal != 0f && (deltaPixels > 0f) != (runningTotal > 0f)) {
            runningTotal = 0f
        }

        runningTotal += deltaPixels

        if (abs(runningTotal) >= thresholdPx) {
            val isPositive = runningTotal > 0f
            runningTotal = 0f
            lastCommandTimeMs = timeMs

            return if (isCarouselMode) {
                if (isPositive) Protocol.CMD_SWIPE_LEFT else Protocol.CMD_SWIPE_RIGHT
            } else {
                if (isPositive) Protocol.CMD_NEXT_ITEM else Protocol.CMD_PREV_ITEM
            }
        }

        return null
    }

    fun reset() {
        runningTotal = 0f
        lastCommandTimeMs = null
    }
}
