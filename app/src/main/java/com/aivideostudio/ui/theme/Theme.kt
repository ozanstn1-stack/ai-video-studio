package com.aivideostudio.ui.theme

import android.app.Activity
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat

private val StudioColorScheme = darkColorScheme(
    primary = StudioColors.Primary,
    onPrimary = StudioColors.OnPrimary,
    primaryContainer = StudioColors.PrimaryContainer,
    onPrimaryContainer = StudioColors.PrimaryBright,
    inversePrimary = StudioColors.PrimaryDim,
    secondary = StudioColors.Secondary,
    onSecondary = StudioColors.OnSecondary,
    secondaryContainer = StudioColors.SecondaryContainer,
    onSecondaryContainer = StudioColors.Secondary,
    tertiary = StudioColors.Tertiary,
    onTertiary = StudioColors.OnTertiary,
    tertiaryContainer = StudioColors.TertiaryContainer,
    onTertiaryContainer = StudioColors.Tertiary,
    background = StudioColors.Background,
    onBackground = StudioColors.TextPrimary,
    surface = StudioColors.Surface,
    onSurface = StudioColors.TextPrimary,
    surfaceVariant = StudioColors.SurfaceVariant,
    onSurfaceVariant = StudioColors.TextSecondary,
    surfaceContainer = StudioColors.SurfaceElevated,
    surfaceContainerHigh = StudioColors.SurfaceVariant,
    surfaceContainerHighest = StudioColors.SurfaceHighest,
    surfaceContainerLow = StudioColors.Surface,
    surfaceContainerLowest = StudioColors.Background,
    surfaceTint = StudioColors.Primary,
    inverseSurface = StudioColors.TextPrimary,
    inverseOnSurface = StudioColors.Background,
    error = StudioColors.Error,
    onError = StudioColors.OnError,
    errorContainer = StudioColors.ErrorContainer,
    onErrorContainer = StudioColors.Error,
    outline = StudioColors.Outline,
    outlineVariant = StudioColors.OutlineVariant,
    scrim = StudioColors.Scrim,
)

/**
 * The app is deliberately dark-first: a bright UI would throw off colour
 * judgement while reviewing footage. Light theme exists only as a fallback for
 * accessibility and keeps the same saturation budget.
 */
@Composable
fun AiVideoStudioTheme(
    @Suppress("UNUSED_PARAMETER") darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            val window = (view.context as? Activity)?.window ?: return@SideEffect
            WindowCompat.getInsetsController(window, view).isAppearanceLightStatusBars = false
            WindowCompat.getInsetsController(window, view).isAppearanceLightNavigationBars = false
        }
    }
    MaterialTheme(
        colorScheme = StudioColorScheme,
        typography = StudioTypography,
        shapes = StudioShapes,
        content = content,
    )
}
