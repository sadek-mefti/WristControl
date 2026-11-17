package com.wristscroll.remote.shared

import kotlin.math.abs
import kotlin.math.hypot

/**
 * Outcome produced by [TouchGestureClassifier].
 * Note: [HoldExit] is an internal watch UI outcome only and is never sent over the Data Layer.
 */
sealed interface TouchOutcome {
    data object None : TouchOutcome
    data class Command(val command: String) : TouchOutcome
    data object HoldExit : TouchOutcome
}

/**
 * Pure Kotlin touch gesture state machine with an injectable clock and zero Android imports.
 *
 * Rules enforced:
 * - Drag: when movement passes [dragThresholdPx] (35 dp), sends the command for the dominant
 *   axis immediately (up = NEXT_ITEM, down = PREV_ITEM, left = SWIPE_LEFT, right = SWIPE_RIGHT)
 *   and ignores the rest of that press.
 * - Tap: a press released within 400 ms that stayed inside [touchSlopPx].
 * - Double tap: a second tap that starts within 300 ms of the first one ending sends DOUBLE_TAP_LIKE.
 * - Single tap: sends SINGLE_TAP_PAUSE once that 300 ms window passes with no second tap.
 * - Hold: a press that stays inside [touchSlopPx] for 1500 ms exits to the Dashboard at that moment,
 *   while the finger is still down.
 * - Still press released between 400 ms and 1500 ms: does nothing on a first press.
 * - Second press inside the 300 ms double-tap window (Rule 2.1):
 *   The first tap waits for the second press to resolve:
 *   1) Second press is a tap (<= 400 ms, within slop) -> DOUBLE_TAP_LIKE only.
 *   2) Second press becomes a drag (>= 35 dp) -> drag command only.
 *   3) Second press becomes a 1.5 s hold -> HoldExit only.
 *   4) Second press is a still press released between 400 ms and 1500 ms -> sends first tap's SINGLE_TAP_PAUSE.
 */
