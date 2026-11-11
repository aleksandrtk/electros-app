package ua.org.electros.app.ui.dashboard

import android.view.HapticFeedbackConstants
import androidx.compose.animation.*
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import ua.org.electros.app.ui.theme.*
import java.util.Locale
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DashboardScreen(
    viewModel: DashboardViewModel,
    modifier: Modifier = Modifier
) {
    val state by viewModel.uiState.collectAsState()
    val context = LocalContext.current

    LaunchedEffect(state.activeSession?.id) {
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
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            val telemetry = state.telemetry

            // 1. Clean Connection Status Plate
            StudioTopHeader(
                isOnline = telemetry?.isOnline ?: false,
                statusText = telemetry?.statusText ?: if (telemetry?.isOnline == true) "В МЕРЕЖІ" else "OFFLINE",
                voltage = telemetry?.voltageCurrent ?: 220
            )

            // Error message banner if any
            if (!state.errorMessage.isNullOrBlank()) {
                ErrorMessageCard(message = state.errorMessage ?: "")
            }

            // Outage / Offline Warning
            if (state.activeSession != null && state.isOutageActive) {
                val remMinText = if (state.activeTargetDurationMinutes > 0) {
                    val remMs = (state.activeTargetDurationMinutes * 60_000L - state.accumulatedChargingMs).coerceAtLeast(0L)
                    formatCountdownHms(remMs)
                } else null
                OutageBanner(
                    deliveredKwh = state.totalDeliveredKwh,
                    targetKwh = state.targetQuotaKwh,
                    isLimitEnabled = state.isKwhLimitEnabled,
                    remainingTimerText = remMinText
                )
            } else if (telemetry != null && !telemetry.isOnline) {
                OfflineWarningCard()
            }

            // 2. Pure RMS Line Voltage Nixie Panel (Variation 4.1 Classic)
            StudioClassicNixieCard(
                voltage = telemetry?.voltageCurrent ?: 0,
                isOnline = telemetry?.isOnline ?: false
            )

            // 3. Central 3D Rotary Potentiometer (Variation 4.1 Classic)
            StudioClassicRotaryKnobCard(
                selectedAmperes = state.selectedAmperes,
                powerKw = telemetry?.powerKw ?: 0.0,
                onAmperageSelected = { viewModel.onAmperageSelected(it) }
            )

            // 4. DLB Auto-Derate Status Indicator Card
            StudioClassicDlbCard(
                isDlbEnabled = state.isVoltageBalancingEnabled,
                lowThreshold = state.voltageLowThreshold,
                voltage = telemetry?.voltageCurrent ?: 0,
                isOnline = telemetry?.isOnline ?: false,
                isCharging = telemetry?.isChargingActive ?: false
            )

            // 5. Charge Timer Status & Duration Picker Card (1-12H with Outage Continuation)
            StudioClassicTimerCard(
                isDurationTimerEnabled = state.isDurationTimerEnabled,
                targetDurationHours = state.targetDurationHours,
                activeTargetDurationMinutes = state.activeTargetDurationMinutes,
                accumulatedChargingMs = state.accumulatedChargingMs,
                lastTickAt = state.lastTickAt,
                hasActiveSession = state.activeSession != null,
                isChargingActive = telemetry?.isChargingActive ?: false,
                isOutageActive = state.isOutageActive,
                isScheduleEnabled = state.isScheduleEnabled,
                scheduleStartTime = state.scheduleStartTime,
                scheduleStopTime = state.scheduleStopTime,
                onSelectDuration = { enabled, hours ->
                    viewModel.setDurationTimer(context, enabled, hours)
                }
            )

            // 6. Heavy Switch Action Button
            StudioClassicActionButton(
                isCharging = telemetry?.isChargingActive ?: false,
                isBusy = state.isStartingOrStopping,
                isDurationTimerEnabled = state.isDurationTimerEnabled,
                targetDurationHours = state.targetDurationHours,
                onStart = { viewModel.onStartClicked(context) },
                onStop = { viewModel.onStopClicked() }
            )
        }
    }
}

