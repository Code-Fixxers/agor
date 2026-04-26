package live.agor.app.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

private val Brand = Color(0xFF1677FF)
private val BrandDim = Color(0xFF0E63D6)

private val DarkColors = darkColorScheme(
    primary = Brand,
    onPrimary = Color.White,
    primaryContainer = BrandDim,
    onPrimaryContainer = Color.White,
    secondary = Color(0xFF4FAFF7),
    background = Color(0xFF0B0D10),
    onBackground = Color(0xFFEAECEF),
    surface = Color(0xFF14171B),
    onSurface = Color(0xFFEAECEF),
    surfaceVariant = Color(0xFF1F242B),
    onSurfaceVariant = Color(0xFFB6BEC8),
    outline = Color(0xFF2A2F37),
    error = Color(0xFFFF6B6B),
)

private val LightColors = lightColorScheme(
    primary = Brand,
    onPrimary = Color.White,
    primaryContainer = Color(0xFFD6E8FF),
    secondary = Color(0xFF1677FF),
    background = Color(0xFFFAFBFC),
    surface = Color.White,
    surfaceVariant = Color(0xFFEFF2F5),
    outline = Color(0xFFD0D5DA),
    error = Color(0xFFD64545),
)

private val AgorTypography = Typography(
    bodyLarge = TextStyle(
        fontFamily = FontFamily.Default,
        fontWeight = FontWeight.Normal,
        fontSize = 16.sp,
        lineHeight = 22.sp,
    ),
    bodyMedium = TextStyle(
        fontFamily = FontFamily.Default,
        fontWeight = FontWeight.Normal,
        fontSize = 14.sp,
        lineHeight = 20.sp,
    ),
    bodySmall = TextStyle(
        fontFamily = FontFamily.Default,
        fontWeight = FontWeight.Normal,
        fontSize = 12.sp,
        lineHeight = 16.sp,
    ),
    titleLarge = TextStyle(
        fontFamily = FontFamily.Default,
        fontWeight = FontWeight.SemiBold,
        fontSize = 20.sp,
        lineHeight = 26.sp,
    ),
    titleMedium = TextStyle(
        fontFamily = FontFamily.Default,
        fontWeight = FontWeight.SemiBold,
        fontSize = 16.sp,
        lineHeight = 22.sp,
    ),
    labelMedium = TextStyle(
        fontFamily = FontFamily.Default,
        fontWeight = FontWeight.Medium,
        fontSize = 13.sp,
        lineHeight = 16.sp,
    ),
)

@Composable
fun AgorTheme(useDark: Boolean = isSystemInDarkTheme(), content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = if (useDark) DarkColors else LightColors,
        typography = AgorTypography,
        content = content,
    )
}

object AgorMono {
    val Family = FontFamily.Default
}
