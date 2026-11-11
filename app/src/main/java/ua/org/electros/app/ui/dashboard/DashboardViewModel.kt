package ua.org.electros.app.ui.dashboard

import android.content.Context
import android.content.SharedPreferences
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
    private val authPreferences: AuthPreferences
) : ViewModel() {

    private val _selectedAmperes = MutableStateFlow(authPreferences.preferredAmperage)
    private val _isBusy = MutableStateFlow(false)
    private val _isRefreshing = MutableStateFlow(false)
    private val _errorMessage = MutableStateFlow<String?>(null)
    private val _prefsTrigger = MutableStateFlow(0)

    private val prefListener = SharedPreferences.OnSharedPreferenceChangeListener { _, _ ->
        _prefsTrigger.value += 1
    }

    private val _operationState = combine(
        _isBusy,
        _isRefreshing,
        _errorMessage,
        _prefsTrigger
    ) { busy, refreshing, err, _ ->
        Triple(busy, refreshing, err)
    }

    val uiState: StateFlow<DashboardUiState> = combine(
        repository.telemetryState,
        repository.observeActiveSession(),
        _selectedAmperes,
        _operationState
    ) { telemetry, activeSession, amperes, opState ->
        val (busy, refreshing, error) = opState
        val isLimitEnabled = authPreferences.isKwhLimitEnabled
        val quota = if (isLimitEnabled) authPreferences.targetKwhLimit else 0.0
        val isDlbEnabled = authPreferences.isVoltageBalancingEnabled
        val dlbThreshold = authPreferences.voltageLowThreshold
        val dlbMaxAmperage = authPreferences.voltageMaxAmperage
        val isTimerEnabled = authPreferences.isDurationTimerEnabled
        val durationHours = authPreferences.targetDurationHours
        val isSchedEnabled = authPreferences.isScheduleEnabled
        val schedStart = authPreferences.scheduleStartTime
        val schedStop = authPreferences.scheduleStopTime

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

        DashboardUiState(
            telemetry = telemetry,
            activeSession = effectiveSession,
            selectedAmperes = amperes,
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
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5000),
        initialValue = DashboardUiState(
            selectedAmperes = authPreferences.preferredAmperage,
            isVoltageBalancingEnabled = authPreferences.isVoltageBalancingEnabled,
            voltageLowThreshold = authPreferences.voltageLowThreshold,
            voltageMaxAmperage = authPreferences.voltageMaxAmperage,
            isDurationTimerEnabled = authPreferences.isDurationTimerEnabled,
            targetDurationHours = authPreferences.targetDurationHours,
            isScheduleEnabled = authPreferences.isScheduleEnabled,
            scheduleStartTime = authPreferences.scheduleStartTime,
            scheduleStopTime = authPreferences.scheduleStopTime
        )
    )

    init {
        authPreferences.registerListener(prefListener)
    }

    override fun onCleared() {
        super.onCleared()
        authPreferences.unregisterListener(prefListener)
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
        _selectedAmperes.value = amperes
        authPreferences.preferredAmperage = amperes
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
        authPreferences.isDurationTimerEnabled = enabled
        authPreferences.targetDurationHours = clampedHours
        _prefsTrigger.value += 1

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
                val quota = if (authPreferences.isKwhLimitEnabled) authPreferences.targetKwhLimit else 0.0
                val amps = currentState.selectedAmperes
                val attachRes = repository.attachSessionToOngoingCharge(quota, amps, durationMinutes)
                attachRes.onSuccess { sessionId ->
                    ChargeMonitorService.start(context, sessionId, quota, durationMinutes)
                }
            }
        }
    }

    fun setScheduleTimer(enabled: Boolean, startTime: String, stopTime: String) {
        authPreferences.isScheduleEnabled = enabled
        authPreferences.scheduleStartTime = startTime
        authPreferences.scheduleStopTime = stopTime
        _prefsTrigger.value += 1

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

            val quota = if (authPreferences.isKwhLimitEnabled) authPreferences.targetKwhLimit else 0.0
            val durationMin = if (authPreferences.isDurationTimerEnabled) authPreferences.targetDurationHours * 60 else 0
            val amps = uiState.value.selectedAmperes

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
