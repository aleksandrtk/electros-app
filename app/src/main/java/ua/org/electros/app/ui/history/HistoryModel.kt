package ua.org.electros.app.ui.history

import ua.org.electros.app.data.local.entity.ChargeSessionEntity
import ua.org.electros.app.data.local.entity.ChargeSliceEntity
import ua.org.electros.app.data.local.entity.SessionStatus
import ua.org.electros.app.data.local.entity.SliceEndReason
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.Year
import java.time.YearMonth
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.roundToInt

/** The period the history screen shows: one calendar month or one calendar year. */
sealed interface HistoryPeriod {
    data class Month(val month: YearMonth) : HistoryPeriod
    data class Year(val year: Int) : HistoryPeriod
}

data class HistorySessionItem(
    val id: String,
    val startedAt: Long,
    val endedAt: Long?,
    val kwh: Double,
    val targetKwh: Double,
    val chargingMs: Long,
    val avgPowerKw: Double?,
    val costUah: Double?,
    val outageCount: Int,
    val amperage: Int,
    val status: SessionStatus,
    val isActive: Boolean
)

data class HistoryDay(
    val date: LocalDate,
    val label: String,
    val totalKwh: Double,
    val sessions: List<HistorySessionItem>
)

data class HistoryMonthRow(
    val month: YearMonth,
    val label: String,
    val kwh: Double,
    val costUah: Double?,
    val sessionCount: Int
)

/** One bar of the chart: a day of the month, or a month of the year. */
data class HistoryBar(val label: String, val kwh: Double, val isCurrent: Boolean)

data class HistoryUiState(
    val period: HistoryPeriod = HistoryPeriod.Month(YearMonth.now()),
    val periodLabel: String = "",
    val canGoBack: Boolean = false,
    val canGoForward: Boolean = false,
    val totalKwh: Double = 0.0,
    val changeVsPreviousPct: Int? = null,
    val totalCostUah: Double? = null,
    val sessionCount: Int = 0,
    val chargingMs: Long = 0L,
    val bars: List<HistoryBar> = emptyList(),
    /** Month view: sessions grouped by day, newest first. */
    val days: List<HistoryDay> = emptyList(),
    /** Year view: months that have sessions, newest first. */
    val months: List<HistoryMonthRow> = emptyList(),
    val tariffUah: Double = 0.0
) {
    val isEmpty: Boolean get() = days.isEmpty() && months.isEmpty()
}

/** Texts and locale the history model needs; supplied by the UI so they follow the app language. */
data class HistoryLabels(
    val locale: Locale,
    val today: String,
    val yesterday: String,
    /** Year label pattern with one integer argument (the year). */
    val yearPattern: String
) {
    companion object {
        val DEFAULT = HistoryLabels(Locale.ENGLISH, "Today", "Yesterday", "%1${'$'}d")
    }
}

internal object HistoryCalculator {
    /** A finished session that delivered less than this was an attempt that never charged; it is not shown. */
    const val MIN_SESSION_KWH = 0.01
    private const val MIN_CHARGING_MS_FOR_POWER = 5 * 60_000L


