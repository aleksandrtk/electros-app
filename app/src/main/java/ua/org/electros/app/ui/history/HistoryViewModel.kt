package ua.org.electros.app.ui.history

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import ua.org.electros.app.data.local.entity.ChargeSessionEntity
import ua.org.electros.app.data.repository.ChargeRepository

data class HistoryUiState(
    val sessions: List<ChargeSessionEntity> = emptyList(),
    val currentMonthKwh: Double = 0.0,
    val previousMonthKwh: Double = 96.9
)

class HistoryViewModel(
    repository: ChargeRepository
) : ViewModel() {

    val uiState: StateFlow<HistoryUiState> = repository.getAllSessions().map { list ->
        val monthTotal = list.sumOf { it.totalKwhDelivered }
        HistoryUiState(
            sessions = list,
            currentMonthKwh = monthTotal
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5000),
        initialValue = HistoryUiState()
    )
}