class TouchGestureClassifier(
    private val dragThresholdPx: Float,
    private val touchSlopPx: Float,
    private val clock: () -> Long = { System.currentTimeMillis() }
) {
    companion object {
        const val TAP_MAX_DURATION_MS: Long = 400L
        const val DOUBLE_TAP_WINDOW_MS: Long = 300L
        const val HOLD_EXIT_DURATION_MS: Long = 1500L
    }

    private var isPointerDown: Boolean = false
    private var downX: Float = 0f
    private var downY: Float = 0f
    private var downTimeMs: Long = 0L
    private var stayedWithinSlop: Boolean = true
    private var pressOutcomeResolved: Boolean = false

    private var pendingFirstTapUpTimeMs: Long? = null
    private var isSecondPressInDoubleTapWindow: Boolean = false

    val hasPendingFirstTap: Boolean
        get() = pendingFirstTapUpTimeMs != null

    val isWaitingForSecondPressResolution: Boolean
        get() = isSecondPressInDoubleTapWindow && isPointerDown

    fun onDown(x: Float, y: Float, timeMs: Long = clock()): TouchOutcome {
        val previousTapUp = pendingFirstTapUpTimeMs
        val expiredFirstTapOutcome = if (previousTapUp != null &&
            (timeMs - previousTapUp) > DOUBLE_TAP_WINDOW_MS
        ) {
            pendingFirstTapUpTimeMs = null
            TouchOutcome.Command(Protocol.CMD_SINGLE_TAP_PAUSE)
        } else {
            TouchOutcome.None
        }

        isSecondPressInDoubleTapWindow =
            previousTapUp != null && (timeMs - previousTapUp) <= DOUBLE_TAP_WINDOW_MS

        isPointerDown = true
        downX = x
        downY = y
        downTimeMs = timeMs
        stayedWithinSlop = true
        pressOutcomeResolved = false

        return expiredFirstTapOutcome
    }

    fun onMove(x: Float, y: Float, timeMs: Long = clock()): TouchOutcome {
        if (!isPointerDown || pressOutcomeResolved) {
            return TouchOutcome.None
        }

        val dx = x - downX
        val dy = y - downY
        val distance = hypot(dx, dy)

        if (stayedWithinSlop && distance > touchSlopPx) {
            stayedWithinSlop = false
        }

        // If 1500 ms elapsed while still inside touch slop, trigger HoldExit immediately
        if (stayedWithinSlop && (timeMs - downTimeMs) >= HOLD_EXIT_DURATION_MS) {
            pressOutcomeResolved = true
            pendingFirstTapUpTimeMs = null
            isSecondPressInDoubleTapWindow = false
            return TouchOutcome.HoldExit
        }

        // Drag: movement passes 35 dp threshold along either axis
        if (abs(dx) >= dragThresholdPx || abs(dy) >= dragThresholdPx) {
            pressOutcomeResolved = true
            pendingFirstTapUpTimeMs = null
            isSecondPressInDoubleTapWindow = false
            return TouchOutcome.Command(resolveDragCommand(dx, dy))
        }

        return TouchOutcome.None
    }

    /**
     * Called when the 1500 ms hold timer fires while the pointer is still down.
     */
    fun onHoldTimeout(timeMs: Long = clock()): TouchOutcome {
        if (isPointerDown &&
            !pressOutcomeResolved &&
            stayedWithinSlop &&
            (timeMs - downTimeMs) >= HOLD_EXIT_DURATION_MS
        ) {
            pressOutcomeResolved = true
            pendingFirstTapUpTimeMs = null
            isSecondPressInDoubleTapWindow = false
            return TouchOutcome.HoldExit
        }
        return TouchOutcome.None
    }

    fun onUp(x: Float, y: Float, timeMs: Long = clock()): TouchOutcome {
        if (!isPointerDown) {
            return TouchOutcome.None
        }

        if (!pressOutcomeResolved) {
            val dx = x - downX
            val dy = y - downY
            val distance = hypot(dx, dy)
            if (stayedWithinSlop && distance > touchSlopPx) {
                stayedWithinSlop = false
            }
            if (abs(dx) >= dragThresholdPx || abs(dy) >= dragThresholdPx) {
                isPointerDown = false
                pressOutcomeResolved = true
                pendingFirstTapUpTimeMs = null
                isSecondPressInDoubleTapWindow = false
                return TouchOutcome.Command(resolveDragCommand(dx, dy))
            }
        }

        isPointerDown = false

        if (pressOutcomeResolved) {
            return TouchOutcome.None
        }

        val pressDurationMs = timeMs - downTimeMs
        val wasSecondPress = isSecondPressInDoubleTapWindow
        isSecondPressInDoubleTapWindow = false

        if (stayedWithinSlop) {
            return when {
                pressDurationMs <= TAP_MAX_DURATION_MS -> {
                    if (wasSecondPress) {
                        // Second press resolved as a tap -> send DOUBLE_TAP_LIKE only
                        pendingFirstTapUpTimeMs = null
                        TouchOutcome.Command(Protocol.CMD_DOUBLE_TAP_LIKE)
                    } else {
                        // First tap -> start 300 ms double-tap window
                        pendingFirstTapUpTimeMs = timeMs
                        TouchOutcome.None
                    }
                }

                pressDurationMs >= HOLD_EXIT_DURATION_MS -> {
                    pendingFirstTapUpTimeMs = null
                    TouchOutcome.HoldExit
                }

                else -> {
                    // Still press released between 400 ms and 1500 ms
                    pendingFirstTapUpTimeMs = null
                    if (wasSecondPress) {
                        // Rule 2.1: Second press was an abandoned 400-1500 ms hold -> send first tap's SINGLE_TAP_PAUSE
                        TouchOutcome.Command(Protocol.CMD_SINGLE_TAP_PAUSE)
                    } else {
                        TouchOutcome.None
                    }
                }
            }
        } else {
            // Moved outside touch slop but never reached 35 dp drag threshold
            pendingFirstTapUpTimeMs = null
            return if (wasSecondPress) {
                TouchOutcome.Command(Protocol.CMD_SINGLE_TAP_PAUSE)
            } else {
                TouchOutcome.None
            }
        }
    }

    /**
     * Called when the 300 ms single-tap delay timer expires.
     */
    fun onSingleTapTimeout(timeMs: Long = clock()): TouchOutcome {
        if (isPointerDown || isSecondPressInDoubleTapWindow) {
            // A second press is currently in progress; wait for it to resolve
            return TouchOutcome.None
        }
        val firstTapUp = pendingFirstTapUpTimeMs ?: return TouchOutcome.None
        if ((timeMs - firstTapUp) >= DOUBLE_TAP_WINDOW_MS) {
            pendingFirstTapUpTimeMs = null
            return TouchOutcome.Command(Protocol.CMD_SINGLE_TAP_PAUSE)
        }
        return TouchOutcome.None
    }

    fun reset() {
        isPointerDown = false
        pressOutcomeResolved = false
        stayedWithinSlop = true
        pendingFirstTapUpTimeMs = null
        isSecondPressInDoubleTapWindow = false
    }

    private fun resolveDragCommand(dx: Float, dy: Float): String {
        return if (abs(dy) >= abs(dx)) {
            if (dy < 0f) Protocol.CMD_NEXT_ITEM else Protocol.CMD_PREV_ITEM
        } else {
            if (dx < 0f) Protocol.CMD_SWIPE_LEFT else Protocol.CMD_SWIPE_RIGHT
        }
    }
}
