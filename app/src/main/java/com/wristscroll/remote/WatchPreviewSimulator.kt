package com.wristscroll.remote

import android.app.Activity
import android.os.SystemClock
import android.view.WindowManager
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.RotateLeft
import androidx.compose.material.icons.automirrored.filled.RotateRight
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
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
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalViewConfiguration
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.wristscroll.remote.shared.Protocol
import com.wristscroll.remote.shared.RotaryAccumulator
import com.wristscroll.remote.shared.TouchGestureClassifier
import com.wristscroll.remote.shared.TouchOutcome
import com.wristscroll.remote.ui.theme.CardCarbon
import com.wristscroll.remote.ui.theme.CrimsonAlert
import com.wristscroll.remote.ui.theme.ElectricLime
import com.wristscroll.remote.ui.theme.JetBrainsMonoFontFamily
import com.wristscroll.remote.ui.theme.OledBlack
import com.wristscroll.remote.ui.theme.SurfaceSlate
import com.wristscroll.remote.ui.theme.TacticalCyan
import com.wristscroll.remote.ui.theme.TextPrimary
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

object PreviewRotaryThresholds {
    const val HIGH_PX = 24f
    const val MEDIUM_PX = 48f
    const val LOW_PX = 80f
}

enum class PreviewRotaryMode {
    FEED,
    CAROUSEL
}

enum class PreviewRotarySensitivity(val thresholdPx: Float) {
    LOW(PreviewRotaryThresholds.LOW_PX),
    MEDIUM(PreviewRotaryThresholds.MEDIUM_PX),
    HIGH(PreviewRotaryThresholds.HIGH_PX)
}

private const val STATUS_DOT_ALPHA = 0.10f
private const val DRAG_THRESHOLD_DP = 35f

/**
 * Interactive preview screen reachable from MainActivity for testing the watch
 * Dashboard and Stealth Remote inside the AI Studio emulator.
 * Delegates all rotary and touch math to :shared RotaryAccumulator and TouchGestureClassifier.
 */
