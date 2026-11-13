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
// iOS Liquid Glass Colors - OLED Dark Palette
// ==========================================
val OledBlack = Color(0xFF000000)
val GlassSurface = Color(0x990E1524) // 60% translucent dark blue-black
val GlassSurfaceVariant = Color(0xB3121C30) // 70% translucent
val GlassSurfaceElevated = Color(0xCC162238) // 80% translucent
val GlassSurfaceHighlight = Color(0x1FFFFFFF) // Specular top sheen

// Accents
val ElectricCyan = Color(0xFF38E5FF)
val DeepCyan = Color(0xFF0D3E4D)
val NeonViolet = Color(0xFF9D65FF)
val SoftPink = Color(0xFFF472B6)
val AmberWarning = Color(0xFFFFB038)
val CoralError = Color(0xFFFF6363)
val SlateMuted = Color(0xFF94A3B8)
val PureWhite = Color(0xFFF8FAFC)

// Glass Borders
val GlassBorderColor = Color(0x2638E5FF) // subtle cyan edge
val GlassBorderSubtle = Color(0x1FFFFFFF) // white glass rim

private val Dark = darkColorScheme(
    primary = ElectricCyan,
    onPrimary = Color(0xFF001E26),
    primaryContainer = DeepCyan,
    onPrimaryContainer = Color(0xFFD0F7FF),
    secondary = NeonViolet,
    onSecondary = Color(0xFF1E0A40),
    secondaryContainer = Color(0xFF311860),
    tertiary = AmberWarning,
    background = OledBlack,
    onBackground = PureWhite,
    surface = OledBlack,
    onSurface = PureWhite,
    surfaceVariant = GlassSurfaceVariant,
    onSurfaceVariant = SlateMuted,
    surfaceContainer = Color(0xE6080D17),
    outline = GlassBorderSubtle,
    outlineVariant = GlassBorderColor,
    error = CoralError,
)

private val Light = Dark // Native JARVIS is designed for high-contrast OLED liquid glass dark experience

private val Type = Typography(
    displaySmall = TextStyle(
        fontSize = 32.sp,
        lineHeight = 38.sp,
        fontWeight = FontWeight.Bold,
        letterSpacing = (-0.6).sp,
        fontFamily = FontFamily.SansSerif,
    ),
    headlineSmall = TextStyle(
        fontSize = 24.sp,
        lineHeight = 30.sp,
        fontWeight = FontWeight.SemiBold,
        letterSpacing = (-0.3).sp,
        fontFamily = FontFamily.SansSerif,
    ),
    titleLarge = TextStyle(
        fontSize = 20.sp,
        lineHeight = 26.sp,
        fontWeight = FontWeight.SemiBold,
        letterSpacing = (-0.2).sp,
        fontFamily = FontFamily.SansSerif,
    ),
    titleMedium = TextStyle(
        fontSize = 16.sp,
        lineHeight = 22.sp,
        fontWeight = FontWeight.SemiBold,
        fontFamily = FontFamily.SansSerif,
    ),
    titleSmall = TextStyle(
        fontSize = 13.sp,
        lineHeight = 18.sp,
        fontWeight = FontWeight.SemiBold,
        letterSpacing = 0.5.sp,
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
        fontSize = 12.sp,
        lineHeight = 16.sp,
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
        letterSpacing = 0.4.sp,
        fontFamily = FontFamily.SansSerif,
    ),
)

// iOS smooth squircle-like rounded corners
private val Corners = Shapes(
    small = RoundedCornerShape(12.dp),
    medium = RoundedCornerShape(20.dp),
    large = RoundedCornerShape(28.dp),
)

/** Premium iOS Liquid Glass theme on deep OLED black. */
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

// Gradient brushes for Liquid Glass effects
val GlassBorderGradient: Brush
    get() = Brush.verticalGradient(
        listOf(
            Color(0x66FFFFFF),
            Color(0x3338E5FF),
            Color(0x0DFFFFFF),
        ),
    )

val GlassSurfaceGradient: Brush
    get() = Brush.verticalGradient(
        listOf(
            Color(0xB3152035),
            Color(0x800C1322),
        ),
    )

val GlassAccentGradient: Brush
    get() = Brush.horizontalGradient(
        listOf(
            Color(0xFF38E5FF),
            Color(0xFF9D65FF),
        ),
    )
