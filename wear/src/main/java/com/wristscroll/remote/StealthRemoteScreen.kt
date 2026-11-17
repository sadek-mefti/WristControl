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
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.rotary.onRotaryScrollEvent
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalViewConfiguration
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.wristscroll.remote.shared.RotaryAccumulator
import com.wristscroll.remote.shared.TouchGestureClassifier
import com.wristscroll.remote.shared.TouchOutcome
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
const val ROTARY_COOLDOWN_MS: Long = RotaryAccumulator.DEFAULT_COOLDOWN_MS

private const val DRAG_THRESHOLD_DP = 35f

@Composable
fun StealthRemoteScreen(
    isConnected: Boolean,
    rotaryMode: RotaryMode,
    sensitivity: RotarySensitivity,
    onSendCommand: (String) -> Unit,
    onExitStealth: () -> Unit
) {
    val context = LocalContext.current
    val density = LocalDensity.current
    val viewConfiguration = LocalViewConfiguration.current
    val focusRequester = remember { FocusRequester() }
    val coroutineScope = rememberCoroutineScope()

    val currentRotaryMode by rememberUpdatedState(rotaryMode)
    val currentSensitivity by rememberUpdatedState(sensitivity)
    val currentOnSendCommand by rememberUpdatedState(onSendCommand)
    val currentOnExitStealth by rememberUpdatedState(onExitStealth)

    val rotaryAccumulator = remember {
        RotaryAccumulator(
            cooldownMs = ROTARY_COOLDOWN_MS,
            clock = { SystemClock.uptimeMillis() }
        )
    }

    val dragThresholdPx = remember(density) { with(density) { DRAG_THRESHOLD_DP.dp.toPx() } }
    val touchSlopPx = viewConfiguration.touchSlop

    val touchClassifier = remember(dragThresholdPx, touchSlopPx) {
        TouchGestureClassifier(
            dragThresholdPx = dragThresholdPx,
            touchSlopPx = touchSlopPx,
            clock = { SystemClock.uptimeMillis() }
        )
    }

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

    fun dispatchTouchOutcome(outcome: TouchOutcome) {
        when (outcome) {
            TouchOutcome.None -> Unit
            is TouchOutcome.Command -> currentOnSendCommand(outcome.command)
            TouchOutcome.HoldExit -> currentOnExitStealth()
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
            .testTag("stealth_remote_canvas")
            // Exact required order: onRotaryScrollEvent -> focusRequester -> focusable()
            .onRotaryScrollEvent { event ->
                val command = rotaryAccumulator.onScroll(
                    deltaPixels = event.verticalScrollPixels,
                    thresholdPx = currentSensitivity.thresholdPx,
                    isCarouselMode = currentRotaryMode == RotaryMode.CAROUSEL,
                    timeMs = SystemClock.uptimeMillis()
                )
                if (command != null) {
                    currentOnSendCommand(command)
                }
                true
            }
            .focusRequester(focusRequester)
            .focusable()
            // Single unified touch recognizer delegating to TouchGestureClassifier
            .pointerInput(touchClassifier) {
                var pendingSingleTapJob: Job? = null
                var holdJob: Job? = null

                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    down.consume()

                    val downTimeMs = SystemClock.uptimeMillis()
                    var lastPosition = down.position

                    touchClassifier.onDown(
                        x = down.position.x,
                        y = down.position.y,
                        timeMs = downTimeMs
                    )

                    holdJob?.cancel()
                    holdJob = coroutineScope.launch {
                        delay(TouchGestureClassifier.HOLD_EXIT_DURATION_MS)
                        val holdOutcome = touchClassifier.onHoldTimeout(SystemClock.uptimeMillis())
                        if (holdOutcome != TouchOutcome.None) {
                            pendingSingleTapJob?.cancel()
                            pendingSingleTapJob = null
                            dispatchTouchOutcome(holdOutcome)
                        }
                    }

                    var pointerDown = true
                    while (pointerDown) {
                        val event = awaitPointerEvent()
                        val change = event.changes.firstOrNull { it.id == down.id }
                            ?: event.changes.firstOrNull()

                        if (change == null || !change.pressed) {
                            pointerDown = false
                            if (change != null) {
                                lastPosition = change.position
                                change.consume()
                            }
                            break
                        }

                        lastPosition = change.position
                        change.consume()

                        val moveOutcome = touchClassifier.onMove(
                            x = change.position.x,
                            y = change.position.y,
                            timeMs = SystemClock.uptimeMillis()
                        )
                        if (moveOutcome != TouchOutcome.None) {
                            holdJob?.cancel()
                            pendingSingleTapJob?.cancel()
                            pendingSingleTapJob = null
                            dispatchTouchOutcome(moveOutcome)
                        }
                    }

                    // Finger lifted
                    holdJob?.cancel()

                    val upOutcome = touchClassifier.onUp(
                        x = lastPosition.x,
                        y = lastPosition.y,
                        timeMs = SystemClock.uptimeMillis()
                    )

                    if (upOutcome != TouchOutcome.None) {
                        pendingSingleTapJob?.cancel()
                        pendingSingleTapJob = null
                        dispatchTouchOutcome(upOutcome)
                    } else if (touchClassifier.hasPendingFirstTap) {
                        pendingSingleTapJob?.cancel()
                        pendingSingleTapJob = coroutineScope.launch {
                            delay(TouchGestureClassifier.DOUBLE_TAP_WINDOW_MS)
                            val timeoutOutcome =
                                touchClassifier.onSingleTapTimeout(SystemClock.uptimeMillis())
                            dispatchTouchOutcome(timeoutOutcome)
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
