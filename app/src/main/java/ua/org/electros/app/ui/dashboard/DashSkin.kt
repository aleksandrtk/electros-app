package ua.org.electros.app.ui.dashboard

import android.graphics.BitmapShader
import android.graphics.Matrix
import android.graphics.RuntimeShader
import android.graphics.Shader.TileMode as AndroidTileMode
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.res.imageResource
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import ua.org.electros.app.R
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.addOutline
import androidx.compose.ui.graphics.ShaderBrush
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.drawOutline
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import ua.org.electros.app.ui.theme.AmberNixie as ClassicAmber
import ua.org.electros.app.ui.theme.IndustrialRed as ClassicRed
import ua.org.electros.app.ui.theme.IndustrialRedBorder as ClassicRedBorder
import ua.org.electros.app.ui.theme.StudioAmberWarn as ClassicAmberWarn
import ua.org.electros.app.ui.theme.StudioChassisDark as ClassicChassis
import ua.org.electros.app.ui.theme.StudioCyan as ClassicCyan
import ua.org.electros.app.ui.theme.StudioGreen as ClassicGreen
import ua.org.electros.app.ui.theme.StudioPanelBorder as ClassicPanelBorder
import ua.org.electros.app.ui.theme.StudioPanelDark as ClassicPanel
import ua.org.electros.app.ui.theme.StudioScreenBorder as ClassicScreenBorder
import ua.org.electros.app.ui.theme.StudioScreenDark as ClassicScreen
import ua.org.electros.app.ui.theme.TextPrimaryDark as ClassicTextPrimary
import ua.org.electros.app.ui.theme.TextSecondaryDark as ClassicTextSecondary
import ua.org.electros.app.ui.theme.TextTertiaryDark as ClassicTextTertiary

/** Which wood a dashboard surface is made of; [NONE] is the classic dark instrument panel. */
enum class WoodTone { NONE, WALNUT, MAPLE, DARK_WALNUT }

/**
 * Colors the dashboard reads. The classic palette keeps the original look; the wood palettes
 * are swapped in per surface, so text on light maple is dark and text on walnut is light.
 */
data class DashPalette(
    val tone: WoodTone,
    val chassis: Color,
    val panel: Color,
    val panelBorder: Color,
    val screen: Color,
    val screenBorder: Color,
    val amber: Color,
    val green: Color,
    val cyan: Color,
    val amberWarn: Color,
    val red: Color,
    val redBorder: Color,
    val textPrimary: Color,
    val textSecondary: Color,
    val textTertiary: Color,
    val knobLabel: Color,
    val knobLabelActive: Color,
    val offBg: Color,
    val offText: Color,
    val onBg: Color,
    val amberBg: Color,
    val startBg: Color,
    val startText: Color,
    val trackBg: Color
) {
    val isWood: Boolean get() = tone != WoodTone.NONE
}

val ClassicDashPalette = DashPalette(
    tone = WoodTone.NONE,
    chassis = ClassicChassis,
    panel = ClassicPanel,
    panelBorder = ClassicPanelBorder,
    screen = ClassicScreen,
    screenBorder = ClassicScreenBorder,
    amber = ClassicAmber,
    green = ClassicGreen,
    cyan = ClassicCyan,
    amberWarn = ClassicAmberWarn,
    red = ClassicRed,
    redBorder = ClassicRedBorder,
    textPrimary = ClassicTextPrimary,
    textSecondary = ClassicTextSecondary,
    textTertiary = ClassicTextTertiary,
    knobLabel = Color(0xFF8E97A8),
    knobLabelActive = Color(0xFFFF9F1C),
    offBg = Color(0xFF2B181A),
    offText = Color(0xFFFF8A80),
    onBg = Color(0xFF15281F),
    amberBg = Color(0xFF2B1D0A),
    startBg = Color(0xFF1E2619),
    startText = ClassicGreen,
    trackBg = Color(0xFF161A22)
)

