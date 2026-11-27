package ua.org.electros.app.ui.dashboard

import android.Manifest
import android.content.pm.PackageManager
import android.view.HapticFeedbackConstants
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import ua.org.electros.app.ui.theme.*
import java.util.Locale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.res.imageResource
import ua.org.electros.app.R
import ua.org.electros.app.ui.asString
import kotlin.math.atan2
import kotlin.math.hypot

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DashboardScreen(
    viewModel: DashboardViewModel,
    modifier: Modifier = Modifier
) {
    val state by viewModel.uiState.collectAsState()
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val notificationPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { _ -> }

    LaunchedEffect(Unit) {
        if (ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.POST_NOTIFICATIONS
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    LaunchedEffect(lifecycleOwner, viewModel) {
        lifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
            viewModel.pollWhileVisible()
        }
    }

    LaunchedEffect(state.activeSession?.id, state.activeSession?.status) {
        if (state.activeSession != null) {
            viewModel.ensureMonitorServiceRunning(context)
        }
    }

    CompositionLocalProvider(
        LocalDashPalette provides if (state.isWoodTheme) WalnutDashPalette else ClassicDashPalette
    ) {
    val onStartCharge = {
        if (ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.POST_NOTIFICATIONS
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
        viewModel.onStartClicked(context)
    }

    PullToRefreshBox(
        isRefreshing = state.isRefreshing,
        onRefresh = { viewModel.refreshTelemetry() },
        modifier = modifier.fillMaxSize()
    ) {
        if (state.isWoodTheme) {
            WoodDashboard(
                state = state,
                viewModel = viewModel,
                onStart = onStartCharge,
                onStop = { viewModel.onStopClicked(context) }
            )
            return@PullToRefreshBox
        }
        Column(
            modifier = Modifier
                .fillMaxSize()
                .dashChassis()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 14.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            val telemetry = state.telemetry
            val activeSession = state.activeSession
            val isChargingOrSessionActive = (telemetry?.isChargingActive == true) || activeSession != null
            val actualAmperes = resolveDisplayedAmperes(telemetry, state.selectedAmperes)

            // 1. Clean Connection Status Plate + Live Actual Amperage Pill (zero extra vertical space)
            StudioTopHeader(
                isOnline = telemetry?.isOnline ?: false,
                statusText = telemetry?.statusText.orEmpty(),
                isChargingOrSessionActive = isChargingOrSessionActive && !state.isOutageActive,
                isChargingActive = telemetry?.isChargingActive == true,
                actualAmperes = actualAmperes,
                nominalAmperes = state.selectedAmperes,
                activeTargetDurationMinutes = state.activeTargetDurationMinutes,
                accumulatedChargingMs = state.accumulatedChargingMs,
                lastTickAt = state.lastTickAt
            )

            // Error or pending-stop warning banner
            val errorMessage = state.errorMessage
            if (errorMessage != null) {
                ErrorMessageCard(message = errorMessage.asString())
            } else if (state.isStopPending) {
                ErrorMessageCard(message = stringResource(R.string.dash_stop_pending))
            }

            // Outage / Offline Warning
            if (state.activeSession != null && state.isOutageActive) {
                val remMinText = if (state.activeTargetDurationMinutes > 0) {
                    val remMs = (state.activeTargetDurationMinutes * 60_000L - state.accumulatedChargingMs).coerceAtLeast(0L)
                    formatCountdownHms(remMs)
                } else null
                OutageBanner(
                    deliveredKwh = state.totalDeliveredKwh,
                    targetKwh = state.activeTargetKwh,
                    isLimitEnabled = state.activeTargetKwh > 0.0,
                    isAutoResumeEnabled = state.isAutoResumeEnabled,
                    isStationOnline = telemetry?.isOnline == true,
                    isBusy = state.isStartingOrStopping,
                    remainingTimerText = remMinText,
                    onCancelOutage = { viewModel.onStopClicked(context) }
                )
            } else if (telemetry != null && !telemetry.isOnline) {
                OfflineWarningCard()
            }

            // 2. Pure RMS Line Voltage Nixie Panel + Session Charged Energy
            StudioClassicNixieCard(
                voltage = telemetry?.voltageCurrent,
                isOnline = telemetry?.isOnline ?: false,
                // Without an app session there is nothing to count from, so no "+0.00".
                isSessionActive = state.activeSession != null,
                deliveredKwh = state.totalDeliveredKwh,
                activeTargetKwh = state.activeTargetKwh
            )

            // 3. Central 3D Rotary Potentiometer (Variation 4.1 Classic)
            StudioClassicRotaryKnobCard(
                selectedAmperes = state.selectedAmperes,
                powerKw = telemetry?.powerKw ?: 0.0,
                onAmperageSelected = { viewModel.onAmperageSelected(it) }
            )

            // 4. DLB Auto-Derate Interactive Card
            StudioClassicDlbCard(
                isDlbEnabled = state.isVoltageBalancingEnabled,
                lowThreshold = state.voltageLowThreshold,
                highThreshold = state.voltageHighThreshold,
                isCharging = telemetry?.isChargingActive == true,
                stationAmperes = telemetry?.selectedCurrentAmperes,
                voltage = telemetry?.voltageCurrent,
                isOnline = telemetry?.isOnline ?: false,
                selectedAmperes = state.selectedAmperes,
                voltageMaxAmperage = state.voltageMaxAmperage,
                stationLowVoltageStop = telemetry?.isLowVoltageStop == true,
                onToggleDlb = {
                    viewModel.toggleVoltageBalancing(!state.isVoltageBalancingEnabled)
                }
            )

            // 5. Energy Quota Limit Card (1-100 kWh auto-stop; configures without auto-starting)
            StudioClassicKwhLimitCard(
                isKwhLimitEnabled = state.isKwhLimitEnabled,
                targetQuotaKwh = state.targetQuotaKwh,
                activeTargetKwh = state.activeTargetKwh,
                hasActiveSession = state.activeSession != null,
                onSelectKwhLimit = { enabled, quotaKwh ->
                    viewModel.setKwhLimit(context, enabled, quotaKwh)
                }
            )

            // 6. Charge Timer Status & Duration Picker Card (1-12H with Outage Continuation)
            StudioClassicTimerCard(
                isDurationTimerEnabled = state.isDurationTimerEnabled,
                targetDurationHours = state.targetDurationHours,
                activeTargetDurationMinutes = state.activeTargetDurationMinutes,
                accumulatedChargingMs = state.accumulatedChargingMs,
                lastTickAt = state.lastTickAt,
                hasActiveSession = state.activeSession != null,
                isChargingActive = telemetry?.isChargingActive ?: false,
                isOutageActive = state.isOutageActive,
                onSelectDuration = { enabled, hours ->
                    viewModel.setDurationTimer(context, enabled, hours)
                }
            )

            // 7. Daily Schedule Timer Indicator Card (e.g. 02:00–16:00)
            StudioClassicScheduleCard(
                isScheduleEnabled = state.isScheduleEnabled,
                scheduleStartTime = state.scheduleStartTime,
                scheduleStopTime = state.scheduleStopTime,
                onUpdateSchedule = { enabled, start, stop ->
                    viewModel.setScheduleTimer(enabled, start, stop)
                }
            )

            // 8. Heavy Switch Action Button
            StudioClassicActionButton(
                isCharging = (telemetry?.isChargingActive ?: false) || state.isStopPending,
                isBusy = state.isStartingOrStopping,
                isKwhLimitEnabled = state.isKwhLimitEnabled,
                targetQuotaKwh = state.targetQuotaKwh,
                isDurationTimerEnabled = state.isDurationTimerEnabled,
                targetDurationHours = state.targetDurationHours,
                onStart = onStartCharge,
                onStop = { viewModel.onStopClicked(context) }
            )
        }
    }
    }
}

@Composable
private fun StudioTopHeader(
    isOnline: Boolean,
    statusText: String,
    isChargingOrSessionActive: Boolean,
    isChargingActive: Boolean,
    actualAmperes: Int,
    nominalAmperes: Int,
    activeTargetDurationMinutes: Int,
    accumulatedChargingMs: Long,
    lastTickAt: Long
) {
    val nowMs = remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(isChargingActive, activeTargetDurationMinutes, lastTickAt, accumulatedChargingMs) {
        nowMs.longValue = System.currentTimeMillis()
        while (isChargingActive && activeTargetDurationMinutes > 0 && lastTickAt > 0L) {
            delay(1000L)
            nowMs.longValue = System.currentTimeMillis()
        }
    }

    val liveDeltaMs = if (isChargingActive && activeTargetDurationMinutes > 0 && lastTickAt > 0L) {
        (nowMs.longValue - lastTickAt).coerceAtLeast(0L)
    } else {
        0L
    }
    val targetMs = activeTargetDurationMinutes * 60_000L
    val elapsedMs = (accumulatedChargingMs + liveDeltaMs).coerceAtLeast(0L)
    val remainingMs = (targetMs - elapsedMs).coerceAtLeast(0L)

    val hasTimerTarget = activeTargetDurationMinutes > 0 && isChargingOrSessionActive
    val isDerated = actualAmperes < nominalAmperes
    val isBoosted = actualAmperes > nominalAmperes
    val modeLabel = if (hasTimerTarget) {
        "${actualAmperes}A · ${formatCountdownHms(remainingMs)}"
    } else {
        "${actualAmperes}A"
    }
    val modeAccent = when {
        isDerated -> AmberNixie
        isBoosted -> SecondaryCyan
        hasTimerTarget -> AmberNixie
        LocalDashPalette.current.isWood -> AmberNixie
        else -> StudioGreen
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 2.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Row(
            modifier = Modifier.weight(1f),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Box(
                modifier = Modifier
                    .size(9.dp)
                    .clip(CircleShape)
                    .background(
                        if (!isOnline) IndustrialRed
                        else if (isChargingActive) StudioGreen
                        else if (LocalDashPalette.current.isWood) AmberNixie
                        else StudioGreen
                    )
            )
            Text(
                text = formatHeaderStatus(statusText, isOnline).asString(),
                style = MaterialTheme.typography.labelMedium.copy(
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.Bold
                ),
                color = TextSecondaryDark,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }

        // Live actual amperage pill (always visible)
        Spacer(modifier = Modifier.width(8.dp))
        Box(
            modifier = Modifier
                .clip(RoundedCornerShape(6.dp))
                .dashScreen()
                .border(1.dp, modeAccent.copy(alpha = 0.45f), RoundedCornerShape(6.dp))
                .padding(horizontal = 8.dp, vertical = 3.dp)
        ) {
            Text(
                text = modeLabel,
                fontSize = 10.sp,
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.Bold,
                color = modeAccent,
                maxLines = 1
            )
        }
    }
}

/**
 * Variation 4.1 Classic: Pure RMS Line Voltage Panel with inline Session Energy Nixie Display
 */
@Composable
private fun StudioClassicNixieCard(
    voltage: Int?,
    isOnline: Boolean,
    isSessionActive: Boolean,
    deliveredKwh: Double,
    activeTargetKwh: Double
) {
    val hasKwhTarget = activeTargetKwh > 0.0
    val progress = if (hasKwhTarget) {
        (deliveredKwh / activeTargetKwh).toFloat().coerceIn(0f, 1f)
    } else {
        0f
    }
    val pct = if (hasKwhTarget) {
        ((deliveredKwh / activeTargetKwh) * 100.0).toInt().coerceIn(0, 100)
    } else {
        0
    }
    val remainingKwh = (activeTargetKwh - deliveredKwh).coerceAtLeast(0.0)

    DashCard(
        tone = WoodTone.WALNUT,
        borderColor = StudioPanelBorder,
        seed = 1f,
        woodAccent = false,
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 14.dp, vertical = 10.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = stringResource(R.string.dash_line_voltage),
                    style = MaterialTheme.typography.labelSmall.copy(
                        fontFamily = FontFamily.Monospace,
                        letterSpacing = 0.6.sp
                    ),
                    color = TextSecondaryDark,
                    fontWeight = FontWeight.Bold
                )
                if (isSessionActive) {
                    Text(
                        text = stringResource(R.string.dash_session_energy),
                        style = MaterialTheme.typography.labelSmall.copy(
                            fontFamily = FontFamily.Monospace,
                            letterSpacing = 0.6.sp
                        ),
                        color = TextSecondaryDark,
                        fontWeight = FontWeight.Bold
                    )
                }
            }

            Spacer(modifier = Modifier.height(4.dp))

            // Cathode Nixie Display Window
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(8.dp))
                    .dashScreen()
                    .border(2.dp, StudioScreenBorder, RoundedCornerShape(8.dp))
                    .padding(horizontal = 14.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = if (isSessionActive) Alignment.CenterVertically else Alignment.Bottom
                ) {
                    Row(
                        verticalAlignment = Alignment.Bottom,
                        horizontalArrangement = Arrangement.spacedBy(5.dp)
                    ) {
                        Text(
                            text = if (isOnline && voltage != null) "$voltage" else "---",
                            fontSize = 28.sp,
                            fontWeight = FontWeight.Black,
                            fontFamily = FontFamily.Monospace,
                            color = if (isOnline && voltage != null) AmberNixie else TextTertiaryDark,
                            letterSpacing = 2.sp
                        )
                        if (!LocalDashPalette.current.isWood || isSessionActive) {
                            Text(
                                text = "V",
                                fontSize = 13.sp,
                                fontWeight = FontWeight.Bold,
                                fontFamily = FontFamily.Monospace,
                                color = if (isOnline && voltage != null) AmberNixie else TextTertiaryDark,
                                modifier = Modifier.padding(bottom = 3.dp)
                            )
                        }
                    }

                    if (isSessionActive) {
                        Box(
                            modifier = Modifier
                                .width(1.dp)
                                .height(28.dp)
                                .background(StudioScreenBorder)
                        )
                        Column(horizontalAlignment = Alignment.End) {
                            if (hasKwhTarget) {
                                Row(
                                    verticalAlignment = Alignment.Bottom,
                                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                                ) {
                                    Text(
                                        text = "%.2f".format(Locale.US, deliveredKwh),
                                        fontSize = 22.sp,
                                        fontWeight = FontWeight.Black,
                                        fontFamily = FontFamily.Monospace,
                                        color = StudioGreen
                                    )
                                    Text(
                                        text = "/ %.0f".format(Locale.US, activeTargetKwh),
                                        fontSize = 12.sp,
                                        fontWeight = FontWeight.Bold,
                                        fontFamily = FontFamily.Monospace,
                                        color = TextSecondaryDark,
                                        modifier = Modifier.padding(bottom = 2.dp)
                                    )
                                    Text(
                                        text = stringResource(R.string.unit_kwh),
                                        fontSize = 11.sp,
                                        fontWeight = FontWeight.Bold,
                                        fontFamily = FontFamily.Monospace,
                                        color = StudioGreen,
                                        modifier = Modifier.padding(bottom = 2.dp)
                                    )
                                }
                                Text(
                                    text = stringResource(R.string.dash_remaining, "%.2f".format(Locale.US, remainingKwh), pct),
                                    fontSize = 10.sp,
                                    fontFamily = FontFamily.Monospace,
                                    color = TextSecondaryDark
                                )
                            } else {
                                Row(
                                    verticalAlignment = Alignment.Bottom,
                                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                                ) {
                                    Text(
                                        text = "+%.2f".format(Locale.US, deliveredKwh),
                                        fontSize = 22.sp,
                                        fontWeight = FontWeight.Black,
                                        fontFamily = FontFamily.Monospace,
                                        color = AmberNixie
                                    )
                                    Text(
                                        text = stringResource(R.string.unit_kwh),
                                        fontSize = 11.sp,
                                        fontWeight = FontWeight.Bold,
                                        fontFamily = FontFamily.Monospace,
                                        color = AmberNixie,
                                        modifier = Modifier.padding(bottom = 2.dp)
                                    )
                                }
                            }
                        }
                    } else if (LocalDashPalette.current.isWood) {
                        Text(
                            text = stringResource(R.string.dash_unit_volts_full),
                            fontSize = 13.sp,
                            fontWeight = FontWeight.Bold,
                            fontFamily = FontFamily.Monospace,
                            letterSpacing = 1.2.sp,
                            color = TextSecondaryDark,
                            modifier = Modifier.padding(bottom = 3.dp)
                        )
                    }
                }

                if (isSessionActive && hasKwhTarget) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(4.dp)
                            .clip(RoundedCornerShape(2.dp))
                            .background(LocalDashPalette.current.trackBg)
                    ) {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth(progress)
                                .height(4.dp)
                                .background(StudioGreen)
                        )
                    }
                }
            }
        }
    }
}

