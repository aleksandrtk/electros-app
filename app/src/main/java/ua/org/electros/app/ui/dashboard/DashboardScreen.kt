package ua.org.electros.app.ui.dashboard

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import ua.org.electros.app.ui.theme.*

@Composable
fun DashboardScreen(
    viewModel: DashboardViewModel,
    modifier: Modifier = Modifier
) {
    val state by viewModel.uiState.collectAsState()
    val context = LocalContext.current

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(SurfaceDark)
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        val telemetry = state.telemetry

        // Top App Bar
        TopHeader(
            isOnline = telemetry?.isOnline ?: false,
            statusText = telemetry?.statusText ?: if (telemetry?.isOnline == true) "Підключено" else "Станція OFFLINE",
            voltage = telemetry?.voltageCurrent ?: 220
        )

        // Error message banner
        if (!state.errorMessage.isNullOrBlank()) {
            ErrorMessageCard(message = state.errorMessage ?: "")
        }

        // Outage banner (only when session was active) or Offline warning
        if (state.activeSession != null && state.isOutageActive) {
            OutageBanner(
                deliveredKwh = state.totalDeliveredKwh,
                targetKwh = state.targetQuotaKwh,
                isLimitEnabled = state.isKwhLimitEnabled
            )
        } else if (telemetry != null && !telemetry.isOnline) {
            OfflineWarningCard()
        }

        // Hero Charging Dial Card
        HeroDialCard(
            deliveredKwh = state.totalDeliveredKwh,
            isKwhLimitEnabled = state.isKwhLimitEnabled,
            targetKwh = state.targetQuotaKwh,
            powerKw = telemetry?.powerKw ?: 0.0,
            currentAmperes = telemetry?.measuredCurrentAmperes ?: 0.0,
            neutralVolts = telemetry?.neutralCurrent ?: 2
        )

        // Segmented Amperage Selector
        AmperageSelectorCard(
            selectedAmperes = state.selectedAmperes,
            onAmperageSelected = { viewModel.onAmperageSelected(it) }
        )

        // Action Buttons: Start / Stop
        ActionButtonsRow(
            isCharging = telemetry?.isChargingActive ?: false,
            hasActiveSession = state.activeSession != null,
            isOnline = telemetry?.isOnline ?: false,
            isBusy = state.isStartingOrStopping,
            onStart = { viewModel.onStartClicked(context) },
            onStop = { viewModel.onStopClicked() }
        )
    }
}

@Composable
private fun TopHeader(isOnline: Boolean, statusText: String, voltage: Int) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column {
            Text(
                text = "Electros",
                style = MaterialTheme.typography.titleLarge,
                color = TextPrimaryDark
            )
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                Box(
                    modifier = Modifier
                        .size(8.dp)
                        .clip(CircleShape)
                        .background(if (isOnline) PrimaryNeonGreen else ErrorRed)
                )
                Text(
                    text = "Гараж • $statusText",
                    style = MaterialTheme.typography.bodyLarge,
                    color = TextSecondaryDark
                )
            }
        }

        Box(
            modifier = Modifier
                .clip(RoundedCornerShape(100.dp))
                .background(SurfaceContainerDark)
                .border(1.dp, OutlineDark, RoundedCornerShape(100.dp))
                .padding(horizontal = 12.dp, vertical = 6.dp)
        ) {
            Text(
                text = if (isOnline) "● $voltage В" else "● 0 В (Вимкнено)",
                style = MaterialTheme.typography.labelSmall,
                color = if (isOnline) PrimaryNeonGreen else ErrorRed
            )
        }
    }
}

@Composable
private fun ErrorMessageCard(message: String) {
    Card(
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = SurfaceContainerHighDark),
        border = CardDefaults.outlinedCardBorder().copy(brush = Brush.linearGradient(listOf(ErrorRed, ErrorRed))),
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier.padding(14.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(text = "⚠️", fontSize = 18.sp)
            Text(text = message, fontSize = 13.sp, color = TextPrimaryDark)
        }
    }
}

@Composable
private fun OutageBanner(deliveredKwh: Double, targetKwh: Double, isLimitEnabled: Boolean) {
    Card(
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = WarningContainerDark),
        border = CardDefaults.outlinedCardBorder().copy(brush = Brush.linearGradient(listOf(WarningAmber, WarningAmber))),
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
                    text = "Зникло світло в гаражі",
                    fontWeight = FontWeight.Bold,
                    fontSize = 13.sp,
                    color = WarningAmber
                )
                Text(
                    text = if (isLimitEnabled) {
                        "Збережено %.1f кВт⋅год. Зарядка продовжиться автоматично до %.0f кВт⋅год при відновленні живлення.".format(deliveredKwh, targetKwh)
                    } else {
                        "Збережено %.1f кВт⋅год. Зарядка продовжиться автоматично при відновленні живлення.".format(deliveredKwh)
                    },
                    fontSize = 12.sp,
                    color = TextPrimaryDark
                )
            }
        }
    }
}

@Composable
private fun OfflineWarningCard() {
    Card(
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = SurfaceContainerHighDark),
        border = CardDefaults.outlinedCardBorder().copy(brush = Brush.linearGradient(listOf(ErrorRed, ErrorRed))),
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
                    text = "Станція вимкнена з розетки (OFFLINE)",
                    fontWeight = FontWeight.Bold,
                    fontSize = 13.sp,
                    color = ErrorRed
                )
                Text(
                    text = "Немає живлення від мережі. Підключіть станцію в розетку перед початком заряджання.",
                    fontSize = 12.sp,
                    color = TextSecondaryDark
                )
            }
        }
    }
}