/** Light text on walnut. */
val WalnutDashPalette = DashPalette(
    tone = WoodTone.WALNUT,
    chassis = Color(0xFF2A1A10),
    panel = Color.Transparent,
    panelBorder = Color.Transparent,
    screen = Color(0xE01A0F08),
    screenBorder = Color(0xFF120A05),
    amber = Color(0xFFEDB676),
    green = Color(0xFF9CC26A),
    cyan = Color(0xFF8CC3D1),
    amberWarn = Color(0xFFEFA445),
    red = Color(0xFFE0694B),
    redBorder = Color(0xFF7A2615),
    textPrimary = Color(0xFFF6E6C8),
    textSecondary = Color(0xFFE3C9A0),
    textTertiary = Color(0xFFA88A66),
    knobLabel = Color(0xFFE3C9A0),
    knobLabelActive = Color(0xFFF4A947),
    offBg = Color(0xC03A1A10),
    offText = Color(0xFFF0A089),
    onBg = Color(0xC0283318),
    amberBg = Color(0xC03D2709),
    startBg = Color.Transparent,
    startText = Color(0xFF3A1D0B),
    trackBg = Color(0xFF120A05)
)

/** Dark text on maple; inlays on it stay dark walnut with light text. */
val MapleDashPalette = WalnutDashPalette.copy(
    tone = WoodTone.MAPLE,
    screen = Color(0xF03B2414),
    screenBorder = Color(0xFF26160B),
    green = Color(0xFF4F7F22),
    textPrimary = Color(0xFF2E1A0B),
    textSecondary = Color(0xFF4A2D16),
    textTertiary = Color(0xFF7A5A3A),
    amberWarn = Color(0xFF8F3E00),
    knobLabel = Color(0xFF4A2D16),
    knobLabelActive = Color(0xFF2E1A0B)
)

val LocalDashPalette = staticCompositionLocalOf { ClassicDashPalette }

fun dashPaletteFor(tone: WoodTone): DashPalette = when (tone) {
    WoodTone.NONE -> ClassicDashPalette
    WoodTone.MAPLE -> MapleDashPalette
    WoodTone.WALNUT, WoodTone.DARK_WALNUT -> WalnutDashPalette
}

// The dashboard reads its colors through these names, which shadow the theme's constants
// in this package, so the same composables draw either look.
internal val StudioChassisDark: Color @Composable @ReadOnlyComposable get() = LocalDashPalette.current.chassis
internal val StudioPanelDark: Color @Composable @ReadOnlyComposable get() = LocalDashPalette.current.panel
internal val StudioPanelBorder: Color @Composable @ReadOnlyComposable get() = LocalDashPalette.current.panelBorder
internal val StudioScreenDark: Color @Composable @ReadOnlyComposable get() = LocalDashPalette.current.screen
internal val StudioScreenBorder: Color @Composable @ReadOnlyComposable get() = LocalDashPalette.current.screenBorder
internal val AmberNixie: Color @Composable @ReadOnlyComposable get() = LocalDashPalette.current.amber
internal val StudioGreen: Color @Composable @ReadOnlyComposable get() = LocalDashPalette.current.green
internal val SecondaryCyan: Color @Composable @ReadOnlyComposable get() = LocalDashPalette.current.cyan
internal val StudioAmberWarn: Color @Composable @ReadOnlyComposable get() = LocalDashPalette.current.amberWarn
internal val IndustrialRed: Color @Composable @ReadOnlyComposable get() = LocalDashPalette.current.red
internal val IndustrialRedBorder: Color @Composable @ReadOnlyComposable get() = LocalDashPalette.current.redBorder
internal val TextPrimaryDark: Color @Composable @ReadOnlyComposable get() = LocalDashPalette.current.textPrimary
internal val TextSecondaryDark: Color @Composable @ReadOnlyComposable get() = LocalDashPalette.current.textSecondary
internal val TextTertiaryDark: Color @Composable @ReadOnlyComposable get() = LocalDashPalette.current.textTertiary

private fun woodTexture(tone: WoodTone): Int = when (tone) {
    WoodTone.MAPLE -> R.drawable.wood_maple
    WoodTone.WALNUT -> R.drawable.wood_walnut
    WoodTone.DARK_WALNUT, WoodTone.NONE -> R.drawable.wood_walnut_dark
}

/** Shown width of one texture tile, so the grain looks the same size on every screen. */
private const val WOOD_TILE_DP = 420f

/**
 * Lights the photo as if its grain were carved: brightness is read as height, and a light from
 * the top left raises the late wood and sinks the pores. Contrast is pushed so the figure reads.
 */
