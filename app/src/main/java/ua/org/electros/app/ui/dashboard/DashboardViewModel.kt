package ua.org.electros.app.ui.dashboard

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import ua.org.electros.app.data.local.entity.ChargeSessionEntity
import ua.org.electros.app.data.local.entity.SessionStatus
import ua.org.electros.app.data.model.TelemetryData
import ua.org.electros.app.data.repository.ChargeRepository

data class DashboardUiState(
    val telemetry: TelemetryData? = null,
    val activeSession: ChargeSessionEntity? = null,
    val selectedAmperes: Int = 16,
    val targetQuotaKwh: Double = 30.0,
    val totalDeliveredKwh: Double = 0.0,
    val isOutageActive: Boolean = false,
    val isStartingOrStopping: Boolean = false,
    val errorMessage: String? = null
)

class DashboardViewModel(
    private val repository: ChargeRepository
) : ViewModel() {

    private val _selectedAmperes = MutableStateFlow(16)
    private val _isBusy = MutableStateFlow(false)
    private val _errorMessage = MutableStateFlow<String?>(null)

    val uiState: StateFlow<DashboardUiState> = combine(
        repository.telemetryState,
        repository.observeActiveSession(),
        _selectedAmperes,
        _isBusy,
        _errorMessage
    ) { telemetry, activeSession, amperes, busy, error ->
        val delivered = activeSession?.totalKwhDelivered ?: (telemetry?.consumedEnergyKwh ?: 0.0)
        val isOutage = activeSession?.status == SessionStatus.OUTAGE_DETECTED ||
                       activeSession?.status == SessionStatus.OUTAGE_STABILIZING ||
                       (telemetry != null && !telemetry.isOnline)

        DashboardUiState(
            telemetry = telemetry,
            activeSession = activeSession,
            selectedAmperes = if (telemetry != null) telemetry.selectedCurrentAmperes else amperes,
            targetQuotaKwh = activeSession?.targetKwh ?: 30.0,
            totalDeliveredKwh = delivered,
            isOutageActive = isOutage,
            isStartingOrStopping = busy,
            errorMessage = error
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5000),
        initialValue = DashboardUiState()
    )

    init {
        refreshTelemetry()
    }

    fun refreshTelemetry() {
        viewModelScope.launch {
            repository.fetchLatestTelemetry()
        }
    }

    fun onAmperageSelected(amperes: Int) {
        _selectedAmperes.value = amperes
        viewModelScope.launch {
            repository.setAmperage(amperes)
            repository.fetchLatestTelemetry()
        }
    }

    fun onStartClicked(context: android.content.Context) {
        viewModelScope.launch {
            _isBusy.value = true
            _errorMessage.value = null
            val quota = uiState.value.targetQuotaKwh
            val amps = uiState.value.selectedAmperes
            val result = repository.startNewSession(quota, amps)
            result.onSuccess { sessionId ->
                ua.org.electros.app.service.ChargeMonitorService.start(context, sessionId, quota)
            }.onFailure { err ->
                _errorMessage.value = err.message
            }
            _isBusy.value = false
        }
    }

    fun onStopClicked() {
        viewModelScope.launch {
            _isBusy.value = true
            _errorMessage.value = null
            val activeId = uiState.value.activeSession?.id
            if (activeId != null) {
                repository.stopActiveSession(activeId)
            }
            _isBusy.value = false
        }
    }
}

