package ua.org.electros.app.ui.theme

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.material3.Card
import androidx.compose.material3.CardColors
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.foundation.border
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.imageResource
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import kotlin.math.roundToInt
import ua.org.electros.app.R
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.unit.dp
import ua.org.electros.app.ui.dashboard.WoodTone
import ua.org.electros.app.ui.dashboard.rememberWoodBrush

/**
 * Colors the secondary screens (limits, history, settings, login, updates) read. Each of those
 * packages shadows the theme's color constants with getters over [LocalScreenPalette], so the
 * same code draws the classic look or the wooden one.
 */
data class ScreenPalette(
    val isWood: Boolean,
    val surface: Color,
    val surfaceContainer: Color,
    val surfaceContainerHigh: Color,
    val surfaceContainerHighest: Color,
    val screen: Color,
    val screenBorder: Color,
    val amber: Color,
    val cyan: Color,
    val green: Color,
    val warning: Color,
    val error: Color,
    val onPrimary: Color,
    val outline: Color,
    val textPrimary: Color,
    val textSecondary: Color,
    val textTertiary: Color
)

val ClassicScreenPalette = ScreenPalette(
    isWood = false,
    surface = SurfaceDark,
    surfaceContainer = SurfaceContainerDark,
    surfaceContainerHigh = SurfaceContainerHighDark,
    surfaceContainerHighest = SurfaceContainerHighestDark,
    screen = StudioScreenDark,
    screenBorder = StudioScreenBorder,
    amber = AmberNixie,
    cyan = StudioCyan,
    green = StudioGreen,
    warning = StudioAmberWarn,
    error = IndustrialRed,
    onPrimary = OnPrimaryDark,
    outline = OutlineDark,
    textPrimary = TextPrimaryDark,
    textSecondary = TextSecondaryDark,
    textTertiary = TextTertiaryDark
)

/** Light lettering on walnut; insets are dark pockets cut into the board. */
val WoodScreenPalette = ScreenPalette(
    isWood = true,
    surface = Color(0xFF24150B),
    surfaceContainer = Color(0xFF2E1B0E),
    surfaceContainerHigh = Color(0xB0170C05),
    surfaceContainerHighest = Color(0xD01A0F08),
    screen = Color(0xE01A0F08),
    screenBorder = Color(0xFF120A05),
    amber = Color(0xFFF4A947),
    cyan = Color(0xFFD9B27A),
    green = Color(0xFF9CC26A),
    warning = Color(0xFFEFA445),
    error = Color(0xFFE0694B),
    onPrimary = Color(0xFF2A1405),
    outline = Color(0xFF5A3A1E),
    textPrimary = Color(0xFFF6E6C8),
    textSecondary = Color(0xFFE3C9A0),
    textTertiary = Color(0xFFA88A66)
)

val LocalScreenPalette = staticCompositionLocalOf { ClassicScreenPalette }

/** Material colors for dialogs, fields and sheets in the wooden look. */
val WoodColorScheme = darkColorScheme(
    primary = WoodScreenPalette.amber,
    onPrimary = WoodScreenPalette.onPrimary,
    primaryContainer = Color(0xFF4A2D16),
    onPrimaryContainer = Color(0xFFFFDDB0),
    secondary = WoodScreenPalette.cyan,
    onSecondary = WoodScreenPalette.onPrimary,
    secondaryContainer = Color(0xFF3D2709),
    onSecondaryContainer = Color(0xFFF6E6C8),
    background = WoodScreenPalette.surface,
    surface = WoodScreenPalette.surface,
    surfaceContainer = WoodScreenPalette.surfaceContainer,
    surfaceContainerHigh = Color(0xFF38220F),
    surfaceContainerHighest = Color(0xFF432913),
    surfaceContainerLow = Color(0xFF2A190D),
    onSurface = WoodScreenPalette.textPrimary,
    onSurfaceVariant = WoodScreenPalette.textSecondary,
    outline = WoodScreenPalette.outline,
    error = WoodScreenPalette.error,
    errorContainer = Color(0xFF4A1C10)
)

/**
 * Screen background: the classic chassis color, or the walnut behind the main screen's boards,
 * lit from the top middle and falling into shade towards the edges and the bottom like there.
 */
@Composable
fun Modifier.screenBackground(): Modifier {
    val palette = LocalScreenPalette.current
    if (!palette.isWood) return this.then(Modifier.drawWithContent { drawRect(palette.surface); drawContent() })
    val walnut = rememberWoodBrush(WoodTone.WALNUT, seed = 11f)
    return this.drawWithContent {
        drawRect(walnut)
        // The main screen's walnut is warmer and redder than the photo.
        drawRect(Color(0x33A0400F))
        drawRect(
            Brush.verticalGradient(
                0f to Color(0x00000000),
                0.3f to Color(0x40000000),
                1f to Color(0x99000000)
            )
        )
        drawRect(
            Brush.horizontalGradient(
                0f to Color(0x60000000),
                0.15f to Color(0x00000000),
                0.85f to Color(0x00000000),
                1f to Color(0x60000000)
            )
        )
        drawRect(
            Brush.radialGradient(
                listOf(Color(0x38FFD8A0), Color(0x00000000)),
                center = Offset(size.width / 2f, 0f),
                radius = size.width * 0.7f
            )
        )
        drawContent()
    }
}

