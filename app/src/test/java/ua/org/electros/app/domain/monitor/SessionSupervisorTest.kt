package ua.org.electros.app.domain.monitor

import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.yield
import org.junit.Test
import ua.org.electros.app.data.local.entity.ChargeSessionEntity
import ua.org.electros.app.data.local.entity.ChargeSliceEntity
import ua.org.electros.app.data.local.entity.SessionStatus
import ua.org.electros.app.data.local.entity.SliceEndReason
import ua.org.electros.app.data.model.TelemetryData
import ua.org.electros.app.domain.accumulator.EnergyAccumulator
import ua.org.electros.app.domain.balancing.VoltageLoadBalancer

class SessionSupervisorTest {

    @Test
    fun kwhLimit_stopFailsThenConfirmsOnNextTick_transitionsStopPendingThenTargetReached() = runTest {
        val gateway = FakeGateway(
            session = session(id = "s1", targetKwh = 10.0),
            slices = mutableListOf(slice("s1", startKwh = 100.0)),
            stopTargetResult = false
        )
        val supervisor = SessionSupervisor(gateway, VoltageLoadBalancer(isEnabled = false)) { 10_000L }
        supervisor.onSessionStarted("s1", targetKwh = 10.0, targetDurationMinutes = 0)

        // Tick 1: reaches 10 kWh, stop fails -> enters STOP_PENDING + ShowStopFailed
        gateway.nextTelemetry = Result.success(telemetry(isOnline = true, isCharging = true, counterKwh = 110.5, voltage = 225))
        val tick1 = supervisor.tick()
        assertThat(tick1.status).isEqualTo(SessionStatus.STOP_PENDING)
        assertThat(tick1.shouldStopService).isFalse()
        assertThat(tick1.events).contains(SupervisorEvent.ShowStopFailed)

        // Tick 2: station now reports inactive -> stop confirmed -> TARGET_REACHED
        gateway.nextTelemetry = Result.success(telemetry(isOnline = true, isCharging = false, counterKwh = 110.5, voltage = 225))
        val tick2 = supervisor.tick()
        assertThat(tick2.status).isEqualTo(SessionStatus.TARGET_REACHED)
        assertThat(tick2.shouldStopService).isTrue()
        assertThat(gateway.finalizedStatus).isEqualTo(SessionStatus.TARGET_REACHED)
        assertThat(tick2.events.any { it is SupervisorEvent.ShowTargetReached }).isTrue()
    }

    @Test
    fun outageRecovery_withAutoResumeEnabled_stabilizesThreeTicksThenResumes() = runTest {
        val gateway = FakeGateway(
            session = session(id = "s2", targetKwh = 20.0),
            slices = mutableListOf(slice("s2", startKwh = 50.0)),
            autoResume = true
        )
        val supervisor = SessionSupervisor(gateway, VoltageLoadBalancer(isEnabled = false)) { 1_000L }
        supervisor.onSessionStarted("s2", targetKwh = 20.0, targetDurationMinutes = 0)

        // Grid drops
        gateway.nextTelemetry = Result.success(telemetry(isOnline = false, isCharging = false, counterKwh = 55.0, voltage = null))
        assertThat(supervisor.tick().status).isEqualTo(SessionStatus.OUTAGE_DETECTED)

        // Returns with stable 220V: tick 1 -> OUTAGE_STABILIZING, tick 2 -> OUTAGE_STABILIZING, tick 3 -> resumes CHARGING
        gateway.nextTelemetry = Result.success(telemetry(isOnline = true, isCharging = false, counterKwh = 55.0, voltage = 220))
        assertThat(supervisor.tick().status).isEqualTo(SessionStatus.OUTAGE_STABILIZING)
        assertThat(supervisor.tick().status).isEqualTo(SessionStatus.OUTAGE_STABILIZING)
        val resumedOutcome = supervisor.tick()
        assertThat(resumedOutcome.status).isEqualTo(SessionStatus.CHARGING)
        assertThat(gateway.autoResumeCalls).isEqualTo(1)
        assertThat(resumedOutcome.events.any { it is SupervisorEvent.ShowOutageResumed }).isTrue()
    }

