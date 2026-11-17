package ua.org.electros.app.ui.history

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch
import ua.org.electros.app.data.local.SettingsStore
import ua.org.electros.app.data.local.entity.SessionStatus
import ua.org.electros.app.ui.theme.*
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

@Composable
fun HistoryScreen(
    viewModel: HistoryViewModel,
    modifier: Modifier = Modifier
) {
    val state by viewModel.uiState.collectAsState()
    var showTariffDialog by rememberSaveable { mutableStateOf(false) }
    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()
    val isMonthView = state.period is HistoryPeriod.Month

    // Index of each day header in the list, so tapping a bar can scroll to that day.
    val dayHeaderIndex = remember(state.days) {
        var index = HEADER_ITEM_COUNT
        state.days.associate { day ->
            val at = index
            index += 1 + day.sessions.size
            day.date.dayOfMonth to at
        }
    }

    LazyColumn(
        state = listState,
        modifier = modifier
            .fillMaxSize()
            .background(SurfaceDark),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        item {
            Text(text = "Історія", style = MaterialTheme.typography.titleLarge, color = TextPrimaryDark)
        }
        item {
            PeriodHeader(
                state = state,
                isMonthView = isMonthView,
                onMonth = viewModel::showMonthView,
                onYear = viewModel::showYearView,
                onBack = viewModel::goBack,
                onForward = viewModel::goForward
            )
        }
        item {
            SummaryCard(
                state = state,
                isMonthView = isMonthView,
                onTariffClick = { showTariffDialog = true },
                onBarClick = { index ->
                    if (isMonthView) {
                        dayHeaderIndex[index + 1]?.let { target ->
                            scope.launch { listState.animateScrollToItem(target) }
                        }
                    } else {
                        val year = (state.period as HistoryPeriod.Year).year
                        viewModel.openMonth(java.time.YearMonth.of(year, index + 1))
                    }
                }
            )
        }

        if (state.isEmpty) {
            item {
                Text(
                    text = if (isMonthView) "У цьому місяці заряджань не було" else "У цьому році заряджань не було",
                    fontSize = 14.sp,
                    color = TextSecondaryDark,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth().padding(top = 24.dp)
                )
            }
        } else if (isMonthView) {
            monthSessions(state)
        } else {
            items(state.months, key = { it.month.toString() }) { row ->
                MonthRow(row, onClick = { viewModel.openMonth(row.month) })
            }
        }
    }

    if (showTariffDialog) {
        TariffDialog(
            initial = state.tariffUah,
            onDismiss = { showTariffDialog = false },
            onSave = { value ->
                viewModel.setTariff(value)
                showTariffDialog = false
            }
        )
    }
}

/** Title, period header and summary card come before the day groups in the list. */
private const val HEADER_ITEM_COUNT = 3

private fun LazyListScope.monthSessions(state: HistoryUiState) {
    state.days.forEach { day ->
        item(key = "day-${day.date}") {
            Row(
                modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(text = day.label, fontSize = 13.sp, color = TextSecondaryDark, fontWeight = FontWeight.SemiBold)
                Text(text = formatKwh(day.totalKwh), fontSize = 13.sp, color = TextSecondaryDark)
            }
        }
        items(day.sessions, key = { it.id }) { session -> SessionCard(session) }
    }
}

@Composable
private fun PeriodHeader(
    state: HistoryUiState,
    isMonthView: Boolean,
    onMonth: () -> Unit,
    onYear: () -> Unit,
    onBack: () -> Unit,
    onForward: () -> Unit
) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(12.dp))
                .background(SurfaceContainerDark)
                .padding(3.dp)
        ) {
            SegmentButton("Місяць", selected = isMonthView, onClick = onMonth, modifier = Modifier.weight(1f))
            SegmentButton("Рік", selected = !isMonthView, onClick = onYear, modifier = Modifier.weight(1f))
        }
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            TextButton(onClick = onBack, enabled = state.canGoBack) {
                Text("‹", fontSize = 22.sp, color = if (state.canGoBack) TextPrimaryDark else TextTertiaryDark)
            }
            Text(text = state.periodLabel, fontSize = 16.sp, fontWeight = FontWeight.SemiBold, color = TextPrimaryDark)
            TextButton(onClick = onForward, enabled = state.canGoForward) {
                Text("›", fontSize = 22.sp, color = if (state.canGoForward) TextPrimaryDark else TextTertiaryDark)
            }
        }
    }
}

@Composable
private fun SegmentButton(text: String, selected: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(10.dp))
            .background(if (selected) SurfaceContainerHighestDark else Color.Transparent)
            .clickable(onClick = onClick)
            .padding(vertical = 8.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = text,
            fontSize = 13.sp,
            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
            color = if (selected) TextPrimaryDark else TextSecondaryDark
        )
    }
}

