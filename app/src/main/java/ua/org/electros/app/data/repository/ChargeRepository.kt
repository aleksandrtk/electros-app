package ua.org.electros.app.data.repository

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import ua.org.electros.app.data.local.AuthPreferences
import ua.org.electros.app.data.local.dao.ChargeDao
import ua.org.electros.app.data.local.entity.ChargeSessionEntity
import ua.org.electros.app.data.local.entity.ChargeSliceEntity
import ua.org.electros.app.data.local.entity.SessionStatus
import ua.org.electros.app.data.local.entity.SliceEndReason
import ua.org.electros.app.data.model.TelemetryData
import ua.org.electros.app.data.network.ElectrosWebScraper
import ua.org.electros.app.domain.accumulator.EnergyAccumulator
import java.util.UUID

class ChargeRepository(
    private val scraper: ElectrosWebScraper,
    private val chargeDao: ChargeDao,
    private val authPreferences: AuthPreferences
) {

    private val _telemetryState = MutableStateFlow<TelemetryData?>(null)
    val telemetryState: StateFlow<TelemetryData?> = _telemetryState.asStateFlow()

    fun observeActiveSession(): Flow<ChargeSessionEntity?> = chargeDao.observeActiveSession()

    fun getAllSessions(): Flow<List<ChargeSessionEntity>> = chargeDao.getAllSessions()

    suspend fun getSlicesForSession(sessionId: String): List<ChargeSliceEntity> {
        return chargeDao.getSlicesForSession(sessionId)
    }

    suspend fun fetchLatestTelemetry(): Result<TelemetryData> {
        val result = scraper.fetchTelemetry()
        result.onSuccess { data ->
            _telemetryState.value = data
        }
        return result
    }

    suspend fun startNewSession(targetKwh: Double, amperage: Int): Result<String> {
        val sessionId = UUID.randomUUID().toString()
        val session = ChargeSessionEntity(
            id = sessionId,
            targetKwh = targetKwh,
            amperageSetting = amperage,
            status = SessionStatus.CHARGING,
            startedAt = System.currentTimeMillis()
        )
        chargeDao.insertSession(session)

        // Set amperage on station first
        scraper.setAmperage(amperage)

        // Start station
        val startResult = scraper.startCharge()
        if (startResult.isSuccess) {
            val telemetry = _telemetryState.value
            val currentCounter = telemetry?.consumedEnergyKwh ?: 0.0
            chargeDao.startNewSlice(sessionId, currentCounter)
            return Result.success(sessionId)
        } else {
            chargeDao.updateSession(session.copy(status = SessionStatus.CANCELLED))
            return Result.failure(startResult.exceptionOrNull() ?: Exception("Start command failed"))
        }
    }

    suspend fun stopActiveSession(sessionId: String): Result<Unit> {
        val stopResult = scraper.stopCharge()
        val telemetry = _telemetryState.value
        val lastCounter = telemetry?.consumedEnergyKwh ?: 0.0

        chargeDao.finalizeActiveSliceAndCommit(
            sessionId = sessionId,
            lastSampledKwh = lastCounter,
            reason = SliceEndReason.MANUAL_STOP
        )

        val activeSession = chargeDao.getSessionById(sessionId)
        if (activeSession != null) {
            chargeDao.updateSession(
                activeSession.copy(
                    status = SessionStatus.CANCELLED,
                    completedAt = System.currentTimeMillis()
                )
            )
        }
        return stopResult
    }

    suspend fun setAmperage(amperes: Int): Result<Unit> {
        return scraper.setAmperage(amperes)
    }

    suspend fun setScheduleTimer(start: String, stop: String): Result<Unit> {
        return scraper.setTimer(start, stop)
    }

    suspend fun handleOutageDetected(sessionId: String, lastCounter: Double) {
        chargeDao.finalizeActiveSliceAndCommit(
            sessionId = sessionId,
            lastSampledKwh = lastCounter,
            reason = SliceEndReason.POWER_OUTAGE
        )
        val session = chargeDao.getSessionById(sessionId) ?: return
        chargeDao.updateSession(session.copy(status = SessionStatus.OUTAGE_DETECTED))
    }

    suspend fun handleOutageStabilizing(sessionId: String) {
        val session = chargeDao.getSessionById(sessionId) ?: return
        chargeDao.updateSession(session.copy(status = SessionStatus.OUTAGE_STABILIZING))
    }

    suspend fun resumeAfterOutage(sessionId: String): Result<Unit> {
        val session = chargeDao.getSessionById(sessionId) ?: return Result.failure(Exception("Session not found"))
        val slices = chargeDao.getSlicesForSession(sessionId)
        val sumDelivered = slices.sumOf { it.finalizedKwh }
        val remaining = EnergyAccumulator.calculateRemainingKwh(session.targetKwh, sumDelivered)

        if (remaining <= 0.1) {
            chargeDao.updateSession(session.copy(status = SessionStatus.TARGET_REACHED, completedAt = System.currentTimeMillis()))
            return Result.success(Unit)
        }

        // Program dead-man hardware timer as safety buffer
        val telemetry = _telemetryState.value
        val currentVoltage = telemetry?.voltageCurrent ?: 220
        val deadManMinutes = EnergyAccumulator.calculateDeadManTimerMinutes(remaining, currentVoltage, session.amperageSetting)
        // Format to HH:MM if needed
        val hours = (deadManMinutes / 60) % 24
        val mins = deadManMinutes % 60
        scraper.setTimer("00:00", String.format("%02d:%02d", hours, mins))

        // Start station
        val startResult = scraper.startCharge()
        if (startResult.isSuccess) {
            val freshTelemetry = _telemetryState.value
            val currentCounter = freshTelemetry?.consumedEnergyKwh ?: 0.0
            chargeDao.startNewSlice(sessionId, currentCounter)
            chargeDao.updateSession(session.copy(status = SessionStatus.CHARGING))
            return Result.success(Unit)
        }
        return startResult
    }

    suspend fun markSessionTargetReached(sessionId: String, lastCounter: Double) {
        scraper.stopCharge()
        chargeDao.finalizeActiveSliceAndCommit(
            sessionId = sessionId,
            lastSampledKwh = lastCounter,
            reason = SliceEndReason.TARGET_REACHED
        )
        val session = chargeDao.getSessionById(sessionId) ?: return
        chargeDao.updateSession(session.copy(status = SessionStatus.TARGET_REACHED, completedAt = System.currentTimeMillis()))
    }

    suspend fun markSessionVehicleStopped(sessionId: String, lastCounter: Double) {
        chargeDao.finalizeActiveSliceAndCommit(
            sessionId = sessionId,
            lastSampledKwh = lastCounter,
            reason = SliceEndReason.VEHICLE_STOPPED
        )
        val session = chargeDao.getSessionById(sessionId) ?: return
        chargeDao.updateSession(session.copy(status = SessionStatus.VEHICLE_STOPPED, completedAt = System.currentTimeMillis()))
    }
}