private const val WOOD_RELIEF_SHADER = """
uniform shader wood;
uniform float uStep;
uniform float uRelief;
uniform float uContrast;

float lum(half3 c) { return dot(float3(c), float3(0.299, 0.587, 0.114)); }

half4 main(float2 p) {
    half4 c = wood.eval(p);
    float l = lum(c.rgb);
    float l1 = lum(wood.eval(p + float2(-uStep, 0.0)).rgb);
    float l2 = lum(wood.eval(p + float2(uStep, 0.0)).rgb);
    float l3 = lum(wood.eval(p + float2(0.0, -uStep)).rgb);
    float l4 = lum(wood.eval(p + float2(0.0, uStep)).rgb);
    float wide = lum(wood.eval(p + float2(0.0, -uStep * 4.0)).rgb) + lum(wood.eval(p + float2(0.0, uStep * 4.0)).rgb);
    float base = max((l1 + l2 + l3 + l4) * 0.25, 0.04);
    float2 g = float2(l2 - l1, l4 - l3) / base;
    // Light from the upper left: slopes facing it brighten, the others fall into shade.
    float lit = 1.0 - uRelief * dot(g, float2(0.45, 0.89));
    // Local contrast: grain darker than its surroundings is pushed deeper.
    float local = (l - wide * 0.5) / base;
    float3 col = float3(c.rgb) * clamp(lit, 0.55, 1.6) * (1.0 + uContrast * local);
    return half4(half3(clamp(col, 0.0, 1.0)), c.a);
}
"""

private fun reliefFor(tone: WoodTone): Pair<Float, Float> = when (tone) {
    WoodTone.MAPLE -> 2.2f to 1.1f
    WoodTone.WALNUT -> 1.8f to 0.9f
    WoodTone.DARK_WALNUT, WoodTone.NONE -> 1.6f to 0.8f
}

/**
 * Tiled photo wood with carved-looking grain. [seed] shifts the tile so neighbouring boards
 * don't show the same figure.
 */
@Composable
fun rememberWoodBrush(tone: WoodTone, seed: Float = 0f): ShaderBrush {
    val image = ImageBitmap.imageResource(woodTexture(tone))
    val density = LocalDensity.current.density
    return remember(image, seed, density) {
        val bitmap = image.asAndroidBitmap()
        val photo = BitmapShader(bitmap, AndroidTileMode.REPEAT, AndroidTileMode.REPEAT)
        val scale = WOOD_TILE_DP * density / bitmap.width
        photo.setLocalMatrix(
            Matrix().apply {
                setScale(scale, scale)
                postTranslate(-(seed * 173f) % (bitmap.width * scale), -(seed * 97f) % (bitmap.height * scale))
            }
        )
        val (relief, contrast) = reliefFor(tone)
        val carved = RuntimeShader(WOOD_RELIEF_SHADER).apply {
            setInputShader("wood", photo)
            setFloatUniform("uStep", 1.2f * density)
            setFloatUniform("uRelief", relief)
            setFloatUniform("uContrast", contrast)
        }
        ShaderBrush(carved)
    }
}