@Composable
private fun SummaryCard(
    state: HistoryUiState,
    isMonthView: Boolean,
    onTariffClick: () -> Unit,
    onBarClick: (Int) -> Unit
) {
    Card(
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = SurfaceContainerDark),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.Bottom) {
                Text(
                    text = "%.1f".format(state.totalKwh),
                    fontSize = 32.sp,
                    fontWeight = FontWeight.Bold,
                    color = TextPrimaryDark
                )
                Text(
                    text = " кВт⋅год",
                    fontSize = 14.sp,
                    color = TextSecondaryDark,
                    modifier = Modifier.padding(bottom = 5.dp)
                )
                Spacer(Modifier.weight(1f))
                state.changeVsPreviousPct?.let { pct ->
                    Text(
                        text = "${if (pct > 0) "+" else if (pct < 0) "−" else ""}${kotlin.math.abs(pct)}% до минулого ${if (isMonthView) "місяця" else "року"}",
                        fontSize = 11.sp,
                        color = TextSecondaryDark,
                        modifier = Modifier.padding(bottom = 6.dp)
                    )
                }
            }
            Spacer(Modifier.height(12.dp))
            Row(modifier = Modifier.fillMaxWidth()) {
                Metric(
                    label = "Вартість",
                    value = state.totalCostUah?.let { formatUah(it) } ?: "Вказати тариф",
                    valueColor = if (state.totalCostUah == null) AmberNixie else TextPrimaryDark,
                    modifier = Modifier.weight(1f).clickable(onClick = onTariffClick)
                )
                Metric(label = "Сесій", value = state.sessionCount.toString(), modifier = Modifier.weight(1f))
                Metric(label = "Час заряду", value = formatDuration(state.chargingMs), modifier = Modifier.weight(1f))
            }
            Spacer(Modifier.height(16.dp))
            BarChart(bars = state.bars, isMonthView = isMonthView, onBarClick = onBarClick)
        }
    }
}

@Composable
private fun Metric(label: String, value: String, modifier: Modifier = Modifier, valueColor: Color = TextPrimaryDark) {
    Column(modifier = modifier) {
        Text(text = label, fontSize = 11.sp, color = TextTertiaryDark)
        Text(text = value, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = valueColor)
    }
}

@Composable
private fun BarChart(bars: List<HistoryBar>, isMonthView: Boolean, onBarClick: (Int) -> Unit) {
    val maxKwh = bars.maxOfOrNull { it.kwh }?.takeIf { it > 0.0 } ?: 1.0
    Row(
        modifier = Modifier.fillMaxWidth().height(80.dp),
        horizontalArrangement = Arrangement.spacedBy(if (isMonthView) 2.dp else 6.dp),
        verticalAlignment = Alignment.Bottom
    ) {
        bars.forEachIndexed { index, bar ->
            val fraction = (bar.kwh / maxKwh).toFloat().coerceIn(0f, 1f)
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxHeight()
                    .clickable(enabled = bar.kwh > 0.0) { onBarClick(index) },
                contentAlignment = Alignment.BottomCenter
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .fillMaxHeight(if (bar.kwh > 0.0) fraction.coerceAtLeast(0.06f) else 0.03f)
                        .clip(RoundedCornerShape(3.dp))
                        .background(
                            when {
                                bar.kwh > 0.0 -> AmberNixie
                                bar.isCurrent -> TextTertiaryDark
                                else -> SurfaceContainerHighestDark
                            }
                        )
                        .then(
                            if (bar.isCurrent && bar.kwh > 0.0) {
                                Modifier.border(1.dp, TextPrimaryDark, RoundedCornerShape(3.dp))
                            } else {
                                Modifier
                            }
                        )
                )
            }
        }
    }
    Spacer(Modifier.height(4.dp))
    if (isMonthView) {
        val labels = listOf(0, 9, 19, bars.lastIndex).filter { it in bars.indices }
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            labels.forEach { Text(text = bars[it].label, fontSize = 10.sp, color = TextTertiaryDark) }
        }
    } else {
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            bars.forEach {
                Text(
                    text = it.label,
                    fontSize = 9.sp,
                    color = TextTertiaryDark,
                    textAlign = TextAlign.Center,
                    maxLines = 1,
                    modifier = Modifier.weight(1f)
                )
            }
        }
    }
}

private val TIME_FORMATTER: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm", Locale.ROOT)