    @Test
    fun outageRecovery_withAutoResumeDisabled_staysOutageAndPromptsManualResume() = runTest {
        val gateway = FakeGateway(
            session = session(id = "s3", targetKwh = 20.0),
            slices = mutableListOf(slice("s3", startKwh = 50.0)),
            autoResume = false
        )
        val supervisor = SessionSupervisor(gateway, VoltageLoadBalancer(isEnabled = false)) { 1_000L }
        supervisor.onSessionStarted("s3", targetKwh = 20.0, targetDurationMinutes = 0)

        gateway.nextTelemetry = Result.success(telemetry(isOnline = false, isCharging = false, counterKwh = 55.0, voltage = null))
        assertThat(supervisor.tick().status).isEqualTo(SessionStatus.OUTAGE_DETECTED)

        gateway.nextTelemetry = Result.success(telemetry(isOnline = true, isCharging = false, counterKwh = 55.0, voltage = 225))
        val outcome = supervisor.tick()
        assertThat(outcome.status).isEqualTo(SessionStatus.OUTAGE_DETECTED)
        assertThat(gateway.autoResumeCalls).isEqualTo(0)
        assertThat(outcome.events.any { it is SupervisorEvent.ShowManualResumeAvailable }).isTrue()
    }

    @Test
    fun counterRegression_stationRebootDetected_closesPreviousSliceCounterAndAutoResumes() = runTest {
        val gateway = FakeGateway(
            session = session(id = "s4", targetKwh = 30.0),
            slices = mutableListOf(slice("s4", startKwh = 100.0)),
            autoResume = true
        )
        val supervisor = SessionSupervisor(gateway, VoltageLoadBalancer(isEnabled = false)) { 1_000L }
        supervisor.onSessionStarted("s4", targetKwh = 30.0, targetDurationMinutes = 0)

        // First sample establishes 112.0 kWh
        gateway.nextTelemetry = Result.success(telemetry(isOnline = true, isCharging = true, counterKwh = 112.0, voltage = 225))
        assertThat(supervisor.tick().status).isEqualTo(SessionStatus.CHARGING)

        // Rapid reboot resets counter to 0.2 kWh (< 112.0 - 0.1 threshold)
        gateway.nextTelemetry = Result.success(telemetry(isOnline = true, isCharging = true, counterKwh = 0.2, voltage = 225))
        val regressionTick = supervisor.tick()
        assertThat(gateway.outageCounters).containsExactly(112.0)
        assertThat(gateway.autoResumeCalls).isEqualTo(1)
        assertThat(regressionTick.status).isEqualTo(SessionStatus.CHARGING)
    }

    @Test
    fun durationTimer_pausesDuringOutageAndStopsAtTargetDuration() = runTest {
        var nowMs = 1_000L
        val gateway = FakeGateway(
            session = session(id = "s5", targetKwh = 0.0, targetDurationMinutes = 2),
            slices = mutableListOf(slice("s5", startKwh = 10.0)),
            stopTargetResult = true
        )
        val supervisor = SessionSupervisor(gateway, VoltageLoadBalancer(isEnabled = false)) { nowMs }
        supervisor.onSessionStarted("s5", targetKwh = 0.0, targetDurationMinutes = 2)

        // Sample 1 at t = 1_000ms (starts duration anchor)
        gateway.nextTelemetry = Result.success(telemetry(isOnline = true, isCharging = true, counterKwh = 10.0, voltage = 225))
        supervisor.tick()
        assertThat(supervisor.accumulatedChargingMs).isEqualTo(0L)

        // Sample 2 at t = 61_000ms -> +60_000ms elapsed
        nowMs = 61_000L
        supervisor.tick()
        assertThat(supervisor.accumulatedChargingMs).isEqualTo(60_000L)

        // Outage lasts 10 minutes -> must NOT count toward active charging duration
        nowMs = 661_000L
        gateway.nextTelemetry = Result.success(telemetry(isOnline = false, isCharging = false, counterKwh = 11.0, voltage = null))
        supervisor.tick()
        assertThat(supervisor.accumulatedChargingMs).isEqualTo(60_000L)

        // Auto/manual back charging at t = 700_000ms (first sample after resume re-anchors without counting gap)
        gateway.restoreChargingState()
        nowMs = 700_000L
        gateway.nextTelemetry = Result.success(telemetry(isOnline = true, isCharging = true, counterKwh = 11.0, voltage = 225))
        supervisor.tick()
        assertThat(supervisor.accumulatedChargingMs).isEqualTo(60_000L)

        // +60_000ms more charging reaches 2 minutes (120_000ms) -> stops & finishes!
        nowMs = 760_000L
        val finalOutcome = supervisor.tick()
        assertThat(supervisor.accumulatedChargingMs).isEqualTo(120_000L)
        assertThat(finalOutcome.status).isEqualTo(SessionStatus.TARGET_REACHED)
        assertThat(finalOutcome.shouldStopService).isTrue()
        assertThat(finalOutcome.events.any { it is SupervisorEvent.ShowDurationTargetReached }).isTrue()
    }