/**
 * Variation 4.1 Classic: Sculpted Dark Graphite Cam Selector Switch
 */
@Composable
private fun StudioClassicRotaryKnobCard(
    selectedAmperes: Int,
    powerKw: Double,
    onAmperageSelected: (Int) -> Unit
) {
    val stops = listOf(6, 8, 10, 12, 16)
    val view = LocalView.current

    val currentIndex = stops.indices.minByOrNull { idx ->
        kotlin.math.abs(stops[idx] - selectedAmperes)
    } ?: 2


    val stageCenterOffsetY = 10.dp
    // Read outside the card, so take the maple colors the card's board uses.
    val knobPalette = if (LocalDashPalette.current.isWood) MapleDashPalette else LocalDashPalette.current
    val woodKnobFace = androidx.compose.ui.graphics.ImageBitmap.imageResource(R.drawable.knob_face)
    // The wood knob is drawn larger, like a real wooden selector.
    val knobScale = if (LocalDashPalette.current.isWood) 1.22f else 1f
    val labelOffsets = listOf(
        Pair((-96).dp * knobScale, 30.dp * knobScale),  // 6A
        Pair((-96).dp * knobScale, (-28).dp * knobScale), // 8A
        Pair(0.dp, (-80).dp * knobScale),     // 10A
        Pair(96.dp * knobScale, (-28).dp * knobScale),    // 12A
        Pair(96.dp * knobScale, 30.dp * knobScale)        // 16A
    )

    DashCard(
        tone = WoodTone.MAPLE,
        borderColor = StudioPanelBorder,
        seed = 2f,
        woodAccent = false,
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 14.dp, vertical = 10.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = stringResource(R.string.dash_charge_current),
                    style = MaterialTheme.typography.labelSmall.copy(
                        fontFamily = FontFamily.Monospace,
                        letterSpacing = 0.6.sp
                    ),
                    color = TextSecondaryDark,
                    fontWeight = FontWeight.Bold
                )
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(6.dp))
                        .dashScreen()
                        .border(1.dp, StudioScreenBorder, RoundedCornerShape(6.dp))
                        .padding(horizontal = 8.dp, vertical = 2.dp)
                ) {
                    Text(
                        text = stringResource(R.string.kw_value, "%.2f".format(Locale.US, powerKw)),
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Black,
                        fontFamily = FontFamily.Monospace,
                        color = AmberNixie
                    )
                }
            }

            Spacer(modifier = Modifier.height(2.dp))

            // Compact tactile stage matching dark machined cam switch aesthetic (drag only on knob)
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(180.dp * knobScale),
                contentAlignment = Alignment.Center
            ) {
                Box(
                    modifier = Modifier.offset(y = stageCenterOffsetY * knobScale),
                    contentAlignment = Alignment.Center
                ) {
                    // Clean high-contrast amperage labels around the recessed dial well
                    stops.forEachIndexed { idx, stop ->
                        val (offsetX, offsetY) = labelOffsets[idx]
                        val isCurrent = idx == currentIndex

                        Text(
                            text = "${stop}A",
                            style = TextStyle(
                                fontSize = if (isCurrent) 17.sp else 16.sp,
                                fontWeight = if (isCurrent) FontWeight.Black else FontWeight.Bold,
                                fontFamily = FontFamily.Monospace,
                                color = if (isCurrent) knobPalette.knobLabelActive else knobPalette.knobLabel,
                                shadow = if (isCurrent && !knobPalette.isWood) {
                                    Shadow(
                                        color = Color(0xB8FF9F1C),
                                        offset = Offset.Zero,
                                        blurRadius = 16f
                                    )
                                } else {
                                    null
                                }
                            ),
                            modifier = Modifier
                                .offset(x = offsetX, y = offsetY)
                                .clip(RoundedCornerShape(6.dp))
                                .clickable {
                                    view.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
                                    onAmperageSelected(stop)
                                }
                                .padding(horizontal = 6.dp, vertical = 3.dp)
                        )
                    }

                    // 140dp 3D Recessed Socket + Sculpted Chamfered Cam Switch Canvas
                    KnobDial(
                        selectedAmperes = selectedAmperes,
                        onAmperageSelected = onAmperageSelected,
                        modifier = Modifier.size(140.dp * knobScale)
                    ) { currentAngle ->
                            val w = size.width
                            val h = size.height
                            val cx = w / 2f
                            val cy = h / 2f
                            val centerOffset = Offset(cx, cy)
                            if (knobPalette.isWood) {
                                drawWoodKnob(currentAngle, woodKnobFace)
                                return@KnobDial
                            }

                            val outerBezelRadius = w * 0.48f
                            val wellRadius = w * 0.472f
                            val discRadius = w * 0.407f

                            // 1. Machined recessed housing well & shadow cavity (static)
                            drawCircle(
                                brush = Brush.linearGradient(
                                    colors = listOf(Color(0xFF07090D), Color(0xFF0C0E14), Color(0xFF191D26)),
                                    start = Offset(w * 0.15f, h * 0.15f),
                                    end = Offset(w * 0.85f, h * 0.85f)
                                ),
                                radius = wellRadius,
                                center = centerOffset
                            )
                            drawCircle(
                                brush = Brush.linearGradient(
                                    colors = listOf(Color(0xFF090B0F), Color(0xFF1D222D), Color(0xFF2E3545)),
                                    start = Offset(w * 0.15f, h * 0.15f),
                                    end = Offset(w * 0.85f, h * 0.85f)
                                ),
                                radius = outerBezelRadius,
                                center = centerOffset,
                                style = Stroke(width = 1.6.dp.toPx())
                            )
                            drawCircle(
                                color = Color(0x99000000),
                                radius = w * 0.456f,
                                center = centerOffset,
                                style = Stroke(width = 2.dp.toPx())
                            )

                            // Cast drop shadow beneath raised cylindrical dial disc
                            drawCircle(
                                brush = Brush.radialGradient(
                                    colors = listOf(
                                        Color(0xC0030407),
                                        Color(0x52030407),
                                        Color.Transparent
                                    ),
                                    center = Offset(cx + w * 0.015f, cy + w * 0.03f),
                                    radius = discRadius * 1.12f
                                ),
                                radius = discRadius * 1.12f,
                                center = Offset(cx + w * 0.015f, cy + w * 0.03f)
                            )

                            // 2. Satin gunmetal circular dial base disc (static light direction)
                            drawCircle(
                                brush = Brush.linearGradient(
                                    colors = listOf(Color(0xFF383F4E), Color(0xFF282E3A), Color(0xFF1B2029)),
                                    start = Offset(w * 0.15f, h * 0.10f),
                                    end = Offset(w * 0.85f, h * 0.90f)
                                ),
                                radius = discRadius,
                                center = centerOffset
                            )
                            drawCircle(
                                brush = Brush.linearGradient(
                                    colors = listOf(Color(0xFF535D70), Color(0xFF2E3442), Color(0xFF12151C)),
                                    start = Offset(w * 0.12f, h * 0.12f),
                                    end = Offset(w * 0.88f, h * 0.88f)
                                ),
                                radius = discRadius,
                                center = centerOffset,
                                style = Stroke(width = 1.3.dp.toPx())
                            )
                            drawCircle(
                                color = Color.White.copy(alpha = 0.05f),
                                radius = discRadius - 1.5.dp.toPx(),
                                center = centerOffset,
                                style = Stroke(width = 0.8.dp.toPx())
                            )

                            // 3. Rotating Chamfered Bar Handle with World-Space Cast Shadow
                            val discClip = Path().apply {
                                addOval(
                                    Rect(
                                        left = cx - discRadius + 0.8f,
                                        top = cy - discRadius + 0.8f,
                                        right = cx + discRadius - 0.8f,
                                        bottom = cy + discRadius - 0.8f
                                    )
                                )
                            }

                            val plateauWidth = w * 0.20f
                            val plateauHeight = w * 0.77f
                            val plateauLeft = cx - plateauWidth / 2f
                            val plateauTop = cy - plateauHeight / 2f
                            val plateauRadius = w * 0.057f

                            // World-space shadow cast by handle onto disc
                            clipPath(discClip) {
                                translate(left = 3.dp.toPx(), top = 4.dp.toPx()) {
                                    rotate(degrees = currentAngle, pivot = centerOffset) {
                                        drawRoundRect(
                                            color = Color(0x88040508),
                                            topLeft = Offset(cx - w * 0.145f, cy - w * 0.405f),
                                            size = Size(w * 0.29f, w * 0.81f),
                                            cornerRadius = CornerRadius(w * 0.09f, w * 0.09f)
                                        )
                                    }
                                }
                            }

                            // Sculpted left & right sloped chamfer skirts clipped flush to disc rim
                            clipPath(discClip) {
                                rotate(degrees = currentAngle, pivot = centerOffset) {
                                    val skirtTop = cy - w * 0.428f
                                    val skirtBottom = cy + w * 0.428f
                                    val shoulderUpper = cy - w * 0.27f
                                    val shoulderLower = cy + w * 0.27f
                                    val outerX = w * 0.164f
                                    val waistX = w * 0.143f

                                    val leftSkirt = Path().apply {
                                        moveTo(cx - outerX, skirtTop)
                                        lineTo(cx, skirtTop)
                                        lineTo(cx, skirtBottom)
                                        lineTo(cx - outerX, skirtBottom)
                                        cubicTo(
                                            cx - w * 0.15f, cy + w * 0.37f,
                                            cx - waistX, cy + w * 0.32f,
                                            cx - waistX, shoulderLower
                                        )
                                        lineTo(cx - waistX, shoulderUpper)
                                        cubicTo(
                                            cx - waistX, cy - w * 0.32f,
                                            cx - w * 0.15f, cy - w * 0.37f,
                                            cx - outerX, skirtTop
                                        )
                                        close()
                                    }
                                    drawPath(
                                        path = leftSkirt,
                                        brush = Brush.linearGradient(
                                            colors = listOf(Color(0xFF485163), Color(0xFF2E3543)),
                                            start = Offset(cx - outerX, skirtTop),
                                            end = Offset(cx, skirtBottom)
                                        )
                                    )

                                    val rightSkirt = Path().apply {
                                        moveTo(cx, skirtTop)
                                        lineTo(cx + outerX, skirtTop)
                                        cubicTo(
                                            cx + w * 0.15f, cy - w * 0.37f,
                                            cx + waistX, cy - w * 0.32f,
                                            cx + waistX, shoulderUpper
                                        )
                                        lineTo(cx + waistX, shoulderLower)
                                        cubicTo(
                                            cx + waistX, cy + w * 0.32f,
                                            cx + w * 0.15f, cy + w * 0.37f,
                                            cx + outerX, skirtBottom
                                        )
                                        lineTo(cx, skirtBottom)
                                        close()
                                    }
                                    drawPath(
                                        path = rightSkirt,
                                        brush = Brush.linearGradient(
                                            colors = listOf(Color(0xFF20252F), Color(0xFF13161D)),
                                            start = Offset(cx, skirtTop),
                                            end = Offset(cx + outerX, skirtBottom)
                                        )
                                    )
                                }
                            }

                            // Raised satin flat front plateau + recessed indicator slot + warm amber LED pill
                            rotate(degrees = currentAngle, pivot = centerOffset) {
                                drawRoundRect(
                                    brush = Brush.linearGradient(
                                        colors = listOf(Color(0xFF383F4D), Color(0xFF2C323E), Color(0xFF212630)),
                                        start = Offset(plateauLeft, plateauTop),
                                        end = Offset(plateauLeft + plateauWidth, plateauTop + plateauHeight)
                                    ),
                                    topLeft = Offset(plateauLeft, plateauTop),
                                    size = Size(plateauWidth, plateauHeight),
                                    cornerRadius = CornerRadius(plateauRadius, plateauRadius)
                                )
                                drawRoundRect(
                                    brush = Brush.linearGradient(
                                        colors = listOf(Color(0xFF5D677B), Color(0xFF323946), Color(0xFF171A22)),
                                        start = Offset(plateauLeft, plateauTop),
                                        end = Offset(plateauLeft + plateauWidth, plateauTop + plateauHeight)
                                    ),
                                    topLeft = Offset(plateauLeft, plateauTop),
                                    size = Size(plateauWidth, plateauHeight),
                                    cornerRadius = CornerRadius(plateauRadius, plateauRadius),
                                    style = Stroke(width = 1.1.dp.toPx())
                                )
                                drawRoundRect(
                                    color = Color.White.copy(alpha = 0.07f),
                                    topLeft = Offset(plateauLeft + 1.2.dp.toPx(), plateauTop + 1.2.dp.toPx()),
                                    size = Size(plateauWidth - 2.4.dp.toPx(), plateauHeight - 2.4.dp.toPx()),
                                    cornerRadius = CornerRadius(plateauRadius * 0.88f, plateauRadius * 0.88f),
                                    style = Stroke(width = 0.7.dp.toPx())
                                )

                                // Deep recessed vertical slot track
                                val trackWidth = w * 0.068f
                                val trackHeight = w * 0.282f
                                val trackLeft = cx - trackWidth / 2f
                                val trackTop = plateauTop + w * 0.046f
                                val trackRadius = trackWidth / 2f

                                drawRoundRect(
                                    color = Color(0xFF07090D),
                                    topLeft = Offset(trackLeft, trackTop),
                                    size = Size(trackWidth, trackHeight),
                                    cornerRadius = CornerRadius(trackRadius, trackRadius)
                                )
                                drawRoundRect(
                                    color = Color(0xFF222732),
                                    topLeft = Offset(trackLeft, trackTop),
                                    size = Size(trackWidth, trackHeight),
                                    cornerRadius = CornerRadius(trackRadius, trackRadius),
                                    style = Stroke(width = 0.9.dp.toPx())
                                )

                                // Soft amber bloom halo inside upper slot
                                val pillWidth = w * 0.048f
                                val pillHeight = w * 0.122f
                                val pillLeft = cx - pillWidth / 2f
                                val pillTop = trackTop + w * 0.011f
                                val pillRadius = pillWidth / 2f
                                val glowCenter = Offset(cx, pillTop + pillHeight / 2f)

                                drawCircle(
                                    brush = Brush.radialGradient(
                                        colors = listOf(
                                            Color(0x66FF9500),
                                            Color(0x22FF8800),
                                            Color.Transparent
                                        ),
                                        center = glowCenter,
                                        radius = w * 0.12f
                                    ),
                                    radius = w * 0.12f,
                                    center = glowCenter
                                )

                                // Glowing Amber LED Pill inside Upper Half of Track
                                drawRoundRect(
                                    brush = Brush.linearGradient(
                                        colors = listOf(Color(0xFFFFE285), Color(0xFFFFA51F), Color(0xFFD95B00)),
                                        start = Offset(pillLeft, pillTop),
                                        end = Offset(pillLeft + pillWidth, pillTop + pillHeight)
                                    ),
                                    topLeft = Offset(pillLeft, pillTop),
                                    size = Size(pillWidth, pillHeight),
                                    cornerRadius = CornerRadius(pillRadius, pillRadius)
                                )

                                // Hot specular filament core line inside amber pill
                                drawRoundRect(
                                    color = Color(0xB8FFF3C4),
                                    topLeft = Offset(cx - w * 0.01f, pillTop + w * 0.015f),
                                    size = Size(w * 0.02f, pillHeight - w * 0.032f),
                                    cornerRadius = CornerRadius(w * 0.01f, w * 0.01f)
                                )
                            }
                        }
                }
            }
        }
    }
}

