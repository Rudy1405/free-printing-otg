package com.freeprintotg.ui

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * 60 / 30 / 10 palette (light values; dark values are paired in each getter).
 *  60 %  base:    Paper  #FAFAFA (screen background)
 *  30 %  support: Mist   soft greys (cards, tiles, chips, dividers)
 *  10 %  accent:  Turquoise #00B4D8 (header, primary actions, selection)
 *  semantic, only when needed: Amber #F59E0B (warning), Red #EF4444 (error)
 */
object Palette {
    // Fixed in both themes: the turquoise brand bar and its dark ink.
    val Turquoise = Color(0xFF00B4D8)
    val OnTurquoise = Color(0xFF062B34)   // dark ink: white on #00B4D8 is only ~2.5:1
    val TurquoiseOnWhite = Color(0xFF006A7E) // icon on the white tile inside the bar
    val Amber = Color(0xFFF59E0B)      // fills and indicators
    val Red = Color(0xFFEF4444)

    // Theme-aware: read inside composables.
    val Paper: Color @Composable get() = pick(0xFFFAFAFA, 0xFF12171B)
    val Mist: Color @Composable get() = pick(0xFFF0F2F4, 0xFF1C2329)
    val MistStrong: Color @Composable get() = pick(0xFFE3E7EA, 0xFF27313A)
    val Line: Color @Composable get() = pick(0xFFDCE1E5, 0xFF33404A)
    val Ink: Color @Composable get() = pick(0xFF1F2933, 0xFFE8EDF1)
    val InkSoft: Color @Composable get() = pick(0xFF5F6B76, 0xFFA7B2BC)
    val TurquoiseDeep: Color @Composable get() = pick(0xFF006A7E, 0xFF4FD3EC) // text/icons: >=5.5:1 on the surfaces of each theme
    val TurquoiseTint: Color @Composable get() = pick(0xFFDDF4F9, 0xFF0E3C47)
    val AmberDeep: Color @Composable get() = pick(0xFFB45309, 0xFFFBBF24)
    val AmberTint: Color @Composable get() = pick(0xFFFEF3D9, 0xFF3A2D10)
    val RedTint: Color @Composable get() = pick(0xFFFDE4E4, 0xFF421C1F)

    @Composable
    private fun pick(light: Long, dark: Long) = Color(if (LocalDarkTheme.current) dark else light)
}

val LocalDarkTheme = compositionLocalOf { false }

private fun scheme(dark: Boolean) = if (!dark) lightColorScheme(
    primary = Palette.Turquoise,
    onPrimary = Palette.OnTurquoise,
    primaryContainer = Color(0xFFDDF4F9),
    onPrimaryContainer = Palette.OnTurquoise,
    secondary = Color(0xFF006A7E),
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFDDF4F9),
    onSecondaryContainer = Palette.OnTurquoise,
    background = Color(0xFFFAFAFA),
    onBackground = Color(0xFF1F2933),
    surface = Color(0xFFFAFAFA),
    onSurface = Color(0xFF1F2933),
    surfaceVariant = Color(0xFFF0F2F4),
    onSurfaceVariant = Color(0xFF5F6B76),
    surfaceContainer = Color(0xFFF0F2F4),
    surfaceContainerHigh = Color(0xFFE3E7EA),
    outline = Color(0xFFDCE1E5),
    outlineVariant = Color(0xFFDCE1E5),
    error = Palette.Red,
    onError = Color.White,
    errorContainer = Color(0xFFFDE4E4),
    onErrorContainer = Color(0xFF7A1212),
) else darkColorScheme(
    primary = Palette.Turquoise,
    onPrimary = Palette.OnTurquoise,
    primaryContainer = Color(0xFF0E3C47),
    onPrimaryContainer = Color(0xFFBFEFF9),
    secondary = Color(0xFF4FD3EC),
    onSecondary = Palette.OnTurquoise,
    secondaryContainer = Color(0xFF0E3C47),
    onSecondaryContainer = Color(0xFFBFEFF9),
    background = Color(0xFF12171B),
    onBackground = Color(0xFFE8EDF1),
    surface = Color(0xFF12171B),
    onSurface = Color(0xFFE8EDF1),
    surfaceVariant = Color(0xFF1C2329),
    onSurfaceVariant = Color(0xFFA7B2BC),
    surfaceContainer = Color(0xFF1C2329),
    surfaceContainerHigh = Color(0xFF27313A),
    outline = Color(0xFF33404A),
    outlineVariant = Color(0xFF33404A),
    error = Palette.Red,
    onError = Color.White,
    errorContainer = Color(0xFF421C1F),
    onErrorContainer = Color(0xFFFCA5A5),
)

private val typography = Typography(
    headlineSmall = TextStyle(fontSize = 22.sp, lineHeight = 28.sp, fontWeight = FontWeight.Medium),
    titleLarge = TextStyle(fontSize = 20.sp, lineHeight = 26.sp, fontWeight = FontWeight.Medium),
    titleMedium = TextStyle(fontSize = 16.sp, lineHeight = 22.sp, fontWeight = FontWeight.SemiBold),
    bodyLarge = TextStyle(fontSize = 17.sp, lineHeight = 24.sp),
    bodyMedium = TextStyle(fontSize = 14.sp, lineHeight = 20.sp),
    bodySmall = TextStyle(fontSize = 12.sp, lineHeight = 16.sp),
    labelLarge = TextStyle(fontSize = 15.sp, lineHeight = 20.sp, fontWeight = FontWeight.Medium),
)

private val shapes = Shapes(
    small = RoundedCornerShape(10.dp),
    medium = RoundedCornerShape(16.dp),
    large = RoundedCornerShape(24.dp),
)

@Composable
fun AppTheme(dark: Boolean = isSystemInDarkTheme(), content: @Composable () -> Unit) {
    CompositionLocalProvider(LocalDarkTheme provides dark) {
        MaterialTheme(colorScheme = scheme(dark), typography = typography, shapes = shapes, content = content)
    }
}
