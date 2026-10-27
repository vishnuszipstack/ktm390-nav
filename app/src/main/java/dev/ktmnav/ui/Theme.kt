package dev.ktmnav.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

object NavColors {
    val Accent = Color(0xFFFF7A1A)      // primary actions, guidance
    val Ok = Color(0xFF34D399)
    val Warning = Color(0xFFFFB547)
    val Danger = Color(0xFFFF5A5F)
    val Tft = Color(0xFF020304)         // TFT preview background
    val TftText = Color(0xFFF4F6F8)
}

private val DarkScheme = darkColorScheme(
    primary = NavColors.Accent,
    onPrimary = Color(0xFF261000),
    secondary = Color(0xFF5B8CFF),
    secondaryContainer = Color(0xFF1C2430),
    onSecondaryContainer = Color(0xFFEAF0F7),
    background = Color(0xFF06080B),
    onBackground = Color(0xFFF1F5F9),
    surface = Color(0xFF10151C),
    onSurface = Color(0xFFF1F5F9),
    surfaceVariant = Color(0xFF19202A),
    onSurfaceVariant = Color(0xFF9AA6B8),
    outline = Color(0xFF2B3442),
    outlineVariant = Color(0xFF1D2531),
    error = NavColors.Danger,
)

private val LightScheme = lightColorScheme(
    primary = Color(0xFFD45A00),
    onPrimary = Color.White,
    secondary = Color(0xFF2F5BEA),
    secondaryContainer = Color(0xFFE3E8EF),
    onSecondaryContainer = Color(0xFF0B1220),
    background = Color(0xFFEDF0F4),
    onBackground = Color(0xFF0B1220),
    surface = Color(0xFFFFFFFF),
    onSurface = Color(0xFF0B1220),
    surfaceVariant = Color(0xFFF1F4F8),
    onSurfaceVariant = Color(0xFF5B6678),
    outline = Color(0xFFCCD3DD),
    outlineVariant = Color(0xFFE2E7EE),
    error = Color(0xFFDC2626),
)

private val base = Typography()

/** Larger type for reading at arm's length on a bar mount. */
private val NavTypography = base.copy(
    labelMedium = base.labelMedium.copy(fontSize = 13.sp, fontWeight = FontWeight.Medium),
    labelLarge = base.labelLarge.copy(fontSize = 15.sp),
    bodyMedium = base.bodyMedium.copy(fontSize = 15.sp),
    bodyLarge = base.bodyLarge.copy(fontSize = 17.sp),
    titleMedium = base.titleMedium.copy(fontSize = 18.sp, fontWeight = FontWeight.SemiBold),
    titleLarge = TextStyle(fontSize = 24.sp, fontWeight = FontWeight.SemiBold),
)

@Composable
fun NavTheme(dark: Boolean, content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = if (dark) DarkScheme else LightScheme,
        typography = NavTypography,
        content = content,
    )
}