/** Inside an oak board: dark lettering, shallow recesses, copper and olive accents. */
val OakScreenPalette = WoodScreenPalette.copy(
    surfaceContainer = Color(0x245A2E0C),
    surfaceContainerHigh = Color(0x2E5A2E0C),
    surfaceContainerHighest = Color(0x555A2E0C),
    screen = Color(0x2E5A2E0C),
    screenBorder = Color(0x605A2E0C),
    amber = Color(0xFF8F3E00),
    cyan = Color(0xFF6B4A1E),
    green = Color(0xFF4F7F22),
    warning = Color(0xFF8F3E00),
    error = Color(0xFFA8341C),
    onPrimary = Color(0xFFF6E6C8),
    outline = Color(0xFF8A6440),
    textPrimary = Color(0xFF2E1A0B),
    textSecondary = Color(0xFF4A2D16),
    textTertiary = Color(0xFF7A5A3A)
)

/** Material colors for fields, buttons and switches set on an oak board. */
val OakColorScheme = lightColorScheme(
    primary = OakScreenPalette.amber,
    onPrimary = OakScreenPalette.onPrimary,
    primaryContainer = Color(0xFFD9A066),
    onPrimaryContainer = Color(0xFF2E1A0B),
    secondary = OakScreenPalette.cyan,
    onSecondary = OakScreenPalette.onPrimary,
    surface = Color(0xFFE2BE8A),
    onSurface = OakScreenPalette.textPrimary,
    onSurfaceVariant = OakScreenPalette.textSecondary,
    surfaceContainerHighest = Color(0xFFCBA272),
    outline = OakScreenPalette.outline,
    outlineVariant = Color(0xFFB08A5E),
    error = OakScreenPalette.error
)

/** Width of the main screen's design in its own pixels; boards use the same scale as there. */
private const val DESIGN_WIDTH = 1115f
/** Corner radius of the oak board in design pixels. */
private const val BOARD_CORNER = 34f
/** Rows and columns at the board's edges (corners and bevel) that are never stretched. */
private const val BOARD_EDGE = 60

/**
 * The oak board around the main screen's knob, cut from the design, drawn at any size: the
 * bevelled edges keep their size, the face stretches along the grain and is mirrored down for
 * taller boards so the grain runs on without a seam.
 */
@Composable
fun Modifier.oakBoard(): Modifier {
    val board = ImageBitmap.imageResource(R.drawable.wood_card)
    val density = LocalDensity.current
    val scale = with(density) { LocalConfiguration.current.screenWidthDp.dp.toPx() } / DESIGN_WIDTH
    val corner = with(density) { (BOARD_CORNER * scale).toDp() }
    return this
        .shadow(10.dp, RoundedCornerShape(corner), ambientColor = Color.Black, spotColor = Color.Black)
        .drawBehind {
            val w = size.width.roundToInt()
            val h = size.height.roundToInt()
            val sw = board.width
            val sh = board.height
            val e = BOARD_EDGE
            val de = (e * scale).roundToInt().coerceAtMost(minOf(w, h) / 2)
            val srcX = intArrayOf(0, e, sw - e, sw)
            val dstX = intArrayOf(0, de, w - de, w)
            fun band(srcY: Int, srcH: Int, dstY: Int, dstH: Int) {
                if (srcH <= 0 || dstH <= 0) return
                for (i in 0..2) {
                    val dw = dstX[i + 1] - dstX[i]
                    if (dw <= 0) continue
                    drawImage(
                        board,
                        srcOffset = IntOffset(srcX[i], srcY),
                        srcSize = IntSize(srcX[i + 1] - srcX[i], srcH),
                        dstOffset = IntOffset(dstX[i], dstY),
                        dstSize = IntSize(dw, dstH),
                        filterQuality = FilterQuality.High
                    )
                }
            }
            band(0, e, 0, de)
            band(sh - e, e, h - de, de)
            val mid = sh - 2 * e
            val tile = (mid * scale).roundToInt().coerceAtLeast(1)
            var y = de
            var flipped = false
            while (y < h - de) {
                val dh = minOf(tile, h - de - y)
                val src = (dh / scale).roundToInt().coerceIn(1, mid)
                if (!flipped) {
                    band(e, src, y, dh)
                } else {
                    withTransform({ scale(1f, -1f, pivot = Offset(0f, y + dh / 2f)) }) {
                        band(sh - e - src, src, y, dh)
                    }
                }
                y += dh
                flipped = !flipped
            }
        }
}

/** A card: the classic Material card, or the oak board of the main screen with dark lettering. */
@Composable
fun ThemedCard(
    modifier: Modifier = Modifier,
    shape: Shape = CardDefaults.shape,
    colors: CardColors = CardDefaults.cardColors(),
    border: BorderStroke? = null,
    content: @Composable ColumnScope.() -> Unit
) {
    val palette = LocalScreenPalette.current
    if (!palette.isWood) {
        Card(modifier = modifier, shape = shape, colors = colors, border = border, content = content)
        return
    }
    // Only a highlighted card (the session in progress) keeps a colored edge.
    val accent = if (border != null) Modifier.border(1.5.dp, OakScreenPalette.amber, shape) else Modifier
    Box(modifier = modifier.oakBoard().then(accent)) {
        MaterialTheme(colorScheme = OakColorScheme, typography = MaterialTheme.typography) {
            CompositionLocalProvider(
                LocalScreenPalette provides OakScreenPalette,
                LocalContentColor provides OakScreenPalette.textPrimary
            ) {
                Column(content = content)
            }
        }
    }
}