/** A wood surface with soft light from the top and a bevelled edge. */
@Composable
fun Modifier.woodSurface(
    tone: WoodTone,
    shape: Shape,
    seed: Float = 0f,
    elevation: Dp = 0.dp,
    accentBorder: Color = Color.Transparent
): Modifier {
    val brush = rememberWoodBrush(tone, seed)
    val bevel = 3.5.dp
    val shaped = if (elevation > 0.dp) {
        this.shadow(elevation, shape, ambientColor = Color(0xFF000000), spotColor = Color(0xFF000000))
    } else {
        this
    }
    return shaped.drawBehind {
        val outline = shape.createOutline(size, layoutDirection, this)
        drawOutline(outline, brush)
        // Walnut boards carry light text, so they are stained darker than the photo;
        // maple gets a honey stain like an oiled oak board.
        when (tone) {
            WoodTone.WALNUT -> drawOutline(outline, Color(0xB0140904))
            WoodTone.MAPLE -> drawOutline(outline, Color(0xFFC0702A), alpha = 0.5f, blendMode = BlendMode.Multiply)
            else -> Unit
        }
        // Board lit from above: brighter top, darker bottom.
        drawOutline(
            outline,
            Brush.verticalGradient(
                0f to Color.White.copy(alpha = 0.16f),
                0.4f to Color.Transparent,
                1f to Color.Black.copy(alpha = 0.30f)
            )
        )
        // Chamfered edge: a band inside the outline, light on top and dark at the bottom,
        // with a thin crease where the chamfer meets the face.
        val chamfer = bevel.toPx()
        val clip = Path().apply { addOutline(outline) }
        clipPath(clip) {
            drawOutline(
                outline,
                Brush.verticalGradient(
                    0f to Color(0xA0FFE8C8),
                    0.12f to Color(0x30FFE8C8),
                    0.5f to Color(0x10000000),
                    0.88f to Color(0x50000000),
                    1f to Color(0xC0000000)
                ),
                style = Stroke(width = chamfer * 2f)
            )
            val inner = shape.createOutline(
                Size(size.width - chamfer * 2f, size.height - chamfer * 2f), layoutDirection, this
            )
            translate(chamfer, chamfer) {
                drawOutline(
                    inner,
                    Brush.verticalGradient(
                        0f to Color(0x55000000),
                        0.5f to Color(0x15000000),
                        1f to Color(0x40FFE8C8),
                        startY = 0f,
                        endY = size.height - chamfer * 2f
                    ),
                    style = Stroke(width = 1.dp.toPx())
                )
            }
        }
        drawOutline(outline, Color(0x80100602), style = Stroke(width = 0.8.dp.toPx()))
        if (accentBorder.alpha > 0f) {
            drawOutline(outline, accentBorder.copy(alpha = accentBorder.alpha * 0.7f), style = Stroke(width = 1.5.dp.toPx()))
        }
    }
}

/**
 * The dashboard's card: the classic outlined card, or a wood board that also switches the
 * palette for its content.
 */
@Composable
fun DashCard(
    tone: WoodTone,
    modifier: Modifier = Modifier,
    borderColor: Color = Color.Unspecified,
    seed: Float = 0f,
    woodAccent: Boolean = false,
    content: @Composable ColumnScope.() -> Unit
) {
    val outer = LocalDashPalette.current
    val shape = RoundedCornerShape(16.dp)
    if (!outer.isWood) {
        val border = if (borderColor == Color.Unspecified) outer.panelBorder else borderColor
        Card(
            shape = shape,
            colors = CardDefaults.cardColors(containerColor = outer.panel),
            border = CardDefaults.outlinedCardBorder().copy(brush = Brush.linearGradient(listOf(border, border))),
            modifier = modifier,
            content = content
        )
        return
    }
    // In wood only warnings keep a colored edge; status borders would fight the grain.
    val accent = if (woodAccent && borderColor != Color.Unspecified) borderColor else Color.Transparent
    Box(modifier = modifier.woodSurface(tone, shape, seed = seed, elevation = 12.dp, accentBorder = accent)) {
        CompositionLocalProvider(LocalDashPalette provides dashPaletteFor(tone)) {
            Column(content = content)
        }
    }
}

/**
 * Display windows and badges. In wood they are recessed into the board: shaded from the top
 * edge, with light catching the lower lip. Callers clip to their shape first.
 */
@Composable
fun Modifier.dashScreen(): Modifier {
    val palette = LocalDashPalette.current
    return this.drawBehind {
        drawRect(palette.screen)
        if (palette.isWood) {
            val depth = minOf(10.dp.toPx(), size.height * 0.45f)
            drawRect(
                Brush.verticalGradient(listOf(Color(0xB0000000), Color.Transparent), startY = 0f, endY = depth),
                size = Size(size.width, depth)
            )
            drawRect(
                Brush.horizontalGradient(listOf(Color(0x60000000), Color.Transparent), startX = 0f, endX = depth),
                size = Size(depth, size.height)
            )
            drawRect(
                Color(0x50FFE0B0),
                topLeft = Offset(0f, size.height - 1.dp.toPx()),
                size = Size(size.width, 1.dp.toPx())
            )
        }
    }
}

