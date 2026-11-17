package com.wristscroll.remote.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable

private val WristScrollOledScheme = darkColorScheme(
    primary = ElectricLime,
    onPrimary = OledBlack,
    secondary = TacticalCyan,
    onSecondary = OledBlack,
    background = OledBlack,
    onBackground = TextPrimary,
    surface = CardCarbon,
    onSurface = TextPrimary,
    surfaceVariant = SurfaceSlate,
    onSurfaceVariant = TextSecondary,
    error = CrimsonAlert,
    onError = OledBlack
)

@Composable
fun WristScrollTheme(
    content: @Composable () -> Unit
) {
    MaterialTheme(
        colorScheme = WristScrollOledScheme,
        typography = Typography,
        content = content
    )
}
