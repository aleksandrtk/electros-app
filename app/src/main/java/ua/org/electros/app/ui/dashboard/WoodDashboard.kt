package ua.org.electros.app.ui.dashboard

import android.view.HapticFeedbackConstants
import androidx.compose.foundation.Image
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.lerp
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.scale
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.imageResource
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import ua.org.electros.app.R
import ua.org.electros.app.ui.asString
import java.util.Locale
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.Path
import androidx.compose.foundation.interaction.collectIsPressedAsState
import kotlin.math.roundToInt
import androidx.compose.ui.graphics.FilterQuality

/**
 * The wooden look of the main screen. The whole panel is one painted image (the reference design
 * with its lettering removed); everything that changes is drawn on top of it at the reference's
 * own coordinates, so the layout matches the design exactly. Coordinates are in reference pixels
 * of the 1115 px wide original and are scaled to the screen width.
 */
private const val REF_WIDTH = 1115f
private const val REF_PANEL_HEIGHT = 1755f
/** Height of the plain walnut band at the bottom of the design, reused to fill taller screens. */
private const val BOTTOM_BAND = 28f

private val SandText = Color(0xFFE8C9A2)
private val SandBright = Color(0xFFEDB676)
private val BrownText = Color(0xFF3B2210)

/**
 * "Modern craft hi-fi" dressing of the same panel: lettering burnt sharply into the wood
 * instead of carved, a milled walnut knob in a brass ring with a light guide, and a brushed bronze start plate.
 */
internal val LocalWoodHiFi = staticCompositionLocalOf { false }
private val WOOD_HIFI: Boolean @Composable @ReadOnlyComposable get() = LocalWoodHiFi.current

internal val Manrope = FontFamily(
    listOf(FontWeight.Medium, FontWeight.SemiBold, FontWeight.Bold).map { w ->
        Font(R.font.manrope, w, variationSettings = FontVariation.Settings(FontVariation.weight(w.weight)))
    }
)
private val JetMono = FontFamily(
    Font(R.font.jetbrains_mono_medium, FontWeight.Medium),
    Font(R.font.jetbrains_mono_bold, FontWeight.Bold)
)
/** Laser-burnt lettering on oak: sharp dark umber. */
private val BurntUmber = Color(0xFF24120A)
private val OledAmber = Color(0xFFFFB347)
private val BrownSoft = Color(0xFF5A3A1E)
private val OffDot = Color(0xFFC2694C)
private val OnDot = Color(0xFF6E9A3A)
private val ChargingDot = Color(0xFF7CC04B)

/**
 * Spreads the design over taller screens. Every band of the panel is stretched by the same
 * factor except the one holding the knob, which stays round.
 */
private class YMap(extra: Float) {
    private val k = 1f + extra / (REF_PANEL_HEIGHT - (KNOB_BAND_END - KNOB_BAND_START))
    operator fun invoke(y: Float): Float = when {
        y <= KNOB_BAND_START -> y * k
        y <= KNOB_BAND_END -> KNOB_BAND_START * k + (y - KNOB_BAND_START)
        else -> KNOB_BAND_START * k + (KNOB_BAND_END - KNOB_BAND_START) + (y - KNOB_BAND_END) * k
    }
    val height: Float get() = this(REF_PANEL_HEIGHT)
    val bands = listOf(0f to KNOB_BAND_START, KNOB_BAND_START to KNOB_BAND_END, KNOB_BAND_END to REF_PANEL_HEIGHT)
}

/** The knob and its socket, kept at the design's proportions. */
private const val KNOB_BAND_START = 565f
private const val KNOB_BAND_END = 950f
/** At most this share of the design's height is added, so tablets don't get stretched boards. */
private const val MAX_STRETCH = 0.3f

/** Places content inside a rectangle of the reference image. */
private class RefScope(val unit: Dp, val map: YMap) {
    fun px(v: Float): Dp = unit * v
    fun py(v: Float): Dp = unit * map(v)
}

@Composable
private fun RefScope.Area(
    x0: Float, y0: Float, x1: Float, y1: Float,
    alignment: Alignment = Alignment.CenterStart,
    modifier: Modifier = Modifier,
    content: @Composable BoxScope.() -> Unit
) {
    Box(
        modifier = Modifier
            .offset(x = px(x0), y = py(y0))
            .width(px(x1 - x0))
            .height(py(y1) - py(y0))
            .then(modifier),
        contentAlignment = alignment,
        content = content
    )
}