/** Screen background: the classic chassis color, or dark walnut planks. */
@Composable
fun Modifier.dashChassis(): Modifier {
    val palette = LocalDashPalette.current
    return if (palette.isWood) {
        woodSurface(WoodTone.DARK_WALNUT, androidx.compose.ui.graphics.RectangleShape, seed = 11f)
            .drawWithContent {
                // Light falls on the top middle and fades to the edges.
                drawRect(
                    Brush.radialGradient(
                        listOf(Color(0x30FFD8A0), Color(0x00000000), Color(0x8A0A0502)),
                        center = Offset(size.width / 2f, size.height * 0.08f),
                        radius = size.maxDimension * 0.85f
                    )
                )
                drawContent()
            }
    } else {
        this.then(Modifier.drawBehind { drawRect(palette.chassis) })
    }
}

/** Brushed copper plate for the start button in the wood look. */
@Composable
fun Modifier.copperPlate(shape: Shape): Modifier {
    val copper = ImageBitmap.imageResource(R.drawable.copper)
    return this
        .shadow(12.dp, shape, ambientColor = Color(0xFF000000), spotColor = Color(0xFF000000))
        .drawBehind {
            val outline = shape.createOutline(size, layoutDirection, this)
            val clip = Path().apply { addOutline(outline) }
            clipPath(clip) {
                drawImage(copper, dstOffset = IntOffset.Zero, dstSize = IntSize(size.width.toInt(), size.height.toInt()))
                // Deeper, aged copper with light along the top edge.
                drawRect(Color(0xFFB0602A), alpha = 0.32f, blendMode = BlendMode.Multiply)
                drawRect(Brush.verticalGradient(listOf(Color(0x40FFD2A0), Color(0x00000000), Color(0x50240A00))))
            }
            clipPath(clip) {
                drawOutline(
                    outline,
                    Brush.verticalGradient(
                        0f to Color(0xC0FFE6C8),
                        0.15f to Color(0x30FFE6C8),
                        0.85f to Color(0x40200800),
                        1f to Color(0xC0200800)
                    ),
                    style = Stroke(width = 6.dp.toPx())
                )
            }
            drawOutline(outline, Color(0x90200800), style = Stroke(width = 0.8.dp.toPx()))
        }
}

/**
 * Wooden knob: the carved face (disc, ridge and lit slot, cut from the reference) turns as one
 * piece inside a dark recess in the board.
 */
fun DrawScope.drawWoodKnob(angle: Float, faceImage: ImageBitmap) {
    val w = size.width
    val cx = w / 2f
    val cy = size.height / 2f
    val center = Offset(cx, cy)
    val wellRadius = w * 0.49f
    val faceRadius = w * 0.455f

    // Recess: dark groove with light catching its lower lip.
    drawCircle(
        brush = Brush.linearGradient(
            listOf(Color(0xFF120804), Color(0xFF2A170B), Color(0xFFC08A52)),
            start = Offset(cx - wellRadius * 0.6f, cy - wellRadius),
            end = Offset(cx + wellRadius * 0.6f, cy + wellRadius)
        ),
        radius = wellRadius,
        center = center
    )
    drawCircle(color = Color(0xFF140A04), radius = wellRadius * 0.965f, center = center)
    // Shadow the knob throws down and right into the recess.
    drawCircle(
        brush = Brush.radialGradient(
            listOf(Color(0xE0000000), Color(0x60000000), Color.Transparent),
            center = Offset(cx + w * 0.015f, cy + w * 0.03f),
            radius = faceRadius * 1.08f
        ),
        radius = faceRadius * 1.08f,
        center = Offset(cx + w * 0.015f, cy + w * 0.03f)
    )
    val d = (faceRadius * 2f).toInt()
    rotate(angle, center) {
        drawImage(
            image = faceImage,
            dstOffset = IntOffset((cx - faceRadius).toInt(), (cy - faceRadius).toInt()),
            dstSize = IntSize(d, d)
        )
    }
    // Keep the light coming from the top whatever the knob's angle.
    drawCircle(
        brush = Brush.verticalGradient(
            listOf(Color(0x22FFE6C0), Color(0x00000000), Color(0x40000000)),
            startY = cy - faceRadius,
            endY = cy + faceRadius
        ),
        radius = faceRadius,
        center = center
    )
}