@Composable
fun WatchRemoteInteractiveSimulator(
    isServiceConnected: Boolean,
    onDispatchWatchCommand: (String) -> Unit
) {
    var isInStealth by remember { mutableStateOf(false) }
    var rotaryMode by remember { mutableStateOf(PreviewRotaryMode.FEED) }
    var sensitivity by remember { mutableStateOf(PreviewRotarySensitivity.MEDIUM) }
    var simulatedPhoneReachable by remember { mutableStateOf(true) }

    val rotaryAccumulator = remember {
        RotaryAccumulator(
            cooldownMs = RotaryAccumulator.DEFAULT_COOLDOWN_MS,
            clock = { SystemClock.uptimeMillis() }
        )
    }

    var displayedRotaryTotal by remember { mutableFloatStateOf(0f) }
    var lastHapticEvent by remember { mutableStateOf("Ready") }
    var lastGestureSummary by remember { mutableStateOf("None") }

    fun handleRotaryDelta(deltaPx: Float) {
        val cmd = rotaryAccumulator.onScroll(
            deltaPixels = deltaPx,
            thresholdPx = sensitivity.thresholdPx,
            isCarouselMode = rotaryMode == PreviewRotaryMode.CAROUSEL,
            timeMs = SystemClock.uptimeMillis()
        )
        displayedRotaryTotal = rotaryAccumulator.runningTotal

        if (cmd != null) {
            if (!simulatedPhoneReachable) {
                lastHapticEvent = "ERROR Haptic (3x 15ms buzz — Phone Disconnected)"
                lastGestureSummary = "$cmd blocked (No reachable phone)"
            } else {
                onDispatchWatchCommand(cmd)
                lastHapticEvent = if (isServiceConnected) {
                    "Command Haptic (1x 25ms tick)"
                } else {
                    "ERROR Haptic (3x 15ms buzz — SERVICE_DISABLED)"
                }
                lastGestureSummary = "Rotary -> $cmd"
            }
        }
    }

    fun handleTouchCommand(cmd: String, sourceGesture: String) {
        if (!simulatedPhoneReachable) {
            lastHapticEvent = "ERROR Haptic (3x 15ms buzz — Phone Disconnected)"
            lastGestureSummary = "$sourceGesture ($cmd) -> Failed (Disconnected)"
            return
        }
        onDispatchWatchCommand(cmd)
        lastHapticEvent = when {
            !isServiceConnected -> "ERROR Haptic (3x 15ms buzz — SERVICE_DISABLED)"
            cmd == Protocol.CMD_DOUBLE_TAP_LIKE -> "Double-Tap Like Haptic (2x 20ms, 40ms gap)"
            else -> "Command Haptic (1x 25ms tick)"
        }
        lastGestureSummary = "$sourceGesture -> $cmd"
    }

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .testTag("watch_simulator_card"),
        colors = CardDefaults.cardColors(containerColor = CardCarbon),
        shape = RoundedCornerShape(16.dp),
        border = BorderStroke(1.dp, ElectricLime.copy(alpha = 0.4f))
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = ":wear Interactive Watch Preview",
                        fontWeight = FontWeight.Bold,
                        fontSize = 16.sp,
                        color = TextPrimary
                    )
                    Text(
                        text = if (isInStealth) {
                            "STEALTH REMOTE (Uses :shared TouchGestureClassifier & RotaryAccumulator)"
                        } else {
                            "WATCH DASHBOARD (Configure Rotary Mode & Sensitivity)"
                        },
                        fontSize = 11.sp,
                        color = TacticalCyan
                    )
                }

                OutlinedButton(
                    onClick = {
                        isInStealth = true
                        lastHapticEvent = "Stealth Enter Haptic (1x 60ms pulse)"
                        lastGestureSummary = "Launched via Tile (start_in_stealth=true)"
                    },
                    shape = RoundedCornerShape(10.dp),
                    border = BorderStroke(1.dp, ElectricLime),
                    modifier = Modifier.testTag("sim_tile_launch_button")
                ) {
                    Text(
                        text = "Tile Launch",
                        fontSize = 11.sp,
                        color = ElectricLime,
                        fontWeight = FontWeight.Bold
                    )
                }
            }

            Box(
                modifier = Modifier
                    .size(256.dp)
                    .clip(CircleShape)
                    .border(4.dp, Color(0xFF253040), CircleShape)
                    .background(OledBlack),
                contentAlignment = Alignment.Center
            ) {
                if (!isInStealth) {
                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(horizontal = 22.dp, vertical = 18.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.SpaceBetween
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(7.dp)
                                    .clip(CircleShape)
                                    .background(
                                        if (simulatedPhoneReachable) ElectricLime else CrimsonAlert
                                    )
                            )
                            Text(
                                text = if (simulatedPhoneReachable) {
                                    "Connected: Pixel Phone"
                                } else {
                                    "Disconnected"
                                },
                                fontSize = 11.sp,
                                fontWeight = FontWeight.SemiBold,
                                color = if (simulatedPhoneReachable) TextPrimary else CrimsonAlert
                            )
                        }

                        Button(
                            onClick = {
                                isInStealth = true
                                lastHapticEvent = "Stealth Enter Haptic (1x 60ms pulse)"
                                lastGestureSummary = "Entered Stealth Remote"
                            },
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(42.dp)
                                .testTag("sim_enter_stealth_button"),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = ElectricLime,
                                contentColor = OledBlack
                            ),
                            shape = RoundedCornerShape(21.dp)
                        ) {
                            Text(
                                text = "Enter Stealth Remote",
                                fontSize = 12.sp,
                                fontWeight = FontWeight.ExtraBold
                            )
                        }

                        OutlinedButton(
                            onClick = {
                                rotaryMode = when (rotaryMode) {
                                    PreviewRotaryMode.FEED -> PreviewRotaryMode.CAROUSEL
                                    PreviewRotaryMode.CAROUSEL -> PreviewRotaryMode.FEED
                                }
                                rotaryAccumulator.reset()
                                displayedRotaryTotal = 0f
                            },
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(36.dp)
                                .testTag("sim_rotary_mode_toggle"),
                            border = BorderStroke(1.dp, TacticalCyan.copy(alpha = 0.6f)),
                            shape = RoundedCornerShape(18.dp)
                        ) {
                            Text(
                                text = if (rotaryMode == PreviewRotaryMode.FEED) {
                                    "Rotary: Feed (Up/Down)"
                                } else {
                                    "Rotary: Carousel (L/R)"
                                },
                                fontSize = 11.sp,
                                color = TacticalCyan,
                                fontWeight = FontWeight.Bold
                            )
                        }

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            PreviewRotarySensitivity.entries.forEach { option ->
                                val selected = sensitivity == option
                                Button(
                                    onClick = {
                                        sensitivity = option
                                        rotaryAccumulator.reset()
                                        displayedRotaryTotal = 0f
                                    },
                                    modifier = Modifier
                                        .weight(1f)
                                        .height(32.dp),
                                    contentPadding = PaddingValues(0.dp),
                                    colors = ButtonDefaults.buttonColors(
                                        containerColor = if (selected) TacticalCyan else SurfaceSlate,
                                        contentColor = if (selected) OledBlack else TextPrimary
                                    ),
                                    shape = RoundedCornerShape(16.dp)
                                ) {
                                    Text(
                                        text = option.name.take(3),
                                        fontSize = 10.sp,
                                        fontWeight = FontWeight.Bold
                                    )
                                }
                            }
                        }
                    }
                } else {
                    val context = LocalContext.current
                    val density = LocalDensity.current
                    val viewConfig = LocalViewConfiguration.current
                    val focusRequester = remember { FocusRequester() }
                    val coroutineScope = rememberCoroutineScope()
                    val currentOnTouchCmd by rememberUpdatedState(::handleTouchCommand)

                    val dragThresholdPx = remember(density) {
                        with(density) { DRAG_THRESHOLD_DP.dp.toPx() }
                    }
                    val touchSlopPx = viewConfig.touchSlop

                    val touchClassifier = remember(dragThresholdPx, touchSlopPx) {
                        TouchGestureClassifier(
                            dragThresholdPx = dragThresholdPx,
                            touchSlopPx = touchSlopPx,
                            clock = { SystemClock.uptimeMillis() }
                        )
                    }

                    fun applyOutcome(outcome: TouchOutcome, label: String) {
                        when (outcome) {
                            TouchOutcome.None -> Unit
                            is TouchOutcome.Command -> currentOnTouchCmd(outcome.command, label)
                            TouchOutcome.HoldExit -> {
                                isInStealth = false
                                lastHapticEvent = "Stealth Exit Haptic (1x 60ms pulse)"
                                lastGestureSummary = "1.5s Hold -> Exited to Dashboard"
                            }
                        }
                    }

                    DisposableEffect(context) {
                        val window = (context as? Activity)?.window
                        window?.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                        onDispose {
                            window?.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                        }
                    }

                    BackHandler(enabled = true) {
                        isInStealth = false
                        lastHapticEvent = "Stealth Exit Haptic (1x 60ms pulse)"
                        lastGestureSummary = "Exited Stealth via Back button"
                    }

                    LaunchedEffect(Unit) {
                        focusRequester.requestFocus()
                    }

                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .background(Color.Black)
                            .testTag("sim_stealth_canvas")
                            .onRotaryScrollEvent { event ->
                                handleRotaryDelta(event.verticalScrollPixels)
                                true
                            }
                            .focusRequester(focusRequester)
                            .focusable()
                            .pointerInput(touchClassifier) {
                                var pendingSingleTapJob: Job? = null
                                var holdJob: Job? = null

                                awaitEachGesture {
                                    val down = awaitFirstDown(requireUnconsumed = false)
                                    down.consume()

                                    var lastPos = down.position
                                    val downOutcome = touchClassifier.onDown(
                                        x = down.position.x,
                                        y = down.position.y,
                                        timeMs = SystemClock.uptimeMillis()
                                    )
                                    if (downOutcome != TouchOutcome.None) {
                                        pendingSingleTapJob?.cancel()
                                        pendingSingleTapJob = null
                                        applyOutcome(downOutcome, "Single Tap (flushed on next down)")
                                    }

                                    holdJob?.cancel()
                                    holdJob = coroutineScope.launch {
                                        delay(TouchGestureClassifier.HOLD_EXIT_DURATION_MS)
                                        val holdOutcome = touchClassifier.onHoldTimeout(
                                            SystemClock.uptimeMillis()
                                        )
                                        if (holdOutcome != TouchOutcome.None) {
                                            pendingSingleTapJob?.cancel()
                                            pendingSingleTapJob = null
                                            applyOutcome(holdOutcome, "1.5s Hold")
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
                                                lastPos = change.position
                                                change.consume()
                                            }
                                            break
                                        }

                                        lastPos = change.position
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
                                            applyOutcome(moveOutcome, "Drag (>35dp)")
                                        }
                                    }

                                    holdJob?.cancel()

                                    val upOutcome = touchClassifier.onUp(
                                        x = lastPos.x,
                                        y = lastPos.y,
                                        timeMs = SystemClock.uptimeMillis()
                                    )

                                    if (upOutcome != TouchOutcome.None) {
                                        pendingSingleTapJob?.cancel()
                                        pendingSingleTapJob = null
                                        applyOutcome(upOutcome, "Touch Up")
                                    } else if (touchClassifier.hasPendingFirstTap) {
                                        pendingSingleTapJob?.cancel()
                                        pendingSingleTapJob = coroutineScope.launch {
                                            delay(TouchGestureClassifier.DOUBLE_TAP_WINDOW_MS)
                                            val timeoutOutcome = touchClassifier.onSingleTapTimeout(
                                                SystemClock.uptimeMillis()
                                            )
                                            applyOutcome(timeoutOutcome, "Single Tap (300ms)")
                                        }
                                    }
                                }
                            }
                    ) {
                        val dotColor = if (simulatedPhoneReachable) {
                            ElectricLime.copy(alpha = STATUS_DOT_ALPHA)
                        } else {
                            CrimsonAlert.copy(alpha = STATUS_DOT_ALPHA)
                        }
                        Box(
                            modifier = Modifier
                                .align(Alignment.TopCenter)
                                .padding(top = 8.dp)
                                .size(4.dp)
                                .clip(CircleShape)
                                .background(dotColor)
                        )
                    }
                }
            }

            Surface(
                color = SurfaceSlate,
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(
                    modifier = Modifier.padding(12.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "Rotary Accumulator: ${displayedRotaryTotal.toInt()} / ±${sensitivity.thresholdPx.toInt()} px",
                            fontFamily = JetBrainsMonoFontFamily,
                            fontSize = 12.sp,
                            color = TacticalCyan,
                            fontWeight = FontWeight.Bold
                        )
                        Surface(
                            onClick = { simulatedPhoneReachable = !simulatedPhoneReachable },
                            color = if (simulatedPhoneReachable) {
                                ElectricLime.copy(alpha = 0.15f)
                            } else {
                                CrimsonAlert.copy(alpha = 0.15f)
                            },
                            shape = RoundedCornerShape(8.dp),
                            border = BorderStroke(
                                1.dp,
                                if (simulatedPhoneReachable) ElectricLime else CrimsonAlert
                            )
                        ) {
                            Text(
                                text = if (simulatedPhoneReachable) "Link: Online" else "Link: Offline",
                                fontSize = 11.sp,
                                fontFamily = JetBrainsMonoFontFamily,
                                color = if (simulatedPhoneReachable) ElectricLime else CrimsonAlert,
                                textAlign = TextAlign.Center,
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                            )
                        }
                    }

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Button(
                            onClick = {
                                if (!isInStealth) isInStealth = true
                                handleRotaryDelta(-16f)
                            },
                            modifier = Modifier
                                .weight(1f)
                                .height(48.dp)
                                .testTag("sim_rotary_ccw_button"),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = CardCarbon,
                                contentColor = TextPrimary
                            ),
                            shape = RoundedCornerShape(10.dp)
                        ) {
                            Icon(
                                imageVector = Icons.AutoMirrored.Filled.RotateLeft,
                                contentDescription = "Rotate Bezel Counter-Clockwise",
                                tint = TacticalCyan,
                                modifier = Modifier.size(18.dp)
                            )
                            Spacer(modifier = Modifier.size(6.dp))
                            Text("Bezel CCW (-16px)", fontSize = 11.sp)
                        }

                        Button(
                            onClick = {
                                if (!isInStealth) isInStealth = true
                                handleRotaryDelta(+16f)
                            },
                            modifier = Modifier
                                .weight(1f)
                                .height(48.dp)
                                .testTag("sim_rotary_cw_button"),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = CardCarbon,
                                contentColor = TextPrimary
                            ),
                            shape = RoundedCornerShape(10.dp)
                        ) {
                            Icon(
                                imageVector = Icons.AutoMirrored.Filled.RotateRight,
                                contentDescription = "Rotate Bezel Clockwise",
                                tint = ElectricLime,
                                modifier = Modifier.size(18.dp)
                            )
                            Spacer(modifier = Modifier.size(6.dp))
                            Text("Bezel CW (+16px)", fontSize = 11.sp)
                        }
                    }

                    Text(
                        text = "Last Gesture: $lastGestureSummary",
                        fontFamily = JetBrainsMonoFontFamily,
                        fontSize = 11.sp,
                        color = TextPrimary
                    )
                    Text(
                        text = "Last Haptic: $lastHapticEvent",
                        fontFamily = JetBrainsMonoFontFamily,
                        fontSize = 11.sp,
                        color = if (lastHapticEvent.startsWith("ERROR")) CrimsonAlert else ElectricLime
                    )
                }
            }
        }
    }
}