@Composable
private fun SessionCard(session: HistorySessionItem) {
    val zone = ZoneId.systemDefault()
    val start = TIME_FORMATTER.format(Instant.ofEpochMilli(session.startedAt).atZone(zone))
    val end = session.endedAt?.let { TIME_FORMATTER.format(Instant.ofEpochMilli(it).atZone(zone)) } ?: "зараз"

    Card(
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = SurfaceContainerDark),
        border = if (session.isActive) {
            CardDefaults.outlinedCardBorder().copy(
                brush = androidx.compose.ui.graphics.SolidColor(StudioGreen.copy(alpha = 0.6f))
            )
        } else {
            null
        },
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp)) {
            Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = "$start – $end",
                    fontSize = 15.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = TextPrimaryDark,
                    fontFamily = FontFamily.Monospace
                )
                Spacer(Modifier.weight(1f))
                Text(
                    text = if (session.isActive && session.targetKwh > 0.0) {
                        "%.1f / %.0f кВт⋅год".format(session.kwh, session.targetKwh)
                    } else {
                        formatKwh(session.kwh)
                    },
                    fontSize = 15.sp,
                    fontWeight = FontWeight.Bold,
                    color = AmberNixie
                )
            }
            if (session.isActive && session.targetKwh > 0.0) {
                Spacer(Modifier.height(8.dp))
                LinearProgressIndicator(
                    progress = { (session.kwh / session.targetKwh).toFloat().coerceIn(0f, 1f) },
                    color = StudioGreen,
                    trackColor = SurfaceContainerHighestDark,
                    drawStopIndicator = {},
                    modifier = Modifier.fillMaxWidth().height(4.dp).clip(RoundedCornerShape(2.dp))
                )
            }
            Spacer(Modifier.height(6.dp))
            val details = buildList {
                add(statusLabel(session.status))
                add(formatDuration(session.chargingMs))
                session.avgPowerKw?.let { add("%.1f кВт сер.".format(it)) }
                session.costUah?.let { add(formatUah(it)) }
                add("${session.amperage}A")
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = details.joinToString("  •  "),
                    fontSize = 12.sp,
                    color = if (session.isActive) StudioGreen else TextSecondaryDark,
                    modifier = Modifier.weight(1f, fill = false)
                )
            }
            if (session.outageCount > 0) {
                Spacer(Modifier.height(2.dp))
                Text(
                    text = "Відключень світла: ${session.outageCount}",
                    fontSize = 12.sp,
                    color = StudioAmberWarn
                )
            }
        }
    }
}

@Composable
private fun MonthRow(row: HistoryMonthRow, onClick: () -> Unit) {
    Card(
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = SurfaceContainerDark),
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(text = row.label, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = TextPrimaryDark)
                Text(
                    text = listOfNotNull(sessionsLabel(row.sessionCount), row.costUah?.let { formatUah(it) })
                        .joinToString("  •  "),
                    fontSize = 12.sp,
                    color = TextSecondaryDark
                )
            }
            Text(text = formatKwh(row.kwh), fontSize = 15.sp, fontWeight = FontWeight.Bold, color = AmberNixie)
            Text(text = "  ›", fontSize = 18.sp, color = TextTertiaryDark)
        }
    }
}

@Composable
private fun TariffDialog(initial: Double, onDismiss: () -> Unit, onSave: (Double) -> Unit) {
    var text by rememberSaveable { mutableStateOf(if (initial > 0.0) "%.2f".format(Locale.ROOT, initial) else "") }
    val parsed = text.replace(',', '.').toDoubleOrNull()
    val isValid = parsed != null && parsed >= 0.0 && parsed <= SettingsStore.MAX_TARIFF_UAH
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Тариф на електроенергію") },
        text = {
            Column {
                Text(
                    text = "Ціна за 1 кВт⋅год з вашого рахунку. Використовується лише для оцінки вартості.",
                    fontSize = 13.sp,
                    color = TextSecondaryDark
                )
                Spacer(Modifier.height(12.dp))
                OutlinedTextField(
                    value = text,
                    onValueChange = { text = it.filter { c -> c.isDigit() || c == '.' || c == ',' }.take(6) },
                    singleLine = true,
                    suffix = { Text("₴/кВт⋅год") },
                    isError = text.isNotEmpty() && !isValid,
                    supportingText = if (text.isNotEmpty() && !isValid) {
                        { Text("Введіть число від 0 до 100") }
                    } else {
                        null
                    },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal)
                )
            }
        },
        confirmButton = {
            TextButton(onClick = { if (isValid) onSave(parsed!!) }, enabled = isValid) { Text("Зберегти") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Скасувати") }
        }
    )
}

private fun statusLabel(status: SessionStatus): String = when (status) {
    SessionStatus.TARGET_REACHED -> "Ліміт досягнуто"
    SessionStatus.VEHICLE_STOPPED -> "Авто завершило"
    SessionStatus.CANCELLED -> "Зупинено вручну"
    SessionStatus.CHARGING -> "Заряджається"
    SessionStatus.OUTAGE_DETECTED,
    SessionStatus.OUTAGE_STABILIZING,
    SessionStatus.AUTO_RESUMING -> "Очікування світла"
    SessionStatus.STOP_PENDING -> "Зупинка…"
    SessionStatus.IDLE -> "Очікування"
}

internal fun formatKwh(kwh: Double): String = "%.1f кВт⋅год".format(kwh)

internal fun formatUah(uah: Double): String = "≈ %,d ₴".format(Math.round(uah)).replace(',', ' ')

internal fun formatDuration(ms: Long): String {
    val totalMinutes = ms / 60_000L
    val hours = totalMinutes / 60
    val minutes = totalMinutes % 60
    return when {
        hours == 0L -> "$minutes хв"
        minutes == 0L -> "$hours г"
        else -> "$hours г $minutes хв"
    }
}

private fun sessionsLabel(count: Int): String {
    val mod10 = count % 10
    val mod100 = count % 100
    val word = when {
        mod10 == 1 && mod100 != 11 -> "сесія"
        mod10 in 2..4 && mod100 !in 12..14 -> "сесії"
        else -> "сесій"
    }
    return "$count $word"
}
