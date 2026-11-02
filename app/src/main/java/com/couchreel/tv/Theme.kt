package com.couchreel.tv

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

val Bg = Color(0xFF12141A)
val Panel = Color(0xFF1B1E27)
val PanelHi = Color(0xFF2A2F3C)
val Accent = Color(0xFFF2A541)
val TextPrimary = Color(0xFFECEFF4)
val TextDim = Color(0xFF9AA3B2)

@Composable
fun CouchTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = darkColorScheme(
            primary = Accent,
            background = Bg,
            surface = Panel,
            onSurface = TextPrimary,
            onBackground = TextPrimary,
        ),
        content = content,
    )
}
