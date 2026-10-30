package com.xuanthanhtnt.xtplayer.ui.theme

import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

object XtColors {
    val leaf = Color(0xFF34C759)
    val lime = Color(0xFFA6E87A)
    val forest = Color(0xFF118A42)
    val accent = Color(0xFF2FBF5B)
    val danger = Color(0xFFFF453A)
    val background = Color(0xFF0E1210)
    val surface = Color(0xFF151B18)
    val surfaceHigh = Color(0xFF1E2622)
    val secondaryText = Color(0xB3FFFFFF)
    val glass = Color(0x1FFFFFFF)
    val glassBorder = Color(0x17FFFFFF)

    val brandGradient = Brush.linearGradient(listOf(lime, leaf, forest))
}

/** Sizes per form factor: phone, tablet, and the 10-foot TV layout. */
data class Dimens(
    val pagePadding: Dp,
    val posterWidth: Dp,
    val landscapeWidth: Dp,
    val corner: Dp,
    val heroHeight: Dp,
    val heroTitle: Int,
    val castSize: Dp,
    val gridSpacing: Dp,
)

val PhoneDimens = Dimens(16.dp, 116.dp, 240.dp, 16.dp, 420.dp, 30, 72.dp, 12.dp)
val TabletDimens = Dimens(28.dp, 140.dp, 280.dp, 18.dp, 480.dp, 40, 80.dp, 18.dp)
val TvDimens = Dimens(48.dp, 140.dp, 270.dp, 16.dp, 400.dp, 40, 80.dp, 22.dp)

/** True on Android TV / Google TV boxes: D-pad navigation, no touch, larger focus effects. */
val LocalIsTv = staticCompositionLocalOf { false }
val LocalDimens = staticCompositionLocalOf { PhoneDimens }

private val scheme = darkColorScheme(
    primary = XtColors.accent,
    onPrimary = Color.Black,
    primaryContainer = XtColors.forest,
    onPrimaryContainer = Color.White,
    secondary = XtColors.leaf,
    onSecondary = Color.Black,
    secondaryContainer = XtColors.surfaceHigh,
    onSecondaryContainer = Color.White,
    background = XtColors.background,
    onBackground = Color.White,
    surface = XtColors.surface,
    onSurface = Color.White,
    surfaceVariant = XtColors.surfaceHigh,
    onSurfaceVariant = XtColors.secondaryText,
    surfaceContainer = XtColors.surface,
    surfaceContainerHigh = XtColors.surfaceHigh,
    surfaceContainerHighest = Color(0xFF26302B),
    surfaceContainerLow = XtColors.surface,
    surfaceContainerLowest = XtColors.background,
    outline = Color(0x40FFFFFF),
    outlineVariant = Color(0x22FFFFFF),
    error = XtColors.danger,
)

@Composable
fun XtTheme(isTv: Boolean, isWide: Boolean, content: @Composable () -> Unit) {
    val base = Typography()
    val typography = base.copy(
        headlineLarge = base.headlineLarge.copy(fontWeight = FontWeight.Bold),
        headlineMedium = base.headlineMedium.copy(fontWeight = FontWeight.Bold),
        titleLarge = base.titleLarge.copy(fontWeight = FontWeight.Bold),
        titleMedium = base.titleMedium.copy(fontWeight = FontWeight.SemiBold),
    )
    CompositionLocalProvider(
        LocalIsTv provides isTv,
        LocalDimens provides when {
            isTv -> TvDimens
            isWide -> TabletDimens
            else -> PhoneDimens
        },
    ) {
        MaterialTheme(colorScheme = scheme, typography = typography) {
            // Text outside a Surface / Scaffold (the tablet and TV layout) must be white, not Compose's default black.
            CompositionLocalProvider(LocalContentColor provides Color.White, content = content)
        }
    }
}

val HeroTitleStyle: (Int) -> TextStyle = { size -> TextStyle(fontSize = size.sp, fontWeight = FontWeight.Black, lineHeight = (size * 1.1).sp) }
