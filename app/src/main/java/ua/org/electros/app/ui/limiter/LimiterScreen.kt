package ua.org.electros.app.ui.limiter

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

        // Charge Duration Timer Card (1-12 hours with Outage Survival)
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
                            text = "ТАЙМЕР ТРИВАЛОСТІ",
                            style = MaterialTheme.typography.labelSmall,
                            color = AmberNixie
                        )
                        Spacer(modifier = Modifier.height(2.dp))
                        Text(
                            text = "Зарядка на заданий час (1–12 год)",
                            style = MaterialTheme.typography.titleMedium,
                            color = TextPrimaryDark
                        )
                    }

                    Switch(
                        checked = state.isDurationTimerEnabled,
                        onCheckedChange = { viewModel.toggleDurationTimer(it) },
                        colors = SwitchDefaults.colors(
                            checkedThumbColor = OnPrimaryDark,
                            checkedTrackColor = AmberNixie
                        )
                    )
                }

                Spacer(modifier = Modifier.height(14.dp))

                if (!state.isDurationTimerEnabled) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(16.dp))
                            .background(SurfaceContainerHighDark)
                            .padding(16.dp)
                    ) {
                        Text(
                            text = "Вимкнено: таймер тривалості заряджання не заданий.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = TextSecondaryDark
                        )
                    }
                } else {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(16.dp))
                            .background(SurfaceContainerHighDark)
                            .padding(14.dp)
                    ) {
                        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(text = "Обраний час зарядки", fontSize = 13.sp, color = TextSecondaryDark)
                                Text(
                                    text = "${state.targetDurationHours} год",
                                    fontSize = 22.sp,
                                    fontWeight = FontWeight.ExtraBold,
                                    color = AmberNixie
                                )
                            }

                            // 2-row 1..12h quick grid
                            val rows = listOf((1..6).toList(), (7..12).toList())
                            rows.forEach { rowHours ->
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                                ) {
                                    rowHours.forEach { h ->
                                        val isSelected = state.targetDurationHours == h
                                        Box(
                                            modifier = Modifier
                                                .weight(1f)
                                                .height(38.dp)
                                                .clip(RoundedCornerShape(10.dp))
                                                .background(if (isSelected) AmberNixie.copy(alpha = 0.2f) else StudioScreenDark)
                                                .border(
                                                    width = if (isSelected) 1.5.dp else 1.dp,
                                                    color = if (isSelected) AmberNixie else StudioScreenBorder,
                                                    shape = RoundedCornerShape(10.dp)
                                                )
                                                .clickable { viewModel.setTargetDurationHours(h) },
                                            contentAlignment = Alignment.Center
                                        ) {
                                            Text(
                                                text = "${h}г",
                                                fontSize = 13.sp,
                                                fontWeight = if (isSelected) FontWeight.Black else FontWeight.SemiBold,
                                                color = if (isSelected) AmberNixie else TextSecondaryDark
                                            )
                                        }
                                    }
                                }
                            }

                            Text(
                                text = if (state.isAutoResumeEnabled) {
                                    "⚡ Якщо світло зникне (OFFLINE), таймер встане на паузу та автоматично продовжить зарядку після відновлення живлення."
                                } else {
                                    "⏸ Якщо світло зникне (OFFLINE), таймер встане на паузу. Автоматичне відновлення вимкнено — після появи світла прийде сповіщення з кнопкою «Відновити заряд»."
                                },
                                style = MaterialTheme.typography.bodySmall,
                                color = if (state.isAutoResumeEnabled) TextSecondaryDark else WarningAmber,
                                lineHeight = 17.sp
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
                    val endpoints = listOf(
                        Triple("ЧАС ПОЧАТКУ", state.startTime) { delta: Int ->
                            val nextStart = ua.org.electros.app.data.local.AppSettings.shiftScheduleHour(state.startTime, delta)
                            viewModel.setScheduleTimes(nextStart, state.stopTime)
                        },
                        Triple("ЧАС ЗАВЕРШЕННЯ", state.stopTime) { delta: Int ->
                            val nextStop = ua.org.electros.app.data.local.AppSettings.shiftScheduleHour(state.stopTime, delta)
                            viewModel.setScheduleTimes(state.startTime, nextStop)
                        }
                    )

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        endpoints.forEach { (label, valueText, onStep) ->
                            Column(
                                modifier = Modifier
                                    .weight(1f)
                                    .clip(RoundedCornerShape(16.dp))
                                    .background(SurfaceContainerHighDark)
                                    .padding(14.dp)
                            ) {
                                Text(text = label, style = MaterialTheme.typography.labelSmall, color = TextSecondaryDark)
                                Spacer(modifier = Modifier.height(6.dp))
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text(
                                        text = "−",
                                        fontSize = 20.sp,
                                        fontWeight = FontWeight.Bold,
                                        color = SecondaryCyan,
                                        modifier = Modifier
                                            .clickable { onStep(-1) }
                                            .padding(horizontal = 6.dp)
                                    )
                                    Text(text = valueText, fontSize = 20.sp, fontWeight = FontWeight.Bold, color = TextPrimaryDark)
                                    Text(
                                        text = "+",
                                        fontSize = 20.sp,
                                        fontWeight = FontWeight.Bold,
                                        color = SecondaryCyan,
                                        modifier = Modifier
                                            .clickable { onStep(+1) }
                                            .padding(horizontal = 6.dp)
                                    )
                                }
                            }
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

                if (!state.scheduleError.isNullOrBlank()) {
                    Text(
                        text = state.scheduleError.orEmpty(),
                        style = MaterialTheme.typography.bodySmall,
                        color = ErrorRed
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                }

                Button(
                    onClick = { viewModel.applyScheduleToStation() },
                    enabled = !state.isApplyingSchedule,
                    shape = RoundedCornerShape(28.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = SecondaryContainerDark, contentColor = OnSecondaryContainerDark),
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(50.dp)
                ) {
                    Text(
                        text = when {
                            state.isApplyingSchedule -> "Передача на станцію..."
                            state.isScheduleSaved -> "✓ Налаштування передано на станцію"
                            else -> "Задати на станцію"
                        },
                        fontWeight = FontWeight.Bold
                    )
                }
            }
        }

        // Dynamic Load Balancing (DLB) Card
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
                            text = "БАЛАНСУВАННЯ НАВАНТАЖЕННЯ (DLB)",
                            style = MaterialTheme.typography.labelSmall,
                            color = WarningAmber
                        )
                        Spacer(modifier = Modifier.height(2.dp))
                        Text(
                            text = "Адаптація за напругою мережі",
                            style = MaterialTheme.typography.titleMedium,
                            color = TextPrimaryDark
                        )
                    }

                    Switch(
                        checked = state.isVoltageBalancingEnabled,
                        onCheckedChange = { viewModel.toggleVoltageBalancing(it) },
                        colors = SwitchDefaults.colors(
                            checkedThumbColor = OnPrimaryDark,
                            checkedTrackColor = WarningAmber
                        )
                    )
                }

                Spacer(modifier = Modifier.height(14.dp))

                if (!state.isVoltageBalancingEnabled) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(16.dp))
                            .background(SurfaceContainerHighDark)
                            .padding(16.dp)
                    ) {
                        Text(
                            text = "Вимкнено: станція працює на фіксованому струмі без контролю просадки напруги.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = TextSecondaryDark
                        )
                    }
                } else {
                    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(16.dp))
                                .background(SurfaceContainerHighDark)
                                .padding(14.dp)
                        ) {
                            Text(
                                text = "⚡ Якщо напруга падає < ${state.voltageLowThreshold} В — струм поступово знижується до 6 А. Якщо піднімається > ${state.voltageHighThreshold} В — підвищується до максимум ${state.voltageMaxAmperage} А.",
                                style = MaterialTheme.typography.bodySmall,
                                color = TextPrimaryDark,
                                lineHeight = 18.sp
                            )
                        }

                        // Low Voltage Slider (< 185V default)
                        Column {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Text(
                                    text = "Поріг зниження струму",
                                    fontSize = 13.sp,
                                    color = TextSecondaryDark
                                )
                                Text(
                                    text = "< ${state.voltageLowThreshold} В",
                                    fontSize = 13.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = ErrorRed
                                )
                            }
                            Slider(
                                value = state.voltageLowThreshold.toFloat().coerceIn(160f, 200f),
                                onValueChange = { viewModel.setVoltageLowThreshold(it.toInt()) },
                                valueRange = 160f..200f,
                                steps = 39,
                                colors = SliderDefaults.colors(
                                    thumbColor = ErrorRed,
                                    activeTrackColor = ErrorRed,
                                    inactiveTrackColor = SurfaceContainerHighestDark
                                ),
                                modifier = Modifier.fillMaxWidth()
                            )
                        }

                        // High Voltage Slider (> 205V default)
                        Column {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Text(
                                    text = "Поріг повернення струму",
                                    fontSize = 13.sp,
                                    color = TextSecondaryDark
                                )
                                Text(
                                    text = "> ${state.voltageHighThreshold} В",
                                    fontSize = 13.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = PrimaryNeonGreen
                                )
                            }
                            Slider(
                                value = state.voltageHighThreshold.toFloat().coerceIn(195f, 230f),
                                onValueChange = { viewModel.setVoltageHighThreshold(it.toInt()) },
                                valueRange = 195f..230f,
                                steps = 34,
                                colors = SliderDefaults.colors(
                                    thumbColor = PrimaryNeonGreen,
                                    activeTrackColor = PrimaryNeonGreen,
                                    inactiveTrackColor = SurfaceContainerHighestDark
                                ),
                                modifier = Modifier.fillMaxWidth()
                            )
                            Text(
                                text = "Мінімальний розрив між порогами — 10 В",
                                style = MaterialTheme.typography.bodySmall,
                                color = TextSecondaryDark
                            )
                        }

                        // Max Amperage Slider (6A - 16A, default 12A)
                        Column {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Text(
                                    text = "Максимальний струм розгону",
                                    fontSize = 13.sp,
                                    color = TextSecondaryDark
                                )
                                Text(
                                    text = "${state.voltageMaxAmperage} А",
                                    fontSize = 13.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = SecondaryCyan
                                )
                            }
                            Slider(
                                value = state.voltageMaxAmperage.toFloat().coerceIn(6f, 16f),
                                onValueChange = { viewModel.setVoltageMaxAmperage(it.toInt()) },
                                valueRange = 6f..16f,
                                steps = 4,
                                colors = SliderDefaults.colors(
                                    thumbColor = SecondaryCyan,
                                    activeTrackColor = SecondaryCyan,
                                    inactiveTrackColor = SurfaceContainerHighestDark
                                ),
                                modifier = Modifier.fillMaxWidth()
                            )
                            Text(
                                text = "При відновленні напруги DLB не підніме струм вище ${state.voltageMaxAmperage} А.",
                                style = MaterialTheme.typography.bodySmall,
                                color = TextSecondaryDark
                            )
                        }
                    }
                }
            }
        }
    }
}