    fun build(
        sessions: List<ChargeSessionEntity>,
        slices: List<ChargeSliceEntity>,
        tariffUah: Double,
        period: HistoryPeriod,
        clock: Clock,
        labels: HistoryLabels = HistoryLabels.DEFAULT
    ): HistoryUiState {
        val locale = labels.locale
        val monthYear = DateTimeFormatter.ofPattern("LLLL yyyy", locale)
        val monthShort = DateTimeFormatter.ofPattern("LLL", locale)
        val monthFull = DateTimeFormatter.ofPattern("LLLL", locale)
        val dayMonth = DateTimeFormatter.ofPattern("d MMMM", locale)
        fun String.capitalized(): String = replaceFirstChar { it.titlecase(locale) }
        fun dayLabel(date: LocalDate, today: LocalDate): String = when (date) {
            today -> labels.today
            today.minusDays(1) -> labels.yesterday
            else -> date.format(dayMonth)
        }
        val zone = clock.zone
        val today = LocalDate.now(clock)
        val slicesBySession = slices.groupBy { it.sessionId }
        val items = sessions
            .map { toItem(it, slicesBySession[it.id].orEmpty(), tariffUah, clock.millis()) }
            .filter { it.isActive || it.kwh >= MIN_SESSION_KWH }
            .sortedByDescending { it.startedAt }

        fun dateOf(item: HistorySessionItem): LocalDate =
            Instant.ofEpochMilli(item.startedAt).atZone(zone).toLocalDate()

        val earliest = items.minOfOrNull { dateOf(it) } ?: today
        val cost: (Double) -> Double? = { kwh -> if (tariffUah > 0.0) kwh * tariffUah else null }

        return when (period) {
            is HistoryPeriod.Month -> {
                val month = period.month
                val inMonth = items.filter { YearMonth.from(dateOf(it)) == month }
                val previous = items.filter { YearMonth.from(dateOf(it)) == month.minusMonths(1) }
                val byDay = inMonth.groupBy { dateOf(it) }
                val total = inMonth.sumOf { it.kwh }
                HistoryUiState(
                    period = period,
                    periodLabel = month.format(monthYear).capitalized(),
                    canGoBack = month > YearMonth.from(earliest),
                    canGoForward = month < YearMonth.from(today),
                    totalKwh = total,
                    changeVsPreviousPct = changePct(total, previous.sumOf { it.kwh }),
                    totalCostUah = cost(total),
                    sessionCount = inMonth.size,
                    chargingMs = inMonth.sumOf { it.chargingMs },
                    bars = (1..month.lengthOfMonth()).map { day ->
                        val date = month.atDay(day)
                        HistoryBar(
                            label = day.toString(),
                            kwh = byDay[date].orEmpty().sumOf { it.kwh },
                            isCurrent = date == today
                        )
                    },
                    days = byDay.entries
                        .sortedByDescending { it.key }
                        .map { (date, daySessions) ->
                            HistoryDay(
                                date = date,
                                label = dayLabel(date, today),
                                totalKwh = daySessions.sumOf { it.kwh },
                                sessions = daySessions
                            )
                        },
                    tariffUah = tariffUah
                )
            }
            is HistoryPeriod.Year -> {
                val year = period.year
                val inYear = items.filter { dateOf(it).year == year }
                val previous = items.filter { dateOf(it).year == year - 1 }
                val byMonth = inYear.groupBy { YearMonth.from(dateOf(it)) }
                val total = inYear.sumOf { it.kwh }
                val currentMonth = YearMonth.from(today)
                HistoryUiState(
                    period = period,
                    periodLabel = labels.yearPattern.format(locale, year),
                    canGoBack = year > earliest.year,
                    canGoForward = year < today.year,
                    totalKwh = total,
                    changeVsPreviousPct = changePct(total, previous.sumOf { it.kwh }),
                    totalCostUah = cost(total),
                    sessionCount = inYear.size,
                    chargingMs = inYear.sumOf { it.chargingMs },
                    bars = (1..12).map { m ->
                        val month = YearMonth.of(year, m)
                        HistoryBar(
                            label = month.format(monthShort).trimEnd('.').take(3).capitalized(),
                            kwh = byMonth[month].orEmpty().sumOf { it.kwh },
                            isCurrent = month == currentMonth
                        )
                    },
                    months = byMonth.entries
                        .sortedByDescending { it.key }
                        .map { (month, monthSessions) ->
                            val kwh = monthSessions.sumOf { it.kwh }
                            HistoryMonthRow(
                                month = month,
                                label = month.format(monthFull).capitalized(),
                                kwh = kwh,
                                costUah = cost(kwh),
                                sessionCount = monthSessions.size
                            )
                        },
                    tariffUah = tariffUah
                )
            }
        }
    }

    /**
     * Energy comes from the session's slices, so a session that is still charging shows what it has
     * delivered so far instead of the 0 kWh stored until its first slice is closed.
     */
    internal fun toItem(
        session: ChargeSessionEntity,
        slices: List<ChargeSliceEntity>,
        tariffUah: Double,
        nowMs: Long
    ): HistorySessionItem {
        val kwh = if (slices.isEmpty()) {
            session.totalKwhDelivered
        } else {
            slices.sumOf { slice ->
                if (slice.endedAt != null) {
                    slice.finalizedKwh
                } else {
                    (slice.stationCounterLastKwh - slice.stationCounterStartKwh).coerceAtLeast(0.0)
                }
            }
        }
        val isActive = !session.status.isTerminal
        val endedAt = if (isActive) null else session.completedAt ?: slices.mapNotNull { it.endedAt }.maxOrNull()
        val chargingMs = when {
            session.accumulatedChargingMs > 0L -> session.accumulatedChargingMs
            endedAt != null -> (endedAt - session.startedAt).coerceAtLeast(0L)
            else -> (nowMs - session.startedAt).coerceAtLeast(0L)
        }
        val avgPowerKw = if (chargingMs >= MIN_CHARGING_MS_FOR_POWER && kwh > 0.0) {
            kwh / (chargingMs / 3_600_000.0)
        } else {
            null
        }
        return HistorySessionItem(
            id = session.id,
            startedAt = session.startedAt,
            endedAt = endedAt,
            kwh = kwh,
            targetKwh = session.targetKwh,
            chargingMs = chargingMs,
            avgPowerKw = avgPowerKw,
            costUah = if (tariffUah > 0.0) kwh * tariffUah else null,
            outageCount = slices.count { it.terminationReason == SliceEndReason.POWER_OUTAGE },
            amperage = session.amperageSetting,
            status = session.status,
            isActive = isActive
        )
    }

    private fun changePct(current: Double, previous: Double): Int? =
        if (previous < MIN_SESSION_KWH) null else (((current - previous) / previous) * 100).roundToInt()


    fun previous(period: HistoryPeriod): HistoryPeriod = when (period) {
        is HistoryPeriod.Month -> HistoryPeriod.Month(period.month.minusMonths(1))
        is HistoryPeriod.Year -> HistoryPeriod.Year(period.year - 1)
    }

    fun next(period: HistoryPeriod): HistoryPeriod = when (period) {
        is HistoryPeriod.Month -> HistoryPeriod.Month(period.month.plusMonths(1))
        is HistoryPeriod.Year -> HistoryPeriod.Year(period.year + 1)
    }

    fun currentMonth(clock: Clock): HistoryPeriod = HistoryPeriod.Month(YearMonth.now(clock))

    fun currentYear(clock: Clock): HistoryPeriod = HistoryPeriod.Year(Year.now(clock).value)
}
