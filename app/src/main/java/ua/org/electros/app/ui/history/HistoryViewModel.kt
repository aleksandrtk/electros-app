package ua.org.electros.app.ui.history

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import ua.org.electros.app.data.local.entity.ChargeSessionEntity
import ua.org.electros.app.data.repository.ChargeRepository
import java.time.Clock
import java.time.Instant
import java.time.YearMonth
import java.time.format.DateTimeFormatter
import java.util.Locale

data class HistoryUiState(
    val sessions: List<ChargeSessionEntity> = emptyList(),
    val currentMonthKwh: Double = 0.0,
    val currentMonthLabel: String = ""
)

class HistoryViewModel(
    repository: ChargeRepository,
    private val clock: Clock = Clock.systemDefaultZone()
) : ViewModel() {

    val uiState: StateFlow<HistoryUiState> = repository.getAllSessions().map { list ->
        buildHistoryUiState(list, clock)
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5000),
        initialValue = buildHistoryUiState(emptyList(), clock)
    )

    companion object {
        private val UK_LOCALE: Locale = Locale.of("uk", "UA")
        private val MONTH_FORMATTER: DateTimeFormatter =
            DateTimeFormatter.ofPattern("LLLL yyyy", UK_LOCALE)

        internal fun buildHistoryUiState(
            sessions: List<ChargeSessionEntity>,
            clock: Clock
        ): HistoryUiState {
            val zone = clock.zone
            val currentMonth = YearMonth.now(clock)
            val monthLabel = currentMonth.format(MONTH_FORMATTER)
                .replaceFirstChar { it.titlecase(UK_LOCALE) }
            val monthTotal = sessions
                .filter { YearMonth.from(Instant.ofEpochMilli(it.startedAt).atZone(zone)) == currentMonth }
                .sumOf { it.totalKwhDelivered }
            return HistoryUiState(
                sessions = sessions,
                currentMonthKwh = monthTotal,
                currentMonthLabel = monthLabel
            )
        }
    }
}

