package com.sticky.reminder

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

/** Windows tarafıyla aynı yumuşak bebek mavisi / lacivert palet. */
object StickyColors {
    val Bg = Color(0xFFE9F1FB)
    val Bar = Color(0xFFD5E5F7)
    val Edge = Color(0xFFB9D0EA)
    val Ink = Color(0xFF1F2F4D)
    val Muted = Color(0xFF5F7593)
    val Accent = Color(0xFF213D68)
    val Focus = Color(0xFF5B86C4)
    val Danger = Color(0xFFC0485A)
    val Hint = Color(0xFF8A9DB8)
    val Tag = Color(0xFF3F6199)
}

/** Not renkleri; adlar Windows'taki ve veritabanındaki değerlerle aynıdır. */
val TASK_COLORS = listOf("blue", "navy", "green", "pink", "yellow", "purple")

fun taskColor(name: String): Color = when (name) {
    "navy" -> Color(0xFF2F4D80)
    "green" -> Color(0xFFA6D9B5)
    "pink" -> Color(0xFFF1B0C2)
    "yellow" -> Color(0xFFF2DC8F)
    "purple" -> Color(0xFFC3B3EA)
    else -> Color(0xFF9CC3EE)
}

@Composable
fun StickyTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = lightColorScheme(
            primary = StickyColors.Accent,
            onPrimary = Color.White,
            background = StickyColors.Bg,
            onBackground = StickyColors.Ink,
            surface = StickyColors.Bg,
            onSurface = StickyColors.Ink,
            outline = StickyColors.Edge,
        ),
        content = content,
    )
}
