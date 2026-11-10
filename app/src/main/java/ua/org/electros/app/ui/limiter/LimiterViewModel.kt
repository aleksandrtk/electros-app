package ua.org.electros.app.ui.limiter

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import ua.org.electros.app.data.local.AuthPreferences
import ua.org.electros.app.data.repository.ChargeRepository

data class LimiterUiState(
    val targetKwh: Double = 30.0,
    val startTime: String = "23:00",
    val stopTime: String = "07:00",
    val isAutoResumeEnabled: Boolean = true,
    val isStopAtTariffBoundaryEnabled: Boolean = false,
    val isScheduleSaved: Boolean = false
)

class LimiterViewModel(
    private val repository: ChargeRepository,
    private val authPreferences: AuthPreferences
) : ViewModel() {

    private val _uiState = MutableStateFlow(
        LimiterUiState(
            isAutoResumeEnabled = authPreferences.autoResumeAfterOutage,
            isStopAtTariffBoundaryEnabled = authPreferences.stopAtTariffBoundary
        )
    )
    val uiState: StateFlow<LimiterUiState> = _uiState.asStateFlow()

    fun updateTargetKwh(delta: Double) {
        val current = _uiState.value.targetKwh
        val updated = (current + delta).coerceIn(5.0, 100.0)
        _uiState.value = _uiState.value.copy(targetKwh = updated)
    }

    fun setExactTargetKwh(quota: Double) {
        _uiState.value = _uiState.value.copy(targetKwh = quota.coerceIn(5.0, 100.0))
    }

    fun setScheduleTimes(start: String, stop: String) {
        _uiState.value = _uiState.value.copy(startTime = start, stopTime = stop)
    }

    fun toggleAutoResume(enabled: Boolean) {
        authPreferences.autoResumeAfterOutage = enabled
        _uiState.value = _uiState.value.copy(isAutoResumeEnabled = enabled)
    }

    fun toggleStopAtTariff(enabled: Boolean) {
        authPreferences.stopAtTariffBoundary = enabled
        _uiState.value = _uiState.value.copy(isStopAtTariffBoundaryEnabled = enabled)
    }

    fun applyScheduleToStation() {
        viewModelScope.launch {
            repository.setScheduleTimer(_uiState.value.startTime, _uiState.value.stopTime)
            _uiState.value = _uiState.value.copy(isScheduleSaved = true)
        }
    }
}

