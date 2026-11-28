package ua.org.electros.app.domain.monitor

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import ua.org.electros.app.data.local.entity.ChargeSessionEntity
import ua.org.electros.app.data.local.entity.ChargeSliceEntity
import ua.org.electros.app.data.local.entity.SessionStatus
import ua.org.electros.app.data.local.entity.SliceEndReason
import ua.org.electros.app.data.model.TelemetryData
import ua.org.electros.app.domain.accumulator.EnergyAccumulator
import ua.org.electros.app.domain.balancing.VoltageLoadBalancer
import ua.org.electros.app.domain.statemachine.ChargingStateMachine
import ua.org.electros.app.domain.statemachine.StateEvent
import ua.org.electros.app.diag.DiagLog
import ua.org.electros.app.diag.kwh
import ua.org.electros.app.diag.reason
import ua.org.electros.app.diag.toLogString

interface SessionMonitorGateway {
    suspend fun fetchTelemetry(): Result<TelemetryData>
    suspend fun getSessionById(sessionId: String): ChargeSessionEntity?
    suspend fun getActiveSession(): ChargeSessionEntity?
    suspend fun getSlicesForSession(sessionId: String): List<ChargeSliceEntity>
    suspend fun updateDurationProgress(sessionId: String, accumulatedMs: Long, lastTickAt: Long)
    suspend fun updateActiveSliceCounter(sessionId: String, counterKwh: Double)
    suspend fun handleOutageDetected(sessionId: String, lastCounter: Double)
    suspend fun handleOutageStabilizing(sessionId: String)
    suspend fun restoreChargingFromExistingSlice(sessionId: String, currentCounter: Double)
    suspend fun resumeAfterOutage(sessionId: String, isManual: Boolean = false): Result<Unit>
    suspend fun markSessionTargetReached(sessionId: String, finalCounter: Double): Boolean
    suspend fun markSessionVehicleStopped(sessionId: String, finalCounter: Double)
    suspend fun finalizeSessionStopped(
        sessionId: String,
        lastCounter: Double,
        sliceReason: SliceEndReason,
        finalStatus: SessionStatus
    )
    suspend fun stopAndConfirm(allowOfflineConfirmation: Boolean): Boolean
    suspend fun stopActiveSession(sessionId: String): Result<Unit>
    suspend fun setAmperage(amperes: Int): Result<Unit>
    suspend fun updateSessionAmperage(sessionId: String, amperes: Int)
    suspend fun refreshDeadManTimer(sessionId: String, currentVoltage: Int, currentDeliveredKwh: Double)
    fun latestCounterOr(fallback: Double): Double
    fun isAutoResumeEnabled(): Boolean
}

sealed interface SupervisorEvent {
    data class ShowOutageAlert(val deliveredKwh: Double, val autoResumeEnabled: Boolean) : SupervisorEvent
    data class ShowManualResumeAvailable(val amperage: Int, val deliveredKwh: Double) : SupervisorEvent
    data object CancelManualResumeAlert : SupervisorEvent
    data class ShowOutageResumed(val amperage: Int) : SupervisorEvent
    data class ShowTargetReached(val deliveredKwh: Double) : SupervisorEvent
    data class ShowDurationTargetReached(val targetDurationMinutes: Int, val deliveredKwh: Double) : SupervisorEvent
    data object ShowStopFailed : SupervisorEvent
    data class ShowBalancingAlert(val oldAmperage: Int, val newAmperage: Int, val voltage: Int) : SupervisorEvent
    data class ShowConnectionLost(val lastKnownAmperes: Int) : SupervisorEvent
    data class UpdateOngoingNotification(
        val powerKw: Double,
        val deliveredKwh: Double,
        val targetKwh: Double,
        val statusText: String,
        val targetDurationMinutes: Int,
        val accumulatedChargingMs: Long,
        /** Energy is actually flowing now; only then is the notification painted green. */
        val isCharging: Boolean = false
    ) : SupervisorEvent
}

data class TickOutcome(
    val status: SessionStatus,
    val events: List<SupervisorEvent>,
    val shouldStopService: Boolean
)

/**
 * Pure domain coordinator for per-tick charging supervision, outage recovery,
 * DLB adjustments, and target stop confirmation.
 */