/**
 * Variation 4.1 Classic: Interactive DLB Auto-Derate Card
 */
@Composable
private fun StudioClassicDlbCard(
    isDlbEnabled: Boolean,
    lowThreshold: Int,
    highThreshold: Int,
    isCharging: Boolean,
    stationAmperes: Int?,
    voltage: Int?,
    isOnline: Boolean,
    selectedAmperes: Int,
    voltageMaxAmperage: Int,
    stationLowVoltageStop: Boolean,
    onToggleDlb: () -> Unit
) {
    val view = LocalView.current
    // The switch alone shows on/off; the pill shows what auto-current is doing right now.
    val status = resolveAutoCurrentStatus(
        enabled = isDlbEnabled,
        isOnline = isOnline,
        voltage = voltage,
        isCharging = isCharging,
        stationAmperes = stationAmperes,
        lowThreshold = lowThreshold,
        highThreshold = highThreshold,
        maxAmperage = voltageMaxAmperage,
        stationLowVoltageStop = stationLowVoltageStop
    )
    val statusText = if (status == AutoCurrentStatus.Off) null else autoCurrentLabel(status)
    val statusColor = when (status) {
        AutoCurrentStatus.Off -> TextTertiaryDark
        AutoCurrentStatus.NoData -> TextSecondaryDark
        is AutoCurrentStatus.Low, is AutoCurrentStatus.Waiting -> StudioAmberWarn
        AutoCurrentStatus.Ok -> StudioGreen
    }
    val borderColor = if (isDlbEnabled) statusColor.copy(alpha = 0.45f) else StudioPanelBorder

    DashCard(
        tone = WoodTone.MAPLE,
        borderColor = borderColor,
        seed = 3f,
        woodAccent = false,
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clickable {
                    view.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
                    onToggleDlb()
                }
                .padding(horizontal = 14.dp, vertical = 10.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Box(
                        modifier = Modifier
                            .size(7.dp)
                            .clip(CircleShape)
                            .background(statusColor)
                    )
                    Text(
                        text = stringResource(R.string.dlb_title),
                        style = MaterialTheme.typography.labelSmall.copy(
                            fontFamily = FontFamily.Monospace,
                            letterSpacing = 0.6.sp
                        ),
                        color = TextSecondaryDark,
                        fontWeight = FontWeight.Bold
                    )
                }

                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    if (statusText != null) StudioStatusPill(statusText, statusColor)
                    Switch(
                        checked = isDlbEnabled,
                        onCheckedChange = {
                            view.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
                            onToggleDlb()
                        },
                        colors = SwitchDefaults.colors(
                            checkedThumbColor = StudioGreen,
                            checkedTrackColor = LocalDashPalette.current.onBg,
                            checkedBorderColor = StudioGreen.copy(alpha = 0.6f),
                            uncheckedThumbColor = TextTertiaryDark,
                            uncheckedTrackColor = StudioScreenDark,
                            uncheckedBorderColor = StudioScreenBorder
                        ),
                        modifier = Modifier.scale(0.75f)
                    )
                }
            }

        }
    }
}

