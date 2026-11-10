package ua.org.electros.app.ui.dashboard

import android.view.HapticFeedbackConstants
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
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

            // Studio Top Header / Status Plate
            StudioTopHeader(
                isOnline = telemetry?.isOnline ?: false,
                statusText = telemetry?.statusText ?: if (telemetry?.isOnline == true) "В МЕРЕЖІ" else "OFFLINE",
                voltage = telemetry?.voltageCurrent ?: 220
            )

            // Error message banner
            if (!state.errorMessage.isNullOrBlank()) {
                ErrorMessageCard(message = state.errorMessage ?: "")
            }

            // Outage banner or Offline warning
            if (state.activeSession != null && state.isOutageActive) {
                OutageBanner(
                    deliveredKwh = state.totalDeliveredKwh,
                    targetKwh = state.targetQuotaKwh,
                    isLimitEnabled = state.isKwhLimitEnabled
                )
            } else if (telemetry != null && !telemetry.isOnline) {
                OfflineWarningCard()
            }

            // RMS Line Voltage Display with DLB Sag-Watch Headroom Meter
            StudioNixieVoltageCard(
                voltage = telemetry?.voltageCurrent ?: 0,
                isOnline = telemetry?.isOnline ?: false,
                isCharging = telemetry?.isChargingActive ?: false
            )

            // The Centerpiece: Heavy Rotary Current Potentiometer
            StudioRotaryKnobCard(
                selectedAmperes = state.selectedAmperes,
                powerKw = telemetry?.powerKw ?: 0.0,
                deliveredKwh = state.totalDeliveredKwh,
                targetKwh = state.targetQuotaKwh,
                isLimitEnabled = state.isKwhLimitEnabled,
                onAmperageSelected = { viewModel.onAmperageSelected(it) }
            )

            // Studio Realtime Telemetry Grid
            StudioTelemetryGrid(
                powerKw = telemetry?.powerKw ?: 0.0,
                currentAmperes = telemetry?.measuredCurrentAmperes ?: 0.0,
                neutralVolts = telemetry?.neutralCurrent ?: 0
            )

            // Heavy Industrial Action Buttons (Start / Emergency Stop)
            StudioActionButtons(
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
                text = "ELECTROS STUDIO // MK-IV",
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

@Composable
private fun StudioNixieVoltageCard(
    voltage: Int,
    isOnline: Boolean,
    isCharging: Boolean
) {
    val headroom = (voltage - 185).coerceAtLeast(0)

    Card(
        shape = RoundedCornerShape(18.dp),
        colors = CardDefaults.cardColors(containerColor = StudioPanelDark),
        border = CardDefaults.outlinedCardBorder().copy(brush = Brush.linearGradient(listOf(StudioPanelBorder, StudioPanelBorder))),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(14.dp)
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
                        letterSpacing = 1.sp
                    ),
                    color = TextSecondaryDark,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    text = if (isOnline) "HEADROOM: +$headroom V" else "OFFLINE",
                    style = MaterialTheme.typography.labelSmall.copy(
                        fontFamily = FontFamily.Monospace,
                        fontWeight = FontWeight.Bold
                    ),
                    color = if (headroom > 20) AmberNixie else StudioAmberWarn
                )
            }

            Spacer(modifier = Modifier.height(8.dp))

            // Cathode Nixie Display Window
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(10.dp))
                    .background(StudioScreenDark)
                    .border(2.dp, StudioScreenBorder, RoundedCornerShape(10.dp))
                    .padding(horizontal = 14.dp, vertical = 10.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.Bottom
                ) {
                    Text(
                        text = if (isOnline) "$voltage.0" else "---.-",
                        fontSize = 32.sp,
                        fontWeight = FontWeight.Black,
                        fontFamily = FontFamily.Monospace,
                        color = AmberNixie,
                        letterSpacing = 1.sp
                    )
                    Text(
                        text = "VOLTS AC (50Hz)",
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold,
                        fontFamily = FontFamily.Monospace,
                        color = AmberNixieDim
                    )
                }
            }

            Spacer(modifier = Modifier.height(10.dp))

            // Sag-Watch 3-Zone Headroom Track (<185V, 185-205V, >205V)
            Column(modifier = Modifier.fillMaxWidth()) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(text = "<185V CUT", fontSize = 9.sp, fontFamily = FontFamily.Monospace, color = IndustrialRed)
                    Text(text = "205V RECOVER", fontSize = 9.sp, fontFamily = FontFamily.Monospace, color = StudioAmberWarn)
                    Text(text = ">220V NOMINAL", fontSize = 9.sp, fontFamily = FontFamily.Monospace, color = StudioGreen)
                }
                Spacer(modifier = Modifier.height(4.dp))

                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(8.dp)
                        .clip(RoundedCornerShape(4.dp))
                        .background(StudioScreenDark)
                ) {
                    Row(modifier = Modifier.fillMaxSize()) {
                        Box(modifier = Modifier.weight(0.25f).fillMaxHeight().background(IndustrialRed.copy(alpha = 0.8f)))
                        Box(modifier = Modifier.weight(0.35f).fillMaxHeight().background(StudioAmberWarn.copy(alpha = 0.8f)))
                        Box(modifier = Modifier.weight(0.40f).fillMaxHeight().background(StudioGreen.copy(alpha = 0.8f)))
                    }

                    // Live voltage indicator pin
                    val pinRatio = ((voltage - 160f) / (240f - 160f)).coerceIn(0f, 1f)
                    Box(
                        modifier = Modifier
                            .fillMaxHeight()
                            .width(6.dp)
                            .offset(x = 0.dp) // Pin aligned visually
                            .align(Alignment.CenterStart)
                            .offset(x = (pinRatio * 300).dp) // approximate track offset
                            .background(Color.White)
                    )
                }
            }

            Spacer(modifier = Modifier.height(10.dp))

            // DLB Derate Status Lamp
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Box(
                        modifier = Modifier
                            .size(8.dp)
                            .clip(CircleShape)
                            .background(if (voltage >= 185) StudioGreen else StudioAmberWarn)
                    )
                    Text(
                        text = if (voltage >= 185) "DLB MONITOR: СТАБІЛЬНО (>185V)" else "DLB MONITOR: ПРОСАДКА НАПРУГИ",
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold,
                        fontFamily = FontFamily.Monospace,
                        color = if (voltage >= 185) StudioGreen else StudioAmberWarn
                    )
                }

                if (isCharging) {
                    Text(
                        text = "CHARGING ACTIVE",
                        fontSize = 10.sp,
                        fontFamily = FontFamily.Monospace,
                        fontWeight = FontWeight.Bold,
                        color = AmberNixie
                    )
                }
            }
        }
    }
}

