package com.wristscroll.remote

import android.app.Activity
import android.os.SystemClock
import android.view.WindowManager
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.rotary.onRotaryScrollEvent
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.wristscroll.remote.shared.Protocol
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.abs
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Visibility alpha for the 4 dp top-centre connection dot in Stealth Remote.
 * Kept in one constant starting at 0.10f for easy device tuning.
 */
const val STEALTH_STATUS_DOT_ALPHA: Float = 0.10f

/**
 * Cooldown duration after a rotary command fires during which incoming rotary
 * scroll events are ignored and not added to the running total.
 */
const val ROTARY_COOLDOWN_MS: Long = 300L

/**
 * Touch gesture timing and distance constants for Stealth Remote.
 */
private const val DRAG_THRESHOLD_DP = 35f
private const val TAP_MAX_DURATION_MS = 400L
private const val DOUBLE_TAP_WINDOW_MS = 300L
private const val HOLD_EXIT_DURATION_MS = 1500L

@Composable
fun StealthRemoteScreen(
    isConnected: Boolean,
    rotaryMode: RotaryMode,
    sensitivity: RotarySensitivity,
    onSendCommand: (String) -> Unit,
    onExitStealth: () -> Unit
) {
    val context = LocalContext.current
    val focusRequester = remember { FocusRequester() }
    val coroutineScope = rememberCoroutineScope()

    val currentRotaryMode by rememberUpdatedState(rotaryMode)
    val currentSensitivity by rememberUpdatedState(sensitivity)
    val currentOnSendCommand by rememberUpdatedState(onSendCommand)
    val currentOnExitStealth by rememberUpdatedState(onExitStealth)

    // Keep screen on while Stealth Remote is active; clear when leaving
    DisposableEffect(context) {
        val window = (context as? Activity)?.window
        window?.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        onDispose {
            window?.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
    }

    // Hardware back button returns to Dashboard
    BackHandler(enabled = true) {
        currentOnExitStealth()
    }

    // Request rotary focus when Stealth Remote appears
    LaunchedEffect(Unit) {
        focusRequester.requestFocus()
    }

    var accumulatedRotaryScroll by remember { mutableFloatStateOf(0f) }
    var lastRotaryCommandUptimeMs by remember { mutableLongStateOf(0L) }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
            .testTag("stealth_remote_canvas")
            // Exact required order: onRotaryScrollEvent -> focusRequester -> focusable()
            .onRotaryScrollEvent { event ->
                val now = SystemClock.uptimeMillis()
                // Ignore rotary events for 300 ms after a command fires and do not add them to total
                if (now - lastRotaryCommandUptimeMs < ROTARY_COOLDOWN_MS) {
                    return@onRotaryScrollEvent true
                }

                val delta = event.verticalScrollPixels
                if (delta == 0f) {
                    return@onRotaryScrollEvent true
                }

                // Reset running total whenever the direction reverses
                if (accumulatedRotaryScroll != 0f &&
                    (delta > 0f) != (accumulatedRotaryScroll > 0f)
                ) {
                    accumulatedRotaryScroll = 0f
                }

                accumulatedRotaryScroll += delta
                val threshold = currentSensitivity.thresholdPx

                if (abs(accumulatedRotaryScroll) >= threshold) {
                    val isPositive = accumulatedRotaryScroll > 0f
                    accumulatedRotaryScroll = 0f
                    lastRotaryCommandUptimeMs = now

                    val command = when (currentRotaryMode) {
                        RotaryMode.FEED -> if (isPositive) {
                            Protocol.CMD_NEXT_ITEM
                        } else {
                            Protocol.CMD_PREV_ITEM
                        }

                        RotaryMode.CAROUSEL -> if (isPositive) {
                            Protocol.CMD_SWIPE_LEFT
                        } else {
                            Protocol.CMD_SWIPE_RIGHT
                        }
                    }
                    currentOnSendCommand(command)
                }
                true
            }
            .focusRequester(focusRequester)
            .focusable()
            // Single unified touch recognizer using awaitEachGesture
            .pointerInput(Unit) {
                val dragThresholdPx = DRAG_THRESHOLD_DP.dp.toPx()
                val touchSlopPx = viewConfiguration.touchSlop

                var lastTapUpTimeMs = 0L
                var pendingSingleTapJob: Job? = null

                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    down.consume()

                    val downTimeMs = SystemClock.uptimeMillis()
                    val startPosition = down.position

                    // Check if this press started within 300 ms of a previous valid tap ending
                    val isSecondPressWithinWindow =
                        lastTapUpTimeMs > 0L && (downTimeMs - lastTapUpTimeMs) <= DOUBLE_TAP_WINDOW_MS

                    if (isSecondPressWithinWindow) {
                        pendingSingleTapJob?.cancel()
                        pendingSingleTapJob = null
                    }

                    val outcomeFired = AtomicBoolean(false)
                    var stayedWithinSlop = true

                    // 1500 ms hold timer that fires while the finger is still down
                    val holdJob = coroutineScope.launch {
                        delay(HOLD_EXIT_DURATION_MS)
                        if (stayedWithinSlop && outcomeFired.compareAndSet(false, true)) {
                            pendingSingleTapJob?.cancel()
                            pendingSingleTapJob = null
                            lastTapUpTimeMs = 0L
                            currentOnExitStealth()
                        }
                    }

                    var pointerDown = true
                    while (pointerDown) {
                        val event = awaitPointerEvent()
                        val change = event.changes.firstOrNull { it.id == down.id }
                            ?: event.changes.firstOrNull()

                        if (change == null || !change.pressed) {
                            pointerDown = false
                            change?.consume()
                            break
                        }

                        change.consume()

                        if (outcomeFired.get()) {
                            // Gesture already produced a drag or 1.5s hold exit; ignore rest of press
                            continue
                        }

                        val dx = change.position.x - startPosition.x
                        val dy = change.position.y - startPosition.y
                        val distance = kotlin.math.hypot(dx, dy)

                        if (stayedWithinSlop && distance > touchSlopPx) {
                            stayedWithinSlop = false
                            holdJob.cancel()
                        }

                        // Drag: when movement passes 35 dp, fire dominant axis straight away
                        if (abs(dx) >= dragThresholdPx || abs(dy) >= dragThresholdPx) {
                            if (outcomeFired.compareAndSet(false, true)) {
                                holdJob.cancel()
                                pendingSingleTapJob?.cancel()
                                pendingSingleTapJob = null
                                lastTapUpTimeMs = 0L

                                val dragCommand = if (abs(dy) >= abs(dx)) {
                                    if (dy < 0f) Protocol.CMD_NEXT_ITEM else Protocol.CMD_PREV_ITEM
                                } else {
                                    if (dx < 0f) Protocol.CMD_SWIPE_LEFT else Protocol.CMD_SWIPE_RIGHT
                                }
                                currentOnSendCommand(dragCommand)
                            }
                        }
                    }

                    // Finger lifted
                    holdJob.cancel()

                    if (!outcomeFired.get() && stayedWithinSlop) {
                        val upTimeMs = SystemClock.uptimeMillis()
                        val pressDurationMs = upTimeMs - downTimeMs

                        // Tap: released within 400 ms that stayed inside touch slop.
                        // A still press released between 400 ms and 1500 ms does nothing.
                        if (pressDurationMs <= TAP_MAX_DURATION_MS) {
                            if (isSecondPressWithinWindow) {
                                // Second tap completed -> send DOUBLE_TAP_LIKE immediately
                                lastTapUpTimeMs = 0L
                                pendingSingleTapJob?.cancel()
                                pendingSingleTapJob = null
                                currentOnSendCommand(Protocol.CMD_DOUBLE_TAP_LIKE)
                            } else {
                                // First tap completed -> wait 300 ms for a potential second tap
                                lastTapUpTimeMs = upTimeMs
                                pendingSingleTapJob?.cancel()
                                val capturedTapUpTime = upTimeMs
                                pendingSingleTapJob = coroutineScope.launch {
                                    delay(DOUBLE_TAP_WINDOW_MS)
                                    if (lastTapUpTimeMs == capturedTapUpTime) {
                                        lastTapUpTimeMs = 0L
                                        currentOnSendCommand(Protocol.CMD_SINGLE_TAP_PAUSE)
                                    }
                                }
                            }
                        } else {
                            // Released between 400 ms and 1500 ms -> do nothing
                            lastTapUpTimeMs = 0L
                        }
                    }
                }
            }
    ) {
        // 4 dp status dot at the top centre: dim green while reachable, dim red when not
        val dotColor = if (isConnected) {
            Color(0xFF00E676).copy(alpha = STEALTH_STATUS_DOT_ALPHA)
        } else {
            Color(0xFFFF5252).copy(alpha = STEALTH_STATUS_DOT_ALPHA)
        }

        Box(
            modifier = Modifier
                .align(Alignment.TopCenter)
                .padding(top = 6.dp)
                .size(4.dp)
                .clip(CircleShape)
                .background(dotColor)
                .testTag("stealth_status_dot")
        )
    }
}