@Composable
internal fun StudioClassicKwhLimitCard(
    startExpanded: Boolean = false,
    isKwhLimitEnabled: Boolean,
    targetQuotaKwh: Double,
    activeTargetKwh: Double,
    hasActiveSession: Boolean,
    onSelectKwhLimit: (Boolean, Double) -> Unit
) {
    val view = LocalView.current
    val expanded = rememberSaveable { mutableStateOf(startExpanded) }
    val hasActiveQuota = hasActiveSession && activeTargetKwh > 0.0
    val isLimitOn = isKwhLimitEnabled || hasActiveQuota
    val displayKwh = if (hasActiveQuota) activeTargetKwh else targetQuotaKwh

    val statusText = if (isLimitOn) stringResource(R.string.kwh_int, displayKwh.toInt()) else null
    val statusColor = if (isLimitOn) StudioGreen else TextTertiaryDark
    val borderColor = if (isLimitOn) StudioGreen.copy(alpha = 0.45f) else StudioPanelBorder

    DashCard(
        tone = WoodTone.WALNUT,
        borderColor = borderColor,
        seed = 4f,
        woodAccent = false,
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clickable { expanded.value = !expanded.value }
                .padding(horizontal = 14.dp, vertical = 10.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Box(
                        modifier = Modifier
                            .size(7.dp)
                            .clip(CircleShape)
                            .background(statusColor)
                    )
                    Text(
                        text = stringResource(R.string.energy_limit_title),
                        style = MaterialTheme.typography.labelSmall.copy(
                            fontFamily = FontFamily.Monospace,
                            letterSpacing = 0.6.sp
                        ),
                        color = TextSecondaryDark,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        text = if (expanded.value) "▴" else "▾",
                        fontSize = 11.sp,
                        fontFamily = FontFamily.Monospace,
                        color = TextTertiaryDark
                    )
                }

                if (statusText != null) StudioStatusPill(statusText, statusColor)
            }

            if (expanded.value) {
                Spacer(modifier = Modifier.height(10.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = stringResource(R.string.energy_limit_hint),
                        fontSize = 10.sp,
                        fontFamily = FontFamily.Monospace,
                        fontWeight = FontWeight.Bold,
                        color = TextTertiaryDark,
                        modifier = Modifier.weight(1f, fill = false).padding(end = 8.dp)
                    )
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(6.dp))
                            .background(if (!isKwhLimitEnabled) LocalDashPalette.current.offBg else StudioScreenDark)
                            .border(
                                width = 1.dp,
                                color = if (!isKwhLimitEnabled) IndustrialRedBorder else StudioScreenBorder,
                                shape = RoundedCornerShape(6.dp)
                            )
                            .clickable {
                                view.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
                                onSelectKwhLimit(false, targetQuotaKwh)
                            }
                            .padding(horizontal = 8.dp, vertical = 4.dp)
                    ) {
                        Text(
                            text = stringResource(R.string.off),
                            maxLines = 1,
                            softWrap = false,
                            fontSize = 10.sp,
                            fontFamily = FontFamily.Monospace,
                            fontWeight = FontWeight.Bold,
                            color = if (!isKwhLimitEnabled) LocalDashPalette.current.offText else TextSecondaryDark
                        )
                    }
                }

                Spacer(modifier = Modifier.height(8.dp))

                // Fine stepper row (-5, -1, current kWh, +1, +5)
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(8.dp))
                        .dashScreen()
                        .border(1.dp, StudioScreenBorder, RoundedCornerShape(8.dp))
                        .padding(horizontal = 8.dp, vertical = 6.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    listOf(-5, -1).forEach { delta ->
                        Text(
                            text = "$delta",
                            fontSize = 12.sp,
                            fontFamily = FontFamily.Monospace,
                            fontWeight = FontWeight.Black,
                            color = TextSecondaryDark,
                            modifier = Modifier
                                .clip(RoundedCornerShape(6.dp))
                                .clickable {
                                    view.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
                                    val next = (targetQuotaKwh + delta).coerceIn(1.0, 100.0)
                                    onSelectKwhLimit(true, next)
                                }
                                .padding(horizontal = 8.dp, vertical = 4.dp)
                        )
                    }
                    Text(
                        text = stringResource(R.string.kwh_int, targetQuotaKwh.toInt()),
                        fontSize = 14.sp,
                        fontFamily = FontFamily.Monospace,
                        fontWeight = FontWeight.Black,
                        color = if (isKwhLimitEnabled) StudioGreen else TextPrimaryDark
                    )
                    listOf(+1, +5).forEach { delta ->
                        Text(
                            text = "+$delta",
                            fontSize = 12.sp,
                            fontFamily = FontFamily.Monospace,
                            fontWeight = FontWeight.Black,
                            color = StudioGreen,
                            modifier = Modifier
                                .clip(RoundedCornerShape(6.dp))
                                .clickable {
                                    view.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
                                    val next = (targetQuotaKwh + delta).coerceIn(1.0, 100.0)
                                    onSelectKwhLimit(true, next)
                                }
                                .padding(horizontal = 8.dp, vertical = 4.dp)
                        )
                    }
                }

                Spacer(modifier = Modifier.height(8.dp))

                // Quick preset pills
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    listOf(10, 16, 20, 30, 40, 50).forEach { kwh ->
                        val isSelected = isKwhLimitEnabled && targetQuotaKwh.toInt() == kwh
                        Box(
                            modifier = Modifier
                                .weight(1f)
                                .clip(RoundedCornerShape(8.dp))
                                .background(if (isSelected) LocalDashPalette.current.onBg else StudioScreenDark)
                                .border(
                                    width = if (isSelected) 1.5.dp else 1.dp,
                                    color = if (isSelected) StudioGreen else StudioScreenBorder,
                                    shape = RoundedCornerShape(8.dp)
                                )
                                .clickable {
                                    view.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
                                    onSelectKwhLimit(true, kwh.toDouble())
                                }
                                .padding(vertical = 7.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                text = "$kwh",
                                fontSize = 11.sp,
                                fontFamily = FontFamily.Monospace,
                                fontWeight = if (isSelected) FontWeight.Black else FontWeight.Bold,
                                color = if (isSelected) StudioGreen else TextSecondaryDark
                            )
                        }
                    }
                }
            }
        }
    }
}

