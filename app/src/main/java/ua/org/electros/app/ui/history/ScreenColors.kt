package ua.org.electros.app.ui.history

import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.ui.graphics.Color
import ua.org.electros.app.ui.theme.LocalScreenPalette

// This package reads its colors through these names, which shadow the theme's constants,
// so the same screens draw the classic look or the wooden one.
internal val SurfaceDark: Color @Composable @ReadOnlyComposable get() = LocalScreenPalette.current.surface
internal val SurfaceContainerDark: Color @Composable @ReadOnlyComposable get() = LocalScreenPalette.current.surfaceContainer
internal val SurfaceContainerHighDark: Color @Composable @ReadOnlyComposable get() = LocalScreenPalette.current.surfaceContainerHigh
internal val SurfaceContainerHighestDark: Color @Composable @ReadOnlyComposable get() = LocalScreenPalette.current.surfaceContainerHighest
internal val StudioScreenDark: Color @Composable @ReadOnlyComposable get() = LocalScreenPalette.current.screen
internal val StudioScreenBorder: Color @Composable @ReadOnlyComposable get() = LocalScreenPalette.current.screenBorder
internal val AmberNixie: Color @Composable @ReadOnlyComposable get() = LocalScreenPalette.current.amber
internal val PrimaryNeonGreen: Color @Composable @ReadOnlyComposable get() = LocalScreenPalette.current.amber
internal val SecondaryCyan: Color @Composable @ReadOnlyComposable get() = LocalScreenPalette.current.cyan
internal val StudioGreen: Color @Composable @ReadOnlyComposable get() = LocalScreenPalette.current.green
internal val StudioAmberWarn: Color @Composable @ReadOnlyComposable get() = LocalScreenPalette.current.warning
internal val WarningAmber: Color @Composable @ReadOnlyComposable get() = LocalScreenPalette.current.warning
internal val ErrorRed: Color @Composable @ReadOnlyComposable get() = LocalScreenPalette.current.error
internal val OnPrimaryDark: Color @Composable @ReadOnlyComposable get() = LocalScreenPalette.current.onPrimary
internal val OutlineDark: Color @Composable @ReadOnlyComposable get() = LocalScreenPalette.current.outline
internal val TextPrimaryDark: Color @Composable @ReadOnlyComposable get() = LocalScreenPalette.current.textPrimary
internal val TextSecondaryDark: Color @Composable @ReadOnlyComposable get() = LocalScreenPalette.current.textSecondary
internal val TextTertiaryDark: Color @Composable @ReadOnlyComposable get() = LocalScreenPalette.current.textTertiary