    @Test
    fun connectionLossDuringCharging_opensContinuationSliceAndComputesExactTotalDelivered() = runTest {
        val gateway = FakeGateway(
            session = session(id = "s6", targetKwh = 10.0),
            slices = mutableListOf(slice("s6", startKwh = 100.0)),
            stopTargetResult = true
        )
        val supervisor = SessionSupervisor(gateway, VoltageLoadBalancer(isEnabled = false)) { 5_000L }
        supervisor.onSessionStarted("s6", targetKwh = 10.0, targetDurationMinutes = 0)

        // Station charges from 100.0 to 105.0 kWh
        gateway.nextTelemetry = Result.success(telemetry(isOnline = true, isCharging = true, counterKwh = 105.0, voltage = 225))
        val chargeTick = supervisor.tick()
        assertThat(chargeTick.status).isEqualTo(SessionStatus.CHARGING)

        // Phone loses Wi-Fi for 3 consecutive failed polls -> triggers outage and closes slice #0 at 5.0 kWh
        gateway.nextTelemetry = Result.failure(IllegalStateException("Network unavailable"))
        supervisor.tick()
        supervisor.tick()
        val failedTick3 = supervisor.tick()
        assertThat(failedTick3.status).isEqualTo(SessionStatus.OUTAGE_DETECTED)
        assertThat(gateway.slices.single().endedAt).isNotNull()
        assertThat(gateway.slices.single().finalizedKwh).isWithin(0.01).of(5.0)

        // Wi-Fi comes back while station kept charging continuously (now 106.5 kWh)
        gateway.nextTelemetry = Result.success(telemetry(isOnline = true, isCharging = true, counterKwh = 106.5, voltage = 225))
        val recoverTick = supervisor.tick()
        assertThat(recoverTick.status).isEqualTo(SessionStatus.CHARGING)
        val ongoingRecover = recoverTick.events.filterIsInstance<SupervisorEvent.UpdateOngoingNotification>().single()
        assertThat(ongoingRecover.deliveredKwh).isWithin(0.01).of(6.5)

        // Must NOT trigger 10.0 kWh target early at 109.0 kWh
        gateway.nextTelemetry = Result.success(telemetry(isOnline = true, isCharging = true, counterKwh = 109.0, voltage = 225))
        val tickAt9 = supervisor.tick()
        assertThat(tickAt9.status).isEqualTo(SessionStatus.CHARGING)

        // Hits exact 10.0 kWh target at 110.0 kWh and records full 10.0 kWh in history
        gateway.nextTelemetry = Result.success(telemetry(isOnline = true, isCharging = true, counterKwh = 110.0, voltage = 225))
        val targetTick = supervisor.tick()
        assertThat(targetTick.status).isEqualTo(SessionStatus.TARGET_REACHED)
        assertThat(targetTick.shouldStopService).isTrue()
        val targetEvent = targetTick.events.filterIsInstance<SupervisorEvent.ShowTargetReached>().single()
        assertThat(targetEvent.deliveredKwh).isWithin(0.01).of(10.0)
        assertThat(gateway.session.totalKwhDelivered).isWithin(0.01).of(10.0)
    }

    @Test
    fun outageDetected_whenStationReturnsOnlineIdle_doesNotDoubleCountFinalizedSlice() = runTest {
        val gateway = FakeGateway(
            session = session(id = "s7", targetKwh = 15.0),
            slices = mutableListOf(slice("s7", startKwh = 100.0)),
            autoResume = false
        )
        val supervisor = SessionSupervisor(gateway, VoltageLoadBalancer(isEnabled = false)) { 5_000L }
        supervisor.onSessionStarted("s7", targetKwh = 15.0, targetDurationMinutes = 0)

        gateway.nextTelemetry = Result.success(telemetry(isOnline = true, isCharging = true, counterKwh = 105.0, voltage = 225))
        supervisor.tick()

        gateway.nextTelemetry = Result.failure(IllegalStateException("timeout"))
        supervisor.tick()
        supervisor.tick()
        assertThat(supervisor.tick().status).isEqualTo(SessionStatus.OUTAGE_DETECTED)

        // Station comes back online but stopped (autoResume = false): no open slice exists
        gateway.nextTelemetry = Result.success(telemetry(isOnline = true, isCharging = false, counterKwh = 105.0, voltage = 225))
        val idleRecover = supervisor.tick()
        val manualPrompt = idleRecover.events.filterIsInstance<SupervisorEvent.ShowManualResumeAvailable>().single()
        val ongoing = idleRecover.events.filterIsInstance<SupervisorEvent.UpdateOngoingNotification>().single()
        assertThat(manualPrompt.deliveredKwh).isWithin(0.01).of(5.0)
        assertThat(ongoing.deliveredKwh).isWithin(0.01).of(5.0)
    }