/**
 * Variation 4.1 Classic: Full-Width Heavy Switch Action Button
 */
@Composable
internal fun StudioClassicTimerCard(
    startExpanded: Boolean = false,
    isDurationTimerEnabled: Boolean,
    targetDurationHours: Int,
    activeTargetDurationMinutes: Int,
    accumulatedChargingMs: Long,
    lastTickAt: Long,
    hasActiveSession: Boolean,
    isChargingActive: Boolean,
    isOutageActive: Boolean,
    onSelectDuration: (Boolean, Int) -> Unit
) {
    val view = LocalView.current
    val expanded = rememberSaveable { mutableStateOf(startExpanded) }
    val nowMs = remember { mutableLongStateOf(System.currentTimeMillis()) }

    LaunchedEffect(
        isChargingActive,
        isOutageActive,
        activeTargetDurationMinutes,
        lastTickAt,
        accumulatedChargingMs
    ) {
        nowMs.longValue = System.currentTimeMillis()
        while (isChargingActive && !isOutageActive && activeTargetDurationMinutes > 0 && lastTickAt > 0L) {
            delay(1000L)
            nowMs.longValue = System.currentTimeMillis()
        }
    }

    val liveDeltaMs = if (isChargingActive && !isOutageActive && activeTargetDurationMinutes > 0 && lastTickAt > 0L) {
        (nowMs.longValue - lastTickAt).coerceAtLeast(0L)
    } else {
        0L
    }
    val targetMs = activeTargetDurationMinutes * 60_000L
    val elapsedMs = (accumulatedChargingMs + liveDeltaMs).coerceAtLeast(0L)
    val remainingMs = (targetMs - elapsedMs).coerceAtLeast(0L)
    val isCountingDown = activeTargetDurationMinutes > 0
    val isTimerOn = isDurationTimerEnabled || isCountingDown

    val statusText = when {
        isCountingDown && (isOutageActive || (hasActiveSession && !isChargingActive)) ->
            stringResource(R.string.timer_paused, formatCountdownHms(remainingMs))
        isCountingDown -> formatCountdownHms(remainingMs)
        isDurationTimerEnabled -> stringResource(R.string.hours_short, targetDurationHours)
        else -> null
    }

    val statusColor = when {
        isCountingDown && (isOutageActive || (hasActiveSession && !isChargingActive)) -> StudioAmberWarn
        isCountingDown -> AmberNixie
        isDurationTimerEnabled -> StudioGreen
        else -> TextTertiaryDark
    }

    val borderColor = if (isCountingDown) AmberNixie.copy(alpha = 0.45f) else StudioPanelBorder

    DashCard(
        tone = WoodTone.WALNUT,
        borderColor = borderColor,
        seed = 5f,
        woodAccent = false,
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clickable { expanded.value = !expanded.value }
                .padding(horizontal = 14.dp, vertical = 10.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Box(
                        modifier = Modifier
                            .size(7.dp)
                            .clip(CircleShape)
                            .background(if (isTimerOn) statusColor else TextTertiaryDark)
                    )
                    Text(
                        text = stringResource(R.string.charge_timer_title),
                        style = MaterialTheme.typography.labelSmall.copy(
                            fontFamily = FontFamily.Monospace,
                            letterSpacing = 0.6.sp
                        ),
                        color = TextSecondaryDark,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        text = if (expanded.value) "▴" else "▾",
                        fontSize = 11.sp,
                        fontFamily = FontFamily.Monospace,
                        color = TextTertiaryDark
                    )
                }

                if (statusText != null) StudioStatusPill(statusText, statusColor)
            }

            if (isCountingDown && targetMs > 0L) {
                Spacer(modifier = Modifier.height(8.dp))
                val progress = (elapsedMs.toFloat() / targetMs.toFloat()).coerceIn(0f, 1f)
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(3.dp)
                        .clip(RoundedCornerShape(2.dp))
                        .dashScreen()
                ) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth(progress)
                            .height(3.dp)
                            .background(statusColor)
                    )
                }
            }

            if (expanded.value) {
                Spacer(modifier = Modifier.height(10.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = stringResource(R.string.charge_timer_hint),
                        fontSize = 10.sp,
                        fontFamily = FontFamily.Monospace,
                        fontWeight = FontWeight.Bold,
                        color = TextTertiaryDark,
                        modifier = Modifier.weight(1f, fill = false).padding(end = 8.dp)
                    )
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(6.dp))
                            .background(if (!isDurationTimerEnabled) LocalDashPalette.current.offBg else StudioScreenDark)
                            .border(
                                width = 1.dp,
                                color = if (!isDurationTimerEnabled) IndustrialRedBorder else StudioScreenBorder,
                                shape = RoundedCornerShape(6.dp)
                            )
                            .clickable {
                                view.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
                                onSelectDuration(false, targetDurationHours)
                            }
                            .padding(horizontal = 8.dp, vertical = 4.dp)
                    ) {
                        Text(
                            text = stringResource(R.string.off),
                            maxLines = 1,
                            softWrap = false,
                            fontSize = 10.sp,
                            fontFamily = FontFamily.Monospace,
                            fontWeight = FontWeight.Bold,
                            color = if (!isDurationTimerEnabled) LocalDashPalette.current.offText else TextSecondaryDark
                        )
                    }
                }

                Spacer(modifier = Modifier.height(8.dp))

                listOf(1..6, 7..12).forEachIndexed { rowIdx, range ->
                    if (rowIdx > 0) Spacer(modifier = Modifier.height(6.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        range.forEach { hours ->
                            val isSelected = isDurationTimerEnabled && targetDurationHours == hours
                            Box(
                                modifier = Modifier
                                    .weight(1f)
                                    .clip(RoundedCornerShape(8.dp))
                                    .background(if (isSelected) LocalDashPalette.current.amberBg else StudioScreenDark)
                                    .border(
                                        width = if (isSelected) 1.5.dp else 1.dp,
                                        color = if (isSelected) AmberNixie else StudioScreenBorder,
                                        shape = RoundedCornerShape(8.dp)
                                    )
                                    .clickable {
                                        view.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
                                        onSelectDuration(true, hours)
                                    }
                                    .padding(vertical = 7.dp),
                                contentAlignment = Alignment.Center
                            ) {
                                Text(
                                    text = stringResource(R.string.hours_short, hours),
                                    fontSize = 11.sp,
                                    fontFamily = FontFamily.Monospace,
                                    fontWeight = if (isSelected) FontWeight.Black else FontWeight.Bold,
                                    color = if (isSelected) AmberNixie else TextSecondaryDark
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

internal fun formatCountdownHms(ms: Long): String {
    val totalSeconds = (ms / 1000L).coerceAtLeast(0L)
    val hours = totalSeconds / 3600L
    val minutes = (totalSeconds % 3600L) / 60L
    val seconds = totalSeconds % 60L
    return "%02d:%02d:%02d".format(hours, minutes, seconds)
}

@Composable
internal fun StudioClassicScheduleCard(
    startExpanded: Boolean = false,
    isScheduleEnabled: Boolean,
    scheduleStartTime: String,
    scheduleStopTime: String,
    onUpdateSchedule: (Boolean, String, String) -> Unit
) {
    val view = LocalView.current
    val expanded = rememberSaveable { mutableStateOf(startExpanded) }
    val draftStart = rememberSaveable(scheduleStartTime) {
        mutableStateOf(scheduleStartTime)
    }
    val draftStop = rememberSaveable(scheduleStopTime) {
        mutableStateOf(scheduleStopTime)
    }

    val statusText = if (isScheduleEnabled) "$scheduleStartTime–$scheduleStopTime" else null
    val statusColor = if (isScheduleEnabled) StudioGreen else TextTertiaryDark
    val borderColor = if (isScheduleEnabled) StudioGreen.copy(alpha = 0.45f) else StudioPanelBorder

    DashCard(
        tone = WoodTone.WALNUT,
        borderColor = borderColor,
        seed = 6f,
        woodAccent = false,
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clickable { expanded.value = !expanded.value }
                .padding(horizontal = 14.dp, vertical = 10.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Box(
                        modifier = Modifier
                            .size(7.dp)
                            .clip(CircleShape)
                            .background(statusColor)
                    )
                    Text(
                        text = stringResource(R.string.schedule_title),
                        style = MaterialTheme.typography.labelSmall.copy(
                            fontFamily = FontFamily.Monospace,
                            letterSpacing = 0.6.sp
                        ),
                        color = TextSecondaryDark,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        text = if (expanded.value) "▴" else "▾",
                        fontSize = 11.sp,
                        fontFamily = FontFamily.Monospace,
                        color = TextTertiaryDark
                    )
                }

                if (statusText != null) StudioStatusPill(statusText, statusColor)
            }

            if (expanded.value) {
                Spacer(modifier = Modifier.height(10.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = stringResource(R.string.schedule_window),
                        fontSize = 10.sp,
                        fontFamily = FontFamily.Monospace,
                        fontWeight = FontWeight.Bold,
                        color = TextTertiaryDark
                    )
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(6.dp))
                            .background(if (!isScheduleEnabled) LocalDashPalette.current.offBg else StudioScreenDark)
                            .border(
                                width = 1.dp,
                                color = if (!isScheduleEnabled) IndustrialRedBorder else StudioScreenBorder,
                                shape = RoundedCornerShape(6.dp)
                            )
                            .clickable {
                                view.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
                                onUpdateSchedule(!isScheduleEnabled, draftStart.value, draftStop.value)
                            }
                            .padding(horizontal = 8.dp, vertical = 4.dp)
                    ) {
                        Text(
                            text = stringResource(if (isScheduleEnabled) R.string.turn_off else R.string.turn_on),
                            fontSize = 10.sp,
                            fontFamily = FontFamily.Monospace,
                            fontWeight = FontWeight.Bold,
                            color = if (isScheduleEnabled) LocalDashPalette.current.offText else StudioGreen
                        )
                    }
                }

                Spacer(modifier = Modifier.height(8.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    listOf(
                        Triple(stringResource(R.string.schedule_from), draftStart.value) { delta: Int ->
                            draftStart.value = ua.org.electros.app.data.local.AppSettings.shiftScheduleHour(draftStart.value, delta)
                        },
                        Triple(stringResource(R.string.schedule_to), draftStop.value) { delta: Int ->
                            draftStop.value = ua.org.electros.app.data.local.AppSettings.shiftScheduleHour(draftStop.value, delta)
                        }
                    ).forEach { (label, valueText, onStep) ->
                        Row(
                            modifier = Modifier
                                .weight(1f)
                                .clip(RoundedCornerShape(8.dp))
                                .dashScreen()
                                .border(1.dp, StudioScreenBorder, RoundedCornerShape(8.dp))
                                .padding(horizontal = 8.dp, vertical = 6.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = "−",
                                fontSize = 16.sp,
                                fontFamily = FontFamily.Monospace,
                                fontWeight = FontWeight.Black,
                                color = TextSecondaryDark,
                                modifier = Modifier
                                    .clickable {
                                        view.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
                                        onStep(-1)
                                    }
                                    .padding(horizontal = 4.dp)
                            )
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                Text(
                                    text = label,
                                    fontSize = 9.sp,
                                    fontFamily = FontFamily.Monospace,
                                    color = TextTertiaryDark
                                )
                                Text(
                                    text = valueText,
                                    fontSize = 13.sp,
                                    fontFamily = FontFamily.Monospace,
                                    fontWeight = FontWeight.Black,
                                    color = AmberNixie
                                )
                            }
                            Text(
                                text = "+",
                                fontSize = 16.sp,
                                fontFamily = FontFamily.Monospace,
                                fontWeight = FontWeight.Black,
                                color = TextSecondaryDark,
                                modifier = Modifier
                                    .clickable {
                                        view.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
                                        onStep(+1)
                                    }
                                    .padding(horizontal = 4.dp)
                            )
                        }
                    }
                }

                Spacer(modifier = Modifier.height(8.dp))

                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(8.dp))
                        .background(LocalDashPalette.current.onBg)
                        .border(1.dp, StudioGreen.copy(alpha = 0.6f), RoundedCornerShape(8.dp))
                        .clickable {
                            view.performHapticFeedback(HapticFeedbackConstants.CONTEXT_CLICK)
                            onUpdateSchedule(true, draftStart.value, draftStop.value)
                        }
                        .padding(vertical = 8.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = stringResource(R.string.set_schedule, draftStart.value, draftStop.value),
                        fontSize = 11.sp,
                        fontFamily = FontFamily.Monospace,
                        fontWeight = FontWeight.Black,
                        color = StudioGreen
                    )
                }
            }
        }
    }
}

