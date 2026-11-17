package ua.org.electros.app.ui.history

import androidx.annotation.StringRes
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import ua.org.electros.app.R
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
    val context = LocalContext.current
    val locale = LocalConfiguration.current.locales[0]
    val labels = HistoryLabels(
        locale = locale,
        today = stringResource(R.string.history_today),
        yesterday = stringResource(R.string.history_yesterday),
        yearPattern = context.getString(R.string.history_year_label)
    )
    LaunchedEffect(labels) { viewModel.setLabels(labels) }
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
            Text(text = stringResource(R.string.history_title), style = MaterialTheme.typography.titleLarge, color = TextPrimaryDark)
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
                    text = stringResource(if (isMonthView) R.string.history_empty_month else R.string.history_empty_year),
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
            SegmentButton(stringResource(R.string.history_month), selected = isMonthView, onClick = onMonth, modifier = Modifier.weight(1f))
            SegmentButton(stringResource(R.string.history_year), selected = !isMonthView, onClick = onYear, modifier = Modifier.weight(1f))
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
                    text = " " + stringResource(R.string.unit_kwh),
                    fontSize = 14.sp,
                    color = TextSecondaryDark,
                    modifier = Modifier.padding(bottom = 5.dp)
                )
                Spacer(Modifier.weight(1f))
                state.changeVsPreviousPct?.let { pct ->
                    Text(
                        text = stringResource(
                            if (isMonthView) R.string.history_change_month else R.string.history_change_year,
                            if (pct > 0) "+" else if (pct < 0) "−" else "",
                            kotlin.math.abs(pct)
                        ),
                        fontSize = 11.sp,
                        color = TextSecondaryDark,
                        modifier = Modifier.padding(bottom = 6.dp)
                    )
                }
            }
            Spacer(Modifier.height(12.dp))
            Row(modifier = Modifier.fillMaxWidth()) {
                Metric(
                    label = stringResource(R.string.history_cost),
                    value = state.totalCostUah?.let { formatUah(it) } ?: stringResource(R.string.history_set_tariff),
                    valueColor = if (state.totalCostUah == null) AmberNixie else TextPrimaryDark,
                    modifier = Modifier.weight(1f).clickable(onClick = onTariffClick)
                )
                Metric(label = stringResource(R.string.history_sessions), value = state.sessionCount.toString(), modifier = Modifier.weight(1f))
                Metric(label = stringResource(R.string.history_charging_time), value = formatDuration(state.chargingMs), modifier = Modifier.weight(1f))
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
    val end = session.endedAt?.let { TIME_FORMATTER.format(Instant.ofEpochMilli(it).atZone(zone)) } ?: stringResource(R.string.history_now)

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
                        stringResource(R.string.kwh_of_target, "%.1f".format(session.kwh), "%.0f".format(session.targetKwh))
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
            val details = listOfNotNull(
                stringResource(statusLabel(session.status)),
                formatDuration(session.chargingMs),
                session.avgPowerKw?.let { stringResource(R.string.history_avg_power, "%.1f".format(it)) },
                session.costUah?.let { formatUah(it) },
                stringResource(R.string.amps_value, session.amperage)
            )
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
                    text = stringResource(R.string.history_outages, session.outageCount),
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
                    text = listOfNotNull(
                        pluralStringResource(R.plurals.history_session_count, row.sessionCount, row.sessionCount),
                        row.costUah?.let { formatUah(it) }
                    )
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
        title = { Text(stringResource(R.string.tariff_title)) },
        text = {
            Column {
                Text(
                    text = stringResource(R.string.tariff_body),
                    fontSize = 13.sp,
                    color = TextSecondaryDark
                )
                Spacer(Modifier.height(12.dp))
                OutlinedTextField(
                    value = text,
                    onValueChange = { text = it.filter { c -> c.isDigit() || c == '.' || c == ',' }.take(6) },
                    singleLine = true,
                    suffix = { Text(stringResource(R.string.tariff_suffix)) },
                    isError = text.isNotEmpty() && !isValid,
                    supportingText = if (text.isNotEmpty() && !isValid) {
                        { Text(stringResource(R.string.tariff_invalid)) }
                    } else {
                        null
                    },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal)
                )
            }
        },
        confirmButton = {
            TextButton(onClick = { if (isValid) onSave(parsed!!) }, enabled = isValid) { Text(stringResource(R.string.save)) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) }
        }
    )
}

@StringRes
private fun statusLabel(status: SessionStatus): Int = when (status) {
    SessionStatus.TARGET_REACHED -> R.string.session_target_reached
    SessionStatus.VEHICLE_STOPPED -> R.string.session_vehicle_stopped
    SessionStatus.CANCELLED -> R.string.session_cancelled
    SessionStatus.CHARGING -> R.string.session_charging
    SessionStatus.OUTAGE_DETECTED,
    SessionStatus.OUTAGE_STABILIZING,
    SessionStatus.AUTO_RESUMING -> R.string.session_waiting_power
    SessionStatus.STOP_PENDING -> R.string.session_stopping
    SessionStatus.IDLE -> R.string.session_idle
}

@Composable
private fun formatKwh(kwh: Double): String = stringResource(R.string.kwh_dec, "%.1f".format(kwh))

@Composable
private fun formatUah(uah: Double): String =
    stringResource(R.string.cost_uah, "%,d".format(Math.round(uah)).replace(',', '\u202F'))

@Composable
private fun formatDuration(ms: Long): String {
    val totalMinutes = (ms / 60_000L).toInt()
    val hours = totalMinutes / 60
    val minutes = totalMinutes % 60
    return when {
        hours == 0 -> stringResource(R.string.duration_min, minutes)
        minutes == 0 -> stringResource(R.string.duration_h, hours)
        else -> stringResource(R.string.duration_h_min, hours, minutes)
    }
}
