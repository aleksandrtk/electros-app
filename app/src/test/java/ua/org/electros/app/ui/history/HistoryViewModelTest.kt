package ua.org.electros.app.ui.history

import com.google.common.truth.Truth.assertThat
import org.junit.Test
import ua.org.electros.app.data.local.entity.ChargeSessionEntity
import ua.org.electros.app.data.local.entity.SessionStatus
import java.time.Clock
import java.time.Instant
import java.time.ZoneId

class HistoryViewModelTest {

    @Test
    fun buildHistoryUiState_filtersDeliveredKwhToCurrentYearMonthAndFormatsLabel() {
        val zone = ZoneId.of("Europe/Kyiv")
        val dec2026Clock = Clock.fixed(Instant.parse("2026-12-15T10:00:00Z"), zone)

        val octSession = session("oct", Instant.parse("2026-10-20T12:00:00Z").toEpochMilli(), 18.5)
        val novSession = session("nov", Instant.parse("2026-11-25T18:00:00Z").toEpochMilli(), 25.0)
        val decSession1 = session("dec-1", Instant.parse("2026-12-02T08:00:00Z").toEpochMilli(), 12.4)
        val decSession2 = session("dec-2", Instant.parse("2026-12-14T21:30:00Z").toEpochMilli(), 7.6)

        val state = HistoryViewModel.buildHistoryUiState(
            sessions = listOf(decSession2, decSession1, novSession, octSession),
            clock = dec2026Clock
        )

        assertThat(state.sessions).hasSize(4)
        assertThat(state.currentMonthKwh).isWithin(0.001).of(20.0)
        assertThat(state.currentMonthLabel).contains("2026")
        assertThat(state.currentMonthLabel).doesNotContain("Листопад")
    }

    private fun session(id: String, startedAt: Long, deliveredKwh: Double) = ChargeSessionEntity(
        id = id,
        targetKwh = 0.0,
        amperageSetting = 16,
        status = SessionStatus.TARGET_REACHED,
        startedAt = startedAt,
        completedAt = startedAt + 3_600_000L,
        totalKwhDelivered = deliveredKwh
    )
}
