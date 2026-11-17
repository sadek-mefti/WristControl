package com.example

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
import androidx.compose.runtime.mutableLongStateOf
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
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.ui.theme.CardCarbon
import com.example.ui.theme.CrimsonAlert
import com.example.ui.theme.ElectricLime
import com.example.ui.theme.JetBrainsMonoFontFamily
import com.example.ui.theme.OledBlack
import com.example.ui.theme.SurfaceSlate
import com.example.ui.theme.TacticalCyan
import com.example.ui.theme.TextPrimary
import com.example.ui.theme.TextSecondary
import com.wristscroll.remote.shared.Protocol
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.abs
import kotlin.math.hypot
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Shared rotary threshold constants (24 px High, 48 px Medium, 80 px Low)
 * matching :wear WatchSettings.kt.
 */
object PreviewRotaryThresholds {
    const val HIGH_PX = 24f
    const val MEDIUM_PX = 48f
    const val LOW_PX = 80f
}

enum class PreviewRotaryMode {
    FEED,
    CAROUSEL
}

enum class PreviewRotarySensitivity(val thresholdPx: Float, val label: String) {
    LOW(PreviewRotaryThresholds.LOW_PX, "Low (80px)"),
    MEDIUM(PreviewRotaryThresholds.MEDIUM_PX, "Med (48px)"),
    HIGH(PreviewRotaryThresholds.HIGH_PX, "High (24px)")
}

private const val STATUS_DOT_ALPHA = 0.10f
private const val ROTARY_COOLDOWN_MS = 300L
private const val DRAG_THRESHOLD_DP = 35f
private const val TAP_MAX_DURATION_MS = 400L
private const val DOUBLE_TAP_WINDOW_MS = 300L
private const val HOLD_EXIT_DURATION_MS = 1500L

