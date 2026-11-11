package ua.org.electros.app.domain.monitor

import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.test.runTest
import org.junit.Test
import ua.org.electros.app.data.local.entity.ChargeSessionEntity
import ua.org.electros.app.data.local.entity.ChargeSliceEntity
import ua.org.electros.app.data.local.entity.SessionStatus
import ua.org.electros.app.data.local.entity.SliceEndReason
import ua.org.electros.app.data.model.TelemetryData
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

        fun restoreChargingState() {
            session = session.copy(status = SessionStatus.CHARGING)
        }

        override suspend fun fetchTelemetry(): Result<TelemetryData> = nextTelemetry
        override suspend fun getSessionById(sessionId: String): ChargeSessionEntity? = session
        override suspend fun getActiveSession(): ChargeSessionEntity? = session
        override suspend fun getSlicesForSession(sessionId: String): List<ChargeSliceEntity> = slices

        override suspend fun updateDurationProgress(sessionId: String, accumulatedMs: Long, lastTickAt: Long) {
            session = session.copy(accumulatedChargingMs = accumulatedMs, lastTickAt = lastTickAt)
        }

        override suspend fun handleOutageDetected(sessionId: String, lastCounter: Double) {
            outageCounters += lastCounter
            session = session.copy(status = SessionStatus.OUTAGE_DETECTED)
        }

        override suspend fun handleOutageStabilizing(sessionId: String) {
            session = session.copy(status = SessionStatus.OUTAGE_STABILIZING)
        }

        override suspend fun restoreChargingFromExistingSlice(sessionId: String) {
            session = session.copy(status = SessionStatus.CHARGING)
        }

        override suspend fun resumeAfterOutage(sessionId: String, isManual: Boolean): Result<Unit> {
            autoResumeCalls++
            session = session.copy(status = SessionStatus.CHARGING)
            return Result.success(Unit)
        }

        override suspend fun markSessionTargetReached(sessionId: String, finalCounter: Double): Boolean {
            return if (stopTargetResult) {
                session = session.copy(status = SessionStatus.TARGET_REACHED, completedAt = 999L)
                true
            } else {
                session = session.copy(status = SessionStatus.STOP_PENDING)
                false
            }
        }

        override suspend fun markSessionVehicleStopped(sessionId: String, finalCounter: Double) {
            session = session.copy(status = SessionStatus.VEHICLE_STOPPED, completedAt = 999L)
        }

        override suspend fun finalizeSessionStopped(
            sessionId: String,
            lastCounter: Double,
            sliceReason: SliceEndReason,
            finalStatus: SessionStatus
        ) {
            finalizedStatus = finalStatus
            session = session.copy(status = finalStatus, completedAt = 999L)
        }

        override suspend fun stopAndConfirm(allowOfflineConfirmation: Boolean): Boolean = true
        override suspend fun stopActiveSession(sessionId: String): Result<Unit> = Result.success(Unit)
        override suspend fun setAmperage(amperes: Int): Result<Unit> = Result.success(Unit)
        override suspend fun updateSessionAmperage(sessionId: String, amperes: Int) {}
        override suspend fun refreshDeadManTimer(sessionId: String, currentVoltage: Int, currentDeliveredKwh: Double) {}
        override fun latestCounterOr(fallback: Double): Double =
            nextTelemetry.getOrNull()?.consumedEnergyKwh ?: fallback
        override fun isAutoResumeEnabled(): Boolean = autoResume
    }
}

