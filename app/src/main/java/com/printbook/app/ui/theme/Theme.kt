package com.printbook.app.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color

private val LightColors = lightColorScheme(
    primary = Copper,
    onPrimary = PaperSurface,
    primaryContainer = CopperSoft,
    onPrimaryContainer = Ink,
    secondary = Ink,
    onSecondary = PaperSurface,
    secondaryContainer = PaperSurface2,
    onSecondaryContainer = Ink,
    tertiary = Copper,
    background = Paper,
    onBackground = Ink,
    surface = PaperSurface,
    onSurface = Ink,
    surfaceVariant = PaperSurface2,
    onSurfaceVariant = Ink2,
    surfaceContainerLowest = PaperSurface,
    surfaceContainerLow = PaperSurface,
    surfaceContainer = Paper,
    surfaceContainerHigh = PaperSurface2,
    surfaceContainerHighest = PaperSurface2,
    outline = LineLight,
    outlineVariant = LineLight,
    error = WarnLight,
    tertiaryContainer = CopperSoft,
)

private val DarkColors = darkColorScheme(
    primary = CopperDark,
    onPrimary = Ink,
    primaryContainer = CopperSoftDark,
    onPrimaryContainer = PaperText,
    secondary = PaperText,
    onSecondary = InkBg,
    secondaryContainer = InkSurface2,
    onSecondaryContainer = PaperText,
    tertiary = CopperDark,
    background = InkBg,
    onBackground = PaperText,
    surface = InkSurface,
    onSurface = PaperText,
    surfaceVariant = InkSurface2,
    onSurfaceVariant = PaperText2,
    surfaceContainerLowest = InkBg,
    surfaceContainerLow = InkSurface,
    surfaceContainer = InkSurface,
    surfaceContainerHigh = InkSurface2,
    surfaceContainerHighest = InkSurface2,
    outline = LineDark,
    outlineVariant = LineDark,
    error = WarnDark,
    tertiaryContainer = CopperSoftDark,
)

/** Tokens do design que não existem no Material 3. */
@Immutable
data class PrintBookTokens(
    val subtle: Color,
    val success: Color,
    val readerBackground: Color,
    val aura: List<Color>,
    val isDark: Boolean,
)

val LocalPrintBookTokens = staticCompositionLocalOf {
    PrintBookTokens(Ink3, OkLight, ReaderBgLight, AuraLight, false)
}

object PrintBook {
    val tokens: PrintBookTokens
        @Composable get() = LocalPrintBookTokens.current
}

/**
 * Tema do print-book (design 1B). Cor dinâmica (Material You) desligada por padrão para manter
 * a identidade: papel/tinta/cobre.
 */
@Composable
fun PrintBookTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    @Suppress("UNUSED_PARAMETER") dynamicColor: Boolean = false,
    content: @Composable () -> Unit,
) {
    val tokens = if (darkTheme) {
        PrintBookTokens(PaperText3, OkDark, ReaderBgDark, AuraDark, true)
    } else {
        PrintBookTokens(Ink3, OkLight, ReaderBgLight, AuraLight, false)
    }
    CompositionLocalProvider(LocalPrintBookTokens provides tokens) {
        MaterialTheme(
            colorScheme = if (darkTheme) DarkColors else LightColors,
            typography = Typography,
            content = content,
        )
    }
}