@Composable
private fun StudioClassicActionButton(
    isCharging: Boolean,
    isBusy: Boolean,
    isKwhLimitEnabled: Boolean,
    targetQuotaKwh: Double,
    isDurationTimerEnabled: Boolean,
    targetDurationHours: Int,
    onStart: () -> Unit,
    onStop: () -> Unit
) {
    if (isCharging) {
        // Red Stop Button
        Button(
            onClick = onStop,
            enabled = !isBusy,
            shape = RoundedCornerShape(12.dp),
            colors = ButtonDefaults.buttonColors(
                containerColor = IndustrialRed,
                contentColor = Color.White,
                disabledContainerColor = StudioPanelDark,
                disabledContentColor = TextTertiaryDark
            ),
            border = ButtonDefaults.outlinedButtonBorder().copy(
                brush = Brush.linearGradient(listOf(IndustrialRedBorder, IndustrialRedBorder))
            ),
            modifier = Modifier
                .fillMaxWidth()
                .height(48.dp)
        ) {
            Text(
                text = stringResource(if (isBusy) R.string.stopping else R.string.stop),
                fontWeight = FontWeight.Black,
                fontFamily = FontFamily.Monospace,
                fontSize = 13.sp,
                letterSpacing = 1.sp
            )
        }
    } else {
        // Start Charge Button
        val palette = LocalDashPalette.current
        val startShape = RoundedCornerShape(12.dp)
        Button(
            onClick = onStart,
            enabled = !isBusy,
            shape = startShape,
            colors = ButtonDefaults.buttonColors(
                containerColor = palette.startBg,
                contentColor = palette.startText,
                disabledContainerColor = if (palette.isWood) Color.Transparent else StudioPanelDark,
                disabledContentColor = if (palette.isWood) palette.startText.copy(alpha = 0.6f) else TextTertiaryDark
            ),
            border = if (palette.isWood) null else ButtonDefaults.outlinedButtonBorder().copy(
                brush = Brush.linearGradient(listOf(StudioGreen.copy(alpha = 0.6f), StudioGreen.copy(alpha = 0.6f)))
            ),
            modifier = Modifier
                .fillMaxWidth()
                .then(if (palette.isWood) Modifier.copperPlate(startShape) else Modifier)
                .height(if (palette.isWood) 54.dp else 48.dp)
        ) {
            Text(
                text = when {
                    isBusy -> stringResource(R.string.starting)
                    isKwhLimitEnabled && isDurationTimerEnabled ->
                        stringResource(R.string.start_charge_kwh_hours, targetQuotaKwh.toInt(), targetDurationHours)
                    isKwhLimitEnabled ->
                        stringResource(R.string.start_charge_kwh, targetQuotaKwh.toInt())
                    isDurationTimerEnabled -> stringResource(R.string.start_charge_hours, targetDurationHours)
                    else -> stringResource(R.string.start_charge)
                },
                fontWeight = FontWeight.Black,
                fontFamily = FontFamily.Monospace,
                fontSize = 13.sp,
                letterSpacing = 1.sp
            )
        }
    }
}

