package app.shilex.android.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

// ==========================================
// AMOLED Liquid Glass Palette (Frosted Dark)
// ==========================================
val OledBlack = Color(0xFF000000)
val GlassSurface = Color(0xBF151519) // 75% smoked translucent obsidian glass
val GlassSurfaceVariant = Color(0xCC1C1C22) // 80% elevated frosted card
val GlassSurfaceElevated = Color(0xE0222229) // 88% modal / floating glass
val GlassSurfaceHighlight = Color(0x28FFFFFF) // crisp specular rim sheen

// Accents
val ElectricCyan = Color(0xFF38E5FF)
val DeepCyan = Color(0xFF0B3542)
val NeonViolet = Color(0xFF9D65FF)
val SoftPink = Color(0xFFF472B6)
val AmberWarning = Color(0xFFFFB038)
val CoralError = Color(0xFFFF6363)
val SlateMuted = Color(0xFF9898A3)
val PureWhite = Color(0xFFF9FAFB)

// Glass Borders
val GlassBorderColor = Color(0x2BFFFFFF) // crisp frosted white rim
val GlassBorderSubtle = Color(0x18FFFFFF) // subtle divider / edge
val GlassDividerColor = Color(0x14FFFFFF)

private val Dark = darkColorScheme(
    primary = ElectricCyan,
    onPrimary = Color(0xFF001B22),
    primaryContainer = DeepCyan,
    onPrimaryContainer = Color(0xFFD0F7FF),
    secondary = NeonViolet,
    onSecondary = Color(0xFF1E0A40),
    secondaryContainer = Color(0xFF2B1654),
    tertiary = AmberWarning,
    background = OledBlack,
    onBackground = PureWhite,
    surface = OledBlack,
    onSurface = PureWhite,
    surfaceVariant = GlassSurfaceVariant,
    onSurfaceVariant = SlateMuted,
    surfaceContainer = Color(0xE6101014),
    outline = GlassBorderSubtle,
    outlineVariant = GlassBorderColor,
    error = CoralError,
)

private val Light = Dark // High-contrast AMOLED Liquid Glass dark experience

private val Type = Typography(
    displaySmall = TextStyle(
        fontSize = 30.sp,
        lineHeight = 36.sp,
        fontWeight = FontWeight.Bold,
        letterSpacing = (-0.6).sp,
        fontFamily = FontFamily.SansSerif,
    ),
    headlineSmall = TextStyle(
        fontSize = 24.sp,
        lineHeight = 30.sp,
        fontWeight = FontWeight.Bold,
        letterSpacing = (-0.4).sp,
        fontFamily = FontFamily.SansSerif,
    ),
    titleLarge = TextStyle(
        fontSize = 20.sp,
        lineHeight = 26.sp,
        fontWeight = FontWeight.SemiBold,
        letterSpacing = (-0.3).sp,
        fontFamily = FontFamily.SansSerif,
    ),
    titleMedium = TextStyle(
        fontSize = 15.5.sp,
        lineHeight = 21.sp,
        fontWeight = FontWeight.Medium,
        fontFamily = FontFamily.SansSerif,
    ),
    titleSmall = TextStyle(
        fontSize = 13.sp,
        lineHeight = 18.sp,
        fontWeight = FontWeight.Medium,
        letterSpacing = 0.1.sp,
        fontFamily = FontFamily.SansSerif,
    ),
    bodyLarge = TextStyle(
        fontSize = 16.sp,
        lineHeight = 23.sp,
        fontWeight = FontWeight.Normal,
        fontFamily = FontFamily.SansSerif,
    ),
    bodyMedium = TextStyle(
        fontSize = 14.sp,
        lineHeight = 20.sp,
        fontWeight = FontWeight.Normal,
        fontFamily = FontFamily.SansSerif,
    ),
    bodySmall = TextStyle(
        fontSize = 12.5.sp,
        lineHeight = 17.sp,
        fontWeight = FontWeight.Normal,
        fontFamily = FontFamily.SansSerif,
    ),
    labelLarge = TextStyle(
        fontSize = 14.sp,
        lineHeight = 20.sp,
        fontWeight = FontWeight.SemiBold,
        fontFamily = FontFamily.SansSerif,
    ),
    labelMedium = TextStyle(
        fontSize = 12.sp,
        lineHeight = 16.sp,
        fontWeight = FontWeight.Medium,
        letterSpacing = 0.2.sp,
        fontFamily = FontFamily.SansSerif,
    ),
)

private val Corners = Shapes(
    small = RoundedCornerShape(14.dp),
    medium = RoundedCornerShape(22.dp),
    large = RoundedCornerShape(30.dp),
)

/** AMOLED Liquid Glass theme on deep black. */
@Composable
fun ShilexTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = Dark,
        typography = Type,
        shapes = Corners,
        content = content,
    )
}

// Common color accessors for screens
val Accent: Color @Composable @ReadOnlyComposable get() = MaterialTheme.colorScheme.primary
val VioletAccent: Color @Composable @ReadOnlyComposable get() = MaterialTheme.colorScheme.secondary
val Background: Color @Composable @ReadOnlyComposable get() = MaterialTheme.colorScheme.background
val Surface: Color @Composable @ReadOnlyComposable get() = MaterialTheme.colorScheme.surfaceVariant
val Muted: Color @Composable @ReadOnlyComposable get() = MaterialTheme.colorScheme.onSurfaceVariant
val Warning: Color @Composable @ReadOnlyComposable get() = MaterialTheme.colorScheme.tertiary

// Smoked Frosted Liquid Glass Brushes (cached once to eliminate per-frame allocations)
val GlassBorderGradient: Brush = Brush.verticalGradient(
    listOf(
        Color(0x47FFFFFF), // crisp top specular highlight
        Color(0x1CFFFFFF),
        Color(0x28FFFFFF), // subtle bottom glass refraction
    ),
)

val GlassSurfaceGradient: Brush = Brush.verticalGradient(
    listOf(
        Color(0xCC1B1B20),
        Color(0xB3121216),
    ),
)

val GlassDockSurfaceGradient: Brush = Brush.verticalGradient(
    listOf(
        Color(0xB81D1D23),
        Color(0xD6111115),
    ),
)

val GlassDockBorderGradient: Brush = Brush.verticalGradient(
    listOf(
        Color(0x59FFFFFF),
        Color(0x2138E5FF),
        Color(0x30FFFFFF),
    ),
)

val GlassAccentGradient: Brush = Brush.horizontalGradient(
    listOf(
        Color(0xFF38E5FF),
        Color(0xFF9D65FF),
    ),
)

val UserChatBubbleGradient: Brush = Brush.horizontalGradient(
    listOf(
        Color(0xFF38E5FF),
        Color(0xFF7C3AED),
    ),
)

val LiveStopButtonGradient: Brush = Brush.verticalGradient(
    listOf(
        Color(0xFFE54D4D),
        Color(0xFF9E1F1F),
    ),
)

val TalkButtonGradient: Brush = Brush.verticalGradient(
    listOf(
        Color(0xFF45EEFF),
        Color(0xFF0C94A8),
    ),
)

val ButtonRimGradient: Brush = Brush.verticalGradient(
    listOf(
        Color(0xAAFFFFFF),
        Color(0x33FFFFFF),
    ),
)

val NeutralRowIconBgGradient: Brush = Brush.verticalGradient(
    listOf(
        Color(0x21FFFFFF),
        Color(0x0DFFFFFF),
    ),
)

val NeutralRowIconBorderGradient: Brush = Brush.verticalGradient(
    listOf(
        Color(0x33FFFFFF),
        Color(0x10FFFFFF),
    ),
)
