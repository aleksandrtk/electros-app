package ua.org.electros.app.data.repository

import kotlinx.coroutines.delay
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
import java.io.IOException
import java.util.UUID

class ChargeRepository(
    private val scraper: ElectrosWebScraper,
    private val chargeDao: ChargeDao,
    private val authPreferences: AuthPreferences? = null
) {

    private val _telemetryState = MutableStateFlow<TelemetryData?>(null)
    val telemetryState: StateFlow<TelemetryData?> = _telemetryState.asStateFlow()

    fun observeActiveSession(): Flow<ChargeSessionEntity?> = chargeDao.observeActiveSession()

    fun getAllSessions(): Flow<List<ChargeSessionEntity>> = chargeDao.getAllSessions()

    suspend fun getSlicesForSession(sessionId: String): List<ChargeSliceEntity> {
        return chargeDao.getSlicesForSession(sessionId)
    }

    suspend fun login(serial: String, password: String): Result<Unit> {
        val result = scraper.login(serial, password)
        if (result.isSuccess) {
            authPreferences?.deviceSerial = serial
            authPreferences?.internetPassword = password
            fetchLatestTelemetry()
        }
        return result
    }

    fun logout() {
        authPreferences?.clearCredentials()
        _telemetryState.value = null
    }

    suspend fun getSessionById(sessionId: String): ChargeSessionEntity? {
        return chargeDao.getSessionById(sessionId)
    }

    suspend fun getActiveSession(): ChargeSessionEntity? {
        return chargeDao.getActiveSession()
    }

    suspend fun fetchLatestTelemetry(isMonitorServiceRunning: Boolean = false): Result<TelemetryData> {
        val result = scraper.fetchTelemetry()
        result.onSuccess { data ->
            _telemetryState.value = data
            if (!isMonitorServiceRunning && (!data.isOnline || !data.isChargingActive)) {
                val active = chargeDao.getActiveSession()
                if (active != null && active.status != SessionStatus.STOP_PENDING) {
                    val isManagedOutageSession = active.targetDurationMinutes > 0 ||
                        active.targetKwh > 0.0 ||
                        (authPreferences?.autoResumeAfterOutage == true)
                    val ageHours = (System.currentTimeMillis() - active.startedAt) / 3_600_000L
                    if (!isManagedOutageSession || (active.totalKwhDelivered == 0.0 && active.accumulatedChargingMs == 0L && ageHours >= 24L)) {
                        chargeDao.cancelAllActiveSessions()
                    }
                }
            }
        }
        return result
    }

    suspend fun startNewSession(
        targetKwh: Double,
        amperage: Int,
        targetDurationMinutes: Int = 0
    ): Result<String> {
        val currentTelemetry = _telemetryState.value
        if (currentTelemetry != null && !currentTelemetry.isOnline) {
            return Result.failure(IllegalStateException("Станція вимкнена з розетки (OFFLINE). Підключіть живлення до станції."))
        }

        // Clear any leftover inactive/stale sessions before starting clean
        chargeDao.cancelAllActiveSessions()

        val sessionId = UUID.randomUUID().toString()
        val now = System.currentTimeMillis()
        val session = ChargeSessionEntity(
            id = sessionId,
            targetKwh = targetKwh,
            amperageSetting = amperage,
            status = SessionStatus.CHARGING,
            startedAt = now,
            targetDurationMinutes = targetDurationMinutes,
            accumulatedChargingMs = 0L,
            lastTickAt = 0L
        )
        chargeDao.insertSession(session)

        // Set amperage on station first
        scraper.setAmperage(amperage)

        // Start station
        val startResult = scraper.startCharge(amperage)
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

    suspend fun attachSessionToOngoingCharge(
        targetKwh: Double,
        amperage: Int,
        targetDurationMinutes: Int
    ): Result<String> {
        chargeDao.cancelAllActiveSessions()
        val sessionId = UUID.randomUUID().toString()
        val now = System.currentTimeMillis()
        val telemetry = _telemetryState.value
        val currentCounter = telemetry?.consumedEnergyKwh ?: 0.0
        val session = ChargeSessionEntity(
            id = sessionId,
            targetKwh = targetKwh,
            amperageSetting = amperage,
            status = SessionStatus.CHARGING,
            startedAt = now,
            targetDurationMinutes = targetDurationMinutes,
            accumulatedChargingMs = 0L,
            lastTickAt = now
        )
        chargeDao.insertSession(session)
        chargeDao.startNewSlice(sessionId, currentCounter, now)
        return Result.success(sessionId)
    }

    suspend fun updateDurationProgress(sessionId: String, accumulatedMs: Long, lastTickAt: Long) {
        chargeDao.updateDurationProgress(sessionId, accumulatedMs, lastTickAt)
    }

    suspend fun updateTargetDurationMinutes(sessionId: String, targetDurationMinutes: Int) {
        chargeDao.updateTargetDurationMinutes(sessionId, targetDurationMinutes)
    }

    suspend fun updateSessionAmperage(sessionId: String, amperes: Int) {
        chargeDao.updateSessionAmperage(sessionId, amperes)
    }

    /**
     * Sends stop command and confirms via fresh telemetry that charging stopped.
     */
    suspend fun stopAndConfirm(
        maxAttempts: Int = 3,
        allowOfflineConfirmation: Boolean = false,
        confirmDelayMs: Long = 4_000L,
        retryDelayMs: Long = 2_000L
    ): Boolean {
        repeat(maxAttempts) { attempt ->
            scraper.stopCharge()
            if (confirmDelayMs > 0L) delay(confirmDelayMs)
            val t = scraper.fetchTelemetry().getOrNull()
            if (t != null) {
                _telemetryState.value = t
                if (!t.isChargingActive && (t.isOnline || allowOfflineConfirmation)) {
                    return true
                }
            }
            if (attempt < maxAttempts - 1 && retryDelayMs > 0L) delay(retryDelayMs)
        }
        return false
    }

    suspend fun finalizeSessionStopped(
        sessionId: String,
        lastCounter: Double,
        sliceReason: SliceEndReason,
        finalStatus: SessionStatus
    ) {
        chargeDao.finalizeActiveSliceAndCommit(
            sessionId = sessionId,
            lastSampledKwh = lastCounter,
            reason = sliceReason
        )
        chargeDao.updateSessionStatus(
            sessionId = sessionId,
            status = finalStatus,
            completedAt = System.currentTimeMillis(),
            lastTickAt = 0L
        )
    }

    suspend fun stopActiveSession(
        sessionId: String,
        confirmDelayMs: Long = 4_000L,
        retryDelayMs: Long = 2_000L
    ): Result<Unit> {
        val session = chargeDao.getSessionById(sessionId)
        val confirmed = stopAndConfirm(
            allowOfflineConfirmation = true,
            confirmDelayMs = confirmDelayMs,
            retryDelayMs = retryDelayMs
        )
        if (!confirmed) {
            if (session != null) {
                chargeDao.updateSessionStatus(
                    sessionId = sessionId,
                    status = SessionStatus.STOP_PENDING,
                    completedAt = null,
                    lastTickAt = 0L
                )
            }
            return Result.failure(IOException("Не вдалося зупинити станцію"))
        }
        if (session != null) {
            val lastCounter = _telemetryState.value?.consumedEnergyKwh ?: 0.0
            finalizeSessionStopped(
                sessionId = sessionId,
                lastCounter = lastCounter,
                sliceReason = SliceEndReason.MANUAL_STOP,
                finalStatus = SessionStatus.CANCELLED
            )
        }
        return Result.success(Unit)
    }

    suspend fun cancelStaleSessionsIfOffline() {
        val telemetry = _telemetryState.value
        if (telemetry != null && !telemetry.isOnline) {
            val active = chargeDao.getActiveSession()
            if (active != null &&
                active.status != SessionStatus.STOP_PENDING &&
                active.totalKwhDelivered == 0.0 &&
                active.targetDurationMinutes == 0 &&
                active.targetKwh == 0.0 &&
                authPreferences?.autoResumeAfterOutage != true
            ) {
                chargeDao.cancelAllActiveSessions()
            }
        }
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
        chargeDao.updateSession(
            session.copy(
                status = SessionStatus.OUTAGE_DETECTED,
                lastTickAt = 0L
            )
        )
    }

    suspend fun handleOutageStabilizing(sessionId: String) {
        val session = chargeDao.getSessionById(sessionId) ?: return
        chargeDao.updateSession(
            session.copy(
                status = SessionStatus.OUTAGE_STABILIZING,
                lastTickAt = 0L
            )
        )
    }

    suspend fun restoreChargingFromExistingSlice(sessionId: String) {
        val session = chargeDao.getSessionById(sessionId) ?: return
        chargeDao.updateSession(
            session.copy(
                status = SessionStatus.CHARGING,
                lastTickAt = System.currentTimeMillis()
            )
        )
    }

    suspend fun resumeAfterOutage(sessionId: String): Result<Unit> {
        val session = chargeDao.getSessionById(sessionId) ?: return Result.failure(Exception("Session not found"))
        val slices = chargeDao.getSlicesForSession(sessionId)
        val sumDelivered = slices.sumOf { it.finalizedKwh }

        if (session.targetKwh > 0.0) {
            val remainingKwh = EnergyAccumulator.calculateRemainingKwh(session.targetKwh, sumDelivered)
            if (remainingKwh <= 0.1) {
                chargeDao.updateSession(session.copy(status = SessionStatus.TARGET_REACHED, completedAt = System.currentTimeMillis(), lastTickAt = 0L))
                return Result.success(Unit)
            }
        }

        if (session.targetDurationMinutes > 0) {
            val targetDurationMs = session.targetDurationMinutes * 60_000L
            if (session.accumulatedChargingMs >= targetDurationMs) {
                chargeDao.updateSession(session.copy(status = SessionStatus.TARGET_REACHED, completedAt = System.currentTimeMillis(), lastTickAt = 0L))
                return Result.success(Unit)
            }
        }

        // Start station with configured amperage
        scraper.setAmperage(session.amperageSetting)
        val startResult = scraper.startCharge(session.amperageSetting)
        if (startResult.isSuccess) {
            val freshTelemetry = _telemetryState.value
            val currentCounter = freshTelemetry?.consumedEnergyKwh ?: 0.0
            chargeDao.startNewSlice(sessionId, currentCounter)
            chargeDao.updateSession(
                session.copy(
                    status = SessionStatus.CHARGING,
                    lastTickAt = 0L
                )
            )
            return Result.success(Unit)
        }
        return startResult
    }

    suspend fun markSessionTargetReached(
        sessionId: String,
        lastCounter: Double,
        confirmDelayMs: Long = 4_000L,
        retryDelayMs: Long = 2_000L
    ): Boolean {
        val stopped = stopAndConfirm(
            allowOfflineConfirmation = false,
            confirmDelayMs = confirmDelayMs,
            retryDelayMs = retryDelayMs
        )
        if (!stopped) {
            chargeDao.updateSessionStatus(
                sessionId = sessionId,
                status = SessionStatus.STOP_PENDING,
                completedAt = null,
                lastTickAt = 0L
            )
            return false
        }
        val finalCounter = _telemetryState.value?.consumedEnergyKwh?.takeIf { it >= lastCounter } ?: lastCounter
        finalizeSessionStopped(
            sessionId = sessionId,
            lastCounter = finalCounter,
            sliceReason = SliceEndReason.TARGET_REACHED,
            finalStatus = SessionStatus.TARGET_REACHED
        )
        return true
    }

    suspend fun markSessionVehicleStopped(sessionId: String, lastCounter: Double) {
        chargeDao.finalizeActiveSliceAndCommit(
            sessionId = sessionId,
            lastSampledKwh = lastCounter,
            reason = SliceEndReason.VEHICLE_STOPPED
        )
        val session = chargeDao.getSessionById(sessionId) ?: return
        chargeDao.updateSession(
            session.copy(
                status = SessionStatus.VEHICLE_STOPPED,
                completedAt = System.currentTimeMillis(),
                lastTickAt = 0L
            )
        )
    }
}

