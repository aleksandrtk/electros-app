package ua.org.electros.app.ui.dashboard

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.math.roundToInt
import ua.org.electros.app.data.local.AppSettings
import ua.org.electros.app.data.local.SettingsStore
import ua.org.electros.app.data.local.entity.ChargeSessionEntity
import ua.org.electros.app.data.local.entity.ChargeSliceEntity
import ua.org.electros.app.data.local.entity.SessionStatus
import ua.org.electros.app.data.model.TelemetryData
import ua.org.electros.app.data.repository.ChargeRepository
import ua.org.electros.app.domain.accumulator.EnergyAccumulator
import ua.org.electros.app.service.ChargeMonitorService

data class DashboardUiState(
    val telemetry: TelemetryData? = null,
    val activeSession: ChargeSessionEntity? = null,
    val selectedAmperes: Int = 16,
    val isKwhLimitEnabled: Boolean = false,
    val targetQuotaKwh: Double = 15.0,
    val activeTargetKwh: Double = 0.0,
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
    val isAutoResumeEnabled: Boolean = true,
    val errorMessage: String? = null
)

class DashboardViewModel(
    private val repository: ChargeRepository,
    private val settingsStore: SettingsStore,
    private val isMonitorServiceRunning: () -> Boolean = { ChargeMonitorService.isRunning },
    private val clockMs: () -> Long = { System.currentTimeMillis() }
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

    @OptIn(ExperimentalCoroutinesApi::class)
    private val activeSessionFlow = repository.observeActiveSession()

    @OptIn(ExperimentalCoroutinesApi::class)
    private val activeSlicesFlow = activeSessionFlow.flatMapLatest { session ->
        if (session != null) repository.observeSlicesForSession(session.id) else flowOf(emptyList())
    }

    val uiState: StateFlow<DashboardUiState> = combine(
        repository.telemetryState,
        activeSessionFlow,
        activeSlicesFlow,
        settingsStore.settingsFlow,
        _operationState
    ) { telemetry, activeSession, slices, settings, opState ->
        val (busy, refreshing, error) = opState
        buildUiState(
            telemetry = telemetry,
            activeSession = activeSession,
            slices = slices,
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
            slices = emptyList(),
            settings = settingsStore.currentSettings(),
            busy = false,
            refreshing = false,
            error = null
        )
    )

    private fun buildUiState(
        telemetry: TelemetryData?,
        activeSession: ChargeSessionEntity?,
        slices: List<ChargeSliceEntity>,
        settings: AppSettings,
        busy: Boolean,
        refreshing: Boolean,
        error: String?
    ): DashboardUiState {
        val isLimitEnabled = settings.isKwhLimitEnabled
        val configuredQuota = settings.targetKwhLimit
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
        val hasRecordedProgress = activeSession != null && (
            activeSession.totalKwhDelivered > 0.0 ||
                activeSession.accumulatedChargingMs > 0L ||
                activeSession.lastTickAt > 0L
            )
        val isStaleIdleOnlineSession = activeSession != null &&
            !isTrackedOutage &&
            !isStopPendingSession &&
            isOnline &&
            !isCharging &&
            !isMonitorServiceRunning() &&
            (clockMs() - activeSession.startedAt) >= STALE_IDLE_SESSION_GRACE_MS

        val effectiveSession = if (activeSession != null &&
            ((!isTrackedOutage &&
                !isStopPendingSession &&
                (!isOnline || !isCharging) &&
                !hasRecordedProgress) ||
                isStaleIdleOnlineSession)
        ) {
            null
        } else {
            activeSession
        }

        val delivered = if (effectiveSession == null) {
            0.0
        } else if (slices.isEmpty()) {
            effectiveSession.totalKwhDelivered
        } else {
            val finalizedSlices = slices.filter { it.endedAt != null }
            val openSlice = slices.lastOrNull { it.endedAt == null }
            val sliceStart = openSlice?.stationCounterStartKwh ?: 0.0
            val counterForCalc = when {
                openSlice == null -> 0.0
                telemetry != null && telemetry.isOnline &&
                    !EnergyAccumulator.isCounterRegression(telemetry.consumedEnergyKwh, openSlice.stationCounterStartKwh) ->
                    telemetry.consumedEnergyKwh
                else -> maxOf(openSlice.stationCounterStartKwh, openSlice.stationCounterLastKwh)
            }
            maxOf(
                effectiveSession.totalKwhDelivered,
                EnergyAccumulator.calculateTotalDeliveredKwh(
                    finalizedSlices = finalizedSlices,
                    activeSliceStartKwh = sliceStart,
                    currentStationCounterKwh = counterForCalc
                )
            )
        }
        val activeTargetQuota = effectiveSession?.targetKwh
            ?: if (isLimitEnabled) configuredQuota else 0.0
        val activeTargetMin = effectiveSession?.targetDurationMinutes ?: if (isTimerEnabled) durationHours * 60 else 0
        val accumulatedMs = effectiveSession?.accumulatedChargingMs ?: 0L
        val lastTick = effectiveSession?.lastTickAt ?: 0L

        val isStopPending = effectiveSession?.status == SessionStatus.STOP_PENDING
        val isOutage = effectiveSession != null &&
                !isStopPending &&
                (effectiveSession.totalKwhDelivered > 0.0 ||
                    effectiveSession.accumulatedChargingMs > 0L ||
                    effectiveSession.lastTickAt > 0L ||
                    isTrackedOutage) &&
                (isTrackedOutage || (telemetry != null && !isOnline))

        return DashboardUiState(
            telemetry = telemetry,
            activeSession = effectiveSession,
            selectedAmperes = settings.preferredAmperage,
            isKwhLimitEnabled = isLimitEnabled,
            targetQuotaKwh = configuredQuota,
            activeTargetKwh = activeTargetQuota,
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
            isAutoResumeEnabled = settings.autoResumeAfterOutage,
            errorMessage = error
        )
    }

    suspend fun pollWhileVisible() {
        repository.cancelStaleSessionsIfOffline(isMonitorServiceRunning = isMonitorServiceRunning())
        var isInitialFetch = true
        while (currentCoroutineContext().isActive) {
            val monitorRunning = isMonitorServiceRunning()
            if (isInitialFetch || !monitorRunning) {
                repository.fetchLatestTelemetry(isMonitorServiceRunning = monitorRunning)
                isInitialFetch = false
            }
            delay(60_000L)
        }
    }

    fun ensureMonitorServiceRunning(context: Context) {
        val active = uiState.value.activeSession ?: return
        if (!isMonitorServiceRunning()) {
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
            repository.fetchLatestTelemetry(isMonitorServiceRunning = isMonitorServiceRunning())
            _isRefreshing.value = false
        }
    }

    fun toggleVoltageBalancing(enabled: Boolean) {
        settingsStore.setVoltageBalancingEnabled(enabled)
    }

    fun onAmperageSelected(amperes: Int) {
        settingsStore.setPreferredAmperage(amperes)
        _errorMessage.value = null
        val currentState = uiState.value
        val active = currentState.activeSession
        viewModelScope.launch {
            if (active != null) {
                repository.updateSessionAmperage(active.id, amperes)
            }
            // WS setAmperage sends a "start" frame on Electros firmware, so verify live charging before pushing it.
            if (currentState.telemetry?.isChargingActive == true) {
                val fresh = repository.fetchLatestTelemetry(isMonitorServiceRunning = isMonitorServiceRunning()).getOrNull()
                if (fresh?.isChargingActive == true) {
                    val setRes = repository.setAmperage(amperes)
                    if (setRes.isSuccess) {
                        if (active != null) {
                            repository.refreshDeadManTimer(
                                sessionId = active.id,
                                voltageVolts = fresh.voltageCurrent ?: 230,
                                deliveredKwh = currentState.totalDeliveredKwh
                            )
                        }
                    } else {
                        _errorMessage.value = formatDashboardError(
                            setRes.exceptionOrNull(),
                            "Failed to update charge current on station"
                        )
                    }
                    repository.fetchLatestTelemetry(isMonitorServiceRunning = isMonitorServiceRunning())
                }
            }
        }
    }

    fun setKwhLimit(context: Context? = null, enabled: Boolean, quotaKwh: Double) {
        val clampedQuota = quotaKwh.roundToInt().toDouble().coerceIn(1.0, 100.0)
        settingsStore.setKwhLimit(enabled, clampedQuota)

        val currentState = uiState.value
        val existingSession = currentState.activeSession
        val appliedQuota = if (enabled) clampedQuota else 0.0

        viewModelScope.launch {
            if (existingSession != null) {
                repository.updateTargetKwh(existingSession.id, appliedQuota)
                if (context != null && !isMonitorServiceRunning()) {
                    ChargeMonitorService.start(
                        context,
                        existingSession.id,
                        appliedQuota,
                        existingSession.targetDurationMinutes
                    )
                }
            } else if (enabled && currentState.telemetry?.isChargingActive == true) {
                attachOngoingSessionIfStillCharging(context)
            }
        }
    }

    fun setDurationTimer(context: Context? = null, enabled: Boolean, hours: Int) {
        val clampedHours = hours.coerceIn(1, 12)
        settingsStore.setDurationTimer(enabled, clampedHours)

        val durationMinutes = if (enabled) clampedHours * 60 else 0
        val currentState = uiState.value
        val existingSession = currentState.activeSession

        viewModelScope.launch {
            if (existingSession != null) {
                repository.updateTargetDurationMinutes(existingSession.id, durationMinutes)
                if (context != null && !isMonitorServiceRunning()) {
                    ChargeMonitorService.start(
                        context,
                        existingSession.id,
                        existingSession.targetKwh,
                        durationMinutes
                    )
                }
            } else if (enabled && currentState.telemetry?.isChargingActive == true) {
                attachOngoingSessionIfStillCharging(context)
            }
        }
    }

    private suspend fun attachOngoingSessionIfStillCharging(context: Context?) {
        val fresh = repository.fetchLatestTelemetry(isMonitorServiceRunning = isMonitorServiceRunning()).getOrNull()
        if (fresh?.isChargingActive != true) return
        val settings = settingsStore.awaitSettings()
        val quota = if (settings.isKwhLimitEnabled) settings.targetKwhLimit else 0.0
        val durationMin = if (settings.isDurationTimerEnabled) settings.targetDurationHours * 60 else 0
        val amps = settings.preferredAmperage
        val attachRes = repository.attachSessionToOngoingCharge(quota, amps, durationMin)
        attachRes.onSuccess { sessionId ->
            if (context != null) {
                ChargeMonitorService.start(context, sessionId, quota, durationMin)
            }
        }
    }

    fun setScheduleTimer(enabled: Boolean, startTime: String, stopTime: String) {
        settingsStore.setSchedule(enabled, startTime, stopTime)
        _errorMessage.value = null

        viewModelScope.launch {
            val res = if (enabled) {
                repository.setScheduleTimer(startTime, stopTime)
            } else {
                repository.setScheduleTimer("00:00", "00:00")
            }
            res.onFailure { err ->
                _errorMessage.value = formatDashboardError(err, "Failed to update station schedule timer")
            }
        }
    }

    fun onStartClicked(context: Context) {
        val telemetry = uiState.value.telemetry
        if (telemetry != null && !telemetry.isOnline) {
            _errorMessage.value = "Station is offline. Connect power to start charging."
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
                _errorMessage.value = formatDashboardError(err, "Failed to start charging")
            }
            _isBusy.value = false
        }
    }

    fun onStopClicked(context: Context? = null) {
        viewModelScope.launch {
            _isBusy.value = true
            _errorMessage.value = null
            val session = uiState.value.activeSession
            val res = if (session != null) {
                repository.stopActiveSession(session.id)
            } else {
                repository.stopActiveSession("manual_immediate")
            }
            if (res.isSuccess && context != null) {
                ChargeMonitorService.stop(context)
            }
            res.onFailure { err ->
                _errorMessage.value = formatDashboardError(err, "Failed to stop station")
            }
            repository.fetchLatestTelemetry(isMonitorServiceRunning = isMonitorServiceRunning())
            _isBusy.value = false
        }
    }

    private fun formatDashboardError(err: Throwable?, fallback: String): String {
        val raw = err?.message?.trim().orEmpty()
        return when {
            raw.isEmpty() -> fallback
            raw.contains("OFFLINE", ignoreCase = true) || raw.contains("вимкнена", ignoreCase = true) ->
                "Station is offline. Connect 220V grid power to start charging."
            raw.contains("Авто не підключено", ignoreCase = true) ->
                "Vehicle is not plugged into the station."
            raw.contains("Не вдалося зупинити", ignoreCase = true) ->
                "Failed to confirm station stop — retrying in background."
            raw.contains("Невірний номер", ignoreCase = true) ->
                "Invalid station serial or password."
            else -> raw
        }
    }

    companion object {
        private const val STALE_IDLE_SESSION_GRACE_MS = 30_000L
    }
}
