package ua.org.electros.app.ui.limiter

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import ua.org.electros.app.data.repository.ScheduleSyncResult
import java.time.LocalTime
import java.time.format.DateTimeFormatter
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
    val scheduleSync: ScheduleSyncUi = ScheduleSyncUi.IN_SYNC,
    /** Local time ("HH:mm") of the write done from this screen, if any. */
    val scheduleWrittenAt: String? = null,
    val isVoltageBalancingEnabled: Boolean = true,
    val voltageLowThreshold: Int = AppSettings.DEFAULT_VOLTAGE_LOW_THRESHOLD,
    val voltageHighThreshold: Int = 205,
    val voltageMaxAmperage: Int = 12
)

enum class ScheduleSyncUi { IN_SYNC, WRITING, PENDING, OFFLINE, AFTER_SESSION, ERROR }

class LimiterViewModel(
    private val repository: ChargeRepository,
    private val settingsStore: SettingsStore
) : ViewModel() {

    private data class ScheduleStatus(
        val writing: Boolean = false,
        val lastResult: ScheduleSyncResult? = null,
        val failed: Boolean = false,
        val writtenAt: String? = null
    )

    private var scheduleSyncJob: Job? = null

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
        return LimiterUiState(
            isKwhLimitEnabled = settings.isKwhLimitEnabled,
            targetKwh = settings.targetKwhLimit,
            isDurationTimerEnabled = settings.isDurationTimerEnabled,
            targetDurationHours = settings.targetDurationHours,
            isScheduleEnabled = settings.isScheduleEnabled,
            startTime = settings.scheduleStartTime,
            stopTime = settings.scheduleStopTime,
            isAutoResumeEnabled = settings.autoResumeAfterOutage,
            scheduleSync = when {
                status.writing -> ScheduleSyncUi.WRITING
                !settings.isSchedulePending -> ScheduleSyncUi.IN_SYNC
                status.failed -> ScheduleSyncUi.ERROR
                status.lastResult == ScheduleSyncResult.OFFLINE -> ScheduleSyncUi.OFFLINE
                status.lastResult == ScheduleSyncResult.AFTER_SESSION -> ScheduleSyncUi.AFTER_SESSION
                else -> ScheduleSyncUi.PENDING
            },
            scheduleWrittenAt = status.writtenAt,
            isVoltageBalancingEnabled = settings.isVoltageBalancingEnabled,
            voltageLowThreshold = settings.voltageLowThreshold,
            voltageHighThreshold = settings.voltageHighThreshold,
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
        settingsStore.setScheduleEnabled(enabled)
        syncSchedule(debounceMs = SCHEDULE_DEBOUNCE_MS)
    }

    fun setScheduleTimes(start: String, stop: String) {
        settingsStore.setScheduleTimes(start, stop)
        syncSchedule(debounceMs = SCHEDULE_DEBOUNCE_MS)
    }

    /**
     * Writes the schedule to the station after a short pause, so a few taps on +/− send one command.
     * While the station is offline it keeps retrying for as long as this screen's ViewModel lives.
     */
    private fun syncSchedule(debounceMs: Long) {
        scheduleSyncJob?.cancel()
        scheduleSyncJob = viewModelScope.launch {
            _scheduleStatus.value = _scheduleStatus.value.copy(writing = true, failed = false)
            delay(debounceMs)
            while (true) {
                val result = repository.syncScheduleToStation()
                val outcome = result.getOrNull()
                _scheduleStatus.value = ScheduleStatus(
                    writing = false,
                    lastResult = outcome,
                    failed = result.isFailure,
                    writtenAt = if (outcome == ScheduleSyncResult.WRITTEN) {
                        LocalTime.now().format(DateTimeFormatter.ofPattern("HH:mm"))
                    } else {
                        _scheduleStatus.value.writtenAt
                    }
                )
                if (outcome == ScheduleSyncResult.WRITTEN || outcome == ScheduleSyncResult.IN_SYNC) break
                delay(SCHEDULE_RETRY_MS)
            }
        }
    }

    init {
        if (settingsStore.currentSettings().isSchedulePending) syncSchedule(debounceMs = 0L)
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

    private companion object {
        const val SCHEDULE_DEBOUNCE_MS = 1_500L
        const val SCHEDULE_RETRY_MS = 30_000L
    }
}