    @Test
    fun manualStopConcurrentWithAutoResumingTick_serializesOperationsAndEndsCancelled() = runTest {
        val resumeEntered = CompletableDeferred<Unit>()
        val allowResumeFinish = CompletableDeferred<Unit>()
        val gateway = FakeGateway(
            session = session(id = "s8", targetKwh = 20.0),
            slices = mutableListOf(slice("s8", startKwh = 50.0)),
            autoResume = true
        ).apply {
            onResumeAfterOutage = {
                resumeEntered.complete(Unit)
                allowResumeFinish.await()
            }
        }
        val supervisor = SessionSupervisor(gateway, VoltageLoadBalancer(isEnabled = false)) { 1_000L }
        supervisor.onSessionStarted("s8", targetKwh = 20.0, targetDurationMinutes = 0)

        // Drive state machine through outage stabilization so next tick enters AUTO_RESUMING
        gateway.nextTelemetry = Result.success(telemetry(isOnline = false, isCharging = false, counterKwh = 52.0, voltage = null))
        supervisor.tick()
        gateway.nextTelemetry = Result.success(telemetry(isOnline = true, isCharging = false, counterKwh = 52.0, voltage = 220))
        supervisor.tick()
        supervisor.tick()
        assertThat(supervisor.currentStatus()).isEqualTo(SessionStatus.OUTAGE_STABILIZING)

        // Next tick enters tryAutoResume -> suspends inside resumeAfterOutage
        val tickJob = async { supervisor.tick() }
        resumeEntered.await()

        // Concurrent manual stop must serialize after the in-flight tick and finish in CANCELLED
        val stopJob = async { supervisor.handleManualStop() }
        yield()
        assertThat(stopJob.isCompleted).isFalse()

        allowResumeFinish.complete(Unit)
        tickJob.await()
        val stopOutcome = stopJob.await()

        assertThat(gateway.callLog).containsExactly("resume", "stop").inOrder()
        assertThat(stopOutcome.status).isEqualTo(SessionStatus.CANCELLED)
        assertThat(stopOutcome.shouldStopService).isTrue()

        // Subsequent queued tick must stop immediately without fetching or sending another start
        gateway.callLog.clear()
        val postStopTick = supervisor.tick()
        assertThat(postStopTick.status).isEqualTo(SessionStatus.CANCELLED)
        assertThat(postStopTick.shouldStopService).isTrue()
        assertThat(gateway.callLog).isEmpty()
    }

    private fun session(
        id: String,
        targetKwh: Double = 0.0,
        targetDurationMinutes: Int = 0
    ) = ChargeSessionEntity(
        id = id,
        targetKwh = targetKwh,
        amperageSetting = 16,
        status = SessionStatus.CHARGING,
        startedAt = 1L,
        completedAt = null,
        totalKwhDelivered = 0.0,
        targetDurationMinutes = targetDurationMinutes,
        accumulatedChargingMs = 0L,
        lastTickAt = 0L
    )

    private fun slice(sessionId: String, startKwh: Double) = ChargeSliceEntity(
        sessionId = sessionId,
        sliceIndex = 0,
        stationCounterStartKwh = startKwh,
        stationCounterLastKwh = startKwh,
        finalizedKwh = 0.0,
        startedAt = 1L,
        endedAt = null,
        terminationReason = null
    )

