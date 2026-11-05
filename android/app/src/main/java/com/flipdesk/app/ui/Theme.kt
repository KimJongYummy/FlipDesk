package com.flipdesk.app.ui

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

val Accent = Color(0xFF7CC4FF)
val AccentGreen = Color(0xFF3DDC84)
val Warn = Color(0xFFFFB74D)
val Danger = Color(0xFFFF6E6E)
val Pad = Color(0xFF0E0E10)
val PadEdge = Color(0xFF1C1C20)
val Muted = Color(0xFF9A9A9A)

private val Amoled = darkColorScheme(
    primary = Accent,
    onPrimary = Color.Black,
    primaryContainer = Color(0xFF0F2A3D),
    onPrimaryContainer = Color(0xFFCDE8FF),
    secondary = AccentGreen,
    onSecondary = Color.Black,
    secondaryContainer = Color(0xFF12301F),
    onSecondaryContainer = Color(0xFFC8F5DA),
    tertiary = Warn,
    background = Color.Black,
    onBackground = Color(0xFFF2F2F2),
    surface = Color.Black,
    onSurface = Color(0xFFF2F2F2),
    surfaceVariant = Color(0xFF16161A),
    onSurfaceVariant = Color(0xFFB8B8C0),
    surfaceContainerLowest = Color.Black,
    surfaceContainerLow = Color(0xFF09090B),
    surfaceContainer = Color(0xFF101013),
    surfaceContainerHigh = Color(0xFF17171B),
    surfaceContainerHighest = Color(0xFF1F1F24),
    outline = Color(0xFF3A3A42),
    outlineVariant = Color(0xFF26262C),
    error = Danger,
)

@Composable
fun FlipDeskTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = Amoled,
        shapes = Shapes(
            small = RoundedCornerShape(12.dp),
            medium = RoundedCornerShape(18.dp),
            large = RoundedCornerShape(26.dp),
        ),
        content = content,
    )
}
