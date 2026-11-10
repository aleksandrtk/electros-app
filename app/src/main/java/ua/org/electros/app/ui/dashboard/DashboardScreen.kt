package ua.org.electros.app.ui.dashboard

import android.content.Context
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
        // Top App Bar
        TopHeader(
            isOnline = state.telemetry?.isOnline ?: false,
            voltage = state.telemetry?.voltageCurrent ?: 220
        )

        // Outage Alert Banner (Conditional)
        if (state.isOutageActive) {
            OutageBanner(deliveredKwh = state.totalDeliveredKwh, targetKwh = state.targetQuotaKwh)
        }

        // Hero Charging Dial Card
        HeroDialCard(
            deliveredKwh = state.totalDeliveredKwh,
            targetKwh = state.targetQuotaKwh,
            powerKw = state.telemetry?.powerKw ?: 0.0,
            currentAmperes = state.telemetry?.measuredCurrentAmperes ?: 0.0,
            neutralVolts = state.telemetry?.neutralCurrent ?: 2
        )

        // Segmented Amperage Selector
        AmperageSelectorCard(
            selectedAmperes = state.selectedAmperes,
            onAmperageSelected = { viewModel.onAmperageSelected(it) }
        )

        // Action Buttons: Start / Stop
        ActionButtonsRow(
            isCharging = state.telemetry?.isChargingActive ?: false,
            isBusy = state.isStartingOrStopping,
            onStart = { viewModel.onStartClicked(context) },
            onStop = { viewModel.onStopClicked() }
        )
    }
}

@Composable
private fun TopHeader(isOnline: Boolean, voltage: Int) {
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
                    text = "Гараж • Electros Station",
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
                text = "● $voltage В",
                style = MaterialTheme.typography.labelSmall,
                color = PrimaryNeonGreen
            )
        }
    }
}

@Composable
private fun OutageBanner(deliveredKwh: Double, targetKwh: Double) {
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
                    text = "Автодозарядка активна",
                    fontWeight = FontWeight.Bold,
                    fontSize = 13.sp,
                    color = WarningAmber
                )
                Text(
                    text = "Збережено %.1f кВт⋅год. При відновленні живлення зарядка продовжиться до %.1f кВт⋅год.".format(deliveredKwh, targetKwh),
                    fontSize = 12.sp,
                    color = TextPrimaryDark
                )
            }
        }
    }
}

@Composable
private fun HeroDialCard(
    deliveredKwh: Double,
    targetKwh: Double,
    powerKw: Double,
    currentAmperes: Double,
    neutralVolts: Int
) {
    val progressRatio = if (targetKwh > 0.0) (deliveredKwh / targetKwh).toFloat().coerceIn(0f, 1f) else 0f
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
                    Text(
                        text = "з %.1f кВт⋅год".format(targetKwh),
                        fontSize = 13.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = PrimaryNeonGreen
                    )
                    Text(
                        text = "%d%% виконано".format((animatedProgress * 100).toInt()),
                        fontSize = 12.sp,
                        color = TextSecondaryDark
                    )
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
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(16.dp))
            .background(SurfaceContainerHighDark)
            .padding(vertical = 12.dp, horizontal = 8.dp),
        contentAlignment = Alignment.Center
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(text = label, style = MaterialTheme.typography.labelSmall, color = TextSecondaryDark)
            Spacer(modifier = Modifier.height(4.dp))
            Text(text = value, fontWeight = FontWeight.Bold, fontSize = 16.sp, color = valueColor)
        }
    }
}

@Composable
private fun AmperageSelectorCard(selectedAmperes: Int, onAmperageSelected: (Int) -> Unit) {
    Card(
        shape = RoundedCornerShape(24.dp),
        colors = CardDefaults.cardColors(containerColor = SurfaceContainerDark),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(text = "Струм заряджання", style = MaterialTheme.typography.titleMedium, color = TextPrimaryDark)
                Text(text = "$selectedAmperes A", fontWeight = FontWeight.Bold, color = SecondaryCyan)
            }
            Spacer(modifier = Modifier.height(10.dp))
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(14.dp))
                    .background(SurfaceContainerHighDark)
                    .padding(4.dp),
                horizontalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                listOf(6, 10, 16, 20, 24, 32).forEach { amps ->
                    val isSelected = amps == selectedAmperes
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .clip(RoundedCornerShape(10.dp))
                            .background(if (isSelected) SecondaryContainerDark else Color.Transparent)
                            .clickable { onAmperageSelected(amps) }
                            .padding(vertical = 8.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = "${amps}A",
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Bold,
                            color = if (isSelected) OnSecondaryContainerDark else TextSecondaryDark
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
            enabled = !isCharging && !isBusy,
            shape = RoundedCornerShape(28.dp),
            colors = ButtonDefaults.buttonColors(
                containerColor = PrimaryNeonGreen,
                contentColor = OnPrimaryDark
            ),
            modifier = Modifier
                .weight(1f)
                .height(54.dp)
        ) {
            Text(text = "▶  Почати", fontWeight = FontWeight.Bold, fontSize = 15.sp)
        }

        Button(
            onClick = onStop,
            enabled = isCharging && !isBusy,
            shape = RoundedCornerShape(28.dp),
            colors = ButtonDefaults.buttonColors(
                containerColor = ErrorContainerDark,
                contentColor = Color(0xFFFFB4AB)
            ),
            modifier = Modifier
                .weight(1f)
                .height(54.dp)
        ) {
            Text(text = "⏹  Зупинити", fontWeight = FontWeight.Bold, fontSize = 15.sp)
        }
    }
}