    private fun telemetry(
        isOnline: Boolean,
        isCharging: Boolean,
        counterKwh: Double,
        voltage: Int?
    ) = TelemetryData(
        statusText = if (isCharging) "Заряджається" else "Очікування",
        isOnline = isOnline,
        voltageMin = voltage,
        voltageAvg = voltage,
        voltageCurrent = voltage,
        voltageMax = voltage,
        neutralMin = null,
        neutralAvg = null,
        neutralCurrent = null,
        neutralMax = null,
        selectedCurrentAmperes = 16,
        measuredCurrentAmperes = if (isCharging) 16.0 else 0.0,
        consumedEnergyKwh = counterKwh,
        powerKw = if (isCharging) 3.5 else 0.0,
        isChargingActive = isCharging
    )

    private class FakeGateway(
        var session: ChargeSessionEntity,
        val slices: MutableList<ChargeSliceEntity>,
        var autoResume: Boolean = true,
        var stopTargetResult: Boolean = true
    ) : SessionMonitorGateway {
        var nextTelemetry: Result<TelemetryData> = Result.failure(IllegalStateException("unset"))
        var finalizedStatus: SessionStatus? = null
        var autoResumeCalls = 0
        val outageCounters = mutableListOf<Double>()
        val callLog = mutableListOf<String>()
        var onResumeAfterOutage: (suspend () -> Unit)? = null

        private fun finalizeLatestSlice(lastCounter: Double, reason: SliceEndReason) {
            val idx = slices.indexOfLast { it.endedAt == null }
            if (idx >= 0) {
                val cur = slices[idx]
                val effectiveLast = maxOf(lastCounter, cur.stationCounterLastKwh)
                val delivered = (effectiveLast - cur.stationCounterStartKwh).coerceAtLeast(0.0)
                slices[idx] = cur.copy(
                    stationCounterLastKwh = effectiveLast,
                    finalizedKwh = delivered,
                    endedAt = 900L,
                    terminationReason = reason
                )
            }
            val sumKwh = slices.sumOf { it.finalizedKwh }
            session = session.copy(totalKwhDelivered = sumKwh)
        }

        private fun openSlice(startCounter: Double) {
            val nextIdx = (slices.maxOfOrNull { it.sliceIndex } ?: -1) + 1
            slices += ChargeSliceEntity(
                sessionId = session.id,
                sliceIndex = nextIdx,
                stationCounterStartKwh = startCounter,
                stationCounterLastKwh = startCounter,
                finalizedKwh = 0.0,
                startedAt = 1_000L
            )
        }

        fun restoreChargingState() {
            val lastCounter = slices.lastOrNull()?.stationCounterLastKwh ?: 0.0
            if (slices.lastOrNull()?.endedAt != null) {
                openSlice(lastCounter)
            }
            session = session.copy(status = SessionStatus.CHARGING)
        }

        override suspend fun fetchTelemetry(): Result<TelemetryData> = nextTelemetry
        override suspend fun getSessionById(sessionId: String): ChargeSessionEntity? = session
        override suspend fun getActiveSession(): ChargeSessionEntity? = session
        override suspend fun getSlicesForSession(sessionId: String): List<ChargeSliceEntity> = slices.toList()

        override suspend fun updateDurationProgress(sessionId: String, accumulatedMs: Long, lastTickAt: Long) {
            session = session.copy(accumulatedChargingMs = accumulatedMs, lastTickAt = lastTickAt)
        }

        override suspend fun updateActiveSliceCounter(sessionId: String, counterKwh: Double) {
            val idx = slices.indexOfLast { it.endedAt == null }
            if (idx >= 0) {
                val cur = slices[idx]
                if (counterKwh > cur.stationCounterLastKwh) {
                    slices[idx] = cur.copy(stationCounterLastKwh = counterKwh)
                }
            }
        }

        override suspend fun handleOutageDetected(sessionId: String, lastCounter: Double) {
            outageCounters += lastCounter
            finalizeLatestSlice(lastCounter, SliceEndReason.POWER_OUTAGE)
            session = session.copy(status = SessionStatus.OUTAGE_DETECTED)
        }

        override suspend fun handleOutageStabilizing(sessionId: String) {
            session = session.copy(status = SessionStatus.OUTAGE_STABILIZING)
        }

        override suspend fun restoreChargingFromExistingSlice(sessionId: String, currentCounter: Double) {
            val latest = slices.lastOrNull()
            if (latest == null) {
                openSlice(currentCounter)
            } else if (latest.endedAt != null) {
                val rolledBack = EnergyAccumulator.isCounterRegression(currentCounter, latest.stationCounterLastKwh) ||
                    currentCounter < latest.stationCounterStartKwh
                openSlice(if (rolledBack) currentCounter else latest.stationCounterLastKwh)
            }
            session = session.copy(status = SessionStatus.CHARGING)
        }

        override suspend fun resumeAfterOutage(sessionId: String, isManual: Boolean): Result<Unit> {
            onResumeAfterOutage?.invoke()
            callLog += "resume"
            autoResumeCalls++
            val anchor = nextTelemetry.getOrNull()?.consumedEnergyKwh
                ?: slices.lastOrNull()?.stationCounterLastKwh
                ?: 0.0
            openSlice(anchor)
            session = session.copy(status = SessionStatus.CHARGING)
            return Result.success(Unit)
        }

        override suspend fun markSessionTargetReached(sessionId: String, finalCounter: Double): Boolean {
            return if (stopTargetResult) {
                finalizeLatestSlice(finalCounter, SliceEndReason.TARGET_REACHED)
                session = session.copy(status = SessionStatus.TARGET_REACHED, completedAt = 999L)
                true
            } else {
                session = session.copy(status = SessionStatus.STOP_PENDING)
                false
            }
        }

        override suspend fun markSessionVehicleStopped(sessionId: String, finalCounter: Double) {
            finalizeLatestSlice(finalCounter, SliceEndReason.VEHICLE_STOPPED)
            session = session.copy(status = SessionStatus.VEHICLE_STOPPED, completedAt = 999L)
        }

        override suspend fun finalizeSessionStopped(
            sessionId: String,
            lastCounter: Double,
            sliceReason: SliceEndReason,
            finalStatus: SessionStatus
        ) {
            finalizeLatestSlice(lastCounter, sliceReason)
            finalizedStatus = finalStatus
            session = session.copy(status = finalStatus, completedAt = 999L)
        }

        override suspend fun stopAndConfirm(allowOfflineConfirmation: Boolean): Boolean = true
        override suspend fun stopActiveSession(sessionId: String): Result<Unit> {
            callLog += "stop"
            val lastCounter = nextTelemetry.getOrNull()?.consumedEnergyKwh
                ?: slices.lastOrNull()?.stationCounterLastKwh
                ?: 0.0
            finalizeLatestSlice(lastCounter, SliceEndReason.MANUAL_STOP)
            session = session.copy(status = SessionStatus.CANCELLED, completedAt = 999L)
            return Result.success(Unit)
        }
        override suspend fun setAmperage(amperes: Int): Result<Unit> = Result.success(Unit)
        override suspend fun updateSessionAmperage(sessionId: String, amperes: Int) {}
        override suspend fun refreshDeadManTimer(sessionId: String, currentVoltage: Int, currentDeliveredKwh: Double) {}
        override fun latestCounterOr(fallback: Double): Double =
            nextTelemetry.getOrNull()?.consumedEnergyKwh ?: fallback
        override fun isAutoResumeEnabled(): Boolean = autoResume
    }

