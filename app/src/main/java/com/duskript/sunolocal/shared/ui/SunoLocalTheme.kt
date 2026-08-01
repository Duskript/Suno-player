package com.duskript.sunolocal.shared.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

/**
 * SunoLocalTheme — Material 3 theme with an ElevenLabs-inspired dark palette.
 *
 * Primary dark accent: deep indigo-purple (#7C3AED -> purple500).
 * Surface colours: near-black with subtle tint.
 *
 * The light scheme is included for completeness but the app is optimised for dark.
 */
private val DarkColorScheme = darkColorScheme(
    primary = Color(0xFF7C3AED),           // ElevenLabs-inspired indigo/purple
    onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFF4C1D95),
    onPrimaryContainer = Color(0xFFEDE9FE),
    secondary = Color(0xFF8B5CF6),
    onSecondary = Color(0xFFFFFFFF),
    secondaryContainer = Color(0xFF5B21B6),
    onSecondaryContainer = Color(0xFFDDD6FE),
    tertiary = Color(0xFFA78BFA),
    background = Color(0xFF0A0A0F),
    onBackground = Color(0xFFF5F5F5),
    surface = Color(0xFF12121A),
    onSurface = Color(0xFFF5F5F5),
    surfaceVariant = Color(0xFF1E1E2A),
    onSurfaceVariant = Color(0xFFC4C4D0),
    outline = Color(0xFF3A3A4A),
    error = Color(0xFFFF6B6B),
    onError = Color(0xFFFFFFFF),
    errorContainer = Color(0xFF93000A),
    onErrorContainer = Color(0xFFFFDAD6)
)

private val LightColorScheme = lightColorScheme(
    primary = Color(0xFF7C3AED),
    onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFFEDE9FE),
    onPrimaryContainer = Color(0xFF2D0C5E),
    secondary = Color(0xFF6D28D9),
    background = Color(0xFFFCFCFF),
    onBackground = Color(0xFF1C1B1F),
    surface = Color(0xFFFCFCFF),
    onSurface = Color(0xFF1C1B1F),
)

@Composable
fun SunoLocalTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit
) {
    val colorScheme = if (darkTheme) DarkColorScheme else LightColorScheme

    MaterialTheme(
        colorScheme = colorScheme,
        content = content
    )
}
