package ua.org.electros.app.data.repository

import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
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
import ua.org.electros.app.diag.DiagLog
import ua.org.electros.app.diag.kwh
import ua.org.electros.app.diag.reason
import ua.org.electros.app.diag.toLogString
import java.io.IOException
import java.time.LocalTime
import java.util.UUID

/** What [ChargeRepository.syncScheduleToStation] did with the user's schedule. */
enum class ScheduleSyncResult { WRITTEN, IN_SYNC, OFFLINE, AFTER_SESSION }

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

    /** Last station timer window the station accepted; null until one is sent in this process. */
    @Volatile
    private var lastTimerSent: Pair<String, String>? = null
    private val scheduleSyncMutex = Mutex()

    private suspend fun sendTimer(start: String, stop: String): Result<Unit> {
        val res = runCatching { scraper.setTimer(start, stop) }.getOrElse { Result.failure(it) }
        lastTimerSent = if (res.isSuccess) start to stop else null
        return res
    }

    fun observeActiveSession(): Flow<ChargeSessionEntity?> = chargeDao.observeActiveSession()

    fun observeSlicesForSession(sessionId: String): Flow<List<ChargeSliceEntity>> =
        chargeDao.observeSlicesForSession(sessionId)

    fun getAllSessions(): Flow<List<ChargeSessionEntity>> = chargeDao.getAllSessions()

    fun observeAllSlices(): Flow<List<ChargeSliceEntity>> = chargeDao.observeAllSlices()

    suspend fun getSlicesForSession(sessionId: String): List<ChargeSliceEntity> {
        return chargeDao.getSlicesForSession(sessionId)
    }

    suspend fun login(serial: String, password: String): Result<Unit> {
        val previousSerial = authPreferences?.deviceSerial.orEmpty()
        val previousPassword = authPreferences?.internetPassword.orEmpty()
        // Any change of credentials drops the old session first. With the old cookie still alive the
        // station treats the login form as already signed in and "accepts" any password, so a changed
        // password was never really checked.
        if (previousSerial != serial.trim() || previousPassword != password.trim()) {
            scraper.clearSession()
        }
        DiagLog.setSecrets(serial, password, previousSerial)
        val result = scraper.login(serial, password)
        DiagLog.i(TAG, "Login: " + if (result.isSuccess) "ok" else "failed, ${result.exceptionOrNull().reason()}")
        if (result.isSuccess) {
            authPreferences?.deviceSerial = serial
            authPreferences?.internetPassword = password
            fetchLatestTelemetry()
        }
        return result
    }

    /**
     * Signs out. A running session is stopped on the station first (which also restores its schedule);
     * if the stop cannot be confirmed the app keeps its credentials, because without them the station
     * would keep charging with no limit and no dead-man timer.
     */
    suspend fun logout(): Result<Unit> {
        val active = chargeDao.getActiveSession()
        DiagLog.i(TAG, "Logout requested, active session: ${active?.id?.take(8) ?: "none"}")
        if (active != null) {
            val stopped = stopActiveSession(active.id)
            if (stopped.isFailure) {
                DiagLog.w(TAG, "Logout aborted: the running session could not be stopped")
                return stopped
            }
        }
        authPreferences?.clearCredentials()
        scraper.clearSession()
        _telemetryState.value = null
        chargeDao.cancelActiveSessionsPreservingEnergy(clockMs())
        return Result.success(Unit)
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
            if (data.isOnline && !data.isChargingActive) rememberIdleCounter(data.consumedEnergyKwh)
            _telemetryState.value = data
        }
        return result
    }

    private suspend fun rememberIdleCounter(counterKwh: Double) {
        val store = settingsStore ?: return
        if (kotlin.math.abs(store.awaitSettings().lastIdleCounterKwh - counterKwh) > 0.0005) {
            store.setLastIdleCounterKwh(counterKwh)
        }
    }

    /**
     * Starts tracking a charge the station is running without an app session, for example one the
     * station started by itself when power came back. The session has no limits; it counts from the
     * station counter last seen while idle, or from 0 when the counter has been reset since (the
     * station counter restarts at 0 on power-up).
     */
    suspend fun adoptUntrackedCharge(telemetry: TelemetryData): String? {
        if (!telemetry.isOnline || !telemetry.isChargingActive) return null
        if (chargeDao.getActiveSession() != null) return null
        val lastIdle = settingsStore?.awaitSettings()?.lastIdleCounterKwh ?: -1.0
        val anchor = if (lastIdle >= 0.0 && lastIdle <= telemetry.consumedEnergyKwh) lastIdle else 0.0
        val now = clockMs()
        val session = ChargeSessionEntity(
            id = UUID.randomUUID().toString(),
            targetKwh = 0.0,
            amperageSetting = telemetry.selectedCurrentAmperes,
            status = SessionStatus.CHARGING,
            startedAt = now,
            targetDurationMinutes = 0,
            accumulatedChargingMs = 0L,
            lastTickAt = now
        )
        chargeDao.insertSession(session)
        chargeDao.startNewSlice(session.id, anchor, now)
        DiagLog.i(
            TAG,
            "Charge started outside the app, tracking it as ${session.id.take(8)} from counter ${anchor.kwh()}: " +
                telemetry.toLogString()
        )
        return session.id
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
        DiagLog.i(
            TAG,
            "Session ${sessionId.take(8)} closed while the monitor was not running " +
                "(counter ${finalCounter.kwh()}, keptAsFinished=$preserveAsVehicleStopped)"
        )
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
        ) ?: run {
            DiagLog.d(TAG) { "Dead-man timer not needed for session ${session.id.take(8)}" }
            return
        }
        val res = sendTimer(window.first, window.second)
        DiagLog.i(
            TAG,
            "Dead-man timer ${window.first}-${window.second} (${voltageVolts}V, ${session.amperageSetting}A, " +
                "delivered ${deliveredKwh.kwh()}): " + if (res.isSuccess) "sent" else "failed, ${res.exceptionOrNull().reason()}"
        )
    }

    suspend fun refreshDeadManTimer(sessionId: String, voltageVolts: Int, deliveredKwh: Double) {
        val session = chargeDao.getSessionById(sessionId) ?: return
        armDeadManTimer(session, voltageVolts, deliveredKwh)
    }

    private suspend fun restoreScheduleTimer(forceRestore: Boolean = false, session: ChargeSessionEntity? = null) {
        val (schedEnabled, schedStart, schedStop) = readScheduleConfig()
        val wasLimited = session != null && (session.targetKwh > 0.0 || session.targetDurationMinutes > 0)
        // A stop clears the station timer first, so the user's own schedule is put back after every stop.
        // A schedule changed during the session is written now too.
        val isPending = settingsStore?.awaitSettings()?.isSchedulePending == true
        val shouldRestore = forceRestore || wasLimited || schedEnabled || isPending
        if (!shouldRestore) return
        val targetStart = if (schedEnabled) schedStart else TIMER_OFF
        val targetStop = if (schedEnabled) schedStop else TIMER_OFF
        // Each timer command can make the station pick charging up again, so never repeat one it already has.
        if (lastTimerSent == targetStart to targetStop) {
            DiagLog.d(TAG) { "Station schedule already $targetStart-$targetStop" }
            settingsStore?.setScheduleOnStation("$targetStart-$targetStop")
            return
        }
        val res = sendTimer(targetStart, targetStop)
        if (res.isSuccess) settingsStore?.setScheduleOnStation("$targetStart-$targetStop")
        DiagLog.i(
            TAG,
            "Station schedule restored to $targetStart-$targetStop: " +
                if (res.isSuccess) "sent" else "failed, ${res.exceptionOrNull().reason()}"
        )
    }

    suspend fun startNewSession(
        targetKwh: Double,
        amperage: Int,
        targetDurationMinutes: Int = 0
    ): Result<String> {
        DiagLog.i(TAG, "Start requested: target=${targetKwh}kWh/${targetDurationMinutes}min amps=$amperage")
        val preStartTelemetry = fetchFreshTelemetryForAnchor()
        DiagLog.i(TAG, "Before start: ${preStartTelemetry?.toLogString() ?: "no telemetry"}")
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
        chargeDao.cancelActiveSessionsPreservingEnergy(clockMs())

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
        DiagLog.i(TAG, "Start command for ${sessionId.take(8)}: " + if (startResult.isSuccess) "confirmed" else "failed, ${startResult.exceptionOrNull().reason()}")
        if (startResult.isSuccess) {
            val anchorTelemetry = fetchFreshTelemetryForAnchor()
            DiagLog.i(TAG, "After start: ${anchorTelemetry?.toLogString() ?: "no telemetry"}")
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
        DiagLog.i(TAG, "Attaching to a charge already running: target=${targetKwh}kWh/${targetDurationMinutes}min, ${anchorTelemetry.toLogString()}")
        chargeDao.cancelActiveSessionsPreservingEnergy(clockMs())
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

    suspend fun updateActiveSliceCounter(sessionId: String, counterKwh: Double) {
        chargeDao.updateActiveSliceCounter(sessionId, counterKwh)
    }

    /**
     * Sends stop command and confirms via fresh telemetry that charging stopped.
     * Waits and polls telemetry once per attempt here rather than double-verifying inside the scraper.
     */
    suspend fun stopAndConfirm(
        maxAttempts: Int = 3,
        allowOfflineConfirmation: Boolean = false,
        confirmDelayMs: Long = 4_000L,
        retryDelayMs: Long = 2_000L,
        holdCheckDelayMs: Long = STOP_HOLD_CHECK_MS
    ): Boolean {
        // While the dead-man window is armed the station treats a stop as a pause and starts charging
        // again a few seconds later (seen in a field log). Turn the window off before stopping.
        clearTimerBeforeStop(settleMs = if (confirmDelayMs > 0L) TIMER_CLEAR_SETTLE_MS else 0L)
        var previousWsLeftCharging = false
        repeat(maxAttempts) { attempt ->
            val httpOnly = attempt > 0 && previousWsLeftCharging
            val sent = scraper.stopCharge(
                verifyWithTelemetry = false,
                useHttpFallbackOnly = httpOnly
            )
            if (confirmDelayMs > 0L) delay(confirmDelayMs)
            val t = scraper.fetchTelemetry().getOrNull()
            DiagLog.i(
                TAG,
                "Stop attempt ${attempt + 1}/$maxAttempts (${if (httpOnly) "HTTP" else "WS"}): " +
                    "send ${if (sent.isSuccess) "ok" else "failed, ${sent.exceptionOrNull().reason()}"}; " +
                    "then ${t?.toLogString() ?: "no telemetry"}"
            )
            if (t != null) {
                _telemetryState.value = t
                if (!t.isChargingActive && (t.isOnline || allowOfflineConfirmation)) {
                    if (!t.isOnline || holdsStopped(holdCheckDelayMs)) {
                        restoreScheduleTimer()
                        return true
                    }
                    DiagLog.w(TAG, "Station started charging again a few seconds after the stop")
                    // The stop itself worked, so the next attempt uses the same channel again.
                    previousWsLeftCharging = false
                    if (attempt < maxAttempts - 1 && retryDelayMs > 0L) delay(retryDelayMs)
                    return@repeat
                }
                previousWsLeftCharging = t.isChargingActive
            } else {
                previousWsLeftCharging = false
            }
            if (attempt < maxAttempts - 1 && retryDelayMs > 0L) delay(retryDelayMs)
        }
        DiagLog.w(TAG, "Station did not confirm the stop after $maxAttempts attempts")
        return false
    }

    /**
     * Puts the idle station into "Заборона заряджання" (status 4), like the developer's web page,
     * where "Зупинити" pressed while nothing charges blocks charging until "Почати". The stop
     * command is sent and the result is confirmed from fresh telemetry.
     */
    suspend fun forbidCharging(confirmDelayMs: Long = 3_000L, maxReads: Int = 3): Result<Unit> {
        val sent = scraper.stopCharge(verifyWithTelemetry = false)
        DiagLog.i(TAG, "Charging ban: stop " + if (sent.isSuccess) "sent" else "failed, ${sent.exceptionOrNull().reason()}")
        if (sent.isFailure) return Result.failure(sent.exceptionOrNull() ?: IOException("Stop command failed"))
        repeat(maxReads) {
            if (confirmDelayMs > 0L) delay(confirmDelayMs)
            val t = scraper.fetchTelemetry().getOrNull() ?: return@repeat
            _telemetryState.value = t
            DiagLog.i(TAG, "Charging ban check: ${t.toLogString()}")
            if (t.isChargingForbidden) return Result.success(Unit)
        }
        return Result.failure(IllegalStateException("Станція не перейшла в «Заборона заряджання»"))
    }

    private suspend fun clearTimerBeforeStop(settleMs: Long) {
        if (lastTimerSent == TIMER_OFF to TIMER_OFF) return
        val res = sendTimer(TIMER_OFF, TIMER_OFF)
        DiagLog.i(TAG, "Station timer cleared before the stop: " + if (res.isSuccess) "sent" else "failed, ${res.exceptionOrNull().reason()}")
        if (res.isSuccess && settleMs > 0L) delay(settleMs)
    }

    /** Looks again after [delayMs]: true when the station is still not charging. */
    private suspend fun holdsStopped(delayMs: Long): Boolean {
        if (delayMs > 0L) delay(delayMs)
        val again = scraper.fetchTelemetry().getOrNull() ?: return true
        _telemetryState.value = again
        DiagLog.i(TAG, "Stop check after ${delayMs / 1000} s: ${again.toLogString()}")
        return !again.isChargingActive
    }

    suspend fun finalizeSessionStopped(
        sessionId: String,
        lastCounter: Double,
        sliceReason: SliceEndReason,
        finalStatus: SessionStatus
    ) {
        val sessionBefore = chargeDao.getSessionById(sessionId)
        DiagLog.i(TAG, "Session ${sessionId.take(8)} finished: $finalStatus (slice $sliceReason, counter ${lastCounter.kwh()})")
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
            val latestSlice = chargeDao.getLatestSliceForSession(sessionId)
            val onlineTelemetry = _telemetryState.value?.takeIf { it.isOnline }?.consumedEnergyKwh
                ?: fetchFreshTelemetryForAnchor()?.takeIf { it.isOnline }?.consumedEnergyKwh
            val lastCounter = onlineTelemetry
                ?: latestSlice?.stationCounterLastKwh
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
        return scraper.setAmperage(amperes).also {
            DiagLog.i(TAG, "Set current ${amperes}A: " + if (it.isSuccess) "confirmed" else "failed, ${it.exceptionOrNull().reason()}")
        }
    }

    /**
     * Writes the user's schedule to the station's own timer if it is not there yet.
     * Nothing is sent while a session runs (its timer is put back when the session ends) or while
     * the station is offline (the caller retries when it comes back online).
     */
    suspend fun syncScheduleToStation(): Result<ScheduleSyncResult> = scheduleSyncMutex.withLock {
        val store = settingsStore ?: return Result.failure(IllegalStateException("No settings"))
        val settings = store.awaitSettings()
        if (!settings.isSchedulePending) return Result.success(ScheduleSyncResult.IN_SYNC)
        if (chargeDao.getActiveSession() != null) {
            DiagLog.i(TAG, "Station schedule ${settings.desiredStationSchedule}: waits for the session to end")
            return Result.success(ScheduleSyncResult.AFTER_SESSION)
        }
        val telemetry = scraper.fetchTelemetry().getOrElse { return Result.failure(it) }
        _telemetryState.value = telemetry
        if (!telemetry.isOnline) {
            DiagLog.i(TAG, "Station schedule ${settings.desiredStationSchedule}: station offline, will retry")
            return Result.success(ScheduleSyncResult.OFFLINE)
        }
        val target = settings.desiredStationSchedule
        val (start, stop) = target.split("-").let { it[0] to it[1] }
        val res = sendTimer(start, stop)
        DiagLog.i(TAG, "Station schedule $target: " + if (res.isSuccess) "sent" else "failed, ${res.exceptionOrNull().reason()}")
        res.map {
            store.setScheduleOnStation(target)
            ScheduleSyncResult.WRITTEN
        }
    }

    suspend fun handleOutageDetected(sessionId: String, lastCounter: Double) {
        DiagLog.i(TAG, "Outage: slice of ${sessionId.take(8)} closed at counter ${lastCounter.kwh()}")
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
                DiagLog.i(TAG, "Resume skipped: energy limit already reached (${sumDelivered.kwh()} of ${session.targetKwh})")
                chargeDao.updateSession(session.copy(status = SessionStatus.TARGET_REACHED, completedAt = System.currentTimeMillis(), lastTickAt = 0L))
                restoreScheduleTimer(session = session)
                return Result.success(Unit)
            }
        }

        if (session.targetDurationMinutes > 0) {
            val targetDurationMs = session.targetDurationMinutes * 60_000L
            if (session.accumulatedChargingMs >= targetDurationMs) {
                DiagLog.i(TAG, "Resume skipped: charge timer already reached")
                chargeDao.updateSession(session.copy(status = SessionStatus.TARGET_REACHED, completedAt = System.currentTimeMillis(), lastTickAt = 0L))
                restoreScheduleTimer(session = session)
                return Result.success(Unit)
            }
        }

        val startResult = scraper.startCharge(session.amperageSetting)
        DiagLog.i(
            TAG,
            "Resume start command (${session.amperageSetting}A, manual=$isManual): " +
                if (startResult.isSuccess) "confirmed" else "failed, ${startResult.exceptionOrNull().reason()}"
        )
        if (startResult.isSuccess) {
            val anchorTelemetry = fetchFreshTelemetryForAnchor()
                ?: return Result.failure(IOException("No telemetry to anchor slice"))
            DiagLog.i(TAG, "New slice after resume at counter ${anchorTelemetry.consumedEnergyKwh.kwh()}")
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
        DiagLog.i(TAG, "Session ${sessionId.take(8)} finished: car stopped charging (counter ${lastCounter.kwh()})")
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
        private const val TAG = "Repository"
        private const val TIMER_OFF = "00:00"
        private const val TIMER_CLEAR_SETTLE_MS = 2_000L
        private const val STOP_HOLD_CHECK_MS = 8_000L
        private const val STARTUP_GRACE_MS = 30_000L
        private const val STALE_OFFLINE_CHARGING_MS = 2 * 3_600_000L
        private const val STALE_OUTAGE_MS = 24 * 3_600_000L
    }
}

