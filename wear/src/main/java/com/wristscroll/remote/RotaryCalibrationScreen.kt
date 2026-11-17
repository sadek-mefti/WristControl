package com.wristscroll.remote

import android.os.SystemClock
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.rotary.onRotaryScrollEvent
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.wear.compose.material.Text
import java.util.Locale

private const val PAUSE_RESET_MS = 500L

private val CalibBlack = Color(0xFF000000)
private val CalibGreen = Color(0xFF00E676)
private val CalibCyan = Color(0xFF00B0FF)
private val CalibMuted = Color(0xFF9AA5B4)

/**
 * Full-screen rotary calibration view on the watch (Requirement 2.7):
 * - Requests rotary focus and consumes events without sending any commands.
 * - Shows verticalScrollPixels of the last event and the running total since the
 *   last pause of 500 ms or more.
 * - Shows the current Low, Medium and High thresholds from RotaryThresholds for comparison.
 * - Exits with the hardware back button.
 */
@Composable
fun RotaryCalibrationScreen(
    onExit: () -> Unit
) {
    val focusRequester = remember { FocusRequester() }

    var lastEventPixels by remember { mutableFloatStateOf(0f) }
    var runningTotalSincePause by remember { mutableFloatStateOf(0f) }
    var lastEventUptimeMs by remember { mutableLongStateOf(0L) }

    BackHandler(enabled = true) {
        onExit()
    }

    LaunchedEffect(Unit) {
        focusRequester.requestFocus()
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(CalibBlack)
            .padding(horizontal = 16.dp, vertical = 14.dp)
            .testTag("rotary_calibration_screen")
            .onRotaryScrollEvent { event ->
                val now = SystemClock.uptimeMillis()
                val delta = event.verticalScrollPixels
                lastEventPixels = delta
                if (lastEventUptimeMs == 0L || (now - lastEventUptimeMs) >= PAUSE_RESET_MS) {
                    runningTotalSincePause = delta
                } else {
                    runningTotalSincePause += delta
                }
                lastEventUptimeMs = now
                true
            }
            .focusRequester(focusRequester)
            .focusable(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Text(
            text = "Calibrate Rotary",
            color = CalibCyan,
            fontSize = 13.sp,
            fontWeight = FontWeight.Bold,
            textAlign = TextAlign.Center
        )

        Text(
            text = String.format(Locale.US, "Last: %+.1f px", lastEventPixels),
            color = Color.White,
            fontFamily = FontFamily.Monospace,
            fontSize = 14.sp,
            fontWeight = FontWeight.Bold,
            modifier = Modifier
                .padding(top = 6.dp)
                .testTag("calib_last_event_px")
        )

        Text(
            text = String.format(Locale.US, "Total: %+.1f px", runningTotalSincePause),
            color = CalibGreen,
            fontFamily = FontFamily.Monospace,
            fontSize = 15.sp,
            fontWeight = FontWeight.Bold,
            modifier = Modifier
                .padding(top = 2.dp)
                .testTag("calib_running_total_px")
        )

        Text(
            text = "(Resets after 500 ms pause)",
            color = CalibMuted,
            fontSize = 9.sp,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(top = 2.dp, bottom = 6.dp)
        )

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceEvenly
        ) {
            ThresholdComparisonItem(label = "High", valuePx = RotaryThresholds.HIGH_PX)
            ThresholdComparisonItem(label = "Med", valuePx = RotaryThresholds.MEDIUM_PX)
            ThresholdComparisonItem(label = "Low", valuePx = RotaryThresholds.LOW_PX)
        }

        Text(
            text = "Press Back to exit",
            color = CalibMuted,
            fontSize = 10.sp,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(top = 6.dp)
        )
    }
}

@Composable
private fun ThresholdComparisonItem(label: String, valuePx: Float) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(
            text = label,
            color = CalibMuted,
            fontSize = 10.sp
        )
        Text(
            text = "${valuePx.toInt()}px",
            color = Color.White,
            fontFamily = FontFamily.Monospace,
            fontSize = 11.sp,
            fontWeight = FontWeight.SemiBold
        )
    }
}
