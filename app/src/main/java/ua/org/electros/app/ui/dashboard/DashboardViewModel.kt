package ua.org.electros.app.ui.dashboard

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import ua.org.electros.app.data.local.AuthPreferences
import ua.org.electros.app.data.local.entity.ChargeSessionEntity
import ua.org.electros.app.data.local.entity.SessionStatus
import ua.org.electros.app.data.model.TelemetryData
import ua.org.electros.app.data.repository.ChargeRepository
import ua.org.electros.app.service.ChargeMonitorService

data class DashboardUiState(
    val telemetry: TelemetryData? = null,
    val activeSession: ChargeSessionEntity? = null,
    val selectedAmperes: Int = 16,
    val isKwhLimitEnabled: Boolean = false,
    val targetQuotaKwh: Double = 0.0,
    val totalDeliveredKwh: Double = 0.0,
    val isOutageActive: Boolean = false,
    val isStartingOrStopping: Boolean = false,
    val errorMessage: String? = null
)

class DashboardViewModel(
    private val repository: ChargeRepository,
    private val authPreferences: AuthPreferences
) : ViewModel() {

    private val _selectedAmperes = MutableStateFlow(authPreferences.preferredAmperage)
    private val _isBusy = MutableStateFlow(false)
    private val _errorMessage = MutableStateFlow<String?>(null)

    val uiState: StateFlow<DashboardUiState> = combine(
        repository.telemetryState,
        repository.observeActiveSession(),
        _selectedAmperes,
        _isBusy,
        _errorMessage
    ) { telemetry, activeSession, amperes, busy, error ->
        val isLimitEnabled = authPreferences.isKwhLimitEnabled
        val quota = if (isLimitEnabled) authPreferences.targetKwhLimit else 0.0
        val isOnline = telemetry?.isOnline == true

        // If station is completely offline and delivered energy is 0, session is stale
        val effectiveSession = if (activeSession != null && (!isOnline && activeSession.totalKwhDelivered == 0.0)) {
            null
        } else {
            activeSession
        }

        val delivered = effectiveSession?.totalKwhDelivered ?: 0.0

        val isOutage = effectiveSession != null &&
                effectiveSession.totalKwhDelivered > 0.0 &&
                (effectiveSession.status == SessionStatus.OUTAGE_DETECTED ||
                        effectiveSession.status == SessionStatus.OUTAGE_STABILIZING ||
                        !isOnline)

        DashboardUiState(
            telemetry = telemetry,
            activeSession = effectiveSession,
            selectedAmperes = amperes,
            isKwhLimitEnabled = isLimitEnabled,
            targetQuotaKwh = quota,
            totalDeliveredKwh = delivered,
            isOutageActive = isOutage,
            isStartingOrStopping = busy,
            errorMessage = error
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5000),
        initialValue = DashboardUiState(selectedAmperes = authPreferences.preferredAmperage)
    )

    init {
        refreshTelemetry()
        viewModelScope.launch {
            repository.cancelStaleSessionsIfOffline()
        }
    }

    fun refreshTelemetry() {
        viewModelScope.launch {
            repository.fetchLatestTelemetry()
        }
    }

    fun onAmperageSelected(amperes: Int) {
        _selectedAmperes.value = amperes
        authPreferences.preferredAmperage = amperes
        viewModelScope.launch {
            repository.setAmperage(amperes)
            repository.fetchLatestTelemetry()
        }
    }

    fun onStartClicked(context: Context) {
        val telemetry = uiState.value.telemetry
        if (telemetry != null && !telemetry.isOnline) {
            _errorMessage.value = "Станція вимкнена з розетки (OFFLINE). Підключіть живлення, щоб почати зарядку."
            return
        }

        viewModelScope.launch {
            _isBusy.value = true
            _errorMessage.value = null

            val quota = if (authPreferences.isKwhLimitEnabled) authPreferences.targetKwhLimit else 0.0
            val amps = uiState.value.selectedAmperes

            val result = repository.startNewSession(quota, amps)
            result.onSuccess { sessionId ->
                ChargeMonitorService.start(context, sessionId, quota)
            }.onFailure { err ->
                _errorMessage.value = err.message ?: "Не вдалося розпочати зарядку"
            }
            _isBusy.value = false
        }
    }

    fun onStopClicked() {
        viewModelScope.launch {
            _isBusy.value = true
            _errorMessage.value = null
            val session = uiState.value.activeSession
            if (session != null) {
                repository.stopActiveSession(session.id)
            } else {
                repository.stopActiveSession("manual_immediate")
            }
            repository.fetchLatestTelemetry()
            _isBusy.value = false
        }
    }
}