    @Test
    fun onlineIdleWithoutCharging_afterThreeTicks_cancelsSessionInsteadOfWaitingForever() = runTest {
        val gateway = FakeGateway(
            session = session(id = "s-idle"),
            slices = mutableListOf(slice("s-idle", startKwh = 50.0)),
            autoResume = true
        )
        val supervisor = SessionSupervisor(gateway, VoltageLoadBalancer(isEnabled = false)) { 1_000L }
        supervisor.onSessionStarted("s-idle", targetKwh = 0.0, targetDurationMinutes = 0)
        gateway.nextTelemetry = Result.success(telemetry(isOnline = true, isCharging = false, counterKwh = 50.0, voltage = 225))

        assertThat(supervisor.tick().status).isEqualTo(SessionStatus.CHARGING)
        assertThat(supervisor.tick().status).isEqualTo(SessionStatus.CHARGING)
        val tick3 = supervisor.tick()

        assertThat(tick3.status).isEqualTo(SessionStatus.CANCELLED)
        assertThat(tick3.shouldStopService).isTrue()
        assertThat(gateway.finalizedStatus).isEqualTo(SessionStatus.CANCELLED)
    }

    @Test
    fun onlineIdleAfterCharging_afterThreeTicks_marksVehicleStopped() = runTest {
        val gateway = FakeGateway(
            session = session(id = "s-stopped"),
            slices = mutableListOf(slice("s-stopped", startKwh = 50.0)),
            autoResume = true
        )
        val supervisor = SessionSupervisor(gateway, VoltageLoadBalancer(isEnabled = false)) { 1_000L }
        supervisor.onSessionStarted("s-stopped", targetKwh = 0.0, targetDurationMinutes = 0)

        gateway.nextTelemetry = Result.success(telemetry(isOnline = true, isCharging = true, counterKwh = 51.0, voltage = 225))
        assertThat(supervisor.tick().status).isEqualTo(SessionStatus.CHARGING)

        gateway.nextTelemetry = Result.success(telemetry(isOnline = true, isCharging = false, counterKwh = 51.0, voltage = 225))
        assertThat(supervisor.tick().status).isEqualTo(SessionStatus.CHARGING)
        assertThat(supervisor.tick().status).isEqualTo(SessionStatus.CHARGING)
        val tick4 = supervisor.tick()

        assertThat(tick4.status).isEqualTo(SessionStatus.VEHICLE_STOPPED)
        assertThat(tick4.shouldStopService).isTrue()
        assertThat(gateway.session.status).isEqualTo(SessionStatus.VEHICLE_STOPPED)
    }

