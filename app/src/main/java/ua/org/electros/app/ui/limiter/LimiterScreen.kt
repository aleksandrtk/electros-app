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
import androidx.compose.ui.graphics.Color
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
                Text(
                    text = "АВТОВИМКНЕННЯ",
                    style = MaterialTheme.typography.labelSmall,
                    color = PrimaryNeonGreen
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = "Ліміт кіловат на сесію",
                    style = MaterialTheme.typography.titleMedium,
                    color = TextPrimaryDark
                )

                Spacer(modifier = Modifier.height(14.dp))

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
                            text = "%.1f кВт⋅год".format(state.targetKwh),
                            fontSize = 28.sp,
                            fontWeight = FontWeight.ExtraBold,
                            color = TextPrimaryDark
                        )
                    }

                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        IconButton(
                            onClick = { viewModel.updateTargetKwh(-5.0) },
                            modifier = Modifier
                                .size(42.dp)
                                .clip(CircleShape)
                                .background(SurfaceContainerHighestDark)
                        ) {
                            Text(text = "−", fontSize = 20.sp, color = TextPrimaryDark, fontWeight = FontWeight.Bold)
                        }

                        IconButton(
                            onClick = { viewModel.updateTargetKwh(5.0) },
                            modifier = Modifier
                                .size(42.dp)
                                .clip(CircleShape)
                                .background(PrimaryNeonGreen)
                        ) {
                            Text(text = "+", fontSize = 20.sp, color = OnPrimaryDark, fontWeight = FontWeight.Bold)
                        }
                    }
                }

                Spacer(modifier = Modifier.height(12.dp))

                // Presets Chips (+10, +20, +30, +50)
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    listOf(10.0, 20.0, 30.0, 50.0).forEach { preset ->
                        val isSelected = state.targetKwh == preset
                        Box(
                            modifier = Modifier
                                .weight(1f)
                                .clip(RoundedCornerShape(14.dp))
                                .background(if (isSelected) PrimaryContainerDark else SurfaceContainerHighDark)
                                .clickable { viewModel.setExactTargetKwh(preset) }
                                .padding(vertical = 10.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                text = "+${preset.toInt()}",
                                fontWeight = FontWeight.Bold,
                                color = if (isSelected) OnPrimaryContainerDark else TextPrimaryDark
                            )
                        }
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
                Text(
                    text = "НІЧНИЙ РОЗКЛАД",
                    style = MaterialTheme.typography.labelSmall,
                    color = SecondaryCyan
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = "Таймер за розкладом",
                    style = MaterialTheme.typography.titleMedium,
                    color = TextPrimaryDark
                )

                Spacer(modifier = Modifier.height(14.dp))

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
                        Text(text = "ПОЧАТОК", style = MaterialTheme.typography.labelSmall, color = TextSecondaryDark)
                        Text(text = state.startTime, fontSize = 22.sp, fontWeight = FontWeight.Bold, color = TextPrimaryDark)
                    }

                    Column(
                        modifier = Modifier
                            .weight(1f)
                            .clip(RoundedCornerShape(16.dp))
                            .background(SurfaceContainerHighDark)
                            .padding(14.dp)
                    ) {
                        Text(text = "КІНЕЦЬ", style = MaterialTheme.typography.labelSmall, color = TextSecondaryDark)
                        Text(text = state.stopTime, fontSize = 22.sp, fontWeight = FontWeight.Bold, color = TextPrimaryDark)
                    }
                }

                Spacer(modifier = Modifier.height(14.dp))

                // Auto Resume Toggle
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "Автовозобновлення після блекауту",
                        fontSize = 13.sp,
                        color = TextPrimaryDark
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
                    Text(text = if (state.isScheduleSaved) "✓ Розклад збережено" else "Задати розклад на станцію", fontWeight = FontWeight.Bold)
                }
            }
        }
    }
}

