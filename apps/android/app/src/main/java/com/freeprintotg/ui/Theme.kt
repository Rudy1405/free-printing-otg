package com.freeprintotg.ui

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * 60 / 30 / 10 palette.
 *  60 %  base:    Paper  #FAFAFA (screen background)
 *  30 %  support: Mist   soft greys (cards, tiles, chips, dividers)
 *  10 %  accent:  Turquoise #00B4D8 (header, primary actions, selection)
 *  semantic, only when needed: Amber #F59E0B (warning), Red #EF4444 (error)
 */
object Palette {
    val Paper = Color(0xFFFAFAFA)
    val Mist = Color(0xFFF0F2F4)
    val MistStrong = Color(0xFFE3E7EA)
    val Line = Color(0xFFDCE1E5)
    val Ink = Color(0xFF1F2933)
    val InkSoft = Color(0xFF5F6B76)

    val Turquoise = Color(0xFF00B4D8)
    val TurquoiseDeep = Color(0xFF006A7E) // text/icons on light surfaces: >=5.5:1 on Paper and Mist
    val TurquoiseTint = Color(0xFFDDF4F9)
    val OnTurquoise = Color(0xFF062B34)   // dark ink: white on #00B4D8 is only ~2.5:1

    val Amber = Color(0xFFF59E0B)      // fills and indicators
    val AmberDeep = Color(0xFFB45309)  // amber icons/text on AmberTint (>=4.5:1)
    val AmberTint = Color(0xFFFEF3D9)
    val Red = Color(0xFFEF4444)
    val RedTint = Color(0xFFFDE4E4)
}

private val scheme = lightColorScheme(
    primary = Palette.Turquoise,
    onPrimary = Palette.OnTurquoise,
    primaryContainer = Palette.TurquoiseTint,
    onPrimaryContainer = Palette.OnTurquoise,
    secondary = Palette.TurquoiseDeep,
    onSecondary = Color.White,
    secondaryContainer = Palette.TurquoiseTint,
    onSecondaryContainer = Palette.OnTurquoise,
    background = Palette.Paper,
    onBackground = Palette.Ink,
    surface = Palette.Paper,
    onSurface = Palette.Ink,
    surfaceVariant = Palette.Mist,
    onSurfaceVariant = Palette.InkSoft,
    surfaceContainer = Palette.Mist,
    surfaceContainerHigh = Palette.MistStrong,
    outline = Palette.Line,
    outlineVariant = Palette.Line,
    error = Palette.Red,
    onError = Color.White,
    errorContainer = Palette.RedTint,
    onErrorContainer = Color(0xFF7A1212),
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
fun AppTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = scheme, typography = typography, shapes = shapes, content = content)
}
