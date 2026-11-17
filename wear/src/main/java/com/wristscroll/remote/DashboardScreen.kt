package com.wristscroll.remote

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.wear.compose.foundation.lazy.ScalingLazyColumn
import androidx.wear.compose.foundation.lazy.rememberScalingLazyListState
import androidx.wear.compose.material.Button
import androidx.wear.compose.material.ButtonDefaults
import androidx.wear.compose.material.Chip
import androidx.wear.compose.material.ChipDefaults
import androidx.wear.compose.material.PositionIndicator
import androidx.wear.compose.material.Scaffold
import androidx.wear.compose.material.Text
import androidx.wear.compose.material.TimeText
import com.wristscroll.remote.shared.Protocol

private val WearBlack = Color(0xFF000000)
private val WearSurface = Color(0xFF141A22)
private val WearAccentGreen = Color(0xFF00E676)
private val WearAccentCyan = Color(0xFF00B0FF)
private val WearErrorRed = Color(0xFFFF5252)
private val WearTextSecondary = Color(0xFF9AA5B4)

@Composable
fun DashboardScreen(
    connectionState: WatchConnectionState,
    preferences: WatchPreferencesState,
    pingResultText: String?,
    isPinging: Boolean,
    onToggleRotaryMode: () -> Unit,
    onSelectSensitivity: (RotarySensitivity) -> Unit,
    onEnterStealth: () -> Unit,
    onPingPhone: () -> Unit,
    onOpenCalibration: () -> Unit
) {
    val listState = rememberScalingLazyListState()

    Scaffold(
        timeText = { TimeText() },
        positionIndicator = { PositionIndicator(scalingLazyListState = listState) },
        modifier = Modifier
            .fillMaxSize()
            .background(WearBlack)
    ) {
        ScalingLazyColumn(
            state = listState,
            modifier = Modifier
                .fillMaxSize()
                .background(WearBlack),
            contentPadding = PaddingValues(
                top = 28.dp,
                bottom = 32.dp,
                start = 10.dp,
                end = 10.dp
            ),
            verticalArrangement = Arrangement.spacedBy(6.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            // 1. Connection status badge
            item {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.Center,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 8.dp, vertical = 2.dp)
                        .testTag("watch_connection_status")
                ) {
                    Box(
                        modifier = Modifier
                            .size(8.dp)
                            .clip(CircleShape)
                            .background(
                                if (connectionState.isConnected) WearAccentGreen else WearErrorRed
                            )
                    )
                    val statusText = if (connectionState.isConnected) {
                        stringResource(
                            R.string.status_connected,
                            connectionState.connectedNodeName ?: "Phone"
                        )
                    } else {
                        stringResource(R.string.status_disconnected)
                    }
                    Text(
                        text = statusText,
                        color = if (connectionState.isConnected) Color.White else WearErrorRed,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(start = 6.dp)
                    )
                }
            }

            // 2. Readable error line when phone reported an error (Requirement 2.5)
            val errorCode = connectionState.lastError
            if (!errorCode.isNullOrEmpty()) {
                item {
                    val readableError = when (errorCode) {
                        Protocol.ERR_SERVICE_DISABLED ->
                            stringResource(R.string.error_service_disabled)
                        Protocol.ERR_PHONE_NOT_READY ->
                            stringResource(R.string.error_phone_not_ready)
                        Protocol.ERR_APP_NOT_ALLOWED ->
                            stringResource(R.string.error_app_not_allowed)
                        else -> errorCode
                    }
                    Text(
                        text = readableError,
                        color = WearErrorRed,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Medium,
                        textAlign = TextAlign.Center,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 8.dp, vertical = 2.dp)
                            .testTag("dashboard_error_text")
                    )
                }
            }

            // 3. Prominent "Enter Stealth Remote" button
            item {
                Chip(
                    onClick = onEnterStealth,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(48.dp)
                        .testTag("enter_stealth_button"),
                    colors = ChipDefaults.primaryChipColors(
                        backgroundColor = WearAccentGreen,
                        contentColor = WearBlack
                    ),
                    label = {
                        Text(
                            text = stringResource(R.string.enter_stealth_remote),
                            color = WearBlack,
                            fontSize = 13.sp,
                            fontWeight = FontWeight.Bold,
                            textAlign = TextAlign.Center,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                )
            }

            // 4. Rotary Mode Toggle (Feed vs Carousel)
            item {
                val modeText = when (preferences.rotaryMode) {
                    RotaryMode.FEED -> stringResource(R.string.rotary_mode_feed)
                    RotaryMode.CAROUSEL -> stringResource(R.string.rotary_mode_carousel)
                }
                Chip(
                    onClick = onToggleRotaryMode,
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("rotary_mode_chip"),
                    colors = ChipDefaults.secondaryChipColors(
                        backgroundColor = WearSurface,
                        contentColor = Color.White,
                        secondaryContentColor = WearAccentCyan
                    ),
                    label = {
                        Text(
                            text = stringResource(R.string.rotary_mode_label),
                            fontSize = 11.sp,
                            color = WearTextSecondary
                        )
                    },
                    secondaryLabel = {
                        Text(
                            text = modeText,
                            fontSize = 13.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = WearAccentCyan
                        )
                    }
                )
            }

            // 5. Sensitivity selector header
            item {
                Text(
                    text = stringResource(R.string.sensitivity_label),
                    fontSize = 11.sp,
                    color = WearTextSecondary,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.padding(top = 4.dp)
                )
            }

            // 6. Sensitivity row: Low, Medium, High
            item {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(4.dp, Alignment.CenterHorizontally),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    RotarySensitivity.entries.forEach { option ->
                        val isSelected = preferences.sensitivity == option
                        Button(
                            onClick = { onSelectSensitivity(option) },
                            modifier = Modifier
                                .weight(1f)
                                .height(36.dp)
                                .clip(RoundedCornerShape(18.dp))
                                .testTag("sensitivity_${option.name.lowercase()}"),
                            colors = ButtonDefaults.buttonColors(
                                backgroundColor = if (isSelected) WearAccentCyan else WearSurface,
                                contentColor = if (isSelected) WearBlack else Color.White
                            )
                        ) {
                            Text(
                                text = option.label,
                                fontSize = 11.sp,
                                fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium
                            )
                        }
                    }
                }
            }

            // 7. "Ping phone" chip (Requirement 2.6)
            item {
                Chip(
                    onClick = {
                        if (!isPinging) {
                            onPingPhone()
                        }
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("ping_phone_chip"),
                    colors = ChipDefaults.secondaryChipColors(
                        backgroundColor = WearSurface,
                        contentColor = Color.White,
                        secondaryContentColor = WearAccentCyan
                    ),
                    label = {
                        Text(
                            text = stringResource(R.string.ping_phone_label),
                            fontSize = 12.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = Color.White
                        )
                    },
                    secondaryLabel = if (pingResultText != null) {
                        {
                            Text(
                                text = pingResultText,
                                fontSize = 11.sp,
                                color = if (pingResultText == stringResource(R.string.ping_no_reply)) {
                                    WearErrorRed
                                } else {
                                    WearAccentGreen
                                }
                            )
                        }
                    } else {
                        null
                    }
                )
            }

            // 8. "Calibrate rotary" chip (Requirement 2.7)
            item {
                Chip(
                    onClick = onOpenCalibration,
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("calibrate_rotary_chip"),
                    colors = ChipDefaults.secondaryChipColors(
                        backgroundColor = WearSurface,
                        contentColor = Color.White
                    ),
                    label = {
                        Text(
                            text = stringResource(R.string.calibrate_rotary_label),
                            fontSize = 12.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = WearAccentCyan
                        )
                    }
                )
            }
        }
    }
}
