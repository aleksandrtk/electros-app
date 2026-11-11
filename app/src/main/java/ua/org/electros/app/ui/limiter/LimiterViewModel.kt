package ua.org.electros.app.ui.limiter

import android.content.SharedPreferences
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import ua.org.electros.app.data.local.AuthPreferences
import ua.org.electros.app.data.repository.ChargeRepository

data class LimiterUiState(
    val isKwhLimitEnabled: Boolean = false,
    val targetKwh: Double = 15.0,
    val isDurationTimerEnabled: Boolean = false,
    val targetDurationHours: Int = 2,
    val isScheduleEnabled: Boolean = false,
    val startTime: String = "23:00",
    val stopTime: String = "07:00",
    val isAutoResumeEnabled: Boolean = true,
    val isScheduleSaved: Boolean = false,
    val isVoltageBalancingEnabled: Boolean = true,
    val voltageLowThreshold: Int = 185,
    val voltageHighThreshold: Int = 205,
    val voltageMaxAmperage: Int = 12
)

class LimiterViewModel(
    private val repository: ChargeRepository,
    private val authPreferences: AuthPreferences
) : ViewModel() {

    private val _uiState = MutableStateFlow(readStateFromPrefs())
    val uiState: StateFlow<LimiterUiState> = _uiState.asStateFlow()

    private val prefListener = SharedPreferences.OnSharedPreferenceChangeListener { _, _ ->
        _uiState.value = readStateFromPrefs().copy(isScheduleSaved = _uiState.value.isScheduleSaved)
    }

    init {
        authPreferences.registerListener(prefListener)
    }

    override fun onCleared() {
        super.onCleared()
        authPreferences.unregisterListener(prefListener)
    }

    private fun readStateFromPrefs(): LimiterUiState {
        val (low, high) = normalizeThresholds(
            authPreferences.voltageLowThreshold,
            authPreferences.voltageHighThreshold
        )
        return LimiterUiState(
            isKwhLimitEnabled = authPreferences.isKwhLimitEnabled,
            targetKwh = authPreferences.targetKwhLimit,
            isDurationTimerEnabled = authPreferences.isDurationTimerEnabled,
            targetDurationHours = authPreferences.targetDurationHours,
            isScheduleEnabled = authPreferences.isScheduleEnabled,
            startTime = authPreferences.scheduleStartTime,
            stopTime = authPreferences.scheduleStopTime,
            isAutoResumeEnabled = authPreferences.autoResumeAfterOutage,
            isVoltageBalancingEnabled = authPreferences.isVoltageBalancingEnabled,
            voltageLowThreshold = low,
            voltageHighThreshold = high,
            voltageMaxAmperage = authPreferences.voltageMaxAmperage
        )
    }

    fun toggleDurationTimer(enabled: Boolean) {
        authPreferences.isDurationTimerEnabled = enabled
        val hours = authPreferences.targetDurationHours
        _uiState.value = _uiState.value.copy(
            isDurationTimerEnabled = enabled
        )
        viewModelScope.launch {
            repository.getActiveSession()?.let { active ->
                repository.updateTargetDurationMinutes(active.id, if (enabled) hours * 60 else 0)
            }
        }
    }

    fun setTargetDurationHours(hours: Int) {
        val clamped = hours.coerceIn(1, 12)
        authPreferences.targetDurationHours = clamped
        _uiState.value = _uiState.value.copy(targetDurationHours = clamped)
        if (_uiState.value.isDurationTimerEnabled) {
            viewModelScope.launch {
                repository.getActiveSession()?.let { active ->
                    repository.updateTargetDurationMinutes(active.id, clamped * 60)
                }
            }
        }
    }

    fun toggleKwhLimit(enabled: Boolean) {
        authPreferences.isKwhLimitEnabled = enabled
        _uiState.value = _uiState.value.copy(isKwhLimitEnabled = enabled)
    }

    fun updateTargetKwh(delta: Double) {
        val current = _uiState.value.targetKwh
        val updated = (current + delta).coerceIn(1.0, 100.0)
        authPreferences.targetKwhLimit = updated
        _uiState.value = _uiState.value.copy(targetKwh = updated)
    }

    fun setTargetKwh(quota: Double) {
        val clamped = quota.coerceIn(1.0, 100.0)
        authPreferences.targetKwhLimit = clamped
        _uiState.value = _uiState.value.copy(targetKwh = clamped)
    }

    fun toggleSchedule(enabled: Boolean) {
        authPreferences.isScheduleEnabled = enabled
        _uiState.value = _uiState.value.copy(isScheduleEnabled = enabled)
    }

    fun setScheduleTimes(start: String, stop: String) {
        authPreferences.scheduleStartTime = start
        authPreferences.scheduleStopTime = stop
        _uiState.value = _uiState.value.copy(startTime = start, stopTime = stop, isScheduleSaved = false)
    }

    fun toggleAutoResume(enabled: Boolean) {
        authPreferences.autoResumeAfterOutage = enabled
        _uiState.value = _uiState.value.copy(isAutoResumeEnabled = enabled)
    }

    fun applyScheduleToStation() {
        viewModelScope.launch {
            if (_uiState.value.isScheduleEnabled) {
                repository.setScheduleTimer(_uiState.value.startTime, _uiState.value.stopTime)
            } else {
                repository.setScheduleTimer("00:00", "00:00")
            }
            _uiState.value = _uiState.value.copy(isScheduleSaved = true)
        }
    }

    fun toggleVoltageBalancing(enabled: Boolean) {
        authPreferences.isVoltageBalancingEnabled = enabled
        _uiState.value = _uiState.value.copy(isVoltageBalancingEnabled = enabled)
    }

    fun setVoltageLowThreshold(threshold: Int) {
        val clamped = clampLowThreshold(threshold, authPreferences.voltageHighThreshold)
        authPreferences.voltageLowThreshold = clamped
        _uiState.value = _uiState.value.copy(voltageLowThreshold = clamped)
    }

    fun setVoltageHighThreshold(threshold: Int) {
        val clamped = clampHighThreshold(threshold, authPreferences.voltageLowThreshold)
        authPreferences.voltageHighThreshold = clamped
        _uiState.value = _uiState.value.copy(voltageHighThreshold = clamped)
    }

    fun setVoltageMaxAmperage(amperage: Int) {
        val clamped = amperage.coerceIn(6, 16)
        authPreferences.voltageMaxAmperage = clamped
        _uiState.value = _uiState.value.copy(voltageMaxAmperage = clamped)
    }

    companion object {
        const val MIN_HYSTERESIS_V = 10

        internal fun clampLowThreshold(requestedLow: Int, currentHigh: Int): Int {
            val maxLow = (currentHigh - MIN_HYSTERESIS_V).coerceAtLeast(160)
            return requestedLow.coerceIn(160, minOf(200, maxLow))
        }

        internal fun clampHighThreshold(requestedHigh: Int, currentLow: Int): Int {
            val minHigh = (currentLow + MIN_HYSTERESIS_V).coerceAtMost(230)
            return requestedHigh.coerceIn(maxOf(195, minHigh), 230)
        }

        internal fun normalizeThresholds(rawLow: Int, rawHigh: Int): Pair<Int, Int> {
            val low = rawLow.coerceIn(160, 200)
            val high = clampHighThreshold(rawHigh, low)
            val adjustedLow = clampLowThreshold(low, high)
            return adjustedLow to high
        }
    }
}
