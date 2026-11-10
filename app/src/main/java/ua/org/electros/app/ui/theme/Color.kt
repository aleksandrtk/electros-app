package ua.org.electros.app.ui.theme

import androidx.compose.ui.graphics.Color

// Analog Hardware Studio Theme Colors
val StudioChassisDark = Color(0xFF101217)       // Deep dark titanium background
val StudioPanelDark = Color(0xFF181B22)         // Tactile beveled card face
val StudioPanelBorder = Color(0xFF282D3A)       // Heavy bezel outline
val StudioScreenDark = Color(0xFF090A0E)        // Recessed cathode display window
val StudioScreenBorder = Color(0xFF232733)      // Inner display bezel

val AmberNixie = Color(0xFFFF9900)             // Signature glowing warm amber
val AmberNixieDim = Color(0xFFB46E00)          // Secondary/dim amber
val AmberNixieGlow = Color(0x66FF9900)         // Cathode tube glow overlay
val AmberNixieText = Color(0xFFFFCC80)         // Soft warm text

val StudioCyan = Color(0xFF38BDF8)             // Subtle engineering blue
val StudioGreen = Color(0xFF10B981)            // Status OK / DLB active
val StudioAmberWarn = Color(0xFFF59E0B)        // Sag / Voltage Derate warning
val IndustrialRed = Color(0xFFDC2626)          // Emergency stop / trip breaker
val IndustrialRedBorder = Color(0xFF991B1B)

val TextPrimaryDark = Color(0xFFEDEDED)        // Clean metallic white
val TextSecondaryDark = Color(0xFF8E95A5)      // Brushed steel gray
val TextTertiaryDark = Color(0xFF5B6272)       // Dim label gray
val OutlineDark = StudioPanelBorder

// Backward compatibility mappings for MaterialTheme tokens
val SurfaceDark = StudioChassisDark
val SurfaceContainerDark = StudioPanelDark
val SurfaceContainerHighDark = Color(0xFF222630)
val SurfaceContainerHighestDark = Color(0xFF2C3240)
val PrimaryNeonGreen = AmberNixie
val OnPrimaryDark = Color(0xFF1C0D00)
val PrimaryContainerDark = Color(0xFF331C00)
val OnPrimaryContainerDark = Color(0xFFFFD499)
val SecondaryCyan = StudioCyan
val SecondaryContainerDark = Color(0xFF00364A)
val OnSecondaryContainerDark = Color(0xFFBBE9FF)
val ErrorRed = IndustrialRed
val ErrorContainerDark = Color(0xFF451111)
val WarningAmber = StudioAmberWarn
val WarningContainerDark = Color(0xFF3D2700)
