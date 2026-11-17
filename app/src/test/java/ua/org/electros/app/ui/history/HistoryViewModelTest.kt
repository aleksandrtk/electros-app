package ua.org.electros.app.ui.history

import com.google.common.truth.Truth.assertThat
import org.junit.Test
import ua.org.electros.app.data.local.entity.ChargeSessionEntity
import ua.org.electros.app.data.local.entity.ChargeSliceEntity
import ua.org.electros.app.data.local.entity.SessionStatus
import ua.org.electros.app.data.local.entity.SliceEndReason
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneId

class HistoryViewModelTest {

    private val zone = ZoneId.of("Europe/Kyiv")
    private val clock = Clock.fixed(Instant.parse("2026-11-17T12:00:00Z"), zone)

    private val uk = HistoryLabels(java.util.Locale.of("uk", "UA"), "Сьогодні", "Вчора", "%1${'$'}d рік")

    private fun at(iso: String) = Instant.parse(iso).toEpochMilli()

    @Test
    fun activeSession_showsEnergyFromOpenSliceInsteadOfZero() {
        val active = session("live", at("2026-11-17T09:55:00Z"), status = SessionStatus.CHARGING, targetKwh = 8.0)
        val slices = listOf(slice("live", 0, start = 100.0, last = 103.4, startedAt = active.startedAt))

        val state = HistoryCalculator.build(listOf(active), slices, 0.0, HistoryPeriod.Month(YearMonth.of(2026, 11)), clock, uk)

        val item = state.days.single().sessions.single()
        assertThat(item.isActive).isTrue()
        assertThat(item.endedAt).isNull()
        assertThat(item.kwh).isWithin(0.001).of(3.4)
        assertThat(state.totalKwh).isWithin(0.001).of(3.4)
        assertThat(state.days.single().label).isEqualTo("Сьогодні")
    }

    @Test
    fun finishedSessionsWithoutEnergy_areHiddenButActiveOnesStay() {
        val attempt = session("attempt", at("2026-11-13T15:53:00Z"), status = SessionStatus.CANCELLED, kwh = 0.0)
        val justStarted = session("now", at("2026-11-17T11:59:00Z"), status = SessionStatus.CHARGING, kwh = 0.0)
        val real = session("real", at("2026-11-12T17:23:00Z"), status = SessionStatus.VEHICLE_STOPPED, kwh = 12.3)

        val state = HistoryCalculator.build(
            listOf(justStarted, attempt, real), emptyList(), 0.0, HistoryPeriod.Month(YearMonth.of(2026, 11)), clock
        )

        val ids = state.days.flatMap { day -> day.sessions.map { it.id } }
        assertThat(ids).containsExactly("now", "real").inOrder()
        assertThat(state.sessionCount).isEqualTo(2)
    }

    @Test
    fun monthView_groupsByDayAndComparesWithPreviousMonthAndCountsCost() {
        val sessions = listOf(
            session("nov-12", at("2026-11-12T17:23:00Z"), kwh = 12.0),
            session("nov-12b", at("2026-11-12T20:00:00Z"), kwh = 3.0),
            session("nov-10", at("2026-11-10T09:42:00Z"), kwh = 9.0),
            session("oct-20", at("2026-10-20T10:00:00Z"), kwh = 20.0)
        )

        val state = HistoryCalculator.build(sessions, emptyList(), 4.32, HistoryPeriod.Month(YearMonth.of(2026, 11)), clock, uk)

        assertThat(state.periodLabel).isEqualTo("Листопад 2026")
        assertThat(state.totalKwh).isWithin(0.001).of(24.0)
        assertThat(state.changeVsPreviousPct).isEqualTo(20)
        assertThat(state.totalCostUah!!).isWithin(0.01).of(24.0 * 4.32)
        assertThat(state.days.map { it.date })
            .containsExactly(LocalDate.of(2026, 11, 12), LocalDate.of(2026, 11, 10)).inOrder()
        assertThat(state.days.first().totalKwh).isWithin(0.001).of(15.0)
        assertThat(state.bars).hasSize(30)
        assertThat(state.bars[11].kwh).isWithin(0.001).of(15.0)
        assertThat(state.bars[16].isCurrent).isTrue()
        assertThat(state.canGoBack).isTrue()
        assertThat(state.canGoForward).isFalse()
    }

    @Test
    fun withoutTariff_costIsHidden() {
        val state = HistoryCalculator.build(
            listOf(session("s", at("2026-11-12T17:23:00Z"), kwh = 5.0)),
            emptyList(), 0.0, HistoryPeriod.Month(YearMonth.of(2026, 11)), clock
        )

        assertThat(state.totalCostUah).isNull()
        assertThat(state.days.single().sessions.single().costUah).isNull()
    }