    @Test
    fun offlineBeforeAnyCharging_cancelsSessionWithoutOutageAlert() = runTest {
        val gateway = FakeGateway(
            session = session(id = "s-unstarted-offline"),
            slices = mutableListOf(slice("s-unstarted-offline", startKwh = 50.0)),
            autoResume = true
        )
        val supervisor = SessionSupervisor(gateway, VoltageLoadBalancer(isEnabled = false)) { 1_000L }
        supervisor.onSessionStarted("s-unstarted-offline", targetKwh = 0.0, targetDurationMinutes = 0)
        gateway.nextTelemetry = Result.success(telemetry(isOnline = false, isCharging = false, counterKwh = 50.0, voltage = null))

        val outcome = supervisor.tick()

        assertThat(outcome.status).isEqualTo(SessionStatus.CANCELLED)
        assertThat(outcome.shouldStopService).isTrue()
        assertThat(outcome.events.filterIsInstance<SupervisorEvent.ShowOutageAlert>()).isEmpty()
        assertThat(gateway.finalizedStatus).isEqualTo(SessionStatus.CANCELLED)
    }

    @Test
    fun connectionLossBeforeAnyCharging_afterThreeFailures_cancelsWithoutOutageAlert() = runTest {
        val gateway = FakeGateway(
            session = session(id = "s-unstarted-fail"),
            slices = mutableListOf(slice("s-unstarted-fail", startKwh = 50.0)),
            autoResume = true
        )
        val supervisor = SessionSupervisor(gateway, VoltageLoadBalancer(isEnabled = false)) { 1_000L }
        supervisor.onSessionStarted("s-unstarted-fail", targetKwh = 0.0, targetDurationMinutes = 0)
        gateway.nextTelemetry = Result.failure(IllegalStateException("timeout"))

        assertThat(supervisor.tick().status).isEqualTo(SessionStatus.CHARGING)
        assertThat(supervisor.tick().status).isEqualTo(SessionStatus.CHARGING)
        val tick3 = supervisor.tick()

        assertThat(tick3.status).isEqualTo(SessionStatus.CANCELLED)
        assertThat(tick3.shouldStopService).isTrue()
        assertThat(tick3.events.filterIsInstance<SupervisorEvent.ShowOutageAlert>()).isEmpty()
    }

    @Test
    fun outageReturningWithUnpluggedStatus_closesAsVehicleStoppedAndCancelsAlert() = runTest {
        val gateway = FakeGateway(
            session = session(id = "s-unplugged"),
            slices = mutableListOf(slice("s-unplugged", startKwh = 50.0)),
            autoResume = true
        )
        val supervisor = SessionSupervisor(gateway, VoltageLoadBalancer(isEnabled = false)) { 1_000L }
        supervisor.onSessionStarted("s-unplugged", targetKwh = 0.0, targetDurationMinutes = 0)

        // Car actively charges first, then garage power cuts out (offline reports 0.0 kWh)
        gateway.nextTelemetry = Result.success(telemetry(isOnline = true, isCharging = true, counterKwh = 52.0, voltage = 225))
        assertThat(supervisor.tick().status).isEqualTo(SessionStatus.CHARGING)

        gateway.nextTelemetry = Result.success(telemetry(isOnline = false, isCharging = false, counterKwh = 0.0, voltage = null))
        val outageOutcome = supervisor.tick()
        assertThat(outageOutcome.status).isEqualTo(SessionStatus.OUTAGE_DETECTED)
        assertThat(gateway.autoResumeCalls).isEqualTo(0)

        // Power returns, but driver unplugged car while power was out
        gateway.nextTelemetry = Result.success(
            telemetry(isOnline = true, isCharging = false, counterKwh = 0.0, voltage = 225)
                .copy(statusText = "Чекаю підключення до авто")
        )
        val recoverOutcome = supervisor.tick()

        assertThat(recoverOutcome.status).isEqualTo(SessionStatus.VEHICLE_STOPPED)
        assertThat(recoverOutcome.shouldStopService).isTrue()
        assertThat(recoverOutcome.events).contains(SupervisorEvent.CancelManualResumeAlert)
        assertThat(gateway.autoResumeCalls).isEqualTo(0)
    }

