package ua.org.electros.app.ui.dashboard

import android.view.HapticFeedbackConstants
import androidx.compose.animation.*
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
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
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import ua.org.electros.app.ui.theme.*
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
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            val telemetry = state.telemetry

            // 1. Studio Top Status Plate (MK-IV)
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
                OutageBanner(
                    deliveredKwh = state.totalDeliveredKwh,
                    targetKwh = state.targetQuotaKwh,
                    isLimitEnabled = state.isKwhLimitEnabled
                )
            } else if (telemetry != null && !telemetry.isOnline) {
                OfflineWarningCard()
            }

            // 2. Pure MK-IV RMS Line Voltage Nixie Panel (Variation 4.1 Classic)
            StudioClassicNixieCard(
                voltage = telemetry?.voltageCurrent ?: 0,
                isOnline = telemetry?.isOnline ?: false
            )

            // 3. Central Rotary Potentiometer (Variation 4.1 Classic)
            StudioClassicRotaryKnobCard(
                selectedAmperes = state.selectedAmperes,
                powerKw = telemetry?.powerKw ?: 0.0,
                onAmperageSelected = { viewModel.onAmperageSelected(it) }
            )

            // 4. DLB Auto-Derate Status Indicator Card (Variation 4.1 Classic)
            StudioClassicDlbCard(
                isDlbEnabled = state.isVoltageBalancingEnabled,
                lowThreshold = state.voltageLowThreshold,
                voltage = telemetry?.voltageCurrent ?: 0,
                isOnline = telemetry?.isOnline ?: false,
                isCharging = telemetry?.isChargingActive ?: false
            )

            // 5. Heavy Switch Action Button (Variation 4.1 Classic)
            StudioClassicActionButton(
                isCharging = telemetry?.isChargingActive ?: false,
                isBusy = state.isStartingOrStopping,
                onStart = { viewModel.onStartClicked(context) },
                onStop = { viewModel.onStopClicked() }
            )
        }
    }
}

@Composable
private fun StudioTopHeader(isOnline: Boolean, statusText: String, voltage: Int) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column {
            Text(
                text = "STUDIO CHARGE CONTROLLER",
                style = MaterialTheme.typography.titleMedium.copy(
                    fontFamily = FontFamily.Monospace,
                    letterSpacing = 0.5.sp
                ),
                color = AmberNixie,
                fontWeight = FontWeight.Black
            )
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                Box(
                    modifier = Modifier
                        .size(8.dp)
                        .clip(CircleShape)
                        .background(if (isOnline) StudioGreen else IndustrialRed)
                )
                Text(
                    text = "ГАРАЖ • $statusText",
                    style = MaterialTheme.typography.labelSmall.copy(
                        fontFamily = FontFamily.Monospace
                    ),
                    color = TextSecondaryDark
                )
            }
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

            // Stage with generous width (210dp) so 12A is 100% visible
            Box(
                modifier = Modifier
                    .size(210.dp)
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
                // Graduation Numbers: 6A, 8A, 10A, 12A, 16A
                stops.forEachIndexed { idx, stop ->
                    val a = angleForIndex(idx)
                    val rad = Math.toRadians(a.toDouble())
                    val labelRadius = 78.dp
                    val offsetX = (labelRadius.value * sin(rad)).dp
                    val offsetY = (-labelRadius.value * cos(rad)).dp
                    val isCurrent = idx == currentIndex

                    Text(
                        text = "${stop}A",
                        fontSize = 11.sp,
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

                // Heavy 118dp Rotary Dial with bidirectional tap
                Box(
                    modifier = Modifier
                        .size(118.dp)
                        .shadow(16.dp, CircleShape)
                        .clip(CircleShape)
                        .background(
                            Brush.radialGradient(
                                colors = listOf(Color(0xFF353B4A), Color(0xFF14171E)),
                                center = Offset(60f, 60f)
                            )
                        )
                        .border(4.dp, StudioPanelBorder, CircleShape)
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
                    // Rotating Pointer Line
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .rotate(animatedAngle)
                    ) {
                        Box(
                            modifier = Modifier
                                .align(Alignment.TopCenter)
                                .padding(top = 6.dp)
                                .width(4.dp)
                                .height(16.dp)
                                .clip(RoundedCornerShape(2.dp))
                                .background(AmberNixie)
                        )
                    }

                    // Non-rotating center core with vertical odometer roll animation
                    Box(
                        modifier = Modifier
                            .size(62.dp)
                            .clip(CircleShape)
                            .background(StudioScreenDark)
                            .border(2.dp, StudioScreenBorder, CircleShape),
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
private fun StudioClassicActionButton(
    isCharging: Boolean,
    isBusy: Boolean,
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
                text = if (isBusy) "ЗАПУСК..." else "START CHARGE",
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
private fun OutageBanner(deliveredKwh: Double, targetKwh: Double, isLimitEnabled: Boolean) {
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
                    text = if (isLimitEnabled) {
                        "Збережено %.1f кВт⋅год. Зарядка продовжиться автоматично до %.0f кВт⋅год при відновленні живлення.".format(deliveredKwh, targetKwh)
                    } else {
                        "Збережено %.1f кВт⋅год. Зарядка продовжиться автоматично при відновленні живлення.".format(deliveredKwh)
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
