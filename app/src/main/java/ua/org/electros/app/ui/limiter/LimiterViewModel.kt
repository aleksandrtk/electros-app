package ua.org.electros.app.ui.limiter

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import ua.org.electros.app.data.local.AppSettings
import ua.org.electros.app.data.local.SettingsStore
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
    val isApplyingSchedule: Boolean = false,
    val scheduleError: String? = null,
    val isVoltageBalancingEnabled: Boolean = true,
    val voltageLowThreshold: Int = 185,
    val voltageHighThreshold: Int = 205,
    val voltageMaxAmperage: Int = 12
)

class LimiterViewModel(
    private val repository: ChargeRepository,
    private val settingsStore: SettingsStore
) : ViewModel() {

    private data class ScheduleStatus(
        val saved: Boolean = false,
        val applying: Boolean = false,
        val error: String? = null
    )

    private val _scheduleStatus = MutableStateFlow(ScheduleStatus())

    val uiState: StateFlow<LimiterUiState> = combine(
        settingsStore.settingsFlow,
        _scheduleStatus
    ) { settings, status ->
        toUiState(settings, status)
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5000),
        initialValue = toUiState(settingsStore.currentSettings(), ScheduleStatus())
    )

    private fun toUiState(settings: AppSettings, status: ScheduleStatus): LimiterUiState {
        val (low, high) = normalizeThresholds(
            settings.voltageLowThreshold,
            settings.voltageHighThreshold
        )
        return LimiterUiState(
            isKwhLimitEnabled = settings.isKwhLimitEnabled,
            targetKwh = settings.targetKwhLimit,
            isDurationTimerEnabled = settings.isDurationTimerEnabled,
            targetDurationHours = settings.targetDurationHours,
            isScheduleEnabled = settings.isScheduleEnabled,
            startTime = settings.scheduleStartTime,
            stopTime = settings.scheduleStopTime,
            isAutoResumeEnabled = settings.autoResumeAfterOutage,
            isScheduleSaved = status.saved,
            isApplyingSchedule = status.applying,
            scheduleError = status.error,
            isVoltageBalancingEnabled = settings.isVoltageBalancingEnabled,
            voltageLowThreshold = low,
            voltageHighThreshold = high,
            voltageMaxAmperage = settings.voltageMaxAmperage
        )
    }

    fun toggleDurationTimer(enabled: Boolean) {
        settingsStore.setDurationTimerEnabled(enabled)
        viewModelScope.launch {
            val hours = settingsStore.awaitSettings().targetDurationHours
            repository.getActiveSession()?.let { active ->
                repository.updateTargetDurationMinutes(active.id, if (enabled) hours * 60 else 0)
            }
        }
    }

    fun setTargetDurationHours(hours: Int) {
        val clamped = hours.coerceIn(1, 12)
        settingsStore.setTargetDurationHours(clamped)
        viewModelScope.launch {
            if (settingsStore.awaitSettings().isDurationTimerEnabled) {
                repository.getActiveSession()?.let { active ->
                    repository.updateTargetDurationMinutes(active.id, clamped * 60)
                }
            }
        }
    }

    fun toggleKwhLimit(enabled: Boolean) {
        settingsStore.setKwhLimitEnabled(enabled)
        viewModelScope.launch {
            val quota = settingsStore.awaitSettings().targetKwhLimit
            repository.getActiveSession()?.let { active ->
                repository.updateTargetKwh(active.id, if (enabled) quota else 0.0)
            }
        }
    }

    fun updateTargetKwh(delta: Double) {
        settingsStore.adjustTargetKwhLimit(delta)
        viewModelScope.launch {
            val settings = settingsStore.awaitSettings()
            if (settings.isKwhLimitEnabled) {
                repository.getActiveSession()?.let { active ->
                    repository.updateTargetKwh(active.id, settings.targetKwhLimit)
                }
            }
        }
    }

    fun setTargetKwh(quota: Double) {
        val clamped = quota.coerceIn(1.0, 100.0)
        settingsStore.setTargetKwhLimit(clamped)
        viewModelScope.launch {
            if (settingsStore.awaitSettings().isKwhLimitEnabled) {
                repository.getActiveSession()?.let { active ->
                    repository.updateTargetKwh(active.id, clamped)
                }
            }
        }
    }

    fun toggleSchedule(enabled: Boolean) {
        _scheduleStatus.value = ScheduleStatus()
        settingsStore.setScheduleEnabled(enabled)
    }

    fun setScheduleTimes(start: String, stop: String) {
        _scheduleStatus.value = ScheduleStatus()
        settingsStore.setScheduleTimes(start, stop)
    }

    fun toggleAutoResume(enabled: Boolean) {
        settingsStore.setAutoResumeAfterOutage(enabled)
    }

    fun applyScheduleToStation() {
        if (_scheduleStatus.value.applying) return
        viewModelScope.launch {
            _scheduleStatus.value = ScheduleStatus(saved = false, applying = true, error = null)
            val settings = settingsStore.awaitSettings()
            val result = if (settings.isScheduleEnabled) {
                repository.setScheduleTimer(settings.scheduleStartTime, settings.scheduleStopTime)
            } else {
                repository.setScheduleTimer("00:00", "00:00")
            }
            _scheduleStatus.value = result.fold(
                onSuccess = { ScheduleStatus(saved = true, applying = false, error = null) },
                onFailure = { err ->
                    ScheduleStatus(
                        saved = false,
                        applying = false,
                        error = err.message ?: "Не вдалося передати налаштування на станцію"
                    )
                }
            )
        }
    }

    fun toggleVoltageBalancing(enabled: Boolean) {
        settingsStore.setVoltageBalancingEnabled(enabled)
    }

    fun setVoltageLowThreshold(threshold: Int) {
        settingsStore.setVoltageLowThreshold(threshold)
    }

    fun setVoltageHighThreshold(threshold: Int) {
        settingsStore.setVoltageHighThreshold(threshold)
    }

    fun setVoltageMaxAmperage(amperage: Int) {
        settingsStore.setVoltageMaxAmperage(amperage)
    }

    companion object {
        const val MIN_HYSTERESIS_V = AppSettings.MIN_HYSTERESIS_V

        internal fun clampLowThreshold(requestedLow: Int, currentHigh: Int): Int =
            AppSettings.clampLowThreshold(requestedLow, currentHigh)

        internal fun clampHighThreshold(requestedHigh: Int, currentLow: Int): Int =
            AppSettings.clampHighThreshold(requestedHigh, currentLow)

        internal fun normalizeThresholds(rawLow: Int, rawHigh: Int): Pair<Int, Int> =
            AppSettings.normalizeThresholds(rawLow, rawHigh)
    }
}