@Composable
private fun StudioTopHeader(isOnline: Boolean, statusText: String, voltage: Int) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 2.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Row(
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
                text = "ГАРАЖ • $statusText",
                style = MaterialTheme.typography.labelMedium.copy(
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.Bold
                ),
                color = TextSecondaryDark
            )
        }

        Box(
            modifier = Modifier
                .clip(RoundedCornerShape(8.dp))
                .background(StudioScreenDark)
                .border(1.dp, StudioScreenBorder, RoundedCornerShape(8.dp))
                .padding(horizontal = 10.dp, vertical = 6.dp)
        ) {
            Text(
                text = if (isOnline && voltage > 0) "$voltage VAC" else if (isOnline) "LINE OK" else "0 VAC",
                style = MaterialTheme.typography.labelSmall.copy(
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.Bold
                ),
                color = if (isOnline) AmberNixie else IndustrialRed
            )
        }
    }
}

/**
 * Variation 4.1 Classic: Pure RMS Line Voltage Panel with Nixie Display
 */
@Composable
private fun StudioClassicNixieCard(voltage: Int, isOnline: Boolean) {
    Card(
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = StudioPanelDark),
        border = CardDefaults.outlinedCardBorder().copy(brush = Brush.linearGradient(listOf(StudioPanelBorder, StudioPanelBorder))),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(14.dp)
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

            Spacer(modifier = Modifier.height(8.dp))

            // Cathode Nixie Display Window
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(8.dp))
                    .background(StudioScreenDark)
                    .border(2.dp, StudioScreenBorder, RoundedCornerShape(8.dp))
                    .padding(horizontal = 16.dp, vertical = 12.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.Bottom
                ) {
                    Text(
                        text = if (isOnline) "$voltage.4" else "---.-",
                        fontSize = 32.sp,
                        fontWeight = FontWeight.Black,
                        fontFamily = FontFamily.Monospace,
                        color = AmberNixie,
                        letterSpacing = 2.sp
                    )
                    Text(
                        text = "VOLTS",
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Bold,
                        fontFamily = FontFamily.Monospace,
                        color = AmberNixie
                    )
                }
            }
        }
    }
}

/**
 * Variation 4.1 Classic: Heavy Rotary Current Potentiometer (MK-IV)
 */
