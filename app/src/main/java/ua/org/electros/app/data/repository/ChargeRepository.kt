package ua.org.electros.app.data.repository

import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import ua.org.electros.app.data.local.AuthPreferences
import ua.org.electros.app.data.local.SettingsStore
import ua.org.electros.app.data.local.dao.ChargeDao
import ua.org.electros.app.data.local.entity.ChargeSessionEntity
import ua.org.electros.app.data.local.entity.ChargeSliceEntity
import ua.org.electros.app.data.local.entity.SessionStatus
import ua.org.electros.app.data.local.entity.SliceEndReason
import ua.org.electros.app.data.model.TelemetryData
import ua.org.electros.app.data.network.ElectrosWebScraper
import ua.org.electros.app.domain.accumulator.EnergyAccumulator
import java.io.IOException
import java.time.LocalTime
import java.util.UUID

class ChargeRepository(
    private val scraper: ElectrosWebScraper,
    private val chargeDao: ChargeDao,
    private val authPreferences: AuthPreferences? = null,
    private val settingsStore: SettingsStore? = null,
    private val isAutoResumeEnabledProvider: (() -> Boolean)? = null,
    private val scheduleConfigProvider: (() -> Triple<Boolean, String, String>)? = null,
    private val localTimeProvider: () -> LocalTime = { LocalTime.now() },
    private val clockMs: () -> Long = { System.currentTimeMillis() }
) {

    private suspend fun isAutoResumeEnabled(): Boolean {
        isAutoResumeEnabledProvider?.let { return it() }
        settingsStore?.let { return it.awaitSettings().autoResumeAfterOutage }
        return true
    }

    private suspend fun readScheduleConfig(): Triple<Boolean, String, String> {
        scheduleConfigProvider?.let { return it() }
        val settings = settingsStore?.awaitSettings()
        return if (settings != null) {
            Triple(settings.isScheduleEnabled, settings.scheduleStartTime, settings.scheduleStopTime)
        } else {
            Triple(false, "23:00", "07:00")
        }
    }

    private val _telemetryState = MutableStateFlow<TelemetryData?>(null)
    val telemetryState: StateFlow<TelemetryData?> = _telemetryState.asStateFlow()

    fun observeActiveSession(): Flow<ChargeSessionEntity?> = chargeDao.observeActiveSession()

    fun observeSlicesForSession(sessionId: String): Flow<List<ChargeSliceEntity>> =
        chargeDao.observeSlicesForSession(sessionId)

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
        scraper.resetAuthBackoff()
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
            if (!isMonitorServiceRunning && (!data.isOnline || !data.isChargingActive)) {
                reconcileInactiveSessionOutsideMonitor(data)
            }
            _telemetryState.value = data
        }
        return result
    }

    private suspend fun reconcileInactiveSessionOutsideMonitor(data: TelemetryData) {
        val active = chargeDao.getActiveSession() ?: return
        if (active.status == SessionStatus.STOP_PENDING) return

        val isTrackedOutage = active.status == SessionStatus.OUTAGE_DETECTED ||
            active.status == SessionStatus.OUTAGE_STABILIZING ||
            active.status == SessionStatus.AUTO_RESUMING
        val slices = chargeDao.getSlicesForSession(active.id)
        val hasFinalizedEnergy = slices.any { it.finalizedKwh > 0.0 }
        val latestSlice = slices.lastOrNull()
        val hasOpenSliceEnergy = data.isOnline &&
            latestSlice != null &&
            latestSlice.endedAt == null &&
            data.consumedEnergyKwh > latestSlice.stationCounterStartKwh + 0.05
        val hasChargingProgress = active.totalKwhDelivered > 0.0 ||
            active.accumulatedChargingMs > 0L ||
            active.lastTickAt > 0L ||
            hasFinalizedEnergy ||
            hasOpenSliceEnergy
        val hasEnergyOrDuration = active.totalKwhDelivered > 0.0 ||
            active.accumulatedChargingMs > 0L ||
            hasFinalizedEnergy ||
            hasOpenSliceEnergy

        val now = clockMs()
        val sessionAgeMs = (now - active.startedAt).coerceAtLeast(0L)
        val lastActivityMs = (now - maxOf(active.startedAt, active.lastTickAt)).coerceAtLeast(0L)

        if (data.isOnline && !data.isChargingActive) {
            if (!isTrackedOutage) {
                if (sessionAgeMs >= STARTUP_GRACE_MS) {
                    closeReconciledSession(active.id, data.consumedEnergyKwh, hasEnergyOrDuration)
                }
            } else if (data.statusText.contains("Чекаю підключення", ignoreCase = true) ||
                (!hasChargingProgress && sessionAgeMs >= STALE_OUTAGE_MS)
            ) {
                closeReconciledSession(active.id, data.consumedEnergyKwh, hasEnergyOrDuration)
            }
            return
        }

        if (!data.isOnline) {
            val neverCharged = !hasChargingProgress
            val isStaleUntrackedCharging = !isTrackedOutage && lastActivityMs >= STALE_OFFLINE_CHARGING_MS
            val isStaleEmptyOutage = isTrackedOutage && !hasEnergyOrDuration && sessionAgeMs >= STALE_OUTAGE_MS
            if (neverCharged || isStaleUntrackedCharging || isStaleEmptyOutage) {
                val safeCounter = maxOf(data.consumedEnergyKwh, latestSlice?.stationCounterLastKwh ?: 0.0)
                closeReconciledSession(active.id, safeCounter, hasEnergyOrDuration)
            }
        }
    }

    private suspend fun closeReconciledSession(
        sessionId: String,
        finalCounter: Double,
        preserveAsVehicleStopped: Boolean
    ) {
        if (preserveAsVehicleStopped) {
            markSessionVehicleStopped(sessionId, finalCounter)
        } else {
            finalizeSessionStopped(
                sessionId = sessionId,
                lastCounter = finalCounter,
                sliceReason = SliceEndReason.MANUAL_STOP,
                finalStatus = SessionStatus.CANCELLED
            )
        }
    }

    private suspend fun fetchFreshTelemetryForAnchor(): TelemetryData? {
        val fresh = scraper.fetchTelemetry().getOrNull()
        if (fresh != null) {
            _telemetryState.value = fresh
            return fresh
        }
        return _telemetryState.value
    }

    suspend fun armDeadManTimer(
        session: ChargeSessionEntity,
        voltageVolts: Int,
        deliveredKwh: Double
    ) {
        val (schedEnabled, schedStart, schedStop) = readScheduleConfig()
        val window = EnergyAccumulator.computeDeadManScheduleWindow(
            now = localTimeProvider(),
            targetKwh = session.targetKwh,
            deliveredKwh = deliveredKwh,
            targetDurationMinutes = session.targetDurationMinutes,
            accumulatedChargingMs = session.accumulatedChargingMs,
            voltageVolts = voltageVolts,
            amperes = session.amperageSetting,
            isScheduleEnabled = schedEnabled,
            scheduleStartTime = schedStart,
            scheduleStopTime = schedStop
        ) ?: return
        runCatching { scraper.setTimer(window.first, window.second) }
    }

    suspend fun refreshDeadManTimer(sessionId: String, voltageVolts: Int, deliveredKwh: Double) {
        val session = chargeDao.getSessionById(sessionId) ?: return
        armDeadManTimer(session, voltageVolts, deliveredKwh)
    }

    private suspend fun restoreScheduleTimer(forceRestore: Boolean = false, session: ChargeSessionEntity? = null) {
        val shouldRestore = forceRestore || (session != null && (session.targetKwh > 0.0 || session.targetDurationMinutes > 0))
        if (!shouldRestore) return
        val (schedEnabled, schedStart, schedStop) = readScheduleConfig()
        val targetStart = if (schedEnabled) schedStart else "00:00"
        val targetStop = if (schedEnabled) schedStop else "00:00"
        runCatching { scraper.setTimer(targetStart, targetStop) }
    }

    suspend fun startNewSession(
        targetKwh: Double,
        amperage: Int,
        targetDurationMinutes: Int = 0
    ): Result<String> {
        val preStartTelemetry = fetchFreshTelemetryForAnchor()
        if (preStartTelemetry != null && !preStartTelemetry.isOnline) {
            return Result.failure(IllegalStateException("Станція вимкнена з розетки (OFFLINE). Підключіть живлення до станції."))
        }
        if (preStartTelemetry != null &&
            !preStartTelemetry.isChargingActive &&
            preStartTelemetry.statusText.contains("Чекаю підключення", ignoreCase = true)
        ) {
            return Result.failure(IllegalStateException("Авто не підключено до станції."))
        }

        // Clear any leftover inactive/stale sessions before starting clean
        chargeDao.cancelAllActiveSessions(clockMs())

        val sessionId = UUID.randomUUID().toString()
        val now = clockMs()
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

        val startResult = scraper.startCharge(amperage)
        if (startResult.isSuccess) {
            val anchorTelemetry = fetchFreshTelemetryForAnchor()
            if (anchorTelemetry == null) {
                chargeDao.updateSession(session.copy(status = SessionStatus.CANCELLED, completedAt = clockMs()))
                return Result.failure(IOException("No telemetry to anchor slice"))
            }
            if (!anchorTelemetry.isOnline) {
                chargeDao.updateSession(session.copy(status = SessionStatus.CANCELLED, completedAt = clockMs()))
                return Result.failure(IllegalStateException("Станція вимкнена з розетки (OFFLINE). Підключіть живлення до станції."))
            }
            if (!anchorTelemetry.isChargingActive &&
                anchorTelemetry.statusText.contains("Чекаю підключення", ignoreCase = true)
            ) {
                chargeDao.updateSession(session.copy(status = SessionStatus.CANCELLED, completedAt = clockMs()))
                return Result.failure(IllegalStateException("Авто не підключено до станції."))
            }
            val anchorNow = clockMs()
            val activeSession = if (anchorTelemetry.isChargingActive) {
                session.copy(lastTickAt = anchorNow).also {
                    chargeDao.updateDurationProgress(sessionId, 0L, anchorNow)
                }
            } else {
                session
            }
            chargeDao.startNewSlice(sessionId, anchorTelemetry.consumedEnergyKwh, anchorNow)
            armDeadManTimer(activeSession, anchorTelemetry.voltageCurrent ?: 230, 0.0)
            return Result.success(sessionId)
        } else {
            chargeDao.updateSession(session.copy(status = SessionStatus.CANCELLED, completedAt = clockMs()))
            return Result.failure(startResult.exceptionOrNull() ?: Exception("Start command failed"))
        }
    }

    suspend fun attachSessionToOngoingCharge(
        targetKwh: Double,
        amperage: Int,
        targetDurationMinutes: Int
    ): Result<String> {
        val anchorTelemetry = fetchFreshTelemetryForAnchor()
            ?: return Result.failure(IOException("No telemetry to anchor slice"))
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
            lastTickAt = now
        )
        chargeDao.insertSession(session)
        chargeDao.startNewSlice(sessionId, anchorTelemetry.consumedEnergyKwh, now)
        armDeadManTimer(session, anchorTelemetry.voltageCurrent ?: 230, 0.0)
        return Result.success(sessionId)
    }

    suspend fun updateDurationProgress(sessionId: String, accumulatedMs: Long, lastTickAt: Long) {
        chargeDao.updateDurationProgress(sessionId, accumulatedMs, lastTickAt)
    }

    suspend fun updateTargetDurationMinutes(sessionId: String, targetDurationMinutes: Int) {
        val previous = chargeDao.getSessionById(sessionId)
        chargeDao.updateTargetDurationMinutes(sessionId, targetDurationMinutes)
        syncDeadManAfterTargetChange(previous, sessionId)
    }

    suspend fun updateTargetKwh(sessionId: String, targetKwh: Double) {
        val previous = chargeDao.getSessionById(sessionId)
        chargeDao.updateTargetKwh(sessionId, targetKwh)
        syncDeadManAfterTargetChange(previous, sessionId)
    }

    private suspend fun syncDeadManAfterTargetChange(previous: ChargeSessionEntity?, sessionId: String) {
        val updated = chargeDao.getSessionById(sessionId) ?: return
        if (updated.targetKwh > 0.0 || updated.targetDurationMinutes > 0) {
            val voltage = _telemetryState.value?.voltageCurrent ?: 230
            armDeadManTimer(updated, voltage, updated.totalKwhDelivered)
        } else if (previous != null && (previous.targetKwh > 0.0 || previous.targetDurationMinutes > 0)) {
            restoreScheduleTimer(forceRestore = true)
        }
    }

    suspend fun updateSessionAmperage(sessionId: String, amperes: Int) {
        chargeDao.updateSessionAmperage(sessionId, amperes)
    }

    /**
     * Sends stop command and confirms via fresh telemetry that charging stopped.
     * Waits and polls telemetry once per attempt here rather than double-verifying inside the scraper.
     */
    suspend fun stopAndConfirm(
        maxAttempts: Int = 3,
        allowOfflineConfirmation: Boolean = false,
        confirmDelayMs: Long = 4_000L,
        retryDelayMs: Long = 2_000L
    ): Boolean {
        var previousWsLeftCharging = false
        repeat(maxAttempts) { attempt ->
            scraper.stopCharge(
                verifyWithTelemetry = false,
                useHttpFallbackOnly = attempt > 0 && previousWsLeftCharging
            )
            if (confirmDelayMs > 0L) delay(confirmDelayMs)
            val t = scraper.fetchTelemetry().getOrNull()
            if (t != null) {
                _telemetryState.value = t
                if (!t.isChargingActive && (t.isOnline || allowOfflineConfirmation)) {
                    return true
                }
                previousWsLeftCharging = t.isChargingActive
            } else {
                previousWsLeftCharging = false
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
        val sessionBefore = chargeDao.getSessionById(sessionId)
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
        restoreScheduleTimer(session = sessionBefore)
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
            val lastCounter = _telemetryState.value?.consumedEnergyKwh
                ?: fetchFreshTelemetryForAnchor()?.consumedEnergyKwh
                ?: 0.0
            finalizeSessionStopped(
                sessionId = sessionId,
                lastCounter = lastCounter,
                sliceReason = SliceEndReason.MANUAL_STOP,
                finalStatus = SessionStatus.CANCELLED
            )
        }
        return Result.success(Unit)
    }

    suspend fun cancelStaleSessionsIfOffline(isMonitorServiceRunning: Boolean = false) {
        if (isMonitorServiceRunning) return
        val telemetry = _telemetryState.value ?: return
        if (!telemetry.isOnline) {
            reconcileInactiveSessionOutsideMonitor(telemetry)
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

    suspend fun restoreChargingFromExistingSlice(sessionId: String, currentCounter: Double) {
        chargeDao.restoreActiveSliceForCharging(
            sessionId = sessionId,
            currentCounterKwh = currentCounter
        )
    }

    suspend fun resumeAfterOutage(sessionId: String, isManual: Boolean = false): Result<Unit> {
        if (!isManual && !isAutoResumeEnabled()) {
            return Result.failure(IllegalStateException("Auto-resume disabled"))
        }

        val session = chargeDao.getSessionById(sessionId) ?: return Result.failure(Exception("Session not found"))
        val slices = chargeDao.getSlicesForSession(sessionId)
        val sumDelivered = slices.sumOf { it.finalizedKwh }

        if (session.targetKwh > 0.0) {
            val remainingKwh = EnergyAccumulator.calculateRemainingKwh(session.targetKwh, sumDelivered)
            if (remainingKwh <= 0.1) {
                chargeDao.updateSession(session.copy(status = SessionStatus.TARGET_REACHED, completedAt = System.currentTimeMillis(), lastTickAt = 0L))
                restoreScheduleTimer(session = session)
                return Result.success(Unit)
            }
        }

        if (session.targetDurationMinutes > 0) {
            val targetDurationMs = session.targetDurationMinutes * 60_000L
            if (session.accumulatedChargingMs >= targetDurationMs) {
                chargeDao.updateSession(session.copy(status = SessionStatus.TARGET_REACHED, completedAt = System.currentTimeMillis(), lastTickAt = 0L))
                restoreScheduleTimer(session = session)
                return Result.success(Unit)
            }
        }

        val startResult = scraper.startCharge(session.amperageSetting)
        if (startResult.isSuccess) {
            val anchorTelemetry = fetchFreshTelemetryForAnchor()
                ?: return Result.failure(IOException("No telemetry to anchor slice"))
            chargeDao.startNewSlice(sessionId, anchorTelemetry.consumedEnergyKwh)
            val resumed = session.copy(
                status = SessionStatus.CHARGING,
                lastTickAt = if (anchorTelemetry.isChargingActive) clockMs() else 0L
            )
            chargeDao.updateSession(resumed)
            armDeadManTimer(resumed, anchorTelemetry.voltageCurrent ?: 230, sumDelivered)
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
        restoreScheduleTimer(session = session)
    }

    companion object {
        private const val STARTUP_GRACE_MS = 30_000L
        private const val STALE_OFFLINE_CHARGING_MS = 2 * 3_600_000L
        private const val STALE_OUTAGE_MS = 24 * 3_600_000L
    }
}