@Composable
private fun HeroDialCard(
    deliveredKwh: Double,
    isKwhLimitEnabled: Boolean,
    targetKwh: Double,
    powerKw: Double,
    currentAmperes: Double,
    neutralVolts: Int
) {
    val progressRatio = if (isKwhLimitEnabled && targetKwh > 0.0) {
        (deliveredKwh / targetKwh).toFloat().coerceIn(0f, 1f)
    } else {
        if (deliveredKwh > 0.0) 0.5f else 0.05f
    }
    val animatedProgress by animateFloatAsState(targetValue = progressRatio, label = "gaugeProgress")

    Card(
        shape = RoundedCornerShape(28.dp),
        colors = CardDefaults.cardColors(containerColor = SurfaceContainerDark),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(20.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            // Circular Arc Gauge
            Box(
                modifier = Modifier.size(200.dp),
                contentAlignment = Alignment.Center
            ) {
                Canvas(modifier = Modifier.size(180.dp)) {
                    val strokeWidth = 14.dp.toPx()
                    // Track
                    drawArc(
                        color = SurfaceContainerHighestDark,
                        startAngle = 135f,
                        sweepAngle = 270f,
                        useCenter = false,
                        style = Stroke(width = strokeWidth, cap = StrokeCap.Round)
                    )
                    // Progress with Neon Gradient
                    drawArc(
                        brush = Brush.linearGradient(listOf(PrimaryNeonGreen, SecondaryCyan)),
                        startAngle = 135f,
                        sweepAngle = 270f * animatedProgress,
                        useCenter = false,
                        style = Stroke(width = strokeWidth, cap = StrokeCap.Round)
                    )
                }

                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(
                        text = "%.1f".format(deliveredKwh),
                        style = MaterialTheme.typography.displayLarge,
                        color = TextPrimaryDark
                    )
                    if (isKwhLimitEnabled && targetKwh > 0.0) {
                        Text(
                            text = "з %.0f кВт⋅год".format(targetKwh),
                            fontSize = 14.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = PrimaryNeonGreen
                        )
                        Text(
                            text = "%d%% виконано".format((animatedProgress * 100).toInt()),
                            fontSize = 12.sp,
                            color = TextSecondaryDark
                        )
                    } else {
                        Text(
                            text = "кВт⋅год",
                            fontSize = 14.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = PrimaryNeonGreen
                        )
                        Text(
                            text = "Без ліміту (до вимкнення авто)",
                            fontSize = 11.sp,
                            color = TextSecondaryDark
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            // Realtime 3-Column Metrics
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                MetricCell(label = "ПОТУЖНІСТЬ", value = "%.1f кВт".format(powerKw), valueColor = PrimaryNeonGreen, modifier = Modifier.weight(1f))
                MetricCell(label = "СТРУМ", value = "%.1f А".format(currentAmperes), valueColor = TextPrimaryDark, modifier = Modifier.weight(1f))
                MetricCell(label = "НЕЙТРАЛЬ", value = "$neutralVolts В", valueColor = if (neutralVolts > 10) WarningAmber else SecondaryCyan, modifier = Modifier.weight(1f))
            }
        }
    }
}

@Composable
private fun MetricCell(label: String, value: String, valueColor: Color, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier
            .clip(RoundedCornerShape(16.dp))
            .background(SurfaceContainerHighDark)
            .padding(vertical = 12.dp, horizontal = 8.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(text = label, style = MaterialTheme.typography.labelSmall, color = TextSecondaryDark)
        Spacer(modifier = Modifier.height(4.dp))
        Text(text = value, fontSize = 16.sp, fontWeight = FontWeight.Bold, color = valueColor)
    }
}

@Composable
private fun AmperageSelectorCard(
    selectedAmperes: Int,
    onAmperageSelected: (Int) -> Unit
) {
    val amperages = listOf(6, 10, 16, 20, 25, 32)

    Card(
        shape = RoundedCornerShape(24.dp),
        colors = CardDefaults.cardColors(containerColor = SurfaceContainerDark),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                text = "СТРУМ ЗАРЯДЖАННЯ",
                style = MaterialTheme.typography.labelSmall,
                color = TextSecondaryDark
            )
            Spacer(modifier = Modifier.height(10.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                amperages.forEach { amp ->
                    val isSelected = selectedAmperes == amp
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .clip(RoundedCornerShape(12.dp))
                            .background(if (isSelected) PrimaryNeonGreen else SurfaceContainerHighDark)
                            .clickable { onAmperageSelected(amp) }
                            .padding(vertical = 10.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = "${amp}A",
                            fontWeight = FontWeight.Bold,
                            fontSize = 13.sp,
                            color = if (isSelected) OnPrimaryDark else TextPrimaryDark
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun ActionButtonsRow(
    isCharging: Boolean,
    hasActiveSession: Boolean,
    isOnline: Boolean,
    isBusy: Boolean,
    onStart: () -> Unit,
    onStop: () -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Button(
            onClick = onStart,
            enabled = !isCharging && !isBusy && isOnline,
            shape = RoundedCornerShape(20.dp),
            colors = ButtonDefaults.buttonColors(containerColor = PrimaryNeonGreen, contentColor = OnPrimaryDark),
            modifier = Modifier
                .weight(1f)
                .height(54.dp)
        ) {
            Text(text = if (isCharging) "Заряджається..." else "Почати", fontWeight = FontWeight.Bold, fontSize = 16.sp)
        }

        Button(
            onClick = onStop,
            enabled = (isCharging || hasActiveSession) && !isBusy,
            shape = RoundedCornerShape(20.dp),
            colors = ButtonDefaults.buttonColors(containerColor = ErrorRed, contentColor = Color.White),
            modifier = Modifier
                .weight(1f)
                .height(54.dp)
        ) {
            Text(text = "Зупинити", fontWeight = FontWeight.Bold, fontSize = 16.sp)
        }
    }
}