@Composable
private fun StudioClassicRotaryKnobCard(
    selectedAmperes: Int,
    powerKw: Double,
    onAmperageSelected: (Int) -> Unit
) {
    val stops = listOf(6, 8, 10, 12, 16)
    val view = LocalView.current

    val currentIndex = stops.indexOf(selectedAmperes).takeIf { it >= 0 } ?: 2 // default 10A

    // Exact symmetrical angles for 5 stops
    val stopAngles = listOf(-110f, -55f, 0f, 55f, 110f)
    fun angleForIndex(idx: Int) = stopAngles[idx.coerceIn(0, stopAngles.lastIndex)]
    val targetAngle = angleForIndex(currentIndex)

    val animatedAngle by animateFloatAsState(
        targetValue = targetAngle,
        animationSpec = spring(stiffness = Spring.StiffnessMediumLow),
        label = "knobRotation"
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
                .padding(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                text = "SELECTABLE CHARGE CURRENT",
                style = MaterialTheme.typography.labelSmall.copy(
                    fontFamily = FontFamily.Monospace,
                    letterSpacing = 0.6.sp
                ),
                color = TextSecondaryDark,
                fontWeight = FontWeight.Bold
            )

            Spacer(modifier = Modifier.height(12.dp))

            // Stage with generous width (234dp) so outer labels sit clear of the 136dp 3D dial
            Box(
                modifier = Modifier
                    .size(234.dp)
                    .pointerInput(stops) {
                        detectDragGestures { change, _ ->
                            change.consume()
                            val center = Offset(size.width / 2f, size.height / 2f)
                            val pos = change.position
                            val dx = pos.x - center.x
                            val dy = pos.y - center.y
                            val angleRad = atan2(dx.toDouble(), -dy.toDouble())
                            var angleDeg = Math.toDegrees(angleRad).toFloat()

                            // Deadzone at bottom
                            if (angleDeg > 130f) {
                                if (selectedAmperes != 16) {
                                    view.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
                                    onAmperageSelected(16)
                                }
                                return@detectDragGestures
                            } else if (angleDeg < -130f) {
                                if (selectedAmperes != 6) {
                                    view.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
                                    onAmperageSelected(6)
                                }
                                return@detectDragGestures
                            }

                            var bestIdx = 0
                            var minDiff = Float.MAX_VALUE
                            stops.indices.forEach { idx ->
                                val stopAngle = angleForIndex(idx)
                                val diff = kotlin.math.abs(angleDeg - stopAngle)
                                if (diff < minDiff) {
                                    minDiff = diff
                                    bestIdx = idx
                                }
                            }
                            if (stops[bestIdx] != selectedAmperes) {
                                view.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
                                onAmperageSelected(stops[bestIdx])
                            }
                        }
                    },
                contentAlignment = Alignment.Center
            ) {
                // Graduation Numbers: 6A, 8A, 10A, 12A, 16A (kept cleanly outside the 136dp 3D dial)
                stops.forEachIndexed { idx, stop ->
                    val a = angleForIndex(idx)
                    val rad = Math.toRadians(a.toDouble())
                    val labelRadius = 92.dp
                    val offsetX = (labelRadius.value * sin(rad)).dp
                    val offsetY = (-labelRadius.value * cos(rad)).dp
                    val isCurrent = idx == currentIndex

                    Text(
                        text = "${stop}A",
                        fontSize = 12.sp,
                        fontWeight = if (isCurrent) FontWeight.Black else FontWeight.Bold,
                        fontFamily = FontFamily.Monospace,
                        color = if (isCurrent) AmberNixie else TextTertiaryDark,
                        modifier = Modifier
                            .offset(x = offsetX, y = offsetY)
                            .clickable {
                                view.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
                                onAmperageSelected(stop)
                            }
                    )
                }

                // Soft directional cast shadow under the floating 3D knob
                Box(
                    modifier = Modifier
                        .offset(x = 3.dp, y = 7.dp)
                        .size(144.dp)
                        .background(
                            brush = Brush.radialGradient(
                                colors = listOf(
                                    Color(0xCC000000),
                                    Color(0x66000000),
                                    Color.Transparent
                                )
                            ),
                            shape = CircleShape
                        )
                )

                // Recessed machined mounting collar on panel
                Box(
                    modifier = Modifier
                        .size(148.dp)
                        .clip(CircleShape)
                        .background(
                            Brush.linearGradient(
                                colors = listOf(Color(0xFF090A0E), Color(0xFF191D26))
                            )
                        )
                        .border(
                            width = 1.dp,
                            brush = Brush.linearGradient(
                                colors = listOf(Color(0xFF060709), Color(0xFF2D3342))
                            ),
                            shape = CircleShape
                        )
                )

                // Heavy 136dp 3D Machined Rotary Dial with bidirectional tap
                Box(
                    modifier = Modifier
                        .size(136.dp)
                        .shadow(18.dp, CircleShape)
                        .clip(CircleShape)
                        .background(
                            Brush.linearGradient(
                                colors = listOf(Color(0xFF4F576A), Color(0xFF20242F), Color(0xFF0B0D12))
                            )
                        )
                        .border(
                            width = 3.dp,
                            brush = Brush.linearGradient(
                                colors = listOf(Color(0xFF7B859C), Color(0xFF2B303D), Color(0xFF0A0C10))
                            ),
                            shape = CircleShape
                        )
                        .pointerInput(stops, currentIndex) {
                            detectTapGestures { offset ->
                                val isRightSide = offset.x >= (size.width / 2f)
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
                        },
                    contentAlignment = Alignment.Center
                ) {
                    // Rotating outer knurled grip skirt + 3D glowing pointer channel
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .rotate(animatedAngle)
                    ) {
                        for (tickIdx in 0 until 24) {
                            Box(
                                modifier = Modifier
                                    .fillMaxSize()
                                    .rotate(tickIdx * 15f)
                            ) {
                                Box(
                                    modifier = Modifier
                                        .align(Alignment.TopCenter)
                                        .padding(top = 3.dp)
                                        .width(2.dp)
                                        .height(6.dp)
                                        .clip(RoundedCornerShape(1.dp))
                                        .background(Color(0xAA0A0C10))
                                )
                            }
                        }

                        // Recessed pointer slot + illuminated Nixie needle
                        Box(
                            modifier = Modifier
                                .align(Alignment.TopCenter)
                                .padding(top = 5.dp)
                                .width(7.dp)
                                .height(25.dp)
                                .clip(RoundedCornerShape(3.5.dp))
                                .background(Color(0xFF090A0E))
                                .padding(1.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Box(
                                modifier = Modifier
                                    .fillMaxSize()
                                    .clip(RoundedCornerShape(2.5.dp))
                                    .background(
                                        Brush.verticalGradient(
                                            colors = listOf(
                                                Color(0xFFFFD180),
                                                AmberNixie,
                                                Color(0xFFD86800)
                                            )
                                        )
                                    )
                            )
                        }
                    }

                    // Raised 3D Domed Cap inside the knurled bevel
                    Box(
                        modifier = Modifier
                            .size(112.dp)
                            .shadow(10.dp, CircleShape)
                            .clip(CircleShape)
                            .background(
                                Brush.linearGradient(
                                    colors = listOf(Color(0xFF424A5B), Color(0xFF232834), Color(0xFF101319))
                                )
                            )
                            .border(
                                width = 1.5.dp,
                                brush = Brush.linearGradient(
                                    colors = listOf(Color(0xFF6B748B), Color(0xFF11141B))
                                ),
                                shape = CircleShape
                            ),
                        contentAlignment = Alignment.Center
                    ) {
                        // Top-left dome specular sheen
                        Box(
                            modifier = Modifier
                                .fillMaxSize()
                                .background(
                                    Brush.verticalGradient(
                                        colors = listOf(
                                            Color(0x2EFFFFFF),
                                            Color(0x0AFFFFFF),
                                            Color.Transparent
                                        )
                                    )
                                )
                        )
                    }

                    // Re-draw glowing tip segment over the raised cap edge so the pointer reads continuously
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .rotate(animatedAngle)
                    ) {
                        Box(
                            modifier = Modifier
                                .align(Alignment.TopCenter)
                                .padding(top = 6.dp)
                                .width(5.dp)
                                .height(22.dp)
                                .clip(RoundedCornerShape(2.5.dp))
                                .background(
                                    Brush.verticalGradient(
                                        colors = listOf(
                                            Color(0xFFFFDA96),
                                            AmberNixie,
                                            Color(0xFFE06D00)
                                        )
                                    )
                                )
                        )
                    }

                    // Non-rotating sunken 3D Nixie core (inverted bevel: dark top-left, lit bottom-right)
                    Box(
                        modifier = Modifier
                            .size(70.dp)
                            .clip(CircleShape)
                            .background(
                                Brush.linearGradient(
                                    colors = listOf(Color(0xFF060709), StudioScreenDark, Color(0xFF171C25))
                                )
                            )
                            .border(
                                width = 2.5.dp,
                                brush = Brush.linearGradient(
                                    colors = listOf(Color(0xFF050608), Color(0xFF474F61))
                                ),
                                shape = CircleShape
                            ),
                        contentAlignment = Alignment.Center
                    ) {
                        AnimatedContent(
                            targetState = selectedAmperes,
                            transitionSpec = {
                                if (targetState > initialState) {
                                    (slideInVertically { height -> height } + fadeIn()).togetherWith(
                                        slideOutVertically { height -> -height } + fadeOut()
                                    )
                                } else {
                                    (slideInVertically { height -> -height } + fadeIn()).togetherWith(
                                        slideOutVertically { height -> height } + fadeOut()
                                    )
                                }
                            },
                            label = "amperageOdometerAnim"
                        ) { amp ->
                            Text(
                                text = "${amp}A",
                                fontSize = 20.sp,
                                fontWeight = FontWeight.Black,
                                fontFamily = FontFamily.Monospace,
                                color = AmberNixie
                            )
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(10.dp))

            // Sub-caption: OUTPUT: 2.18 kW ACTIVE
            Text(
                text = "OUTPUT: %.2f kW ACTIVE".format(powerKw),
                fontSize = 11.sp,
                fontWeight = FontWeight.Bold,
                fontFamily = FontFamily.Monospace,
                color = AmberNixie,
                letterSpacing = 1.sp
            )
        }
    }
}

/**
 * Variation 4.1 Classic: DLB Auto-Derate Status Card
 */
@Composable
private fun StudioClassicDlbCard(
    isDlbEnabled: Boolean,
    lowThreshold: Int,
    voltage: Int,
    isOnline: Boolean,
    isCharging: Boolean
) {
    Card(
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = StudioPanelDark),
        border = CardDefaults.outlinedCardBorder().copy(brush = Brush.linearGradient(listOf(StudioPanelBorder, StudioPanelBorder))),
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 14.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "DLB AUTO-DERATE",
                style = MaterialTheme.typography.labelSmall.copy(
                    fontFamily = FontFamily.Monospace
                ),
                color = TextSecondaryDark,
                fontWeight = FontWeight.Bold
            )

            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                val (statusText, statusColor) = when {
                    !isDlbEnabled -> Pair("DISABLED", TextTertiaryDark)
                    !isOnline || voltage <= 0 -> Pair("STANDBY", TextSecondaryDark)
                    voltage < lowThreshold -> Pair("STABILIZING (<${lowThreshold}V)", StudioAmberWarn)
                    else -> Pair("ENGAGED (>${lowThreshold}V)", StudioGreen)
                }

                Box(
                    modifier = Modifier
                        .size(8.dp)
                        .clip(CircleShape)
                        .background(statusColor)
                )
                Text(
                    text = statusText,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold,
                    fontFamily = FontFamily.Monospace,
                    color = statusColor
                )
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
    isScheduleEnabled: Boolean,
    scheduleStartTime: String,
    scheduleStopTime: String,
    onSelectDuration: (Boolean, Int) -> Unit
) {
    val view = LocalView.current
    val expanded = androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf(false) }
    val nowMs = androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf(System.currentTimeMillis()) }

    androidx.compose.runtime.LaunchedEffect(
        isChargingActive,
        isOutageActive,
        activeTargetDurationMinutes,
        lastTickAt,
        accumulatedChargingMs
    ) {
        nowMs.value = System.currentTimeMillis()
        while (isChargingActive && !isOutageActive && activeTargetDurationMinutes > 0 && lastTickAt > 0L) {
            kotlinx.coroutines.delay(1000L)
            nowMs.value = System.currentTimeMillis()
        }
    }

    val liveDeltaMs = if (isChargingActive && !isOutageActive && activeTargetDurationMinutes > 0 && lastTickAt > 0L) {
        (nowMs.value - lastTickAt).coerceAtLeast(0L)
    } else {
        0L
    }
    val targetMs = activeTargetDurationMinutes * 60_000L
    val elapsedMs = (accumulatedChargingMs + liveDeltaMs).coerceAtLeast(0L)
    val remainingMs = (targetMs - elapsedMs).coerceAtLeast(0L)
    val isCountingDown = activeTargetDurationMinutes > 0
    val isTimerOn = isDurationTimerEnabled || isCountingDown || isScheduleEnabled

    val statusText = when {
        isCountingDown && (isOutageActive || (hasActiveSession && !isChargingActive)) ->
            "PAUSED • ${formatCountdownHms(remainingMs)}"
        isCountingDown ->
            "ON • ${formatCountdownHms(remainingMs)}"
        isDurationTimerEnabled ->
            "ON • ${targetDurationHours}H"
        isScheduleEnabled ->
            "ON • $scheduleStartTime–$scheduleStopTime"
        else ->
            "OFF"
    }

    val statusColor = when {
        isCountingDown && (isOutageActive || (hasActiveSession && !isChargingActive)) -> StudioAmberWarn
        isCountingDown -> AmberNixie
        isDurationTimerEnabled || isScheduleEnabled -> StudioGreen
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
                .padding(horizontal = 14.dp, vertical = 12.dp)
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
private fun StudioClassicActionButton(
    isCharging: Boolean,
    isBusy: Boolean,
    isDurationTimerEnabled: Boolean,
    targetDurationHours: Int,
    onStart: () -> Unit,
    onStop: () -> Unit
) {
    if (isCharging) {
        // Red Emergency Stop Switch Button
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
                text = if (isBusy) "ЗУПИНКА..." else "EMERGENCY STOP",
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
                    isBusy -> "ЗАПУСК..."
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
    remainingTimerText: String? = null
) {
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
            Column {
                Text(
                    text = "ЗНИКЛО СВІТЛО В ГАРАЖІ",
                    fontWeight = FontWeight.Black,
                    fontFamily = FontFamily.Monospace,
                    fontSize = 12.sp,
                    color = StudioAmberWarn
                )
                Text(
                    text = when {
                        remainingTimerText != null && isLimitEnabled ->
                            "Збережено %.1f кВт⋅год (таймер: %s). Зарядка продовжиться автоматично до %.0f кВт⋅год при відновленні живлення.".format(
                                deliveredKwh,
                                remainingTimerText,
                                targetKwh
                            )
                        remainingTimerText != null ->
                            "Збережено %.1f кВт⋅год • Залишок таймера: %s. Зарядка продовжиться автоматично при відновленні живлення.".format(
                                deliveredKwh,
                                remainingTimerText
                            )
                        isLimitEnabled ->
                            "Збережено %.1f кВт⋅год. Зарядка продовжиться автоматично до %.0f кВт⋅год при відновленні живлення.".format(
                                deliveredKwh,
                                targetKwh
                            )
                        else ->
                            "Збережено %.1f кВт⋅год. Зарядка продовжиться автоматично при відновленні живлення.".format(
                                deliveredKwh
                            )
                    },
                    fontSize = 11.sp,
                    color = TextPrimaryDark
                )
            }
        }
    }
}

@Composable
private fun OfflineWarningCard() {
    Card(
        shape = RoundedCornerShape(14.dp),
        colors = CardDefaults.cardColors(containerColor = StudioPanelDark),
        border = CardDefaults.outlinedCardBorder().copy(brush = Brush.linearGradient(listOf(IndustrialRed, IndustrialRed))),
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier.padding(14.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(text = "🔌", fontSize = 20.sp)
            Column {
                Text(
                    text = "СТАНЦІЯ OFFLINE (НЕМАЄ ЖИВЛЕННЯ)",
                    fontWeight = FontWeight.Black,
                    fontFamily = FontFamily.Monospace,
                    fontSize = 12.sp,
                    color = IndustrialRed
                )
                Text(
                    text = "Немає живлення від мережі 220В або станція вимкнена з розетки.",
                    fontSize = 11.sp,
                    color = TextSecondaryDark
                )
            }
        }
    }
}