@Composable
internal fun ErrorMessageCard(message: String) {
    DashCard(
        tone = WoodTone.WALNUT,
        borderColor = IndustrialRed,
        seed = 7f,
        woodAccent = true,
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier.padding(14.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(text = "⚠️", fontSize = 18.sp)
            Text(text = message, fontSize = 12.sp, fontFamily = FontFamily.Monospace, color = TextPrimaryDark)
        }
    }
}

@Composable
internal fun OutageBanner(
    deliveredKwh: Double,
    targetKwh: Double,
    isLimitEnabled: Boolean,
    isAutoResumeEnabled: Boolean,
    isStationOnline: Boolean,
    isBusy: Boolean,
    remainingTimerText: String? = null,
    onCancelOutage: () -> Unit
) {
    val timerSuffix = if (remainingTimerText != null) stringResource(R.string.outage_timer_suffix, remainingTimerText) else ""
    val titleText = if (!isAutoResumeEnabled && isStationOnline) {
        stringResource(R.string.outage_title_restored)
    } else {
        stringResource(R.string.outage_title_lost)
    }
    val delivered = "%.1f".format(deliveredKwh)
    val target = "%.0f".format(targetKwh)
    val bodyText = when {
        !isAutoResumeEnabled && isStationOnline -> stringResource(R.string.outage_body_manual, delivered, timerSuffix)
        !isAutoResumeEnabled -> stringResource(R.string.outage_body_disabled, delivered, timerSuffix)
        remainingTimerText != null && isLimitEnabled ->
            stringResource(R.string.outage_body_timer_limit, delivered, remainingTimerText, target)
        remainingTimerText != null -> stringResource(R.string.outage_body_auto, delivered, timerSuffix)
        isLimitEnabled -> stringResource(R.string.outage_body_limit, delivered, target)
        else -> stringResource(R.string.outage_body_auto, delivered, "")
    }

    DashCard(
        tone = WoodTone.WALNUT,
        borderColor = StudioAmberWarn,
        seed = 8f,
        woodAccent = true,
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier.padding(14.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(text = "⚠️", fontSize = 20.sp)
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                Text(
                    text = titleText,
                    fontWeight = FontWeight.Black,
                    fontFamily = FontFamily.Monospace,
                    fontSize = 12.sp,
                    color = StudioAmberWarn
                )
                Text(
                    text = bodyText,
                    fontSize = 11.sp,
                    color = TextPrimaryDark
                )
                Card(
                    onClick = onCancelOutage,
                    enabled = !isBusy,
                    shape = RoundedCornerShape(8.dp),
                    colors = CardDefaults.cardColors(
                        containerColor = if (isBusy) StudioPanelDark else StudioAmberWarn.copy(alpha = 0.14f)
                    ),
                    border = CardDefaults.outlinedCardBorder().copy(
                        brush = Brush.linearGradient(listOf(StudioAmberWarn, StudioAmberWarn))
                    )
                ) {
                    Text(
                        text = stringResource(if (isBusy) R.string.cancelling else R.string.cancel_session),
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                        fontWeight = FontWeight.Bold,
                        fontFamily = FontFamily.Monospace,
                        fontSize = 11.sp,
                        color = StudioAmberWarn
                    )
                }
            }
        }
    }
}