    @Test
    fun outageWithZeroCounter_preservesLastSampledCounterAndDeliveredKwh() = runTest {
        val gateway = FakeGateway(
            session = session(id = "s-outage-zero"),
            slices = mutableListOf(slice("s-outage-zero", startKwh = 100.0)),
            autoResume = true
        )
        val supervisor = SessionSupervisor(gateway, VoltageLoadBalancer(isEnabled = false)) { 1_000L }
        supervisor.onSessionStarted("s-outage-zero", targetKwh = 20.0, targetDurationMinutes = 0)

        // Actively charging, advances counter to 105.0 kWh
        gateway.nextTelemetry = Result.success(telemetry(isOnline = true, isCharging = true, counterKwh = 105.0, voltage = 225))
        supervisor.tick()

        // Power goes out, station reports offline with 0.0 kWh
        gateway.nextTelemetry = Result.success(telemetry(isOnline = false, isCharging = false, counterKwh = 0.0, voltage = null))
        val outcome = supervisor.tick()

        assertThat(outcome.status).isEqualTo(SessionStatus.OUTAGE_DETECTED)
        assertThat(gateway.outageCounters.last()).isEqualTo(105.0)
        assertThat(gateway.session.totalKwhDelivered).isEqualTo(5.0)
        assertThat(gateway.slices.first().finalizedKwh).isEqualTo(5.0)
        val outageAlert = outcome.events.filterIsInstance<SupervisorEvent.ShowOutageAlert>().single()
        assertThat(outageAlert.deliveredKwh).isWithin(0.01).of(5.0)

        // Power returns with rebooted 0.0 kWh counter: must still go through stabilization without premature stop
        gateway.nextTelemetry = Result.success(telemetry(isOnline = true, isCharging = false, counterKwh = 0.0, voltage = 225))
        val stabilize1 = supervisor.tick()
        assertThat(stabilize1.status).isEqualTo(SessionStatus.OUTAGE_STABILIZING)
        assertThat(gateway.autoResumeCalls).isEqualTo(0)
        assertThat(gateway.session.totalKwhDelivered).isWithin(0.01).of(5.0)
    }

    @Test
    fun counterRegressionToExactZero_resetsActiveCounterWithoutInflatingTotalDelivered() = runTest {
        val gateway = FakeGateway(
            session = session(id = "s-reg-zero", targetKwh = 30.0),
            slices = mutableListOf(slice("s-reg-zero", startKwh = 100.0)),
            autoResume = true
        )
        val supervisor = SessionSupervisor(gateway, VoltageLoadBalancer(isEnabled = false)) { 1_000L }
        supervisor.onSessionStarted("s-reg-zero", targetKwh = 30.0, targetDurationMinutes = 0)

        gateway.nextTelemetry = Result.success(telemetry(isOnline = true, isCharging = true, counterKwh = 112.0, voltage = 225))
        assertThat(supervisor.tick().status).isEqualTo(SessionStatus.CHARGING)

        // Station rapidly reboots to 0.0 kWh while staying online
        gateway.nextTelemetry = Result.success(telemetry(isOnline = true, isCharging = true, counterKwh = 0.0, voltage = 225))
        val regressionTick = supervisor.tick()
        assertThat(regressionTick.status).isEqualTo(SessionStatus.CHARGING)
        assertThat(regressionTick.shouldStopService).isFalse()
        val ongoing = regressionTick.events.filterIsInstance<SupervisorEvent.UpdateOngoingNotification>().single()
        assertThat(ongoing.deliveredKwh).isWithin(0.01).of(12.0)
    }
}

