package ua.org.electros.app.ui.limiter

import androidx.compose.foundation.background
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import ua.org.electros.app.ui.theme.*

@Composable
fun LimiterScreen(
    viewModel: LimiterViewModel,
    modifier: Modifier = Modifier
) {
    val state by viewModel.uiState.collectAsState()

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(SurfaceDark)
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Text(
            text = "Ліміт та розклад",
            style = MaterialTheme.typography.titleLarge,
            color = TextPrimaryDark
        )

        // Energy Quota Card
        Card(
            shape = RoundedCornerShape(24.dp),
            colors = CardDefaults.cardColors(containerColor = SurfaceContainerDark),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(modifier = Modifier.padding(20.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "АВТОВИМКНЕННЯ",
                            style = MaterialTheme.typography.labelSmall,
                            color = PrimaryNeonGreen
                        )
                        Spacer(modifier = Modifier.height(2.dp))
                        Text(
                            text = "Обмеження за об'ємом (кВт⋅год)",
                            style = MaterialTheme.typography.titleMedium,
                            color = TextPrimaryDark
                        )
                    }

                    Switch(
                        checked = state.isKwhLimitEnabled,
                        onCheckedChange = { viewModel.toggleKwhLimit(it) },
                        colors = SwitchDefaults.colors(
                            checkedThumbColor = OnPrimaryDark,
                            checkedTrackColor = PrimaryNeonGreen
                        )
                    )
                }

                Spacer(modifier = Modifier.height(14.dp))

                if (!state.isKwhLimitEnabled) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(16.dp))
                            .background(SurfaceContainerHighDark)
                            .padding(16.dp)
                    ) {
                        Text(
                            text = "Вимкнено: станція заряджатиме безперервно, доки електромобіль сам не завершить заряд (наприклад, 80% або 100%).",
                            style = MaterialTheme.typography.bodyMedium,
                            color = TextSecondaryDark
                        )
                    }
                } else {
                    // Value Display & Stepper
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(18.dp))
                            .background(SurfaceContainerHighDark)
                            .padding(16.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column {
                            Text(text = "Цільовий об'єм", fontSize = 12.sp, color = TextSecondaryDark)
                            Text(
                                text = "${state.targetKwh.toInt()} кВт⋅год",
                                fontSize = 28.sp,
                                fontWeight = FontWeight.ExtraBold,
                                color = TextPrimaryDark
                            )
                        }

                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            IconButton(
                                onClick = { viewModel.updateTargetKwh(-1.0) },
                                modifier = Modifier
                                    .size(42.dp)
                                    .clip(CircleShape)
                                    .background(SurfaceContainerHighestDark)
                            ) {
                                Text(text = "−", fontSize = 22.sp, color = TextPrimaryDark, fontWeight = FontWeight.Bold)
                            }

                            IconButton(
                                onClick = { viewModel.updateTargetKwh(1.0) },
                                modifier = Modifier
                                    .size(42.dp)
                                    .clip(CircleShape)
                                    .background(PrimaryNeonGreen)
                            ) {
                                Text(text = "+", fontSize = 22.sp, color = OnPrimaryDark, fontWeight = FontWeight.Bold)
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(14.dp))

                    // Continuous Slider with 1 kWh step (1 to 100)
                    Slider(
                        value = state.targetKwh.toFloat(),
                        onValueChange = { viewModel.setTargetKwh(it.toDouble().coerceIn(1.0, 100.0)) },
                        valueRange = 1f..100f,
                        steps = 98,
                        colors = SliderDefaults.colors(
                            thumbColor = PrimaryNeonGreen,
                            activeTrackColor = PrimaryNeonGreen,
                            inactiveTrackColor = SurfaceContainerHighestDark
                        ),
                        modifier = Modifier.fillMaxWidth()
                    )

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(text = "1 кВт⋅год", fontSize = 11.sp, color = TextSecondaryDark)
                        Text(text = "50 кВт⋅год", fontSize = 11.sp, color = TextSecondaryDark)
                        Text(text = "100 кВт⋅год", fontSize = 11.sp, color = TextSecondaryDark)
                    }
                }
            }
        }

        // Night Tariff Scheduler Card
        Card(
            shape = RoundedCornerShape(24.dp),
            colors = CardDefaults.cardColors(containerColor = SurfaceContainerDark),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(modifier = Modifier.padding(20.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "РОЗКЛАД ТАЙМЕРА",
                            style = MaterialTheme.typography.labelSmall,
                            color = SecondaryCyan
                        )
                        Spacer(modifier = Modifier.height(2.dp))
                        Text(
                            text = "Зарядка за розкладом",
                            style = MaterialTheme.typography.titleMedium,
                            color = TextPrimaryDark
                        )
                    }

                    Switch(
                        checked = state.isScheduleEnabled,
                        onCheckedChange = { viewModel.toggleSchedule(it) },
                        colors = SwitchDefaults.colors(
                            checkedThumbColor = OnPrimaryDark,
                            checkedTrackColor = SecondaryCyan
                        )
                    )
                }

                Spacer(modifier = Modifier.height(14.dp))

                if (state.isScheduleEnabled) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Column(
                            modifier = Modifier
                                .weight(1f)
                                .clip(RoundedCornerShape(16.dp))
                                .background(SurfaceContainerHighDark)
                                .padding(14.dp)
                        ) {
                            Text(text = "ЧАС ПОЧАТКУ", style = MaterialTheme.typography.labelSmall, color = TextSecondaryDark)
                            Spacer(modifier = Modifier.height(4.dp))
                            Text(text = state.startTime, fontSize = 22.sp, fontWeight = FontWeight.Bold, color = TextPrimaryDark)
                        }

                        Column(
                            modifier = Modifier
                                .weight(1f)
                                .clip(RoundedCornerShape(16.dp))
                                .background(SurfaceContainerHighDark)
                                .padding(14.dp)
                        ) {
                            Text(text = "ЧАС ЗАВЕРШЕННЯ", style = MaterialTheme.typography.labelSmall, color = TextSecondaryDark)
                            Spacer(modifier = Modifier.height(4.dp))
                            Text(text = state.stopTime, fontSize = 22.sp, fontWeight = FontWeight.Bold, color = TextPrimaryDark)
                        }
                    }

                    Spacer(modifier = Modifier.height(14.dp))
                }

                // Auto Resume Toggle
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "Автоматичне відновлення після зникнення світла",
                        fontSize = 13.sp,
                        color = TextPrimaryDark,
                        modifier = Modifier.weight(1f)
                    )
                    Switch(
                        checked = state.isAutoResumeEnabled,
                        onCheckedChange = { viewModel.toggleAutoResume(it) }
                    )
                }

                Spacer(modifier = Modifier.height(14.dp))

                Button(
                    onClick = { viewModel.applyScheduleToStation() },
                    shape = RoundedCornerShape(28.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = SecondaryContainerDark, contentColor = OnSecondaryContainerDark),
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(50.dp)
                ) {
                    Text(
                        text = if (state.isScheduleSaved) "✓ Налаштування передано на станцію" else "Задати на станцію",
                        fontWeight = FontWeight.Bold
                    )
                }
            }
        }
    }
}
