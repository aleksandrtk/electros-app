package ua.org.electros.app.ui.dashboard

import android.Manifest
import android.content.pm.PackageManager
import android.view.HapticFeedbackConstants
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
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
import kotlinx.coroutines.delay
import ua.org.electros.app.ui.theme.*
import java.util.Locale
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

    PullToRefreshBox(
        isRefreshing = state.isRefreshing,
        onRefresh = { viewModel.refreshTelemetry() },
        modifier = modifier.fillMaxSize()
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .background(StudioChassisDark)
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
                statusText = telemetry?.statusText ?: if (telemetry?.isOnline == true) "ONLINE" else "OFFLINE",
                isChargingOrSessionActive = isChargingOrSessionActive && !state.isOutageActive,
                isChargingActive = telemetry?.isChargingActive == true,
                actualAmperes = actualAmperes,
                nominalAmperes = state.selectedAmperes,
                activeTargetDurationMinutes = state.activeTargetDurationMinutes,
                accumulatedChargingMs = state.accumulatedChargingMs,
                lastTickAt = state.lastTickAt
            )

            // Error or pending-stop warning banner
            if (!state.errorMessage.isNullOrBlank()) {
                ErrorMessageCard(message = state.errorMessage ?: "")
            } else if (state.isStopPending) {
                ErrorMessageCard(message = "Failed to confirm station stop — retrying stop command.")
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
                isSessionActive = isChargingOrSessionActive,
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
                voltage = telemetry?.voltageCurrent,
                isOnline = telemetry?.isOnline ?: false,
                selectedAmperes = state.selectedAmperes,
                voltageMaxAmperage = state.voltageMaxAmperage,
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
                onStart = {
                    if (ContextCompat.checkSelfPermission(
                            context,
                            Manifest.permission.POST_NOTIFICATIONS
                        ) != PackageManager.PERMISSION_GRANTED
                    ) {
                        notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                    }
                    viewModel.onStartClicked(context)
                },
                onStop = { viewModel.onStopClicked(context) }
            )
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
                    .background(if (isOnline) StudioGreen else IndustrialRed)
            )
            Text(
                text = "GARAGE • ${formatHeaderStatus(statusText, isOnline)}",
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
                .background(StudioScreenDark)
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

private fun formatHeaderStatus(statusText: String, isOnline: Boolean): String {
    val raw = statusText.trim()
    return when {
        !isOnline -> "OFFLINE"
        raw.contains("Чекаю підключення", ignoreCase = true) || raw.contains("Waiting", ignoreCase = true) -> "WAITING FOR VEHICLE"
        raw.contains("Підключено. Немає заряду", ignoreCase = true) -> "CONNECTED • IDLE"
        raw.contains("Заряджання", ignoreCase = true) || raw.contains("Заряджається", ignoreCase = true) || raw.contains("Charging", ignoreCase = true) -> "CHARGING"
        raw.contains("Помилка заряджання", ignoreCase = true) -> "CHARGE ERROR"
        raw.contains("Заборона заряджання", ignoreCase = true) -> "CHARGING FORBIDDEN"
        raw.contains("Напруга нижче", ignoreCase = true) -> "LOW VOLTAGE"
        raw.contains("Помилка зв'язку", ignoreCase = true) -> "COMMUNICATION ERROR"
        raw.contains("Ток витоку", ignoreCase = true) -> "LEAKAGE DETECTED"
        raw.contains("перевищив 40", ignoreCase = true) -> "OVERCURRENT > 40A"
        raw.contains("Станція OFFLINE", ignoreCase = true) || raw.contains("OFFLINE", ignoreCase = true) -> "OFFLINE"
        raw.contains("В МЕРЕЖІ", ignoreCase = true) || raw.contains("Підключено", ignoreCase = true) || raw.contains("ONLINE", ignoreCase = true) -> "ONLINE"
        raw.isNotBlank() -> raw.uppercase(Locale.US)
        else -> "ONLINE"
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

    Card(
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = StudioPanelDark),
        border = CardDefaults.outlinedCardBorder().copy(brush = Brush.linearGradient(listOf(StudioPanelBorder, StudioPanelBorder))),
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
                    text = "LINE VOLTAGE (RMS)",
                    style = MaterialTheme.typography.labelSmall.copy(
                        fontFamily = FontFamily.Monospace,
                        letterSpacing = 0.6.sp
                    ),
                    color = TextSecondaryDark,
                    fontWeight = FontWeight.Bold
                )
                if (isSessionActive) {
                    Text(
                        text = if (isOnline) "SESSION ENERGY" else "STORED ENERGY",
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
                    .background(StudioScreenDark)
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
                        if (isSessionActive) {
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

                    if (!isSessionActive) {
                        Text(
                            text = "VOLTS",
                            fontSize = 14.sp,
                            fontWeight = FontWeight.Bold,
                            fontFamily = FontFamily.Monospace,
                            color = AmberNixie
                        )
                    } else {
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
                                        text = "kWh",
                                        fontSize = 11.sp,
                                        fontWeight = FontWeight.Bold,
                                        fontFamily = FontFamily.Monospace,
                                        color = StudioGreen,
                                        modifier = Modifier.padding(bottom = 2.dp)
                                    )
                                }
                                Text(
                                    text = "Remaining %.2f · %d%%".format(Locale.US, remainingKwh, pct),
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
                                        text = "kWh",
                                        fontSize = 11.sp,
                                        fontWeight = FontWeight.Bold,
                                        fontFamily = FontFamily.Monospace,
                                        color = AmberNixie,
                                        modifier = Modifier.padding(bottom = 2.dp)
                                    )
                                }
                            }
                        }
                    }
                }

                if (isSessionActive && hasKwhTarget) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(4.dp)
                            .clip(RoundedCornerShape(2.dp))
                            .background(Color(0xFF161A22))
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

    val stopAngles = listOf(-114f, -58f, 0f, 58f, 114f)
    fun angleForIndex(idx: Int) = stopAngles[idx.coerceIn(0, stopAngles.lastIndex)]
    val targetAngle = angleForIndex(currentIndex)

    fun findClosestStopIndex(angle: Float): Int {
        var best = 0
        var minDiff = Float.MAX_VALUE
        stopAngles.forEachIndexed { i, a ->
            val diff = kotlin.math.abs(angle - a)
            if (diff < minDiff) {
                minDiff = diff
                best = i
            }
        }
        return best
    }

    var isDragging by remember { mutableStateOf(false) }
    var dragAngle by remember { mutableFloatStateOf(targetAngle) }
    var lastHapticIndex by remember { mutableIntStateOf(currentIndex) }

    LaunchedEffect(targetAngle, isDragging) {
        if (!isDragging) {
            dragAngle = targetAngle
        }
    }

    val animatedAngle by animateFloatAsState(
        targetValue = targetAngle,
        animationSpec = spring(
            dampingRatio = 0.85f,
            stiffness = Spring.StiffnessMedium
        ),
        label = "knobRotation"
    )

    val currentAngle = if (isDragging) dragAngle else animatedAngle

    val stageCenterOffsetY = 10.dp
    val labelOffsets = listOf(
        Pair((-96).dp, 30.dp),  // 6A
        Pair((-96).dp, (-28).dp), // 8A
        Pair(0.dp, (-80).dp),     // 10A
        Pair(96.dp, (-28).dp),    // 12A
        Pair(96.dp, 30.dp)        // 16A
    )

    Card(
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = StudioPanelDark),
        border = CardDefaults.outlinedCardBorder().copy(brush = Brush.linearGradient(listOf(StudioPanelBorder, StudioPanelBorder))),
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
                    text = "CHARGE CURRENT",
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
                        .background(StudioScreenDark)
                        .border(1.dp, StudioScreenBorder, RoundedCornerShape(6.dp))
                        .padding(horizontal = 8.dp, vertical = 2.dp)
                ) {
                    Text(
                        text = "%.2f kW".format(powerKw),
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
                    .height(180.dp),
                contentAlignment = Alignment.Center
            ) {
                Box(
                    modifier = Modifier.offset(y = stageCenterOffsetY),
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
                                color = if (isCurrent) Color(0xFFFF9F1C) else Color(0xFF8E97A8),
                                shadow = if (isCurrent) {
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
                    Box(
                        modifier = Modifier
                            .size(140.dp)
                            .pointerInput(stops, currentIndex) {
                                awaitEachGesture {
                                    val down = awaitFirstDown(requireUnconsumed = false)
                                    val cx = size.width / 2f
                                    val cy = size.height / 2f
                                    val radius = size.width * 0.48f
                                    val startDist = hypot(down.position.x - cx, down.position.y - cy)

                                    // Only interact if the touch starts inside the circular knob well
                                    if (startDist > radius) return@awaitEachGesture

                                    var isDragStarted = false
                                    var totalDragMovement = 0f
                                    val startPos = down.position
                                    var lastTouchAngle = Math.toDegrees(atan2((startPos.x - cx).toDouble(), -(startPos.y - cy).toDouble())).toFloat()
                                    var currentKnobAngle = targetAngle

                                    while (true) {
                                        val event = awaitPointerEvent()
                                        val change = event.changes.firstOrNull { it.id == down.id } ?: break

                                        if (!change.pressed) {
                                            // Finger released
                                            if (isDragStarted) {
                                                change.consume()
                                                isDragging = false
                                                val finalIdx = findClosestStopIndex(dragAngle)
                                                if (finalIdx != currentIndex) {
                                                    view.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
                                                    onAmperageSelected(stops[finalIdx])
                                                } else {
                                                    view.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
                                                }
                                            } else if (totalDragMovement < 8.dp.toPx()) {
                                                // Tap on knob: left half decreases, right half increases
                                                change.consume()
                                                val isRightSide = startPos.x >= cx
                                                val newIdx = if (isRightSide) {
                                                    (currentIndex + 1).coerceAtMost(stops.lastIndex)
                                                } else {
                                                    (currentIndex - 1).coerceAtLeast(0)
                                                }
                                                if (newIdx != currentIndex) {
                                                    view.performHapticFeedback(HapticFeedbackConstants.CONTEXT_CLICK)
                                                    onAmperageSelected(stops[newIdx])
                                                }
                                            }
                                            break
                                        }

                                        val curPos = change.position
                                        val dx = curPos.x - cx
                                        val dy = curPos.y - cy
                                        val distFromStart = hypot(curPos.x - startPos.x, curPos.y - startPos.y)
                                        totalDragMovement += distFromStart

                                        if (!isDragStarted && distFromStart > 6.dp.toPx()) {
                                            isDragStarted = true
                                            isDragging = true
                                            lastHapticIndex = currentIndex
                                            currentKnobAngle = targetAngle
                                            dragAngle = targetAngle
                                        }

                                        if (isDragStarted) {
                                            change.consume()
                                            val currentTouchAngle = Math.toDegrees(atan2(dx.toDouble(), -dy.toDouble())).toFloat()
                                            var delta = currentTouchAngle - lastTouchAngle
                                            if (delta > 180f) delta -= 360f
                                            if (delta < -180f) delta += 360f
                                            lastTouchAngle = currentTouchAngle

                                            // Accumulate angle
                                            currentKnobAngle += delta

                                            // End-stop resistance past -114° and +114°
                                            val boundedAngle = when {
                                                currentKnobAngle > 114f -> 114f + ((currentKnobAngle - 114f) * 0.18f).coerceAtMost(14f)
                                                currentKnobAngle < -114f -> -114f + ((currentKnobAngle - (-114f)) * 0.18f).coerceAtLeast(-14f)
                                                else -> currentKnobAngle
                                            }

                                            // Apply rotational weight/damping (0.72)
                                            dragAngle = dragAngle + (boundedAngle - dragAngle) * 0.72f

                                            val closestIdx = findClosestStopIndex(dragAngle)
                                            if (closestIdx != lastHapticIndex) {
                                                lastHapticIndex = closestIdx
                                                view.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
                                            }
                                        }
                                    }
                                    isDragging = false
                                }
                            },
                        contentAlignment = Alignment.Center
                    ) {
                        Canvas(modifier = Modifier.fillMaxSize()) {
                            val w = size.width
                            val h = size.height
                            val cx = w / 2f
                            val cy = h / 2f
                            val centerOffset = Offset(cx, cy)

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
}

/**
 * Variation 4.1 Classic: Interactive DLB Auto-Derate Card
 */
@Composable
private fun StudioClassicDlbCard(
    isDlbEnabled: Boolean,
    lowThreshold: Int,
    voltage: Int?,
    isOnline: Boolean,
    selectedAmperes: Int,
    voltageMaxAmperage: Int,
    onToggleDlb: () -> Unit
) {
    val view = LocalView.current
    val (statusText, statusColor) = when {
        !isDlbEnabled -> Pair("OFF", TextTertiaryDark)
        !isOnline || voltage == null || voltage <= 0 -> Pair("ON • STANDBY", TextSecondaryDark)
        voltage < lowThreshold -> Pair("ON • STABILIZING (<${lowThreshold}V)", StudioAmberWarn)
        else -> Pair("ON • ENGAGED (>${lowThreshold}V)", StudioGreen)
    }
    val borderColor = if (isDlbEnabled) statusColor.copy(alpha = 0.45f) else StudioPanelBorder

    Card(
        shape = RoundedCornerShape(14.dp),
        colors = CardDefaults.cardColors(containerColor = StudioPanelDark),
        border = CardDefaults.outlinedCardBorder().copy(brush = Brush.linearGradient(listOf(borderColor, borderColor))),
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
                        text = "AUTO-CURRENT",
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
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(6.dp))
                            .background(StudioScreenDark)
                            .border(1.dp, StudioScreenBorder, RoundedCornerShape(6.dp))
                            .padding(horizontal = 8.dp, vertical = 3.dp)
                    ) {
                        Text(
                            text = statusText,
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Black,
                            fontFamily = FontFamily.Monospace,
                            color = statusColor
                        )
                    }
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(6.dp))
                            .background(if (isDlbEnabled) Color(0xFF15281F) else StudioScreenDark)
                            .border(
                                width = 1.dp,
                                color = if (isDlbEnabled) StudioGreen.copy(alpha = 0.6f) else StudioScreenBorder,
                                shape = RoundedCornerShape(6.dp)
                            )
                            .padding(horizontal = 7.dp, vertical = 3.dp)
                    ) {
                        Text(
                            text = if (isDlbEnabled) "ON" else "OFF",
                            fontSize = 10.sp,
                            fontWeight = FontWeight.Bold,
                            fontFamily = FontFamily.Monospace,
                            color = if (isDlbEnabled) StudioGreen else AmberNixie
                        )
                    }
                }
            }

            if (isDlbEnabled && selectedAmperes > voltageMaxAmperage) {
                Text(
                    text = "Max auto-current: ${voltageMaxAmperage}A (selected ${selectedAmperes}A)",
                    style = MaterialTheme.typography.labelSmall,
                    color = StudioAmberWarn
                )
            } else {
                Text(
                    text = if (isDlbEnabled) {
                        "Tap to disable • Derate <${lowThreshold}V, ramp to ${voltageMaxAmperage}A"
                    } else {
                        "Tap to enable voltage-sag protection"
                    },
                    fontSize = 10.sp,
                    color = TextTertiaryDark
                )
            }
        }
    }
}