class SessionSupervisor(
    private val gateway: SessionMonitorGateway,
    val voltageLoadBalancer: VoltageLoadBalancer,
    private val clockMs: () -> Long = System::currentTimeMillis
) {
    var activeSessionId: String? = null
        private set
    var activeTargetKwh: Double = 0.0
        private set
    var activeTargetDurationMinutes: Int = 0
        private set
    var accumulatedChargingMs: Long = 0L
        private set
    var lastKnownAmperes: Int = 16
        private set
    var stateMachine: ChargingStateMachine = ChargingStateMachine(SessionStatus.CHARGING)
        private set

    private var lastSampledCounterKwh: Double = 0.0
    private var lastActiveChargingSampleAt: Long = 0L
    private var zeroCurrentConsecutiveTicks: Int = 0
    private var inactiveOnlineConsecutiveTicks: Int = 0
    private var hasObservedActiveCharging: Boolean = false
    private var consecutiveFailureTicks: Int = 0
    private var hasAlertedConnectionLost: Boolean = false
    private var hasAlertedManualResume: Boolean = false
    private var pendingStopIsManual: Boolean = false
    private var hasAlertedStopFailed: Boolean = false
    private var lastDeadManRefreshAtMs: Long = 0L
    private val operationMutex = Mutex()

    fun onSessionStarted(sessionId: String?, targetKwh: Double, targetDurationMinutes: Int) {
        val isNewSession = sessionId != null && sessionId != activeSessionId
        activeSessionId = sessionId
        activeTargetKwh = targetKwh
        activeTargetDurationMinutes = targetDurationMinutes
        if (isNewSession) {
            accumulatedChargingMs = 0L
            lastActiveChargingSampleAt = 0L
            lastSampledCounterKwh = 0.0
            zeroCurrentConsecutiveTicks = 0
            inactiveOnlineConsecutiveTicks = 0
            hasObservedActiveCharging = false
            consecutiveFailureTicks = 0
            hasAlertedConnectionLost = false
            hasAlertedManualResume = false
            pendingStopIsManual = false
            hasAlertedStopFailed = false
            lastDeadManRefreshAtMs = 0L
            stateMachine = ChargingStateMachine(SessionStatus.CHARGING)
        }
    }

    suspend fun hydrateFromDatabase() = operationMutex.withLock {
        val id = activeSessionId ?: return@withLock
        gateway.getSessionById(id)?.let { dbSession ->
            activeTargetKwh = dbSession.targetKwh
            activeTargetDurationMinutes = dbSession.targetDurationMinutes
            accumulatedChargingMs = dbSession.accumulatedChargingMs
            lastKnownAmperes = dbSession.amperageSetting
            stateMachine = ChargingStateMachine(dbSession.status)
            inactiveOnlineConsecutiveTicks = 0
            val latestSlice = gateway.getSlicesForSession(id).lastOrNull()
            lastSampledCounterKwh = latestSlice?.stationCounterLastKwh ?: 0.0
            if (dbSession.accumulatedChargingMs > 0L || dbSession.totalKwhDelivered > 0.0 || dbSession.lastTickAt > 0L) {
                hasObservedActiveCharging = true
            }
            DiagLog.i(
                TAG,
                "Session ${id.take(8)} loaded: status=${dbSession.status} target=${dbSession.targetKwh}kWh/" +
                    "${dbSession.targetDurationMinutes}min amps=${dbSession.amperageSetting} " +
                    "delivered=${dbSession.totalKwhDelivered.kwh()} chargingMs=${dbSession.accumulatedChargingMs} " +
                    "lastCounter=${lastSampledCounterKwh.kwh()}"
            )
        } ?: DiagLog.w(TAG, "Session ${id.take(8)} not found in the database")
    }

    fun currentStatus(): SessionStatus = stateMachine.getState()

    private fun transition(event: StateEvent): SessionStatus {
        val before = stateMachine.getState()
        val after = stateMachine.transition(event)
        if (after != before) {
            DiagLog.i(TAG, "State $before -> $after (${event.javaClass.simpleName})")
        }
        return after
    }

    suspend fun tick(): TickOutcome = operationMutex.withLock {
        val events = mutableListOf<SupervisorEvent>()
        if (stateMachine.getState().isTerminal) {
            return@withLock TickOutcome(stateMachine.getState(), events, shouldStopService = true)
        }
        val sessionId = activeSessionId
            ?: return@withLock TickOutcome(stateMachine.getState(), events, shouldStopService = false)
        val result = gateway.fetchTelemetry()
        DiagLog.d(TAG) {
            "Tick ${sessionId.take(8)} [${stateMachine.getState()}] " + result.fold(
                onSuccess = { it.toLogString() },
                onFailure = { "telemetry failed #${consecutiveFailureTicks + 1}: ${it.reason()}" }
            )
        }

        val dbSession = gateway.getSessionById(sessionId)
        if (dbSession == null || dbSession.status.isTerminal) {
            DiagLog.i(TAG, "Session ${sessionId.take(8)} is ${dbSession?.status ?: "gone"} in the database, monitor stops")
            transition(StateEvent.CancelRequested)
            return@withLock TickOutcome(stateMachine.getState(), events, shouldStopService = true)
        }

        if (dbSession.status == SessionStatus.STOP_PENDING && stateMachine.getState() != SessionStatus.STOP_PENDING) {
            transition(StateEvent.StopPending)
        }

        activeTargetKwh = dbSession.targetKwh
        activeTargetDurationMinutes = dbSession.targetDurationMinutes

        var stopRequested = false

        result.onSuccess { data ->
            stopRequested = evaluateTelemetrySuccess(sessionId, dbSession, data, events)
        }.onFailure {
            stopRequested = evaluateTelemetryFailure(sessionId, dbSession, events)
        }

        val postStatus = stateMachine.getState()
        TickOutcome(
            status = postStatus,
            events = events,
            shouldStopService = stopRequested || postStatus.isTerminal
        )
    }

    private fun hasChargingEvidence(totalDelivered: Double, dbLastTickAt: Long = 0L): Boolean =
        hasObservedActiveCharging || totalDelivered > 0.0 || accumulatedChargingMs > 0L || dbLastTickAt > 0L

    private fun isVehicleUnplugged(statusText: String): Boolean =
        statusText.contains("Чекаю підключення", ignoreCase = true)

    private suspend fun closeStoppedOrUnstartedSession(
        sessionId: String,
        counterKwh: Double,
        hasEvidence: Boolean
    ) {
        if (hasEvidence) {
            transition(StateEvent.VehicleStoppedCharging)
            gateway.markSessionVehicleStopped(sessionId, counterKwh)
        } else {
            gateway.finalizeSessionStopped(
                sessionId = sessionId,
                lastCounter = counterKwh,
                sliceReason = SliceEndReason.MANUAL_STOP,
                finalStatus = SessionStatus.CANCELLED
            )
            transition(StateEvent.CancelRequested)
        }
    }

    private suspend fun evaluateTelemetrySuccess(
        sessionId: String,
        dbSession: ChargeSessionEntity,
        rawData: TelemetryData,
        events: MutableList<SupervisorEvent>
    ): Boolean {
        // The HTTP page rounds the counter to 0.1 kWh; a reading slightly below the last precise one is
        // rounding, not a station reboot.
        val data = if (rawData.isOnline && !rawData.isPreciseCounter &&
            rawData.consumedEnergyKwh < lastSampledCounterKwh &&
            lastSampledCounterKwh - rawData.consumedEnergyKwh <= HTTP_COUNTER_ROUNDING_KWH
        ) {
            DiagLog.d(TAG) {
                "HTTP counter ${rawData.consumedEnergyKwh.kwh()} is rounding below ${lastSampledCounterKwh.kwh()}, kept the precise value"
            }
            rawData.copy(consumedEnergyKwh = lastSampledCounterKwh)
        } else {
            rawData
        }
        consecutiveFailureTicks = 0
        hasAlertedConnectionLost = false
        lastKnownAmperes = data.selectedCurrentAmperes
        if (data.isOnline && data.isChargingActive) {
            hasObservedActiveCharging = true
            inactiveOnlineConsecutiveTicks = 0
        }
        var slices = gateway.getSlicesForSession(sessionId)
        var activeSlice = slices.lastOrNull { it.endedAt == null }

        val priorSliceCounter = maxOf(lastSampledCounterKwh, activeSlice?.stationCounterLastKwh ?: 0.0)
        if (data.isOnline &&
            activeSlice != null &&
            EnergyAccumulator.isCounterRegression(data.consumedEnergyKwh, priorSliceCounter)
        ) {
            DiagLog.w(
                TAG,
                "Station counter went back from ${priorSliceCounter.kwh()} to ${data.consumedEnergyKwh.kwh()} kWh " +
                    "(station restarted), closing the slice"
            )
            handleCounterRegression(
                sessionId = sessionId,
                dbSession = dbSession,
                slices = slices,
                activeSliceStart = activeSlice.stationCounterStartKwh,
                preRebootCounter = priorSliceCounter,
                events = events
            )
            slices = gateway.getSlicesForSession(sessionId)
            activeSlice = slices.lastOrNull { it.endedAt == null }
        }
        if (data.isOnline) {
            lastSampledCounterKwh = data.consumedEnergyKwh
            if (activeSlice != null) {
                gateway.updateActiveSliceCounter(sessionId, data.consumedEnergyKwh)
            }
        }

        if (data.isOnline &&
            data.isChargingActive &&
            (stateMachine.getState() == SessionStatus.OUTAGE_DETECTED ||
                stateMachine.getState() == SessionStatus.OUTAGE_STABILIZING ||
                stateMachine.getState() == SessionStatus.AUTO_RESUMING)
        ) {
            transition(StateEvent.ResumeCommandSent)
            inactiveOnlineConsecutiveTicks = 0
            hasAlertedManualResume = false
            events += SupervisorEvent.CancelManualResumeAlert
            gateway.restoreChargingFromExistingSlice(sessionId, data.consumedEnergyKwh)
            slices = gateway.getSlicesForSession(sessionId)
            activeSlice = slices.lastOrNull { it.endedAt == null }
        }

        val effectiveCounter = if (data.isOnline) {
            data.consumedEnergyKwh
        } else {
            maxOf(
                data.consumedEnergyKwh,
                lastSampledCounterKwh,
                activeSlice?.stationCounterLastKwh ?: 0.0
            )
        }

        // Only open slices contribute active counter delta; closed slices are already in finalizedSlices.
        val sliceStart = activeSlice?.stationCounterStartKwh ?: effectiveCounter
        val totalDelivered = EnergyAccumulator.calculateTotalDeliveredKwh(
            finalizedSlices = slices.filter { it.endedAt != null },
            activeSliceStartKwh = sliceStart,
            currentStationCounterKwh = effectiveCounter
        )
        val hasEvidence = hasChargingEvidence(totalDelivered, dbSession.lastTickAt)
        DiagLog.d(TAG) {
            "Progress delivered=${totalDelivered.kwh()} target=${activeTargetKwh}kWh/${activeTargetDurationMinutes}min " +
                "chargingMs=$accumulatedChargingMs slices=${slices.size} open=${activeSlice != null} " +
                "idleTicks=$inactiveOnlineConsecutiveTicks zeroTicks=$zeroCurrentConsecutiveTicks evidence=$hasEvidence"
        }

        if (!data.isOnline) {
            lastActiveChargingSampleAt = 0L
            gateway.updateDurationProgress(sessionId, accumulatedChargingMs, 0L)
            if (stateMachine.getState() == SessionStatus.CHARGING && !hasEvidence) {
                DiagLog.i(TAG, "Station offline before charging ever started, session cancelled")
                closeStoppedOrUnstartedSession(sessionId, effectiveCounter, hasEvidence = false)
                return true
            }
            if (stateMachine.getState() == SessionStatus.CHARGING ||
                stateMachine.getState() == SessionStatus.OUTAGE_STABILIZING ||
                stateMachine.getState() == SessionStatus.AUTO_RESUMING
            ) {
                DiagLog.w(TAG, "Station offline: power outage, delivered so far ${totalDelivered.kwh()} kWh")
                transition(StateEvent.OutageDetected)
                hasAlertedManualResume = false
                gateway.handleOutageDetected(sessionId, effectiveCounter)
                events += SupervisorEvent.ShowOutageAlert(totalDelivered, gateway.isAutoResumeEnabled())
            }
        } else {
            val state = stateMachine.getState()
            if ((state == SessionStatus.OUTAGE_DETECTED ||
                    state == SessionStatus.OUTAGE_STABILIZING ||
                    state == SessionStatus.AUTO_RESUMING) &&
                isVehicleUnplugged(data.statusText)
            ) {
                DiagLog.i(TAG, "Car unplugged during the outage, session closed")
                hasAlertedManualResume = false
                events += SupervisorEvent.CancelManualResumeAlert
                closeStoppedOrUnstartedSession(sessionId, data.consumedEnergyKwh, hasEvidence)
                return true
            }
            when (state) {
                SessionStatus.OUTAGE_DETECTED -> handleOutageDetectedOnline(sessionId, dbSession, data, totalDelivered, events)
                SessionStatus.OUTAGE_STABILIZING -> handleOutageStabilizingOnline(sessionId, dbSession, data, totalDelivered, events)
                SessionStatus.AUTO_RESUMING -> handleAutoResumingOnline(sessionId, dbSession, data, totalDelivered, events)
                SessionStatus.CHARGING -> {
                    val finished = handleChargingTick(sessionId, dbSession, data, totalDelivered, events)
                    if (finished) return true
                }
                SessionStatus.STOP_PENDING -> {
                    val finished = handleStopPendingTick(sessionId, data, totalDelivered, events)
                    if (finished) return true
                }
                else -> {}
            }
        }

        val ongoingStatusText = if (stateMachine.getState() == SessionStatus.STOP_PENDING) {
            "Очікування зупинки..."
        } else {
            data.statusText
        }
        events += SupervisorEvent.UpdateOngoingNotification(
            powerKw = data.powerKw,
            deliveredKwh = totalDelivered,
            targetKwh = activeTargetKwh,
            statusText = ongoingStatusText,
            targetDurationMinutes = activeTargetDurationMinutes,
            accumulatedChargingMs = accumulatedChargingMs,
            isCharging = stateMachine.getState() == SessionStatus.CHARGING && data.isChargingActive
        )
        return false
    }

    private suspend fun handleCounterRegression(
        sessionId: String,
        dbSession: ChargeSessionEntity,
        slices: List<ChargeSliceEntity>,
        activeSliceStart: Double,
        preRebootCounter: Double,
        events: MutableList<SupervisorEvent>
    ) {
        lastActiveChargingSampleAt = 0L
        transition(StateEvent.OutageDetected)
        gateway.handleOutageDetected(sessionId, preRebootCounter)
        val outageDelivered = EnergyAccumulator.calculateTotalDeliveredKwh(
            finalizedSlices = slices.filter { it.endedAt != null },
            activeSliceStartKwh = activeSliceStart,
            currentStationCounterKwh = preRebootCounter
        )
        if (gateway.isAutoResumeEnabled()) {
            val resumeRes = gateway.resumeAfterOutage(sessionId)
            if (resumeRes.isSuccess) {
                transition(StateEvent.ResumeCommandSent)
                inactiveOnlineConsecutiveTicks = 0
                hasAlertedManualResume = false
                events += SupervisorEvent.CancelManualResumeAlert
                events += SupervisorEvent.ShowOutageResumed(dbSession.amperageSetting)
            }
        } else if (!hasAlertedManualResume) {
            hasAlertedManualResume = true
            events += SupervisorEvent.ShowManualResumeAvailable(dbSession.amperageSetting, outageDelivered)
        }
    }

    private suspend fun handleOutageDetectedOnline(
        sessionId: String,
        dbSession: ChargeSessionEntity,
        data: TelemetryData,
        totalDelivered: Double,
        events: MutableList<SupervisorEvent>
    ) {
        if (!gateway.isAutoResumeEnabled()) {
            if (!hasAlertedManualResume) {
                hasAlertedManualResume = true
                events += SupervisorEvent.ShowManualResumeAvailable(dbSession.amperageSetting, totalDelivered)
            }
            return
        }
        val voltage = data.voltageCurrent ?: return
        val nextState = transition(StateEvent.GridRestored(voltage))
        if (nextState == SessionStatus.OUTAGE_STABILIZING) {
            gateway.handleOutageStabilizing(sessionId)
        }
    }

    private suspend fun handleOutageStabilizingOnline(
        sessionId: String,
        dbSession: ChargeSessionEntity,
        data: TelemetryData,
        totalDelivered: Double,
        events: MutableList<SupervisorEvent>
    ) {
        if (!gateway.isAutoResumeEnabled()) {
            transition(StateEvent.OutageDetected)
            gateway.handleOutageDetected(sessionId, data.consumedEnergyKwh)
            if (!hasAlertedManualResume) {
                hasAlertedManualResume = true
                events += SupervisorEvent.ShowManualResumeAvailable(dbSession.amperageSetting, totalDelivered)
            }
            return
        }
        val voltage = data.voltageCurrent ?: return
        if (voltage in 180..250) {
            val nextState = transition(StateEvent.VoltageStable)
            if (nextState == SessionStatus.AUTO_RESUMING) {
                tryAutoResume(sessionId, dbSession, events)
            }
        } else {
            transition(StateEvent.VoltageUnstable)
            gateway.handleOutageDetected(sessionId, data.consumedEnergyKwh)
        }
    }

    private suspend fun handleAutoResumingOnline(
        sessionId: String,
        dbSession: ChargeSessionEntity,
        data: TelemetryData,
        totalDelivered: Double,
        events: MutableList<SupervisorEvent>
    ) {
        if (!gateway.isAutoResumeEnabled()) {
            transition(StateEvent.OutageDetected)
            gateway.handleOutageDetected(sessionId, data.consumedEnergyKwh)
            if (!hasAlertedManualResume) {
                hasAlertedManualResume = true
                events += SupervisorEvent.ShowManualResumeAvailable(dbSession.amperageSetting, totalDelivered)
            }
            return
        }
        tryAutoResume(sessionId, dbSession, events)
    }

    private suspend fun tryAutoResume(
        sessionId: String,
        dbSession: ChargeSessionEntity,
        events: MutableList<SupervisorEvent>
    ) {
        if (stateMachine.getState().isTerminal || gateway.getSessionById(sessionId)?.status?.isTerminal == true) {
            return
        }
        val resumeRes = gateway.resumeAfterOutage(sessionId)
        DiagLog.i(TAG, "Auto-resume after outage: " + if (resumeRes.isSuccess) "ok" else "failed, ${resumeRes.exceptionOrNull().reason()}")
        if (resumeRes.isSuccess) {
            transition(StateEvent.ResumeCommandSent)
            inactiveOnlineConsecutiveTicks = 0
            lastActiveChargingSampleAt = 0L
            hasAlertedManualResume = false
            events += SupervisorEvent.CancelManualResumeAlert
            events += SupervisorEvent.ShowOutageResumed(dbSession.amperageSetting)
        }
    }

    private suspend fun handleChargingTick(
        sessionId: String,
        dbSession: ChargeSessionEntity,
        data: TelemetryData,
        totalDelivered: Double,
        events: MutableList<SupervisorEvent>
    ): Boolean {
        val now = clockMs()
        if (data.isChargingActive) {
            inactiveOnlineConsecutiveTicks = 0
            lowVoltageStepDoneForPause = false
            if (lastActiveChargingSampleAt > 0L) {
                val deltaMs = (now - lastActiveChargingSampleAt).coerceIn(0L, 120_000L)
                accumulatedChargingMs += deltaMs
            }
            lastActiveChargingSampleAt = now
            gateway.updateDurationProgress(sessionId, accumulatedChargingMs, now)
        } else {
            if (lastActiveChargingSampleAt > 0L) {
                val deltaMs = (now - lastActiveChargingSampleAt).coerceIn(0L, 15_000L)
                accumulatedChargingMs += deltaMs
                lastActiveChargingSampleAt = 0L
            }
            gateway.updateDurationProgress(sessionId, accumulatedChargingMs, 0L)
        }

        if (activeTargetDurationMinutes > 0 && accumulatedChargingMs >= activeTargetDurationMinutes * 60_000L) {
            if (attemptTargetStopAndFinish(sessionId, data.consumedEnergyKwh, totalDelivered, isDurationTarget = true, events)) {
                return true
            }
        }

        if (stateMachine.getState() == SessionStatus.CHARGING &&
            activeTargetKwh > 0.0 &&
            totalDelivered >= activeTargetKwh
        ) {
            if (attemptTargetStopAndFinish(sessionId, data.consumedEnergyKwh, totalDelivered, isDurationTarget = false, events)) {
                return true
            }
        }

        if (stateMachine.getState() == SessionStatus.CHARGING && !data.isChargingActive) {
            zeroCurrentConsecutiveTicks = 0
            inactiveOnlineConsecutiveTicks++
            val hasEvidence = hasChargingEvidence(totalDelivered, dbSession.lastTickAt)
            val isLimited = activeTargetKwh > 0.0 || activeTargetDurationMinutes > 0
            // The station's undervoltage cut-off is a pause: it restarts by itself when the voltage
            // recovers, so the session is kept like a limited one instead of being closed as "car stopped",
            // and auto-current drops to its minimum so the restart does not trip it again.
            val isLowVoltagePause = data.isLowVoltageStop
            if (isLowVoltagePause && hasEvidence) stepDownOnStationLowVoltage(sessionId, data, totalDelivered, events)
            val keepWatching = (isLimited || isLowVoltagePause) && hasEvidence && !isVehicleUnplugged(data.statusText)
            val threshold = when {
                !hasEvidence -> UNSTARTED_STOP_TICKS
                keepWatching -> LIMITED_IDLE_STOP_TICKS
                else -> INACTIVE_ONLINE_STOP_TICKS
            }
            if (inactiveOnlineConsecutiveTicks >= threshold) {
                DiagLog.i(
                    TAG,
                    "Not charging for $inactiveOnlineConsecutiveTicks ticks (limit $threshold, evidence=$hasEvidence, " +
                        "limited=$isLimited, lowVoltage=$isLowVoltagePause, status='${data.statusText}'), closing the session"
                )
                // A limited session whose car is still plugged in only pauses (undervoltage, car-side
                // pause), so the station stays armed. When finally giving up, the station is stopped
                // first, otherwise it would keep charging with no limit and no dead-man timer.
                val stopped = if (hasEvidence && !keepWatching) true else gateway.stopAndConfirm(allowOfflineConfirmation = false)
                if (!stopped && keepWatching) {
                    DiagLog.w(TAG, "Station did not confirm the stop, the session stays open")
                    return false
                }
                closeStoppedOrUnstartedSession(
                    sessionId = sessionId,
                    counterKwh = data.consumedEnergyKwh,
                    hasEvidence = hasEvidence
                )
                return true
            }
        } else if (stateMachine.getState() == SessionStatus.CHARGING &&
            data.measuredCurrentAmperes < 0.5 &&
            data.isChargingActive
        ) {
            zeroCurrentConsecutiveTicks++
            if (zeroCurrentConsecutiveTicks >= 30) {
                DiagLog.i(TAG, "Station reports charging but the car draws no current for 30 ticks, session closed")
                transition(StateEvent.VehicleStoppedCharging)
                gateway.markSessionVehicleStopped(sessionId, data.consumedEnergyKwh)
                return true
            }
        } else {
            zeroCurrentConsecutiveTicks = 0
        }

        val currentVoltage = data.voltageCurrent
        if (stateMachine.getState() == SessionStatus.CHARGING &&
            voltageLoadBalancer.isEnabled &&
            data.isChargingActive &&
            currentVoltage != null
        ) {
            val recommendedA = voltageLoadBalancer.evaluate(
                currentVoltage = currentVoltage,
                currentAmperage = data.selectedCurrentAmperes
            )
            if (recommendedA != null && recommendedA != data.selectedCurrentAmperes) {
                val prevA = data.selectedCurrentAmperes
                val updateRes = gateway.setAmperage(recommendedA)
                DiagLog.i(
                    TAG,
                    "Auto-current at ${currentVoltage}V: ${prevA}A -> ${recommendedA}A " +
                        if (updateRes.isSuccess) "ok" else "failed, ${updateRes.exceptionOrNull().reason()}"
                )
                if (updateRes.isSuccess) {
                    lastKnownAmperes = recommendedA
                    lastDeadManRefreshAtMs = now
                    gateway.updateSessionAmperage(sessionId, recommendedA)
                    gateway.refreshDeadManTimer(sessionId, currentVoltage, totalDelivered)
                    events += SupervisorEvent.ShowBalancingAlert(prevA, recommendedA, currentVoltage)
                }
            }
        }

        val hasTarget = activeTargetKwh > 0.0 || activeTargetDurationMinutes > 0
        if (stateMachine.getState() == SessionStatus.CHARGING &&
            data.isChargingActive &&
            hasTarget &&
            (lastDeadManRefreshAtMs == 0L || now - lastDeadManRefreshAtMs >= DEAD_MAN_REFRESH_INTERVAL_MS)
        ) {
            lastDeadManRefreshAtMs = now
            DiagLog.d(TAG) { "Refreshing the dead-man timer at ${currentVoltage ?: 230}V, delivered ${totalDelivered.kwh()}" }
            gateway.refreshDeadManTimer(sessionId, currentVoltage ?: 230, totalDelivered)
        }
        return false
    }

    private suspend fun handleStopPendingTick(
        sessionId: String,
        data: TelemetryData,
        totalDelivered: Double,
        events: MutableList<SupervisorEvent>
    ): Boolean {
        val reachedKwh = activeTargetKwh > 0.0 && totalDelivered >= activeTargetKwh
        val reachedDur = activeTargetDurationMinutes > 0 && accumulatedChargingMs >= activeTargetDurationMinutes * 60_000L
        val isManualStop = pendingStopIsManual || (!reachedKwh && !reachedDur)

        // One idle reading is not enough: the station may pick charging up again seconds later,
        // so even then the stop is repeated and held for a while before the session is closed.
        val stopped = if (!data.isOnline && !data.isChargingActive && isManualStop) true
        else gateway.stopAndConfirm(allowOfflineConfirmation = isManualStop)
        DiagLog.i(
            TAG,
            "Waiting for stop (manual=$isManualStop, reachedKwh=$reachedKwh, reachedTime=$reachedDur): " +
                if (stopped) "station stopped" else "station still charging"
        )
        return if (stopped) {
            val finalKwh = gateway.latestCounterOr(data.consumedEnergyKwh)
            finalizeStopConfirmed(
                sessionId = sessionId,
                finalKwh = finalKwh,
                totalDelivered = totalDelivered,
                isManualStop = isManualStop,
                isDurationTarget = reachedDur,
                events = events
            )
            true
        } else {
            if (!hasAlertedStopFailed) {
                hasAlertedStopFailed = true
                events += SupervisorEvent.ShowStopFailed
            }
            false
        }
    }

    private suspend fun evaluateTelemetryFailure(
        sessionId: String,
        dbSession: ChargeSessionEntity,
        events: MutableList<SupervisorEvent>
    ): Boolean {
        consecutiveFailureTicks++
        if (consecutiveFailureTicks == 3) {
            DiagLog.w(TAG, "No telemetry for 3 ticks in a row: connection to the station lost")
        }
        if (consecutiveFailureTicks >= 3) {
            lastActiveChargingSampleAt = 0L
            gateway.updateDurationProgress(sessionId, accumulatedChargingMs, 0L)
            if (stateMachine.getState() == SessionStatus.CHARGING) {
                val slices = gateway.getSlicesForSession(sessionId)
                val activeSlice = slices.lastOrNull { it.endedAt == null }
                val delivered = EnergyAccumulator.calculateTotalDeliveredKwh(
                    finalizedSlices = slices.filter { it.endedAt != null },
                    activeSliceStartKwh = activeSlice?.stationCounterStartKwh ?: lastSampledCounterKwh,
                    currentStationCounterKwh = lastSampledCounterKwh
                )
                if (!hasChargingEvidence(delivered, dbSession.lastTickAt)) {
                    closeStoppedOrUnstartedSession(sessionId, lastSampledCounterKwh, hasEvidence = false)
                    return true
                }
            }
            if (!hasAlertedConnectionLost) {
                hasAlertedConnectionLost = true
                events += SupervisorEvent.ShowConnectionLost(lastKnownAmperes)
            }
            if (stateMachine.getState() == SessionStatus.CHARGING) {
                transition(StateEvent.OutageDetected)
                hasAlertedManualResume = false
                gateway.handleOutageDetected(sessionId, lastSampledCounterKwh)
            }
        }
        return false
    }

    private suspend fun attemptTargetStopAndFinish(
        sessionId: String,
        consumedKwh: Double,
        totalDelivered: Double,
        isDurationTarget: Boolean,
        events: MutableList<SupervisorEvent>
    ): Boolean {
        pendingStopIsManual = false
        DiagLog.i(
            TAG,
            (if (isDurationTarget) "Charge timer reached (${accumulatedChargingMs / 60_000} min)" else "Energy limit reached") +
                ": delivered ${totalDelivered.kwh()} of ${activeTargetKwh} kWh, stopping the station"
        )
        val stopped = gateway.markSessionTargetReached(sessionId, consumedKwh)
        if (!stopped) DiagLog.w(TAG, "Station did not confirm the stop after the limit")
        return if (stopped) {
            transition(StateEvent.TargetQuotaReached)
            events += if (isDurationTarget) {
                SupervisorEvent.ShowDurationTargetReached(activeTargetDurationMinutes, totalDelivered)
            } else {
                SupervisorEvent.ShowTargetReached(totalDelivered)
            }
            true
        } else {
            transition(StateEvent.StopPending)
            if (!hasAlertedStopFailed) {
                hasAlertedStopFailed = true
                events += SupervisorEvent.ShowStopFailed
            }
            false
        }
    }

    private suspend fun finalizeStopConfirmed(
        sessionId: String,
        finalKwh: Double,
        totalDelivered: Double,
        isManualStop: Boolean,
        isDurationTarget: Boolean,
        events: MutableList<SupervisorEvent>
    ) {
        if (isManualStop) {
            gateway.finalizeSessionStopped(
                sessionId = sessionId,
                lastCounter = finalKwh,
                sliceReason = SliceEndReason.MANUAL_STOP,
                finalStatus = SessionStatus.CANCELLED
            )
            transition(StateEvent.CancelRequested)
        } else {
            gateway.finalizeSessionStopped(
                sessionId = sessionId,
                lastCounter = finalKwh,
                sliceReason = SliceEndReason.TARGET_REACHED,
                finalStatus = SessionStatus.TARGET_REACHED
            )
            transition(StateEvent.TargetQuotaReached)
            events += if (isDurationTarget) {
                SupervisorEvent.ShowDurationTargetReached(activeTargetDurationMinutes, totalDelivered)
            } else {
                SupervisorEvent.ShowTargetReached(totalDelivered)
            }
        }
    }

    suspend fun handleManualStop(): TickOutcome = operationMutex.withLock {
        val events = mutableListOf<SupervisorEvent>()
        if (stateMachine.getState().isTerminal) {
            return@withLock TickOutcome(stateMachine.getState(), events, shouldStopService = true)
        }
        val id = activeSessionId ?: gateway.getActiveSession()?.id
        if (id == null) {
            val stopped = gateway.stopAndConfirm(allowOfflineConfirmation = true)
            return@withLock if (stopped) {
                transition(StateEvent.CancelRequested)
                TickOutcome(stateMachine.getState(), events, shouldStopService = true)
            } else {
                if (!hasAlertedStopFailed) {
                    hasAlertedStopFailed = true
                    events += SupervisorEvent.ShowStopFailed
                }
                TickOutcome(SessionStatus.STOP_PENDING, events, shouldStopService = true)
            }
        }
        activeSessionId = id
        if (gateway.getSessionById(id)?.status?.isTerminal == true) {
            transition(StateEvent.CancelRequested)
            return@withLock TickOutcome(stateMachine.getState(), events, shouldStopService = true)
        }

        val res = gateway.stopActiveSession(id)
        DiagLog.i(TAG, "Manual stop of ${id.take(8)}: " + if (res.isSuccess) "ok" else "failed, ${res.exceptionOrNull().reason()}")
        if (res.isSuccess) {
            pendingStopIsManual = false
            transition(StateEvent.CancelRequested)
            TickOutcome(stateMachine.getState(), events, shouldStopService = true)
        } else {
            pendingStopIsManual = true
            transition(StateEvent.StopPending)
            if (!hasAlertedStopFailed) {
                hasAlertedStopFailed = true
                events += SupervisorEvent.ShowStopFailed
            }
            TickOutcome(stateMachine.getState(), events, shouldStopService = false)
        }
    }

    suspend fun handleManualResume(): Pair<List<SupervisorEvent>, Boolean> = operationMutex.withLock {
        val events = mutableListOf<SupervisorEvent>()
        if (stateMachine.getState().isTerminal) {
            return@withLock events to false
        }
        val id = activeSessionId ?: gateway.getActiveSession()?.id
            ?: return@withLock events to false

        activeSessionId = id
        val dbSession = gateway.getSessionById(id)
        if (dbSession?.status?.isTerminal == true) {
            transition(StateEvent.CancelRequested)
            return@withLock events to false
        }
        if (stateMachine.getState() == SessionStatus.CHARGING || dbSession?.status == SessionStatus.CHARGING) {
            hasAlertedManualResume = false
            events += SupervisorEvent.CancelManualResumeAlert
            return@withLock events to true
        }

        val res = gateway.resumeAfterOutage(id, isManual = true)
        DiagLog.i(TAG, "Manual resume of ${id.take(8)}: " + if (res.isSuccess) "ok" else "failed, ${res.exceptionOrNull().reason()}")
        if (res.isSuccess) {
            transition(StateEvent.ResumeCommandSent)
            inactiveOnlineConsecutiveTicks = 0
            lastActiveChargingSampleAt = 0L
            hasAlertedManualResume = false
            events += SupervisorEvent.CancelManualResumeAlert
            events += SupervisorEvent.ShowOutageResumed(dbSession?.amperageSetting ?: lastKnownAmperes)
            events to true
        } else {
            events += SupervisorEvent.ShowManualResumeAvailable(
                dbSession?.amperageSetting ?: lastKnownAmperes,
                dbSession?.totalKwhDelivered ?: 0.0
            )
            events to false
        }
    }

    /** Set once the current was lowered for the station's current undervoltage pause. */
    private var lowVoltageStepDoneForPause = false

    /**
     * The station cut the charge on its own undervoltage protection. The sag happens under load and
     * lasts seconds, so the 10-second samples often show a voltage above the auto-current threshold.
     * The current drops straight to the auto-current minimum (6 A by default): a cut-off means the
     * line cannot carry the load, and stepping down 2 A per cut-off would take several more trips.
     * The usual recovery stages raise it again once the voltage holds.
     */
    private suspend fun stepDownOnStationLowVoltage(
        sessionId: String,
        data: TelemetryData,
        totalDelivered: Double,
        events: MutableList<SupervisorEvent>
    ) {
        if (lowVoltageStepDoneForPause || !voltageLoadBalancer.isEnabled) return
        lowVoltageStepDoneForPause = true
        val prevA = data.selectedCurrentAmperes
        val nextA = voltageLoadBalancer.minAmperage
        if (nextA >= prevA) return
        val voltage = data.voltageCurrent ?: 0
        val res = gateway.setAmperage(nextA)
        DiagLog.i(
            TAG,
            "Station undervoltage cut-off at ${voltage}V: ${prevA}A -> ${nextA}A " +
                if (res.isSuccess) "ok" else "failed, ${res.exceptionOrNull().reason()}"
        )
        if (res.isSuccess) {
            voltageLoadBalancer.reset()
            lastKnownAmperes = nextA
            gateway.updateSessionAmperage(sessionId, nextA)
            if (activeTargetKwh > 0.0 || activeTargetDurationMinutes > 0) {
                lastDeadManRefreshAtMs = clockMs()
                gateway.refreshDeadManTimer(sessionId, if (voltage > 0) voltage else 230, totalDelivered)
            }
            events += SupervisorEvent.ShowBalancingAlert(prevA, nextA, voltage)
        } else {
            lowVoltageStepDoneForPause = false
        }
    }

    companion object {
        private const val TAG = "Supervisor"
        private const val HTTP_COUNTER_ROUNDING_KWH = 0.1
        private const val INACTIVE_ONLINE_STOP_TICKS = 3
        /** About 2.5 minutes for the car to start drawing current after a start command. */
        private const val UNSTARTED_STOP_TICKS = 15
        /** About 12 hours of pause before a limited session with a plugged-in car is given up. */
        private const val LIMITED_IDLE_STOP_TICKS = 4_320
        private const val DEAD_MAN_REFRESH_INTERVAL_MS = 15 * 60_000L
    }
}

