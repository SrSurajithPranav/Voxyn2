package com.voxyn.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

val VoxynCyan    = Color(0xFF00E5FF)
val VoxynBg      = Color(0xFF080C10)
val VoxynSurface = Color(0xFF0F1419)
val VoxynText    = Color(0xFFE0F7FA)

private val DarkColors = darkColorScheme(
    primary         = VoxynCyan,
    background      = VoxynBg,
    surface         = VoxynSurface,
    onBackground    = VoxynText,
    onSurface       = VoxynText,
)

@Composable
fun VoxynTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = DarkColors, content = content)
}
