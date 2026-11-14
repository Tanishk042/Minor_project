package org.forestear.node.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

val ForestBackground = Color(0xFF0B1512)
val ForestSurface = Color(0xFF12201B)
val ForestSurfaceHigh = Color(0xFF1A2C25)
val ForestGreen = Color(0xFF6FCF97)
val ForestAmber = Color(0xFFF2C94C)
val ForestRed = Color(0xFFEB5757)
val ForestBlue = Color(0xFF56CCF2)
val ForestText = Color(0xFFE4F1EA)
val ForestMuted = Color(0xFF8FA79C)

private val scheme = darkColorScheme(
    primary = ForestGreen,
    onPrimary = Color(0xFF062117),
    primaryContainer = Color(0xFF1E4A38),
    onPrimaryContainer = ForestText,
    secondary = ForestAmber,
    onSecondary = Color(0xFF2A2105),
    secondaryContainer = Color(0xFF4A3D12),
    onSecondaryContainer = ForestText,
    tertiary = ForestBlue,
    error = ForestRed,
    onError = Color(0xFF2A0808),
    background = ForestBackground,
    onBackground = ForestText,
    surface = ForestSurface,
    onSurface = ForestText,
    surfaceVariant = ForestSurfaceHigh,
    onSurfaceVariant = ForestMuted,
    outline = Color(0xFF345247),
)

private val typography = Typography(
    headlineMedium = TextStyle(fontWeight = FontWeight.SemiBold, fontSize = 26.sp),
    titleLarge = TextStyle(fontWeight = FontWeight.SemiBold, fontSize = 20.sp),
    titleMedium = TextStyle(fontWeight = FontWeight.SemiBold, fontSize = 16.sp),
    labelLarge = TextStyle(fontWeight = FontWeight.Medium, fontSize = 14.sp),
    bodySmall = TextStyle(fontSize = 12.sp),
)

@Composable
fun ForestEarTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = scheme, typography = typography, content = content)
}