@Composable
private fun StudioClassicKwhLimitCard(
    isKwhLimitEnabled: Boolean,
    targetQuotaKwh: Double,
    activeTargetKwh: Double,
    hasActiveSession: Boolean,
    onSelectKwhLimit: (Boolean, Double) -> Unit
) {
    val view = LocalView.current
    val expanded = rememberSaveable { mutableStateOf(false) }
    val hasActiveQuota = hasActiveSession && activeTargetKwh > 0.0
    val isLimitOn = isKwhLimitEnabled || hasActiveQuota
    val displayKwh = if (hasActiveQuota) activeTargetKwh else targetQuotaKwh

    val statusText = if (isLimitOn) "ON • ${displayKwh.toInt()} kWh" else "OFF"
    val statusColor = if (isLimitOn) StudioGreen else TextTertiaryDark
    val borderColor = if (isLimitOn) StudioGreen.copy(alpha = 0.45f) else StudioPanelBorder

    Card(
        shape = RoundedCornerShape(14.dp),
        colors = CardDefaults.cardColors(containerColor = StudioPanelDark),
        border = CardDefaults.outlinedCardBorder().copy(brush = Brush.linearGradient(listOf(borderColor, borderColor))),
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
                        text = "ENERGY LIMIT (KWH)",
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

                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(6.dp))
                        .background(StudioScreenDark)
                        .border(1.dp, StudioScreenBorder, RoundedCornerShape(6.dp))
                        .padding(horizontal = 8.dp, vertical = 3.dp)
                ) {
                    Text(
                        text = statusText,
                        fontSize = 11.sp,
                        fontFamily = FontFamily.Monospace,
                        fontWeight = FontWeight.Black,
                        color = statusColor
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
                        text = "AUTO-STOP QUOTA (NO AUTO-START)",
                        fontSize = 10.sp,
                        fontFamily = FontFamily.Monospace,
                        fontWeight = FontWeight.Bold,
                        color = TextTertiaryDark
                    )
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(6.dp))
                            .background(if (!isKwhLimitEnabled) Color(0xFF2B181A) else StudioScreenDark)
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
                            text = "OFF",
                            fontSize = 10.sp,
                            fontFamily = FontFamily.Monospace,
                            fontWeight = FontWeight.Bold,
                            color = if (!isKwhLimitEnabled) Color(0xFFFF8A80) else TextSecondaryDark
                        )
                    }
                }

                Spacer(modifier = Modifier.height(8.dp))

                // Fine stepper row (-5, -1, current kWh, +1, +5)
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(8.dp))
                        .background(StudioScreenDark)
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
                        text = "${targetQuotaKwh.toInt()} kWh",
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
                                .background(if (isSelected) Color(0xFF15281F) else StudioScreenDark)
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
private fun StudioClassicTimerCard(
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
    val expanded = rememberSaveable { mutableStateOf(false) }
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
            "PAUSED • ${formatCountdownHms(remainingMs)}"
        isCountingDown ->
            "ON • ${formatCountdownHms(remainingMs)}"
        isDurationTimerEnabled ->
            "ON • ${targetDurationHours}H"
        else ->
            "OFF"
    }

    val statusColor = when {
        isCountingDown && (isOutageActive || (hasActiveSession && !isChargingActive)) -> StudioAmberWarn
        isCountingDown -> AmberNixie
        isDurationTimerEnabled -> StudioGreen
        else -> TextTertiaryDark
    }

    val borderColor = if (isCountingDown) AmberNixie.copy(alpha = 0.45f) else StudioPanelBorder

    Card(
        shape = RoundedCornerShape(14.dp),
        colors = CardDefaults.cardColors(containerColor = StudioPanelDark),
        border = CardDefaults.outlinedCardBorder().copy(brush = Brush.linearGradient(listOf(borderColor, borderColor))),
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
                        text = "CHARGE TIMER",
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

                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(6.dp))
                        .background(StudioScreenDark)
                        .border(1.dp, StudioScreenBorder, RoundedCornerShape(6.dp))
                        .padding(horizontal = 8.dp, vertical = 3.dp)
                ) {
                    Text(
                        text = statusText,
                        fontSize = 11.sp,
                        fontFamily = FontFamily.Monospace,
                        fontWeight = FontWeight.Black,
                        color = statusColor
                    )
                }
            }

            if (isCountingDown && targetMs > 0L) {
                Spacer(modifier = Modifier.height(8.dp))
                val progress = (elapsedMs.toFloat() / targetMs.toFloat()).coerceIn(0f, 1f)
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(3.dp)
                        .clip(RoundedCornerShape(2.dp))
                        .background(StudioScreenDark)
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
                        text = "LIMIT: 1–12H (AUTO-RESUMES IF OFFLINE)",
                        fontSize = 10.sp,
                        fontFamily = FontFamily.Monospace,
                        fontWeight = FontWeight.Bold,
                        color = TextTertiaryDark
                    )
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(6.dp))
                            .background(if (!isDurationTimerEnabled) Color(0xFF2B181A) else StudioScreenDark)
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
                            text = "OFF",
                            fontSize = 10.sp,
                            fontFamily = FontFamily.Monospace,
                            fontWeight = FontWeight.Bold,
                            color = if (!isDurationTimerEnabled) Color(0xFFFF8A80) else TextSecondaryDark
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
                                    .background(if (isSelected) Color(0xFF2B1D0A) else StudioScreenDark)
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
                                    text = "${hours}H",
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

