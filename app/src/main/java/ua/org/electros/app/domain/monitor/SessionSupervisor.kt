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

interface SessionMonitorGateway {
    suspend fun fetchTelemetry(): Result<TelemetryData>
    suspend fun getSessionById(sessionId: String): ChargeSessionEntity?
    suspend fun getActiveSession(): ChargeSessionEntity?
    suspend fun getSlicesForSession(sessionId: String): List<ChargeSliceEntity>
    suspend fun updateDurationProgress(sessionId: String, accumulatedMs: Long, lastTickAt: Long)
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
        val accumulatedChargingMs: Long
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
            if (dbSession.accumulatedChargingMs > 0L || dbSession.totalKwhDelivered > 0.0 || dbSession.lastTickAt > 0L) {
                hasObservedActiveCharging = true
            }
        }
    }

    fun currentStatus(): SessionStatus = stateMachine.getState()

    suspend fun tick(): TickOutcome = operationMutex.withLock {
        val events = mutableListOf<SupervisorEvent>()
        if (stateMachine.getState().isTerminal) {
            return@withLock TickOutcome(stateMachine.getState(), events, shouldStopService = true)
        }
        val sessionId = activeSessionId
            ?: return@withLock TickOutcome(stateMachine.getState(), events, shouldStopService = false)
        val result = gateway.fetchTelemetry()

        val dbSession = gateway.getSessionById(sessionId)
        if (dbSession == null || dbSession.status.isTerminal) {
            stateMachine.transition(StateEvent.CancelRequested)
            return@withLock TickOutcome(stateMachine.getState(), events, shouldStopService = true)
        }

        if (dbSession.status == SessionStatus.STOP_PENDING && stateMachine.getState() != SessionStatus.STOP_PENDING) {
            stateMachine.transition(StateEvent.StopPending)
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
            stateMachine.transition(StateEvent.VehicleStoppedCharging)
            gateway.markSessionVehicleStopped(sessionId, counterKwh)
        } else {
            gateway.finalizeSessionStopped(
                sessionId = sessionId,
                lastCounter = counterKwh,
                sliceReason = SliceEndReason.MANUAL_STOP,
                finalStatus = SessionStatus.CANCELLED
            )
            stateMachine.transition(StateEvent.CancelRequested)
        }
    }

    private suspend fun evaluateTelemetrySuccess(
        sessionId: String,
        dbSession: ChargeSessionEntity,
        data: TelemetryData,
        events: MutableList<SupervisorEvent>
    ): Boolean {
        consecutiveFailureTicks = 0
        hasAlertedConnectionLost = false
        lastKnownAmperes = data.selectedCurrentAmperes
        if (data.isOnline && data.isChargingActive) {
            hasObservedActiveCharging = true
            inactiveOnlineConsecutiveTicks = 0
        }
        var slices = gateway.getSlicesForSession(sessionId)
        var activeSlice = slices.lastOrNull { it.endedAt == null }

        if (data.isOnline && EnergyAccumulator.isCounterRegression(data.consumedEnergyKwh, lastSampledCounterKwh)) {
            handleCounterRegression(
                sessionId = sessionId,
                dbSession = dbSession,
                slices = slices,
                activeSliceStart = activeSlice?.stationCounterStartKwh ?: lastSampledCounterKwh,
                events = events
            )
            slices = gateway.getSlicesForSession(sessionId)
            activeSlice = slices.lastOrNull { it.endedAt == null }
        }
        lastSampledCounterKwh = data.consumedEnergyKwh

        if (data.isOnline &&
            data.isChargingActive &&
            (stateMachine.getState() == SessionStatus.OUTAGE_DETECTED ||
                stateMachine.getState() == SessionStatus.OUTAGE_STABILIZING ||
                stateMachine.getState() == SessionStatus.AUTO_RESUMING)
        ) {
            stateMachine.transition(StateEvent.ResumeCommandSent)
            inactiveOnlineConsecutiveTicks = 0
            hasAlertedManualResume = false
            events += SupervisorEvent.CancelManualResumeAlert
            gateway.restoreChargingFromExistingSlice(sessionId, data.consumedEnergyKwh)
            slices = gateway.getSlicesForSession(sessionId)
            activeSlice = slices.lastOrNull { it.endedAt == null }
        }

        // Only open slices contribute active counter delta; closed slices are already in finalizedSlices.
        val sliceStart = activeSlice?.stationCounterStartKwh ?: data.consumedEnergyKwh
        val totalDelivered = EnergyAccumulator.calculateTotalDeliveredKwh(
            finalizedSlices = slices.filter { it.endedAt != null },
            activeSliceStartKwh = sliceStart,
            currentStationCounterKwh = data.consumedEnergyKwh
        )
        val hasEvidence = hasChargingEvidence(totalDelivered, dbSession.lastTickAt)

        if (!data.isOnline) {
            lastActiveChargingSampleAt = 0L
            gateway.updateDurationProgress(sessionId, accumulatedChargingMs, 0L)
            if (stateMachine.getState() == SessionStatus.CHARGING && !hasEvidence) {
                closeStoppedOrUnstartedSession(sessionId, data.consumedEnergyKwh, hasEvidence = false)
                return true
            }
            if (stateMachine.getState() == SessionStatus.CHARGING ||
                stateMachine.getState() == SessionStatus.OUTAGE_STABILIZING ||
                stateMachine.getState() == SessionStatus.AUTO_RESUMING
            ) {
                stateMachine.transition(StateEvent.OutageDetected)
                hasAlertedManualResume = false
                gateway.handleOutageDetected(sessionId, data.consumedEnergyKwh)
                events += SupervisorEvent.ShowOutageAlert(totalDelivered, gateway.isAutoResumeEnabled())
            }
        } else {
            val state = stateMachine.getState()
            if ((state == SessionStatus.OUTAGE_DETECTED ||
                    state == SessionStatus.OUTAGE_STABILIZING ||
                    state == SessionStatus.AUTO_RESUMING) &&
                isVehicleUnplugged(data.statusText)
            ) {
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
            accumulatedChargingMs = accumulatedChargingMs
        )
        return false
    }

    private suspend fun handleCounterRegression(
        sessionId: String,
        dbSession: ChargeSessionEntity,
        slices: List<ChargeSliceEntity>,
        activeSliceStart: Double,
        events: MutableList<SupervisorEvent>
    ) {
        lastActiveChargingSampleAt = 0L
        stateMachine.transition(StateEvent.OutageDetected)
        gateway.handleOutageDetected(sessionId, lastSampledCounterKwh)
        val outageDelivered = EnergyAccumulator.calculateTotalDeliveredKwh(
            finalizedSlices = slices.filter { it.endedAt != null },
            activeSliceStartKwh = activeSliceStart,
            currentStationCounterKwh = lastSampledCounterKwh
        )
        if (gateway.isAutoResumeEnabled()) {
            val resumeRes = gateway.resumeAfterOutage(sessionId)
            if (resumeRes.isSuccess) {
                stateMachine.transition(StateEvent.ResumeCommandSent)
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
        val nextState = stateMachine.transition(StateEvent.GridRestored(voltage))
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
            stateMachine.transition(StateEvent.OutageDetected)
            gateway.handleOutageDetected(sessionId, data.consumedEnergyKwh)
            if (!hasAlertedManualResume) {
                hasAlertedManualResume = true
                events += SupervisorEvent.ShowManualResumeAvailable(dbSession.amperageSetting, totalDelivered)
            }
            return
        }
        val voltage = data.voltageCurrent ?: return
        if (voltage in 180..250) {
            val nextState = stateMachine.transition(StateEvent.VoltageStable)
            if (nextState == SessionStatus.AUTO_RESUMING) {
                tryAutoResume(sessionId, dbSession, events)
            }
        } else {
            stateMachine.transition(StateEvent.VoltageUnstable)
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
            stateMachine.transition(StateEvent.OutageDetected)
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
        if (resumeRes.isSuccess) {
            stateMachine.transition(StateEvent.ResumeCommandSent)
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
            if (inactiveOnlineConsecutiveTicks >= INACTIVE_ONLINE_STOP_TICKS) {
                closeStoppedOrUnstartedSession(
                    sessionId = sessionId,
                    counterKwh = data.consumedEnergyKwh,
                    hasEvidence = hasChargingEvidence(totalDelivered, dbSession.lastTickAt)
                )
                return true
            }
        } else if (stateMachine.getState() == SessionStatus.CHARGING &&
            data.measuredCurrentAmperes < 0.5 &&
            data.isChargingActive
        ) {
            zeroCurrentConsecutiveTicks++
            if (zeroCurrentConsecutiveTicks >= 30) {
                stateMachine.transition(StateEvent.VehicleStoppedCharging)
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
                if (updateRes.isSuccess) {
                    lastKnownAmperes = recommendedA
                    gateway.updateSessionAmperage(sessionId, recommendedA)
                    gateway.refreshDeadManTimer(sessionId, currentVoltage, totalDelivered)
                    events += SupervisorEvent.ShowBalancingAlert(prevA, recommendedA, currentVoltage)
                }
            }
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

        val stopped = if (!data.isChargingActive) true else gateway.stopAndConfirm(allowOfflineConfirmation = isManualStop)
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
                stateMachine.transition(StateEvent.OutageDetected)
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
        val stopped = gateway.markSessionTargetReached(sessionId, consumedKwh)
        return if (stopped) {
            stateMachine.transition(StateEvent.TargetQuotaReached)
            events += if (isDurationTarget) {
                SupervisorEvent.ShowDurationTargetReached(activeTargetDurationMinutes, totalDelivered)
            } else {
                SupervisorEvent.ShowTargetReached(totalDelivered)
            }
            true
        } else {
            stateMachine.transition(StateEvent.StopPending)
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
            stateMachine.transition(StateEvent.CancelRequested)
        } else {
            gateway.finalizeSessionStopped(
                sessionId = sessionId,
                lastCounter = finalKwh,
                sliceReason = SliceEndReason.TARGET_REACHED,
                finalStatus = SessionStatus.TARGET_REACHED
            )
            stateMachine.transition(StateEvent.TargetQuotaReached)
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
        val id = activeSessionId
            ?: return@withLock TickOutcome(stateMachine.getState(), events, shouldStopService = true)
        if (gateway.getSessionById(id)?.status?.isTerminal == true) {
            stateMachine.transition(StateEvent.CancelRequested)
            return@withLock TickOutcome(stateMachine.getState(), events, shouldStopService = true)
        }

        val res = gateway.stopActiveSession(id)
        if (res.isSuccess) {
            pendingStopIsManual = false
            stateMachine.transition(StateEvent.CancelRequested)
            TickOutcome(stateMachine.getState(), events, shouldStopService = true)
        } else {
            pendingStopIsManual = true
            stateMachine.transition(StateEvent.StopPending)
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
            stateMachine.transition(StateEvent.CancelRequested)
            return@withLock events to false
        }
        if (stateMachine.getState() == SessionStatus.CHARGING || dbSession?.status == SessionStatus.CHARGING) {
            hasAlertedManualResume = false
            events += SupervisorEvent.CancelManualResumeAlert
            return@withLock events to true
        }

        val res = gateway.resumeAfterOutage(id, isManual = true)
        if (res.isSuccess) {
            stateMachine.transition(StateEvent.ResumeCommandSent)
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

    companion object {
        private const val INACTIVE_ONLINE_STOP_TICKS = 3
    }
}