    @Test
    fun yearView_summarisesMonthsAndDrawsTwelveBars() {
        val sessions = listOf(
            session("nov", at("2026-11-12T17:23:00Z"), kwh = 12.0),
            session("oct-a", at("2026-10-05T10:00:00Z"), kwh = 7.0),
            session("oct-b", at("2026-10-20T10:00:00Z"), kwh = 3.0),
            session("2025", at("2025-12-30T10:00:00Z"), kwh = 44.0)
        )

        val state = HistoryCalculator.build(sessions, emptyList(), 0.0, HistoryPeriod.Year(2026), clock, uk)

        assertThat(state.periodLabel).isEqualTo("2026 рік")
        assertThat(state.totalKwh).isWithin(0.001).of(22.0)
        assertThat(state.changeVsPreviousPct).isEqualTo(-50)
        assertThat(state.bars).hasSize(12)
        assertThat(state.bars[9].kwh).isWithin(0.001).of(10.0)
        assertThat(state.bars[10].isCurrent).isTrue()
        assertThat(state.months.map { it.month })
            .containsExactly(YearMonth.of(2026, 11), YearMonth.of(2026, 10)).inOrder()
        assertThat(state.months[1].sessionCount).isEqualTo(2)
        assertThat(state.days).isEmpty()
        assertThat(state.canGoBack).isTrue()
        assertThat(state.canGoForward).isFalse()
    }

    @Test
    fun englishLabels_formatMonthAndYearInEnglish() {
        val en = HistoryLabels(java.util.Locale.ENGLISH, "Today", "Yesterday", "%1${'$'}d")
        val sessions = listOf(session("s", at("2026-11-17T09:00:00Z"), kwh = 5.0))

        val month = HistoryCalculator.build(sessions, emptyList(), 0.0, HistoryPeriod.Month(YearMonth.of(2026, 11)), clock, en)
        val year = HistoryCalculator.build(sessions, emptyList(), 0.0, HistoryPeriod.Year(2026), clock, en)

        assertThat(month.periodLabel).isEqualTo("November 2026")
        assertThat(month.days.single().label).isEqualTo("Today")
        assertThat(year.periodLabel).isEqualTo("2026")
        assertThat(year.bars[10].label).isEqualTo("Nov")
    }

    @Test
    fun sessionItem_countsOutagesAndAveragePower() {
        val start = at("2026-11-12T17:00:00Z")
        val finished = session(
            "s", start, status = SessionStatus.TARGET_REACHED, kwh = 0.0,
            completedAt = start + 4 * 3_600_000L, chargingMs = 3 * 3_600_000L
        )
        val slices = listOf(
            slice("s", 0, start = 10.0, last = 13.0, startedAt = start, endedAt = start + 3_600_000L,
                finalizedKwh = 3.0, reason = SliceEndReason.POWER_OUTAGE),
            slice("s", 1, start = 13.0, last = 16.0, startedAt = start + 7_200_000L, endedAt = start + 4 * 3_600_000L,
                finalizedKwh = 3.0, reason = SliceEndReason.TARGET_REACHED)
        )

        val item = HistoryCalculator.toItem(finished, slices, tariffUah = 0.0, nowMs = clock.millis())

        assertThat(item.kwh).isWithin(0.001).of(6.0)
        assertThat(item.outageCount).isEqualTo(1)
        assertThat(item.avgPowerKw!!).isWithin(0.001).of(2.0)
        assertThat(item.endedAt).isEqualTo(start + 4 * 3_600_000L)
    }

    private fun session(
        id: String,
        startedAt: Long,
        status: SessionStatus = SessionStatus.VEHICLE_STOPPED,
        kwh: Double = 0.0,
        targetKwh: Double = 0.0,
        completedAt: Long? = if (status.isTerminal) startedAt + 3_600_000L else null,
        chargingMs: Long = 0L
    ) = ChargeSessionEntity(
        id = id,
        targetKwh = targetKwh,
        amperageSetting = 10,
        status = status,
        startedAt = startedAt,
        completedAt = completedAt,
        totalKwhDelivered = kwh,
        accumulatedChargingMs = chargingMs
    )

    private fun slice(
        sessionId: String,
        index: Int,
        start: Double,
        last: Double,
        startedAt: Long,
        endedAt: Long? = null,
        finalizedKwh: Double = 0.0,
        reason: SliceEndReason? = null
    ) = ChargeSliceEntity(
        sessionId = sessionId,
        sliceIndex = index,
        stationCounterStartKwh = start,
        stationCounterLastKwh = last,
        finalizedKwh = finalizedKwh,
        startedAt = startedAt,
        endedAt = endedAt,
        terminationReason = reason
    )
}