@Composable
internal fun OfflineWarningCard() {
    DashCard(
        tone = WoodTone.WALNUT,
        borderColor = IndustrialRed,
        seed = 9f,
        woodAccent = true,
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(text = "🔌", fontSize = 20.sp)
            Column {
                Text(
                    text = stringResource(R.string.offline_title),
                    fontWeight = FontWeight.Black,
                    fontFamily = FontFamily.Monospace,
                    fontSize = 12.sp,
                    color = IndustrialRed
                )
                Text(
                    text = stringResource(R.string.offline_body),
                    fontSize = 11.sp,
                    color = TextSecondaryDark
                )
            }
        }
    }
}

/** Live state of a dashboard card. Shown only while the feature is on; the card's own control shows on/off. */
@Composable
private fun StudioStatusPill(text: String, color: Color) {
    // The pill is a dark inlay, so on maple it takes the light walnut colors.
    val onInlay = if (LocalDashPalette.current.tone == WoodTone.MAPLE) {
        when (color) {
            MapleDashPalette.textSecondary -> WalnutDashPalette.textSecondary
            MapleDashPalette.textTertiary -> WalnutDashPalette.textTertiary
            MapleDashPalette.green -> WalnutDashPalette.green
            else -> color
        }
    } else {
        color
    }
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(6.dp))
            .dashScreen()
            .border(1.dp, StudioScreenBorder, RoundedCornerShape(6.dp))
            .padding(horizontal = 8.dp, vertical = 3.dp)
    ) {
        Text(
            text = text,
            fontSize = 11.sp,
            fontFamily = FontFamily.Monospace,
            fontWeight = FontWeight.Black,
            color = onInlay
        )
    }
}