@Composable
private fun RefScope.sp(refPx: Float): TextUnit = with(LocalDensity.current) { px(refPx).toSp() }

/**
 * Lettering cut into the board: the top edge of each glyph falls into shade and its lower edge
 * catches the light, so the text reads as engraved rather than printed on top.
 */
@Composable
private fun RefScope.Engraved(
    text: String,
    sizePx: Float,
    color: Color,
    weight: FontWeight = FontWeight.Medium,
    spacing: Float = 0.06f,
    onDark: Boolean = false,
    mono: Boolean = false
) {
    if (WOOD_HIFI) {
        // Burnt in by a laser: one crisp layer, no bevel.
        Text(
            text = text,
            style = TextStyle(
                fontFamily = if (mono) JetMono else Manrope,
                fontWeight = if (weight == FontWeight.Normal) FontWeight.Medium else FontWeight.SemiBold,
                fontSize = sp(sizePx * 0.9f),
                letterSpacing = sp(sizePx * (spacing * 0.6f)),
                color = if (onDark) color else if (color == BrownText || color == BrownSoft) BurntUmber else color
            ),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
        return
    }
    val base = TextStyle(
        fontFamily = if (mono) JetMono else Manrope,
        fontWeight = weight,
        fontSize = sp(sizePx),
        letterSpacing = sp(sizePx * spacing)
    )
    val edge = px(sizePx * 0.05f)
    Box {
        // Lit lower wall of the cut.
        Text(
            text = text,
            style = base.copy(color = if (onDark) Color(0x38FFE2B8) else Color(0x66FFE9C8)),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.offset(y = edge)
        )
        // Shaded upper wall.
        Text(
            text = text,
            style = base.copy(color = if (onDark) Color(0xE0000000) else Color(0xA0140800)),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.offset(y = -edge * 0.7f)
        )
        Text(
            text = text,
            style = base.copy(color = color),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

/**
 * The auto-current toggle sunk into the oak like the status badge next to it: a dark pocket
 * whose upper wall is in shade and whose lower rim catches the light, a muted olive track set
 * into it, and a flat thumb that slides inside the pocket.
 */
@Composable
private fun WoodToggle(checked: Boolean, onToggle: () -> Unit, modifier: Modifier = Modifier) {
    val position by animateFloatAsState(if (checked) 1f else 0f, label = "toggle")
    Canvas(
        modifier = modifier.clickable(
            interactionSource = remember { MutableInteractionSource() },
            indication = null,
            onClick = onToggle
        )
    ) {
        val lip = size.height * 0.06f
        val pocketSize = Size(size.width, size.height - lip)
        val corner = CornerRadius(pocketSize.height / 2f, pocketSize.height / 2f)
        // Light catching the board's edge just below the pocket.
        drawRoundRect(color = Color(0x8CFFDDAE), topLeft = Offset(0f, lip), size = pocketSize, cornerRadius = corner)
        // The pocket.
        drawRoundRect(color = Color(0xFF2A1609), size = pocketSize, cornerRadius = corner)
        // Track inside it, olive when on, the pocket's own dark wood when off.
        val inset = pocketSize.height * 0.08f
        val track = Size(pocketSize.width - inset * 2f, pocketSize.height - inset * 2f)
        val trackCorner = CornerRadius(track.height / 2f, track.height / 2f)
        drawRoundRect(
            color = lerp(Color(0xFF2F1A0C), Color(0xFF4C5A20), position),
            topLeft = Offset(inset, inset),
            size = track,
            cornerRadius = trackCorner
        )
        // Shade cast by the pocket's upper wall over everything inside.
        drawRoundRect(
            brush = Brush.verticalGradient(
                0f to Color(0xD0000000),
                0.35f to Color(0x40000000),
                0.6f to Color(0x00000000),
                startY = 0f,
                endY = pocketSize.height
            ),
            size = pocketSize,
            cornerRadius = corner
        )
        // Thumb, flat and resting in the pocket.
        val r = track.height / 2f * 0.9f
        val cx = inset + track.height / 2f + (track.width - track.height) * position
        val cy = pocketSize.height / 2f + pocketSize.height * 0.03f
        drawCircle(
            color = lerp(Color(0xFF6B4A2C), Color(0xFF5F7030), position),
            radius = r,
            center = Offset(cx, cy)
        )
        drawCircle(
            brush = Brush.verticalGradient(
                listOf(Color(0x30FFF0D0), Color(0x00000000), Color(0x50000000)),
                startY = cy - r,
                endY = cy + r
            ),
            radius = r,
            center = Offset(cx, cy)
        )
        drawCircle(color = Color(0x60000000), radius = r, center = Offset(cx, cy), style = Stroke(width = r * 0.06f))
    }
}

private enum class WoodSheet { LIMIT, TIMER, SCHEDULE }

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun WoodDashboard(
    state: DashboardUiState,
    viewModel: DashboardViewModel,
    onStart: () -> Unit,
    onStop: () -> Unit
) {
    val context = LocalContext.current
    val view = LocalView.current
    val telemetry = state.telemetry
    val isOnline = telemetry?.isOnline == true
    val voltage = telemetry?.voltageCurrent
    val panel = ImageBitmap.imageResource(R.drawable.wood_screen)
    val knobFace = ImageBitmap.imageResource(R.drawable.knob_face)
    var openSheet by rememberSaveable { mutableStateOf<WoodSheet?>(null) }

    CompositionLocalProvider(LocalWoodHiFi provides state.isWoodHiFi) {
    BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
        val unit = maxWidth / REF_WIDTH
        val boxHeight = maxHeight
        // Warnings need the free space under the button; otherwise the boards grow to fill it.
        val hasWarnings = state.errorMessage != null || state.isStopPending ||
            (state.activeSession != null && state.isOutageActive) || (telemetry != null && !isOnline)
        val extra = if (hasWarnings) 0f else
            ((boxHeight - unit * REF_PANEL_HEIGHT) / unit).coerceIn(0f, REF_PANEL_HEIGHT * MAX_STRETCH)
        val map = remember(extra) { YMap(extra) }
        val panelHeight = unit * map.height
        Column(
            modifier = Modifier
                .fillMaxSize()
                // Taller screens than the design continue with the design's own bottom band of
                // walnut, stretched down, so there is no seam.
                .drawBehind {
                    val bandTop = (REF_PANEL_HEIGHT - BOTTOM_BAND).toInt()
                    drawImage(
                        panel,
                        srcOffset = IntOffset(0, bandTop),
                        srcSize = IntSize(panel.width, BOTTOM_BAND.toInt()),
                        dstOffset = IntOffset(0, (panelHeight.toPx() - 2f).toInt()),
                        dstSize = IntSize(size.width.toInt(), (size.height - panelHeight.toPx() + 2f).toInt().coerceAtLeast(1))
                    )
                }
                .verticalScroll(rememberScrollState())
        ) {
            Box(modifier = Modifier.fillMaxWidth().height(panelHeight)) {
                Canvas(modifier = Modifier.fillMaxSize()) {
                    val scale = size.width / REF_WIDTH
                    map.bands.forEach { (from, to) ->
                        val top = (map(from) * scale).roundToInt()
                        val bottom = (map(to) * scale).roundToInt()
                        drawImage(
                            panel,
                            srcOffset = IntOffset(0, from.toInt()),
                            srcSize = IntSize(panel.width, (to - from).toInt()),
                            dstOffset = IntOffset(0, top),
                            dstSize = IntSize(size.width.roundToInt(), bottom - top),
                            filterQuality = FilterQuality.High
                        )
                    }
                }
                val ref = RefScope(unit, map)
                with(ref) {
                    WoodHeader(state, isOnline)
                    WoodVoltage(state, isOnline, voltage)
                    WoodCurrent(state, knobFace) { amps -> viewModel.onAmperageSelected(amps) }
                    WoodAutoCurrent(state, isOnline, voltage, panel) {
                        view.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
                        viewModel.toggleVoltageBalancing(!state.isVoltageBalancingEnabled)
                    }
                    WoodRow(1215f, stringResource(R.string.energy_limit_title), state.isKwhLimitEnabled) { openSheet = WoodSheet.LIMIT }
                    WoodRow(1335f, stringResource(R.string.charge_timer_title), state.isDurationTimerEnabled) { openSheet = WoodSheet.TIMER }
                    WoodRow(1455f, stringResource(R.string.schedule_title), state.isScheduleEnabled) { openSheet = WoodSheet.SCHEDULE }
                    WoodActionButton(state, onStart, onStop)
                }
            }
            // Warnings sit on the walnut below the button, where the design has free space.
            CompositionLocalProvider(LocalDashPalette provides WalnutDashPalette) {
                Column(
                    modifier = Modifier
                        .heightIn(min = (boxHeight - panelHeight).coerceAtLeast(0.dp))
                        .padding(horizontal = 14.dp, vertical = 4.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    val errorMessage = state.errorMessage
                    if (errorMessage != null) {
                        ErrorMessageCard(message = errorMessage.asString())
                    } else if (state.isStopPending) {
                        ErrorMessageCard(message = stringResource(R.string.dash_stop_pending))
                    }
                    if (state.activeSession != null && state.isOutageActive) {
                        val remMinText = if (state.activeTargetDurationMinutes > 0) {
                            formatCountdownHms((state.activeTargetDurationMinutes * 60_000L - state.accumulatedChargingMs).coerceAtLeast(0L))
                        } else null
                        OutageBanner(
                            deliveredKwh = state.totalDeliveredKwh,
                            targetKwh = state.activeTargetKwh,
                            isLimitEnabled = state.activeTargetKwh > 0.0,
                            isAutoResumeEnabled = state.isAutoResumeEnabled,
                            isStationOnline = isOnline,
                            isBusy = state.isStartingOrStopping,
                            remainingTimerText = remMinText,
                            onCancelOutage = onStop
                        )
                    } else if (telemetry != null && !isOnline) {
                        OfflineWarningCard()
                    }
                }
            }
        }
    }
    }

    val sheet = openSheet
    if (sheet != null) {
        ModalBottomSheet(
            onDismissRequest = { openSheet = null },
            containerColor = Color(0xFF2A170B)
        ) {
            CompositionLocalProvider(LocalDashPalette provides WalnutDashPalette) {
                Box(modifier = Modifier.padding(start = 14.dp, end = 14.dp, bottom = 28.dp)) {
                    when (sheet) {
                        WoodSheet.LIMIT -> StudioClassicKwhLimitCard(
                            startExpanded = true,
                            isKwhLimitEnabled = state.isKwhLimitEnabled,
                            targetQuotaKwh = state.targetQuotaKwh,
                            activeTargetKwh = state.activeTargetKwh,
                            hasActiveSession = state.activeSession != null,
                            onSelectKwhLimit = { enabled, quota -> viewModel.setKwhLimit(context, enabled, quota) }
                        )
                        WoodSheet.TIMER -> StudioClassicTimerCard(
                            startExpanded = true,
                            isDurationTimerEnabled = state.isDurationTimerEnabled,
                            targetDurationHours = state.targetDurationHours,
                            activeTargetDurationMinutes = state.activeTargetDurationMinutes,
                            accumulatedChargingMs = state.accumulatedChargingMs,
                            lastTickAt = state.lastTickAt,
                            hasActiveSession = state.activeSession != null,
                            isChargingActive = telemetry?.isChargingActive ?: false,
                            isOutageActive = state.isOutageActive,
                            onSelectDuration = { enabled, hours -> viewModel.setDurationTimer(context, enabled, hours) }
                        )
                        WoodSheet.SCHEDULE -> StudioClassicScheduleCard(
                            startExpanded = true,
                            isScheduleEnabled = state.isScheduleEnabled,
                            scheduleStartTime = state.scheduleStartTime,
                            scheduleStopTime = state.scheduleStopTime,
                            onUpdateSchedule = { enabled, start, stop -> viewModel.setScheduleTimer(enabled, start, stop) }
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun RefScope.WoodHeader(state: DashboardUiState, isOnline: Boolean) {
    val telemetry = state.telemetry
    Area(26f, 62f, 54f, 94f, Alignment.Center) {
        Box(
            modifier = Modifier
                .size(px(22f))
                .clip(CircleShape)
                .background(
                    when {
                        // Green while energy flows, amber when the station is online and idle.
                        isOnline && telemetry?.isChargingActive == true -> ChargingDot
                        isOnline -> Color(0xFFE59A5C)
                        else -> OffDot
                    }
                )
        )
    }
    Area(72f, 50f, 980f, 106f) {
        Engraved(formatHeaderStatus(telemetry?.statusText.orEmpty(), isOnline).asString().uppercase(), 36f, SandText, onDark = true)
    }
    val amps = resolveDisplayedAmperes(telemetry, state.selectedAmperes)
    Area(996f, 48f, 1090f, 110f, Alignment.Center) {
        Engraved("${amps}A", 32f, SandBright, FontWeight.Medium, 0.04f, onDark = true, mono = true)
    }
}

@Composable
private fun RefScope.WoodVoltage(state: DashboardUiState, isOnline: Boolean, voltage: Int?) {
    Area(66f, 165f, 1040f, 212f) {
        Engraved(stringResource(R.string.dash_line_voltage).uppercase(), 34f, SandText, onDark = true)
    }
    Area(72f, 222f, 1048f, 348f) { SmokedGlass(Modifier.fillMaxSize()) }
    val value = if (isOnline && voltage != null) "$voltage" else "---"
    Area(108f, 228f, 640f, 342f) {
        Row(verticalAlignment = Alignment.Bottom) {
            OledText(value, 92f)
            Box(modifier = Modifier.padding(start = px(12f), bottom = px(12f))) { OledText("V", 44f) }
        }
    }
    if (state.activeSession != null) {
        Area(560f, 250f, 1022f, 330f, Alignment.CenterEnd) {
            val delivered = state.totalDeliveredKwh
            val text = if (state.activeTargetKwh > 0.0) {
                "%.2f / %.0f %s".format(Locale.US, delivered, state.activeTargetKwh, stringResource(R.string.unit_kwh))
            } else {
                "+%.2f %s".format(Locale.US, delivered, stringResource(R.string.unit_kwh))
            }
            OledText(text, 40f)
        }
    }
}

@Composable
private fun RefScope.WoodCurrent(state: DashboardUiState, knobFace: ImageBitmap, onSelect: (Int) -> Unit) {
    val view = LocalView.current
    Area(66f, 436f, 840f, 482f) {
        Engraved(stringResource(R.string.dash_charge_current).uppercase(), 34f, BrownText)
    }
    Area(868f, 430f, 1048f, 490f, Alignment.Center) {
        Engraved(stringResource(R.string.kw_value, "%.2f".format(Locale.US, state.telemetry?.powerKw ?: 0.0)), 32f, SandBright, FontWeight.Medium, 0.03f, onDark = true, mono = true)
    }
    val labels = listOf(
        6 to Offset(278f, 840f),
        8 to Offset(278f, 687f),
        10 to Offset(560f, 545f),
        12 to Offset(835f, 687f),
        16 to Offset(835f, 840f)
    )
    labels.forEach { (amps, c) ->
        Area(c.x - 70f, c.y - 40f, c.x + 70f, c.y + 40f, Alignment.Center,
            modifier = Modifier
                .clip(RoundedCornerShape(px(16f)))
                .clickable {
                    view.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
                    onSelect(amps)
                }
        ) {
            Engraved("${amps}A", 50f, BrownText, if (amps == state.selectedAmperes) FontWeight.Bold else FontWeight.Medium, 0.02f, mono = true)
        }
    }
    // The carved face from the design turns over its own picture, centred on the same spot.
    val walnut = ImageBitmap.imageResource(R.drawable.wood_walnut_dark)
    val hiFi = WOOD_HIFI
    val r = 158f
    Area(561.5f - r, 760f - r, 561.5f + r, 760f + r) {
        KnobDial(
            selectedAmperes = state.selectedAmperes,
            onAmperageSelected = onSelect,
            modifier = Modifier.fillMaxSize()
        ) { angle ->
            if (hiFi) {
                drawBrassKnob(angle, walnut)
            } else {
                val d = size.width.toInt()
                rotate(angle, Offset(size.width / 2f, size.height / 2f)) {
                    drawImage(knobFace, dstOffset = IntOffset.Zero, dstSize = IntSize(d, d))
                }
            }
        }
    }
}

@Composable
private fun RefScope.WoodAutoCurrent(state: DashboardUiState, isOnline: Boolean, voltage: Int?, panel: ImageBitmap, onToggle: () -> Unit) {
    val enabled = state.isVoltageBalancingEnabled
    val telemetry = state.telemetry
    val status = autoCurrentLabel(
        resolveAutoCurrentStatus(
            enabled = enabled,
            isOnline = isOnline,
            voltage = voltage,
            isCharging = telemetry?.isChargingActive == true,
            stationAmperes = telemetry?.selectedCurrentAmperes,
            lowThreshold = state.voltageLowThreshold,
            highThreshold = state.voltageHighThreshold,
            maxAmperage = state.voltageMaxAmperage
        )
    )
    Area(30f, 1003f, 1088f, 1192f, modifier = Modifier.clickable(onClick = onToggle)) {}
    // The slot of the picture sits high on the board; plain board is laid over it and a
    // slot of our own is cut in the middle, so the single line of the card is centred.
    Area(552f, 1026f, 916f, 1118f) {
        Canvas(Modifier.fillMaxSize().graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }) {
            drawImage(
                panel,
                srcOffset = IntOffset(552, 1104),
                srcSize = IntSize(364, 72),
                dstSize = IntSize(size.width.roundToInt(), size.height.roundToInt()),
                filterQuality = FilterQuality.High
            )
            // Feathered edges, so the patch melts into the board around it.
            val fx = 22f / 364f
            val fy = 18f / 92f
            drawRect(
                Brush.horizontalGradient(0f to Color.Transparent, fx to Color.Black, 1f - fx to Color.Black, 1f to Color.Transparent),
                blendMode = BlendMode.DstIn
            )
            drawRect(
                Brush.verticalGradient(0f to Color.Transparent, fy to Color.Black, 1f - fy to Color.Black, 1f to Color.Transparent),
                blendMode = BlendMode.DstIn
            )
        }
    }
    Area(578f, 1066f, 890f, 1128f, Alignment.Center, modifier = Modifier.woodSlot(px(16f))) {
        Engraved(status.uppercase(), 32f, SandBright, FontWeight.Medium, 0.03f, onDark = true)
    }
    Area(66f, 1084f, 92f, 1110f, Alignment.Center) {
        Box(Modifier.size(px(20f)).clip(CircleShape).background(if (enabled) OnDot else OffDot))
    }
    Area(108f, 1074f, 570f, 1122f) {
        Engraved(stringResource(R.string.dlb_title).uppercase(), 36f, BrownText)
    }
    Area(918f, 1062f, 1052f, 1132f, Alignment.Center) {
        WoodToggle(enabled, onToggle, Modifier.fillMaxWidth().height(px(60f)))
    }
}

/** A dark slot routed into the board, like the ones in the reference picture. */
private fun Modifier.woodSlot(corner: Dp): Modifier = this.drawBehind {
    val c = CornerRadius(corner.toPx(), corner.toPx())
    drawRoundRect(Color(0xFF1A0906), cornerRadius = c)
    drawRoundRect(
        brush = Brush.verticalGradient(listOf(Color(0xFF26170E), Color(0xFF32200F), Color(0xFF3A1F0C))),
        topLeft = Offset(0f, size.height * 0.08f),
        size = Size(size.width, size.height * 0.92f),
        cornerRadius = c
    )
    // Lit lower lip of the cut.
    drawRoundRect(
        color = Color(0x55FFD9A6),
        topLeft = Offset(size.width * 0.02f, size.height - 2.5f),
        size = Size(size.width * 0.96f, 2.5f),
        cornerRadius = CornerRadius(2f, 2f)
    )
}

@Composable
private fun RefScope.WoodRow(top: Float, title: String, isOn: Boolean, onClick: () -> Unit) {
    Area(30f, top, 1088f, top + 97f, modifier = Modifier.clip(RoundedCornerShape(px(26f))).clickable(onClick = onClick)) {}
    Area(66f, top + 38f, 92f, top + 64f, Alignment.Center) {
        Box(Modifier.size(px(20f)).clip(CircleShape).background(if (isOn) OnDot else OffDot))
    }
    Area(112f, top + 24f, 1060f, top + 76f) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Engraved(title.uppercase(), 38f, SandText, onDark = true)
            Engraved("  ▾", 24f, SandText.copy(alpha = 0.6f), FontWeight.Normal, 0f, onDark = true)
        }
    }
}

@Composable
private fun RefScope.WoodActionButton(state: DashboardUiState, onStart: () -> Unit, onStop: () -> Unit) {
    val telemetry = state.telemetry
    val isCharging = (telemetry?.isChargingActive ?: false) || state.isStopPending
    val busy = state.isStartingOrStopping
    val text = when {
        isCharging && busy -> stringResource(R.string.stopping)
        isCharging -> stringResource(R.string.stop)
        busy -> stringResource(R.string.starting)
        state.isKwhLimitEnabled && state.isDurationTimerEnabled ->
            stringResource(R.string.start_charge_kwh_hours, state.targetQuotaKwh.toInt(), state.targetDurationHours)
        state.isKwhLimitEnabled -> stringResource(R.string.start_charge_kwh, state.targetQuotaKwh.toInt())
        state.isDurationTimerEnabled -> stringResource(R.string.start_charge_hours, state.targetDurationHours)
        else -> stringResource(R.string.start_charge)
    }
    if (WOOD_HIFI) {
        BronzePlate(text, isCharging, busy) { if (isCharging) onStop() else onStart() }
        return
    }
    Area(
        34f, 1580f, 1084f, 1700f, Alignment.Center,
        modifier = Modifier
            .clip(RoundedCornerShape(px(28f)))
            // While charging the copper is tinted red, so the plate reads as a stop button.
            .drawBehind {
                if (isCharging) drawRect(Color(0xFFB02A18), alpha = 0.55f, blendMode = BlendMode.Multiply)
            }
            .clickable(enabled = !busy) { if (isCharging) onStop() else onStart() }
    ) {
        Engraved(text.uppercase(), 44f, if (isCharging) Color(0xFFFBE3CF) else Color(0xFF3A1C0A), FontWeight.Medium, 0.08f)
    }
}

/** Amber OLED digits glowing behind the glass. */
@Composable
private fun RefScope.OledText(text: String, sizePx: Float) {
    Text(
        text = text,
        style = TextStyle(
            fontFamily = JetMono,
            fontWeight = FontWeight.Medium,
            fontSize = sp(sizePx),
            color = OledAmber,
            shadow = Shadow(color = Color(0xB0FF8A1E), blurRadius = with(LocalDensity.current) { px(sizePx * 0.28f).toPx() })
        ),
        maxLines = 1
    )
}

/** A pane of smoked glass set flush into the walnut, with a soft reflection across it. */
@Composable
private fun RefScope.SmokedGlass(modifier: Modifier) {
    val corner = px(18f)
    Canvas(modifier = modifier) {
        val c = CornerRadius(corner.toPx(), corner.toPx())
        drawRoundRect(color = Color(0xFF0A0604), cornerRadius = c)
        drawRoundRect(
            brush = Brush.verticalGradient(listOf(Color(0xFF1E140D), Color(0xFF0D0805), Color(0xFF140C07))),
            topLeft = Offset(2f, 2f),
            size = Size(size.width - 4f, size.height - 4f),
            cornerRadius = c
        )
        // Reflection: a faint diagonal sheen over the upper left.
        val clip = Path().apply {
            addRoundRect(androidx.compose.ui.geometry.RoundRect(0f, 0f, size.width, size.height, c))
        }
        clipPath(clip) {
            drawRect(
                Brush.linearGradient(
                    0f to Color(0x1AFFFFFF),
                    0.35f to Color(0x08FFFFFF),
                    0.36f to Color(0x00FFFFFF),
                    start = Offset(0f, 0f),
                    end = Offset(size.width * 0.6f, size.height * 1.4f)
                )
            )
            drawRect(Brush.verticalGradient(listOf(Color(0x66000000), Color.Transparent), endY = size.height * 0.25f))
        }
        // Polished glass edge, lit along the bottom.
        drawRoundRect(color = Color(0x55FFE0B8), cornerRadius = c, style = Stroke(width = 1.2f), topLeft = Offset(0f, 1.5f))
        drawRoundRect(color = Color(0xFF050302), cornerRadius = c, style = Stroke(width = 1.5f))
    }
}

/** Milled walnut knob in a brass ring, with a glowing light guide as its pointer. */
private fun androidx.compose.ui.graphics.drawscope.DrawScope.drawBrassKnob(angle: Float, walnut: ImageBitmap) {
    val r = size.minDimension / 2f
    val c = Offset(size.width / 2f, size.height / 2f)
    // Shadow the knob drops into its socket.
    drawCircle(
        Brush.radialGradient(listOf(Color(0xB0000000), Color.Transparent), center = c + Offset(0f, r * 0.06f), radius = r * 1.04f),
        radius = r * 1.04f, center = c + Offset(0f, r * 0.06f)
    )
    // Brass ring.
    drawCircle(
        Brush.sweepGradient(
            listOf(Color(0xFFB8893B), Color(0xFFF4DA94), Color(0xFF8A6421), Color(0xFFD9B05A), Color(0xFF7A5519), Color(0xFFEFCF80), Color(0xFFB8893B)),
            center = c
        ),
        radius = r * 0.97f, center = c
    )
    drawCircle(Color(0x80000000), radius = r * 0.97f, center = c, style = Stroke(width = r * 0.012f))
    // Walnut face, turning with the knob.
    val face = r * 0.86f
    // Plain photo walnut, enlarged so the grain reads at knob size.
    rotate(angle, c) {
        clipPath(Path().apply { addOval(androidx.compose.ui.geometry.Rect(c, face)) }) {
            val crop = (walnut.width * 0.55f).toInt()
            drawImage(
                walnut,
                srcOffset = IntOffset((walnut.width - crop) / 2, (walnut.height - crop) / 2),
                srcSize = IntSize(crop, crop),
                dstOffset = IntOffset((c.x - face).toInt(), (c.y - face).toInt()),
                dstSize = IntSize((face * 2).toInt(), (face * 2).toInt()),
                filterQuality = FilterQuality.High
            )
        }
    }
    drawCircle(Color(0x50140A04), radius = face, center = c)
    // Milled rings and light from the top left.
    for (i in 1..6) drawCircle(Color(0x10FFE8C8), radius = face * (0.3f + i * 0.1f), center = c, style = Stroke(width = 1f))
    drawCircle(
        Brush.linearGradient(listOf(Color(0x30FFE8C8), Color.Transparent, Color(0x60000000)), start = c - Offset(face, face), end = c + Offset(face, face)),
        radius = face, center = c
    )
    drawCircle(Color(0xA0000000), radius = face, center = c, style = Stroke(width = r * 0.025f))
    // Light guide: an amber line from the hub to the rim, glowing.
    rotate(angle, c) {
        val start = c - Offset(0f, face * 0.32f)
        val end = c - Offset(0f, face * 0.86f)
        drawLine(Color(0x1A000000), start, end, strokeWidth = r * 0.11f, cap = StrokeCap.Round)
        drawLine(Color(0x55FF9A2E), start, end, strokeWidth = r * 0.085f, cap = StrokeCap.Round)
        drawLine(Color(0xFFFFC46B), start, end, strokeWidth = r * 0.035f, cap = StrokeCap.Round)
    }
}

/** Brushed bronze start plate that gives under the finger. */
@Composable
private fun RefScope.BronzePlate(text: String, isCharging: Boolean, busy: Boolean, onClick: () -> Unit) {
    val view = LocalView.current
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val press by animateFloatAsState(if (pressed) 1f else 0f, label = "press")
    val strokes = remember {
        val rng = java.util.Random(3)
        List(140) { rng.nextFloat() to (rng.nextFloat() * 0.10f) }
    }
    Area(
        28f, 1576f, 1088f, 1696f, Alignment.Center,
        modifier = Modifier
            .scale(1f - press * 0.015f)
            .clickable(interactionSource = interaction, indication = null, enabled = !busy) {
                view.performHapticFeedback(HapticFeedbackConstants.CONFIRM)
                onClick()
            }
    ) {
        val corner = px(30f)
        Canvas(modifier = Modifier.fillMaxSize()) {
            val cr = CornerRadius(corner.toPx(), corner.toPx())
            val clip = Path().apply { addRoundRect(androidx.compose.ui.geometry.RoundRect(0f, 0f, size.width, size.height, cr)) }
            clipPath(clip) {
                drawRect(
                    Brush.verticalGradient(
                        if (isCharging) listOf(Color(0xFFB0503A), Color(0xFF8E3524), Color(0xFF6E2618))
                        else listOf(Color(0xFFC08A52), Color(0xFFA06A35), Color(0xFF7C4E24))
                    )
                )
                // Brushing: fine horizontal strokes of lighter and darker metal.
                strokes.forEachIndexed { i, (y, a) ->
                    val yy = y * size.height
                    drawLine(
                        if (i % 2 == 0) Color.White.copy(alpha = a) else Color.Black.copy(alpha = a),
                        Offset(0f, yy), Offset(size.width, yy), strokeWidth = 1f
                    )
                }
                drawRect(Brush.horizontalGradient(listOf(Color(0x30000000), Color(0x18FFF0D8), Color(0x30000000))))
                if (press > 0f) drawRect(Color.Black.copy(alpha = 0.18f * press))
            }
            // Chamfer: lit top edge, dark lower edge.
            drawRoundRect(
                Brush.verticalGradient(0f to Color(0xC0FFE6C0), 0.12f to Color(0x00FFE6C0), 0.85f to Color(0x00000000), 1f to Color(0xB0200A00)),
                cornerRadius = cr, style = Stroke(width = 3.dp.toPx())
            )
            drawRoundRect(Color(0xA0200A00), cornerRadius = cr, style = Stroke(width = 1f))
        }
        Text(
            text = text.uppercase(),
            style = TextStyle(
                fontFamily = Manrope,
                fontWeight = FontWeight.Bold,
                fontSize = sp(40f),
                letterSpacing = sp(40f * 0.12f),
                color = if (isCharging) Color(0xFFFBE3CF) else Color(0xFF2A1405)
            ),
            maxLines = 1
        )
    }
}