private fun formatCountdownHms(ms: Long): String {
    val totalSeconds = (ms / 1000L).coerceAtLeast(0L)
    val hours = totalSeconds / 3600L
    val minutes = (totalSeconds % 3600L) / 60L
    val seconds = totalSeconds % 60L
    return "%02d:%02d:%02d".format(hours, minutes, seconds)
}

@Composable
private fun StudioClassicScheduleCard(
    isScheduleEnabled: Boolean,
    scheduleStartTime: String,
    scheduleStopTime: String,
    onUpdateSchedule: (Boolean, String, String) -> Unit
) {
    val view = LocalView.current
    val expanded = rememberSaveable { mutableStateOf(false) }
    val draftStart = rememberSaveable(scheduleStartTime) {
        mutableStateOf(scheduleStartTime)
    }
    val draftStop = rememberSaveable(scheduleStopTime) {
        mutableStateOf(scheduleStopTime)
    }

    val statusText = if (isScheduleEnabled) "ON • $scheduleStartTime–$scheduleStopTime" else "OFF"
    val statusColor = if (isScheduleEnabled) StudioGreen else TextTertiaryDark
    val borderColor = if (isScheduleEnabled) StudioGreen.copy(alpha = 0.45f) else StudioPanelBorder

    Card(
        shape = RoundedCornerShape(14.dp),
        colors = CardDefaults.cardColors(containerColor = StudioPanelDark),
        border = CardDefaults.outlinedCardBorder().copy(brush = Brush.linearGradient(listOf(borderColor, borderColor))),
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
                        text = "SCHEDULE TIMER",
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

                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(6.dp))
                        .background(StudioScreenDark)
                        .border(1.dp, StudioScreenBorder, RoundedCornerShape(6.dp))
                        .padding(horizontal = 8.dp, vertical = 3.dp)
                ) {
                    Text(
                        text = statusText,
                        fontSize = 11.sp,
                        fontFamily = FontFamily.Monospace,
                        fontWeight = FontWeight.Black,
                        color = statusColor
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
                        text = "DAILY WINDOW",
                        fontSize = 10.sp,
                        fontFamily = FontFamily.Monospace,
                        fontWeight = FontWeight.Bold,
                        color = TextTertiaryDark
                    )
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(6.dp))
                            .background(if (!isScheduleEnabled) Color(0xFF2B181A) else StudioScreenDark)
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
                            text = if (isScheduleEnabled) "TURN OFF" else "TURN ON",
                            fontSize = 10.sp,
                            fontFamily = FontFamily.Monospace,
                            fontWeight = FontWeight.Bold,
                            color = if (isScheduleEnabled) Color(0xFFFF8A80) else StudioGreen
                        )
                    }
                }

                Spacer(modifier = Modifier.height(8.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    listOf(
                        Triple("FROM", draftStart.value) { delta: Int ->
                            draftStart.value = ua.org.electros.app.data.local.AppSettings.shiftScheduleHour(draftStart.value, delta)
                        },
                        Triple("TO", draftStop.value) { delta: Int ->
                            draftStop.value = ua.org.electros.app.data.local.AppSettings.shiftScheduleHour(draftStop.value, delta)
                        }
                    ).forEach { (label, valueText, onStep) ->
                        Row(
                            modifier = Modifier
                                .weight(1f)
                                .clip(RoundedCornerShape(8.dp))
                                .background(StudioScreenDark)
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
                        .background(Color(0xFF15281F))
                        .border(1.dp, StudioGreen.copy(alpha = 0.6f), RoundedCornerShape(8.dp))
                        .clickable {
                            view.performHapticFeedback(HapticFeedbackConstants.CONTEXT_CLICK)
                            onUpdateSchedule(true, draftStart.value, draftStop.value)
                        }
                        .padding(vertical = 8.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = "SET SCHEDULE • ${draftStart.value}–${draftStop.value}",
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
                text = if (isBusy) "STOPPING..." else "STOP",
                fontWeight = FontWeight.Black,
                fontFamily = FontFamily.Monospace,
                fontSize = 13.sp,
                letterSpacing = 1.sp
            )
        }
    } else {
        // Start Charge Button
        Button(
            onClick = onStart,
            enabled = !isBusy,
            shape = RoundedCornerShape(12.dp),
            colors = ButtonDefaults.buttonColors(
                containerColor = Color(0xFF1E2619),
                contentColor = StudioGreen,
                disabledContainerColor = StudioPanelDark,
                disabledContentColor = TextTertiaryDark
            ),
            border = ButtonDefaults.outlinedButtonBorder().copy(
                brush = Brush.linearGradient(listOf(StudioGreen.copy(alpha = 0.6f), StudioGreen.copy(alpha = 0.6f)))
            ),
            modifier = Modifier
                .fillMaxWidth()
                .height(48.dp)
        ) {
            Text(
                text = when {
                    isBusy -> "STARTING..."
                    isKwhLimitEnabled && isDurationTimerEnabled ->
                        "START CHARGE (${targetQuotaKwh.toInt()} kWh • ${targetDurationHours}H)"
                    isKwhLimitEnabled ->
                        "START CHARGE (${targetQuotaKwh.toInt()} kWh)"
                    isDurationTimerEnabled -> "START CHARGE (${targetDurationHours}H)"
                    else -> "START CHARGE"
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
private fun ErrorMessageCard(message: String) {
    Card(
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = StudioPanelDark),
        border = CardDefaults.outlinedCardBorder().copy(brush = Brush.linearGradient(listOf(IndustrialRed, IndustrialRed))),
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
private fun OutageBanner(
    deliveredKwh: Double,
    targetKwh: Double,
    isLimitEnabled: Boolean,
    isAutoResumeEnabled: Boolean,
    isStationOnline: Boolean,
    isBusy: Boolean,
    remainingTimerText: String? = null,
    onCancelOutage: () -> Unit
) {
    val timerSuffix = if (remainingTimerText != null) " • Timer remaining: %s".format(remainingTimerText) else ""
    val titleText = if (!isAutoResumeEnabled && isStationOnline) {
        "POWER RESTORED • AWAITING START"
    } else {
        "GRID POWER LOST"
    }
    val bodyText = when {
        !isAutoResumeEnabled && isStationOnline ->
            "Preserved %.1f kWh%s. Auto-resume is off — press START to continue, or cancel session.".format(
                deliveredKwh,
                timerSuffix
            )
        !isAutoResumeEnabled ->
            "Preserved %.1f kWh%s. Auto-resume is disabled in settings — charging will not resume automatically.".format(
                deliveredKwh,
                timerSuffix
            )
        remainingTimerText != null && isLimitEnabled ->
            "Preserved %.1f kWh (timer: %s). Charging will resume automatically up to %.0f kWh when power returns.".format(
                deliveredKwh,
                remainingTimerText,
                targetKwh
            )
        remainingTimerText != null ->
            "Preserved %.1f kWh%s. Charging will resume automatically when power returns.".format(
                deliveredKwh,
                timerSuffix
            )
        isLimitEnabled ->
            "Preserved %.1f kWh. Charging will resume automatically up to %.0f kWh when power returns.".format(
                deliveredKwh,
                targetKwh
            )
        else ->
            "Preserved %.1f kWh. Charging will resume automatically when power returns.".format(
                deliveredKwh
            )
    }

    Card(
        shape = RoundedCornerShape(14.dp),
        colors = CardDefaults.cardColors(containerColor = StudioPanelDark),
        border = CardDefaults.outlinedCardBorder().copy(brush = Brush.linearGradient(listOf(StudioAmberWarn, StudioAmberWarn))),
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
                        text = if (isBusy) "CANCELLING..." else "CANCEL SESSION",
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
private fun OfflineWarningCard() {
    Card(
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = StudioPanelDark),
        border = CardDefaults.outlinedCardBorder().copy(brush = Brush.linearGradient(listOf(IndustrialRed, IndustrialRed))),
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
                    text = "STATION OFFLINE (NO POWER)",
                    fontWeight = FontWeight.Black,
                    fontFamily = FontFamily.Monospace,
                    fontSize = 12.sp,
                    color = IndustrialRed
                )
                Text(
                    text = "No 220V grid power or station unplugged.",
                    fontSize = 11.sp,
                    color = TextSecondaryDark
                )
            }
        }
    }
}
