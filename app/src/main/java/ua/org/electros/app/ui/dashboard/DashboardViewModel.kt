package ua.org.electros.app.ui.dashboard

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import ua.org.electros.app.data.local.AppSettings
import ua.org.electros.app.data.local.SettingsStore
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
    val isStopPending: Boolean = false,
    val isStartingOrStopping: Boolean = false,
    val isRefreshing: Boolean = false,
    val isVoltageBalancingEnabled: Boolean = true,
    val voltageLowThreshold: Int = 185,
    val voltageMaxAmperage: Int = 12,
    val isDurationTimerEnabled: Boolean = false,
    val targetDurationHours: Int = 2,
    val activeTargetDurationMinutes: Int = 0,
    val accumulatedChargingMs: Long = 0L,
    val lastTickAt: Long = 0L,
    val isScheduleEnabled: Boolean = false,
    val scheduleStartTime: String = "23:00",
    val scheduleStopTime: String = "07:00",
    val errorMessage: String? = null
)

class DashboardViewModel(
    private val repository: ChargeRepository,
    private val settingsStore: SettingsStore
) : ViewModel() {

    private val _isBusy = MutableStateFlow(false)
    private val _isRefreshing = MutableStateFlow(false)
    private val _errorMessage = MutableStateFlow<String?>(null)

    private val _operationState = combine(
        _isBusy,
        _isRefreshing,
        _errorMessage
    ) { busy, refreshing, err ->
        Triple(busy, refreshing, err)
    }

    val uiState: StateFlow<DashboardUiState> = combine(
        repository.telemetryState,
        repository.observeActiveSession(),
        settingsStore.settingsFlow,
        _operationState
    ) { telemetry, activeSession, settings, opState ->
        val (busy, refreshing, error) = opState
        buildUiState(
            telemetry = telemetry,
            activeSession = activeSession,
            settings = settings,
            busy = busy,
            refreshing = refreshing,
            error = error
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5000),
        initialValue = buildUiState(
            telemetry = null,
            activeSession = null,
            settings = settingsStore.currentSettings(),
            busy = false,
            refreshing = false,
            error = null
        )
    )

    private fun buildUiState(
        telemetry: TelemetryData?,
        activeSession: ChargeSessionEntity?,
        settings: AppSettings,
        busy: Boolean,
        refreshing: Boolean,
        error: String?
    ): DashboardUiState {
        val isLimitEnabled = settings.isKwhLimitEnabled
        val quota = if (isLimitEnabled) settings.targetKwhLimit else 0.0
        val isDlbEnabled = settings.isVoltageBalancingEnabled
        val dlbThreshold = settings.voltageLowThreshold
        val dlbMaxAmperage = settings.voltageMaxAmperage
        val isTimerEnabled = settings.isDurationTimerEnabled
        val durationHours = settings.targetDurationHours
        val isSchedEnabled = settings.isScheduleEnabled
        val schedStart = settings.scheduleStartTime
        val schedStop = settings.scheduleStopTime

        val isOnline = telemetry?.isOnline == true
        val isCharging = telemetry?.isChargingActive == true

        val isTrackedOutage = activeSession?.status in listOf(
            SessionStatus.OUTAGE_DETECTED,
            SessionStatus.OUTAGE_STABILIZING,
            SessionStatus.AUTO_RESUMING
        )
        val isStopPendingSession = activeSession?.status == SessionStatus.STOP_PENDING

        val effectiveSession = if (activeSession != null &&
            !isTrackedOutage &&
            !isStopPendingSession &&
            (!isOnline || !isCharging) &&
            activeSession.totalKwhDelivered == 0.0 &&
            activeSession.accumulatedChargingMs == 0L
        ) {
            null
        } else {
            activeSession
        }

        val delivered = effectiveSession?.totalKwhDelivered ?: 0.0
        val activeTargetMin = effectiveSession?.targetDurationMinutes ?: if (isTimerEnabled) durationHours * 60 else 0
        val accumulatedMs = effectiveSession?.accumulatedChargingMs ?: 0L
        val lastTick = effectiveSession?.lastTickAt ?: 0L

        val isStopPending = effectiveSession?.status == SessionStatus.STOP_PENDING
        val isOutage = effectiveSession != null &&
                !isStopPending &&
                (effectiveSession.totalKwhDelivered > 0.0 ||
                    effectiveSession.accumulatedChargingMs > 0L ||
                    effectiveSession.targetDurationMinutes > 0 ||
                    isTrackedOutage) &&
                (effectiveSession.status == SessionStatus.OUTAGE_DETECTED ||
                    effectiveSession.status == SessionStatus.OUTAGE_STABILIZING ||
                    !isOnline)

        return DashboardUiState(
            telemetry = telemetry,
            activeSession = effectiveSession,
            selectedAmperes = settings.preferredAmperage,
            isKwhLimitEnabled = isLimitEnabled,
            targetQuotaKwh = quota,
            totalDeliveredKwh = delivered,
            isOutageActive = isOutage,
            isStopPending = isStopPending,
            isStartingOrStopping = busy,
            isRefreshing = refreshing,
            isVoltageBalancingEnabled = isDlbEnabled,
            voltageLowThreshold = dlbThreshold,
            voltageMaxAmperage = dlbMaxAmperage,
            isDurationTimerEnabled = isTimerEnabled,
            targetDurationHours = durationHours,
            activeTargetDurationMinutes = activeTargetMin,
            accumulatedChargingMs = accumulatedMs,
            lastTickAt = lastTick,
            isScheduleEnabled = isSchedEnabled,
            scheduleStartTime = schedStart,
            scheduleStopTime = schedStop,
            errorMessage = error
        )
    }

    suspend fun pollWhileVisible() {
        repository.cancelStaleSessionsIfOffline()
        while (currentCoroutineContext().isActive) {
            repository.fetchLatestTelemetry(isMonitorServiceRunning = ChargeMonitorService.isRunning)
            delay(60_000L)
        }
    }

    fun ensureMonitorServiceRunning(context: Context) {
        val active = uiState.value.activeSession ?: return
        if (!ChargeMonitorService.isRunning) {
            ChargeMonitorService.start(
                context,
                active.id,
                active.targetKwh,
                active.targetDurationMinutes
            )
        }
    }

    fun refreshTelemetry() {
        viewModelScope.launch {
            _isRefreshing.value = true
            repository.fetchLatestTelemetry(isMonitorServiceRunning = ChargeMonitorService.isRunning)
            _isRefreshing.value = false
        }
    }

    fun onAmperageSelected(amperes: Int) {
        settingsStore.setPreferredAmperage(amperes)
        val active = uiState.value.activeSession
        viewModelScope.launch {
            if (active != null) {
                repository.updateSessionAmperage(active.id, amperes)
            }
            repository.setAmperage(amperes)
            repository.fetchLatestTelemetry(isMonitorServiceRunning = ChargeMonitorService.isRunning)
        }
    }

    fun setDurationTimer(context: Context, enabled: Boolean, hours: Int) {
        val clampedHours = hours.coerceIn(1, 12)
        settingsStore.setDurationTimer(enabled, clampedHours)

        val durationMinutes = if (enabled) clampedHours * 60 else 0
        val currentState = uiState.value
        val existingSession = currentState.activeSession

        viewModelScope.launch {
            if (existingSession != null) {
                repository.updateTargetDurationMinutes(existingSession.id, durationMinutes)
                if (!ChargeMonitorService.isRunning) {
                    ChargeMonitorService.start(
                        context,
                        existingSession.id,
                        existingSession.targetKwh,
                        durationMinutes
                    )
                }
            } else if (enabled && currentState.telemetry?.isChargingActive == true) {
                val settings = settingsStore.awaitSettings()
                val quota = if (settings.isKwhLimitEnabled) settings.targetKwhLimit else 0.0
                val amps = settings.preferredAmperage
                val attachRes = repository.attachSessionToOngoingCharge(quota, amps, durationMinutes)
                attachRes.onSuccess { sessionId ->
                    ChargeMonitorService.start(context, sessionId, quota, durationMinutes)
                }
            }
        }
    }

    fun setScheduleTimer(enabled: Boolean, startTime: String, stopTime: String) {
        settingsStore.setSchedule(enabled, startTime, stopTime)

        viewModelScope.launch {
            if (enabled) {
                repository.setScheduleTimer(startTime, stopTime)
            } else {
                repository.setScheduleTimer("00:00", "00:00")
            }
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

            val settings = settingsStore.awaitSettings()
            val quota = if (settings.isKwhLimitEnabled) settings.targetKwhLimit else 0.0
            val durationMin = if (settings.isDurationTimerEnabled) settings.targetDurationHours * 60 else 0
            val amps = settings.preferredAmperage

            val result = repository.startNewSession(quota, amps, durationMin)
            result.onSuccess { sessionId ->
                ChargeMonitorService.start(context, sessionId, quota, durationMin)
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
            val res = if (session != null) {
                repository.stopActiveSession(session.id)
            } else {
                repository.stopActiveSession("manual_immediate")
            }
            res.onFailure { err ->
                _errorMessage.value = err.message ?: "Не вдалося зупинити станцію"
            }
            repository.fetchLatestTelemetry(isMonitorServiceRunning = ChargeMonitorService.isRunning)
            _isBusy.value = false
        }
    }
}