@Composable
fun WatchRemoteInteractiveSimulator(
    isServiceActive: Boolean,
    onDispatchWatchCommand: (String) -> Unit
) {
    var isInStealth by remember { mutableStateOf(false) }
    var rotaryMode by remember { mutableStateOf(PreviewRotaryMode.FEED) }
    var sensitivity by remember { mutableStateOf(PreviewRotarySensitivity.MEDIUM) }
    var simulatedPhoneReachable by remember { mutableStateOf(true) }

    var accumulatedRotaryPx by remember { mutableFloatStateOf(0f) }
    var lastRotaryCommandTimeMs by remember { mutableLongStateOf(0L) }
    var lastHapticEvent by remember { mutableStateOf("Ready") }
    var lastGestureSummary by remember { mutableStateOf("None") }

    fun handleRotaryDelta(deltaPx: Float) {
        val now = SystemClock.uptimeMillis()
        if (now - lastRotaryCommandTimeMs < ROTARY_COOLDOWN_MS) {
            lastGestureSummary = "Ignored (300ms rotary cooldown)"
            return
        }
        if (deltaPx == 0f) return

        // Reset running total whenever direction reverses
        if (accumulatedRotaryPx != 0f && (deltaPx > 0f) != (accumulatedRotaryPx > 0f)) {
            accumulatedRotaryPx = 0f
        }

        accumulatedRotaryPx += deltaPx
        if (abs(accumulatedRotaryPx) >= sensitivity.thresholdPx) {
            val positive = accumulatedRotaryPx > 0f
            accumulatedRotaryPx = 0f
            lastRotaryCommandTimeMs = now

            val cmd = when (rotaryMode) {
                PreviewRotaryMode.FEED -> if (positive) Protocol.CMD_NEXT_ITEM else Protocol.CMD_PREV_ITEM
                PreviewRotaryMode.CAROUSEL -> if (positive) Protocol.CMD_SWIPE_LEFT else Protocol.CMD_SWIPE_RIGHT
            }
            if (!simulatedPhoneReachable) {
                lastHapticEvent = "ERROR Haptic (3x 15ms buzz — Phone Disconnected)"
                lastGestureSummary = "$cmd blocked (No reachable phone)"
            } else {
                onDispatchWatchCommand(cmd)
                lastHapticEvent = if (isServiceActive) {
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
            !isServiceActive -> "ERROR Haptic (3x 15ms buzz — SERVICE_DISABLED)"
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
                Column {
                    Text(
                        text = ":wear Interactive Watch Simulator",
                        fontWeight = FontWeight.Bold,
                        fontSize = 16.sp,
                        color = TextPrimary
                    )
                    Text(
                        text = if (isInStealth) {
                            "Mode: STEALTH REMOTE (Drag >35dp, Tap, Double-Tap, or 1.5s Hold to Exit)"
                        } else {
                            "Mode: WATCH DASHBOARD (Configure Rotary Mode & Sensitivity)"
                        },
                        fontSize = 12.sp,
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

            // Circular Wear OS Watch Frame (250.dp diameter)
            Box(
                modifier = Modifier
                    .size(256.dp)
                    .clip(CircleShape)
                    .border(4.dp, Color(0xFF253040), CircleShape)
                    .background(OledBlack),
                contentAlignment = Alignment.Center
            ) {
                if (!isInStealth) {
                    // Watch Dashboard UI inside the round watch screen
                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(horizontal = 22.dp, vertical = 18.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.SpaceBetween
                    ) {
                        // Connection status row
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(7.dp)
                                    .clip(CircleShape)
                                    .background(if (simulatedPhoneReachable) ElectricLime else CrimsonAlert)
                            )
                            Text(
                                text = if (simulatedPhoneReachable) "Connected: Pixel Phone" else "Disconnected",
                                fontSize = 11.sp,
                                fontWeight = FontWeight.SemiBold,
                                color = if (simulatedPhoneReachable) TextPrimary else CrimsonAlert
                            )
                        }

                        // Prominent "Enter Stealth Remote" button
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

                        // Rotary Mode Toggle
                        OutlinedButton(
                            onClick = {
                                rotaryMode = when (rotaryMode) {
                                    PreviewRotaryMode.FEED -> PreviewRotaryMode.CAROUSEL
                                    PreviewRotaryMode.CAROUSEL -> PreviewRotaryMode.FEED
                                }
                                accumulatedRotaryPx = 0f
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

                        // Sensitivity buttons (Low, Med, High)
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            PreviewRotarySensitivity.entries.forEach { option ->
                                val selected = sensitivity == option
                                Button(
                                    onClick = {
                                        sensitivity = option
                                        accumulatedRotaryPx = 0f
                                    },
                                    modifier = Modifier
                                        .weight(1f)
                                        .height(32.dp),
                                    contentPadding = androidx.compose.foundation.layout.PaddingValues(0.dp),
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
                    // Pure Black (#000000) Stealth Remote Canvas with 4dp status dot and awaitEachGesture
                    val context = LocalContext.current
                    val focusRequester = remember { FocusRequester() }
                    val coroutineScope = rememberCoroutineScope()
                    val currentOnTouchCmd by rememberUpdatedState(::handleTouchCommand)

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
                            .pointerInput(Unit) {
                                val dragThresholdPx = DRAG_THRESHOLD_DP.dp.toPx()
                                val touchSlopPx = viewConfiguration.touchSlop
                                var lastTapUpTimeMs = 0L
                                var pendingSingleTapJob: Job? = null

                                awaitEachGesture {
                                    val down = awaitFirstDown(requireUnconsumed = false)
                                    down.consume()

                                    val downTimeMs = SystemClock.uptimeMillis()
                                    val startPos = down.position

                                    val isSecondPressWithinWindow =
                                        lastTapUpTimeMs > 0L &&
                                            (downTimeMs - lastTapUpTimeMs) <= DOUBLE_TAP_WINDOW_MS

                                    if (isSecondPressWithinWindow) {
                                        pendingSingleTapJob?.cancel()
                                        pendingSingleTapJob = null
                                    }

                                    val outcomeFired = AtomicBoolean(false)
                                    var stayedWithinSlop = true

                                    val holdJob = coroutineScope.launch {
                                        delay(HOLD_EXIT_DURATION_MS)
                                        if (stayedWithinSlop && outcomeFired.compareAndSet(false, true)) {
                                            pendingSingleTapJob?.cancel()
                                            pendingSingleTapJob = null
                                            lastTapUpTimeMs = 0L
                                            isInStealth = false
                                            lastHapticEvent = "Stealth Exit Haptic (1x 60ms pulse)"
                                            lastGestureSummary = "1.5s Hold -> Exited to Dashboard"
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

                                        if (outcomeFired.get()) continue

                                        val dx = change.position.x - startPos.x
                                        val dy = change.position.y - startPos.y
                                        val dist = hypot(dx, dy)

                                        if (stayedWithinSlop && dist > touchSlopPx) {
                                            stayedWithinSlop = false
                                            holdJob.cancel()
                                        }

                                        if (abs(dx) >= dragThresholdPx || abs(dy) >= dragThresholdPx) {
                                            if (outcomeFired.compareAndSet(false, true)) {
                                                holdJob.cancel()
                                                pendingSingleTapJob?.cancel()
                                                pendingSingleTapJob = null
                                                lastTapUpTimeMs = 0L

                                                val cmd = if (abs(dy) >= abs(dx)) {
                                                    if (dy < 0f) Protocol.CMD_NEXT_ITEM else Protocol.CMD_PREV_ITEM
                                                } else {
                                                    if (dx < 0f) Protocol.CMD_SWIPE_LEFT else Protocol.CMD_SWIPE_RIGHT
                                                }
                                                currentOnTouchCmd(cmd, "Drag (>35dp)")
                                            }
                                        }
                                    }

                                    holdJob.cancel()

                                    if (!outcomeFired.get() && stayedWithinSlop) {
                                        val upTimeMs = SystemClock.uptimeMillis()
                                        val pressDurationMs = upTimeMs - downTimeMs

                                        if (pressDurationMs <= TAP_MAX_DURATION_MS) {
                                            if (isSecondPressWithinWindow) {
                                                lastTapUpTimeMs = 0L
                                                pendingSingleTapJob?.cancel()
                                                pendingSingleTapJob = null
                                                currentOnTouchCmd(
                                                    Protocol.CMD_DOUBLE_TAP_LIKE,
                                                    "Double Tap (<300ms)"
                                                )
                                            } else {
                                                lastTapUpTimeMs = upTimeMs
                                                pendingSingleTapJob?.cancel()
                                                val capturedUp = upTimeMs
                                                pendingSingleTapJob = coroutineScope.launch {
                                                    delay(DOUBLE_TAP_WINDOW_MS)
                                                    if (lastTapUpTimeMs == capturedUp) {
                                                        lastTapUpTimeMs = 0L
                                                        currentOnTouchCmd(
                                                            Protocol.CMD_SINGLE_TAP_PAUSE,
                                                            "Single Tap (after 300ms window)"
                                                        )
                                                    }
                                                }
                                            }
                                        } else {
                                            lastTapUpTimeMs = 0L
                                            lastGestureSummary =
                                                "Ignored still press (${pressDurationMs}ms between 400..1500ms)"
                                        }
                                    }
                                }
                            }
                    ) {
                        // 4 dp status dot at the top centre with alpha = 0.10f
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

            // Hardware Bezel / Crown Rotation Simulator Controls (active in Stealth Remote)
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
                            text = "Rotary Accumulator: ${accumulatedRotaryPx.toInt()} / ±${sensitivity.thresholdPx.toInt()} px",
                            fontFamily = JetBrainsMonoFontFamily,
                            fontSize = 12.sp,
                            color = TacticalCyan,
                            fontWeight = FontWeight.Bold
                        )
                        TextButtonSwitchConnection(
                            connected = simulatedPhoneReachable,
                            onToggle = { simulatedPhoneReachable = !simulatedPhoneReachable }
                        )
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

@Composable
private fun TextButtonSwitchConnection(
    connected: Boolean,
    onToggle: () -> Unit
) {
    Surface(
        onClick = onToggle,
        color = if (connected) ElectricLime.copy(alpha = 0.15f) else CrimsonAlert.copy(alpha = 0.15f),
        shape = RoundedCornerShape(8.dp),
        border = BorderStroke(1.dp, if (connected) ElectricLime else CrimsonAlert)
    ) {
        Text(
            text = if (connected) "Link: Online" else "Link: Offline",
            fontSize = 11.sp,
            fontFamily = JetBrainsMonoFontFamily,
            color = if (connected) ElectricLime else CrimsonAlert,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
        )
    }
}