@Composable
private fun StudioRotaryKnobCard(
    selectedAmperes: Int,
    powerKw: Double,
    deliveredKwh: Double,
    targetKwh: Double,
    isLimitEnabled: Boolean,
    onAmperageSelected: (Int) -> Unit
) {
    val stops = listOf(6, 8, 10, 12, 16, 20, 25, 32)
    val view = LocalView.current

    val currentIndex = stops.indexOf(selectedAmperes).takeIf { it >= 0 } ?: 2 // default 10A

    // Angles from -135 deg to +135 deg (270 degree span)
    val startAngle = -135f
    val sweepAngle = 270f
    val stepAngle = sweepAngle / (stops.size - 1)

    fun angleForIndex(idx: Int) = startAngle + (idx * stepAngle)
    val targetAngle = angleForIndex(currentIndex)

    val animatedAngle by animateFloatAsState(
        targetValue = targetAngle,
        animationSpec = spring(stiffness = Spring.StiffnessMediumLow),
        label = "knobRotation"
    )

    Card(
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = StudioPanelDark),
        border = CardDefaults.outlinedCardBorder().copy(brush = Brush.linearGradient(listOf(StudioPanelBorder, StudioPanelBorder))),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(18.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                text = "SELECTABLE CHARGE CURRENT",
                style = MaterialTheme.typography.labelSmall.copy(
                    fontFamily = FontFamily.Monospace,
                    letterSpacing = 1.sp
                ),
                color = TextSecondaryDark,
                fontWeight = FontWeight.Bold
            )

            Spacer(modifier = Modifier.height(16.dp))

            // Dial Stage (Knob + Outer Graduations)
            Box(
                modifier = Modifier
                    .size(200.dp)
                    .pointerInput(stops) {
                        detectDragGestures { change, _ ->
                            change.consume()
                            val center = Offset(size.width / 2f, size.height / 2f)
                            val pos = change.position
                            val angleRad = atan2(pos.y - center.y, pos.x - center.x)
                            var angleDeg = Math.toDegrees(angleRad.toDouble()).toFloat() + 90f
                            if (angleDeg > 180f) angleDeg -= 360f

                            // Find nearest stop
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
                // Background graduation tick marks
                Canvas(modifier = Modifier.fillMaxSize()) {
                    val center = Offset(size.width / 2f, size.height / 2f)
                    val outerRadius = size.width / 2f - 8.dp.toPx()
                    val tickLength = 10.dp.toPx()

                    stops.indices.forEach { idx ->
                        val a = angleForIndex(idx) - 90f
                        val rad = Math.toRadians(a.toDouble())
                        val cosVal = cos(rad).toFloat()
                        val sinVal = sin(rad).toFloat()

                        val pStart = Offset(center.x + outerRadius * cosVal, center.y + outerRadius * sinVal)
                        val pEnd = Offset(center.x + (outerRadius - tickLength) * cosVal, center.y + (outerRadius - tickLength) * sinVal)

                        val isCurrent = idx == currentIndex
                        drawLine(
                            color = if (isCurrent) AmberNixie else StudioPanelBorder,
                            start = pStart,
                            end = pEnd,
                            strokeWidth = if (isCurrent) 3.dp.toPx() else 1.5.dp.toPx()
                        )
                    }
                }

                // Surrounding text graduation markers
                stops.forEachIndexed { idx, stop ->
                    val a = angleForIndex(idx) - 90f
                    val rad = Math.toRadians(a.toDouble())
                    val labelRadius = 84.dp
                    val offsetX = (labelRadius.value * cos(rad)).dp
                    val offsetY = (labelRadius.value * sin(rad)).dp
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

                // The Heavy Knob itself (126dp)
                Box(
                    modifier = Modifier
                        .size(126.dp)
                        .shadow(16.dp, CircleShape)
                        .clip(CircleShape)
                        .background(
                            Brush.radialGradient(
                                colors = listOf(Color(0xFF353B4A), Color(0xFF14171E)),
                                center = Offset(60f, 60f)
                            )
                        )
                        .border(4.dp, StudioPanelBorder, CircleShape)
                        .clickable {
                            // Tap knob cycles to next stop
                            view.performHapticFeedback(HapticFeedbackConstants.CONTEXT_CLICK)
                            val nextIdx = (currentIndex + 1) % stops.size
                            onAmperageSelected(stops[nextIdx])
                        },
                    contentAlignment = Alignment.Center
                ) {
                    // Rotating Pointer Marker Line
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

                    // Center Recessed Core Display
                    Box(
                        modifier = Modifier
                            .size(62.dp)
                            .clip(CircleShape)
                            .background(StudioScreenDark)
                            .border(2.dp, StudioScreenBorder, CircleShape),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = "${selectedAmperes}A",
                            fontSize = 20.sp,
                            fontWeight = FontWeight.Black,
                            fontFamily = FontFamily.Monospace,
                            color = AmberNixie
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(10.dp))

            // Realtime Active Output Caption
            Text(
                text = "OUTPUT: %.2f kW ACTIVE".format(powerKw),
                fontSize = 12.sp,
                fontWeight = FontWeight.Bold,
                fontFamily = FontFamily.Monospace,
                color = AmberNixie
            )

            if (isLimitEnabled && targetKwh > 0.0) {
                Text(
                    text = "%.1f з %.0f кВт⋅год (%d%%)".format(deliveredKwh, targetKwh, ((deliveredKwh / targetKwh) * 100).toInt().coerceIn(0, 100)),
                    fontSize = 11.sp,
                    fontFamily = FontFamily.Monospace,
                    color = TextSecondaryDark,
                    modifier = Modifier.padding(top = 2.dp)
                )
            } else {
                Text(
                    text = "%.1f кВт⋅год збережено".format(deliveredKwh),
                    fontSize = 11.sp,
                    fontFamily = FontFamily.Monospace,
                    color = TextSecondaryDark,
                    modifier = Modifier.padding(top = 2.dp)
                )
            }
        }
    }
}

@Composable
private fun StudioTelemetryGrid(
    powerKw: Double,
    currentAmperes: Double,
    neutralVolts: Int
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        StudioMetricCell(
            label = "ПОТУЖНІСТЬ",
            value = "%.1f kW".format(powerKw),
            valueColor = AmberNixie,
            modifier = Modifier.weight(1f)
        )
        StudioMetricCell(
            label = "СТРУМ",
            value = "%.1f A".format(currentAmperes),
            valueColor = TextPrimaryDark,
            modifier = Modifier.weight(1f)
        )
        StudioMetricCell(
            label = "НЕЙТРАЛЬ",
            value = "$neutralVolts V",
            valueColor = if (neutralVolts > 10) StudioAmberWarn else StudioCyan,
            modifier = Modifier.weight(1f)
        )
    }
}

@Composable
private fun StudioMetricCell(
    label: String,
    value: String,
    valueColor: Color,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .clip(RoundedCornerShape(12.dp))
            .background(StudioPanelDark)
            .border(1.dp, StudioPanelBorder, RoundedCornerShape(12.dp))
            .padding(vertical = 12.dp, horizontal = 6.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(
            text = label,
            fontSize = 9.sp,
            fontWeight = FontWeight.Bold,
            fontFamily = FontFamily.Monospace,
            color = TextSecondaryDark
        )
        Spacer(modifier = Modifier.height(4.dp))
        Text(
            text = value,
            fontSize = 15.sp,
            fontWeight = FontWeight.Black,
            fontFamily = FontFamily.Monospace,
            color = valueColor
        )
    }
}

@Composable
private fun StudioActionButtons(
    isCharging: Boolean,
    isBusy: Boolean,
    onStart: () -> Unit,
    onStop: () -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        // Start Charge Button
        Button(
            onClick = onStart,
            enabled = !isCharging && !isBusy,
            shape = RoundedCornerShape(12.dp),
            colors = ButtonDefaults.buttonColors(
                containerColor = Color(0xFF262C1E),
                contentColor = StudioGreen,
                disabledContainerColor = StudioPanelDark,
                disabledContentColor = TextTertiaryDark
            ),
            border = ButtonDefaults.outlinedButtonBorder().copy(brush = Brush.linearGradient(listOf(StudioGreen.copy(alpha = 0.6f), StudioGreen.copy(alpha = 0.6f)))),
            modifier = Modifier
                .weight(1f)
                .height(52.dp)
        ) {
            Text(
                text = if (isCharging) "ЗАРЯДЖАННЯ..." else "ПОЧАТИ ЗАРЯД",
                fontWeight = FontWeight.Black,
                fontFamily = FontFamily.Monospace,
                fontSize = 13.sp,
                letterSpacing = 0.5.sp
            )
        }

        // Emergency Stop Button
        Button(
            onClick = onStop,
            enabled = isCharging && !isBusy,
            shape = RoundedCornerShape(12.dp),
            colors = ButtonDefaults.buttonColors(
                containerColor = IndustrialRed,
                contentColor = Color.White,
                disabledContainerColor = StudioPanelDark,
                disabledContentColor = TextTertiaryDark
            ),
            border = ButtonDefaults.outlinedButtonBorder().copy(brush = Brush.linearGradient(listOf(IndustrialRedBorder, IndustrialRedBorder))),
            modifier = Modifier
                .weight(1f)
                .height(52.dp)
        ) {
            Text(
                text = "EMERGENCY STOP",
                fontWeight = FontWeight.Black,
                fontFamily = FontFamily.Monospace,
                fontSize = 13.sp,
                letterSpacing = 0.5.sp
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
