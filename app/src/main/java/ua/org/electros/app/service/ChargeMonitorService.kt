package ua.org.electros.app.service

import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.IBinder
import android.os.PowerManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import ua.org.electros.app.ElectrosApplication
import ua.org.electros.app.data.local.AuthPreferences
import ua.org.electros.app.data.local.entity.SessionStatus
import ua.org.electros.app.data.local.entity.SliceEndReason
import ua.org.electros.app.data.repository.ChargeRepository
import ua.org.electros.app.domain.accumulator.EnergyAccumulator
import ua.org.electros.app.domain.balancing.VoltageLoadBalancer
import ua.org.electros.app.domain.statemachine.ChargingStateMachine
import ua.org.electros.app.domain.statemachine.StateEvent

class ChargeMonitorService : Service() {

    private val serviceJob = Job()
    private val scope = CoroutineScope(Dispatchers.Default + serviceJob)

    private lateinit var repository: ChargeRepository
    private lateinit var authPreferences: AuthPreferences
    private lateinit var notificationHelper: NotificationHelper
    private lateinit var powerManager: PowerManager
    private var wakeLock: PowerManager.WakeLock? = null

    private var stateMachine = ChargingStateMachine(SessionStatus.CHARGING)
    private val voltageLoadBalancer = VoltageLoadBalancer()
    private var monitoringJob: Job? = null
    private var activeSessionId: String? = null
    private var activeTargetKwh: Double = 0.0
    private var activeTargetDurationMinutes: Int = 0
    private var accumulatedChargingMs: Long = 0L
    private var lastActiveChargingSampleAt: Long = 0L
    private var lastSampledCounterKwh: Double = 0.0
    private var lastKnownAmperes: Int = 10
    private var zeroCurrentConsecutiveTicks = 0
    private var consecutiveFailureTicks = 0
    private var hasAlertedConnectionLost = false
    private var pendingStopIsManual = false
    private var hasAlertedStopFailed = false

    override fun onCreate() {
        super.onCreate()
        isRunning = true
        val app = application as ElectrosApplication
        repository = app.repository
        authPreferences = app.authPreferences
        notificationHelper = NotificationHelper(this)
        powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager

        syncLoadBalancerPrefs()

        wakeLock = powerManager.newWakeLock(
            PowerManager.PARTIAL_WAKE_LOCK,
            "ElectrosApp:ChargeMonitorServiceWakeLock"
        ).apply {
            setReferenceCounted(false)
        }
    }

    private fun syncLoadBalancerPrefs() {
        voltageLoadBalancer.isEnabled = authPreferences.isVoltageBalancingEnabled
        voltageLoadBalancer.lowThresholdV = authPreferences.voltageLowThreshold
        voltageLoadBalancer.highThresholdV = authPreferences.voltageHighThreshold
        voltageLoadBalancer.minAmperage = authPreferences.voltageMinAmperage
        voltageLoadBalancer.maxAmperage = authPreferences.voltageMaxAmperage
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        isRunning = true
        if (intent?.action == NotificationHelper.ACTION_STOP_CHARGE) {
            handleManualStop()
            return START_NOT_STICKY
        }

        val incomingSessionId = intent?.getStringExtra(EXTRA_SESSION_ID)
        val isNewSession = incomingSessionId != null && incomingSessionId != activeSessionId
        activeSessionId = incomingSessionId
        activeTargetKwh = intent?.getDoubleExtra(EXTRA_TARGET_KWH, 0.0) ?: 0.0
        activeTargetDurationMinutes = intent?.getIntExtra(EXTRA_TARGET_DURATION_MIN, 0) ?: 0

        if (isNewSession) {
            accumulatedChargingMs = 0L
            lastActiveChargingSampleAt = 0L
            lastSampledCounterKwh = 0.0
            zeroCurrentConsecutiveTicks = 0
            consecutiveFailureTicks = 0
            hasAlertedConnectionLost = false
            pendingStopIsManual = false
            hasAlertedStopFailed = false
            stateMachine = ChargingStateMachine(SessionStatus.CHARGING)
        }

        val initialNotification = notificationHelper.buildForegroundNotification(
            powerKw = 0.0,
            deliveredKwh = 0.0,
            targetKwh = activeTargetKwh,
            statusText = "Ініціалізація...",
            targetDurationMinutes = activeTargetDurationMinutes,
            accumulatedChargingMs = accumulatedChargingMs
        )
        startForeground(NotificationHelper.NOTIFICATION_ID_STATUS, initialNotification)

        startMonitoringLoop()

        return START_REDELIVER_INTENT
    }

    private fun startMonitoringLoop() {
        monitoringJob?.cancel()
        monitoringJob = scope.launch {
            // Hydrate state from Room DB so reboots/service restarts preserve duration and outage status
            activeSessionId?.let { id ->
                repository.getSessionById(id)?.let { dbSession ->
                    activeTargetKwh = dbSession.targetKwh
                    activeTargetDurationMinutes = dbSession.targetDurationMinutes
                    accumulatedChargingMs = dbSession.accumulatedChargingMs
                    lastKnownAmperes = dbSession.amperageSetting
                    stateMachine = ChargingStateMachine(dbSession.status)
                }
            }

            while (isActive) {
                val preStatus = stateMachine.getState()
                if (preStatus == SessionStatus.TARGET_REACHED ||
                    preStatus == SessionStatus.CANCELLED ||
                    preStatus == SessionStatus.VEHICLE_STOPPED
                ) {
                    stopSelf()
                    break
                }

                pollTelemetryAndEvaluateState()

                val postStatus = stateMachine.getState()
                if (postStatus == SessionStatus.TARGET_REACHED ||
                    postStatus == SessionStatus.CANCELLED ||
                    postStatus == SessionStatus.VEHICLE_STOPPED
                ) {
                    stopSelf()
                    break
                }

                // Adaptive delay: 10 seconds while charging/stabilizing, 60 seconds during outage
                val pollDelayMs = if (postStatus == SessionStatus.OUTAGE_DETECTED) 60_000L else 10_000L
                delay(pollDelayMs)
            }
        }
    }

    private suspend fun pollTelemetryAndEvaluateState() {
        syncLoadBalancerPrefs()

        // Acquire wake lock strictly for the duration of the telemetry network request
        wakeLock?.acquire(15_000L)
        val result = try {
            repository.fetchLatestTelemetry(isMonitorServiceRunning = true)
        } finally {
            if (wakeLock?.isHeld == true) {
                wakeLock?.release()
            }
        }

        val sessionId = activeSessionId ?: return
        val dbSession = repository.getSessionById(sessionId)
        if (dbSession == null || dbSession.status in listOf(
                SessionStatus.TARGET_REACHED,
                SessionStatus.CANCELLED,
                SessionStatus.VEHICLE_STOPPED
            )
        ) {
            stateMachine.transition(StateEvent.CancelRequested)
            return
        }

        if (dbSession.status == SessionStatus.STOP_PENDING && stateMachine.getState() != SessionStatus.STOP_PENDING) {
            stateMachine.transition(StateEvent.StopPending)
        }

        // Sync live targets from Room in case user adjusted duration timer mid-session
        activeTargetKwh = dbSession.targetKwh
        activeTargetDurationMinutes = dbSession.targetDurationMinutes

        result.onSuccess { data ->
            consecutiveFailureTicks = 0
            hasAlertedConnectionLost = false
            lastKnownAmperes = data.selectedCurrentAmperes
            val slices = repository.getSlicesForSession(sessionId)
            val latestSlice = slices.lastOrNull()
            val sliceStart = latestSlice?.stationCounterStartKwh ?: data.consumedEnergyKwh

            // Check for counter regression (rapid station reboot)
            if (EnergyAccumulator.isCounterRegression(data.consumedEnergyKwh, lastSampledCounterKwh)) {
                lastActiveChargingSampleAt = 0L
                repository.handleOutageDetected(sessionId, lastSampledCounterKwh)
                val resumeRes = repository.resumeAfterOutage(sessionId)
                if (resumeRes.isSuccess) {
                    stateMachine.transition(StateEvent.ResumeCommandSent)
                    notificationHelper.showOutageResumedAlert(dbSession.amperageSetting)
                }
            }
            lastSampledCounterKwh = data.consumedEnergyKwh

            val totalDelivered = EnergyAccumulator.calculateTotalDeliveredKwh(
                finalizedSlices = slices.filter { it.endedAt != null },
                activeSliceStartKwh = sliceStart,
                currentStationCounterKwh = data.consumedEnergyKwh
            )

            // Evaluate state machine events
            if (!data.isOnline) {
                lastActiveChargingSampleAt = 0L
                repository.updateDurationProgress(sessionId, accumulatedChargingMs, 0L)
                if (stateMachine.getState() == SessionStatus.CHARGING ||
                    stateMachine.getState() == SessionStatus.OUTAGE_STABILIZING ||
                    stateMachine.getState() == SessionStatus.AUTO_RESUMING
                ) {
                    stateMachine.transition(StateEvent.OutageDetected)
                    repository.handleOutageDetected(sessionId, data.consumedEnergyKwh)
                    notificationHelper.showOutageAlert(totalDelivered)
                }
            } else {
                // If station came back online and is already actively charging (e.g. brief phone drop or auto-recovery)
                if (data.isChargingActive && (stateMachine.getState() == SessionStatus.OUTAGE_DETECTED ||
                        stateMachine.getState() == SessionStatus.OUTAGE_STABILIZING ||
                        stateMachine.getState() == SessionStatus.AUTO_RESUMING)
                ) {
                    stateMachine.transition(StateEvent.ResumeCommandSent)
                    repository.restoreChargingFromExistingSlice(sessionId)
                }

                when (stateMachine.getState()) {
                    SessionStatus.OUTAGE_DETECTED -> {
                        val nextState = stateMachine.transition(StateEvent.GridRestored(data.voltageCurrent))
                        if (nextState == SessionStatus.OUTAGE_STABILIZING) {
                            repository.handleOutageStabilizing(sessionId)
                        }
                    }
                    SessionStatus.OUTAGE_STABILIZING -> {
                        if (data.voltageCurrent in 180..250) {
                            val nextState = stateMachine.transition(StateEvent.VoltageStable)
                            if (nextState == SessionStatus.AUTO_RESUMING) {
                                val resumeRes = repository.resumeAfterOutage(sessionId)
                                if (resumeRes.isSuccess) {
                                    stateMachine.transition(StateEvent.ResumeCommandSent)
                                    lastActiveChargingSampleAt = 0L
                                    notificationHelper.showOutageResumedAlert(dbSession.amperageSetting)
                                }
                            }
                        } else {
                            stateMachine.transition(StateEvent.VoltageUnstable)
                            repository.handleOutageDetected(sessionId, data.consumedEnergyKwh)
                        }
                    }
                    SessionStatus.AUTO_RESUMING -> {
                        val resumeRes = repository.resumeAfterOutage(sessionId)
                        if (resumeRes.isSuccess) {
                            stateMachine.transition(StateEvent.ResumeCommandSent)
                            lastActiveChargingSampleAt = 0L
                            notificationHelper.showOutageResumedAlert(dbSession.amperageSetting)
                        }
                    }
                    SessionStatus.CHARGING -> {
                        val now = System.currentTimeMillis()
                        if (data.isChargingActive) {
                            if (lastActiveChargingSampleAt > 0L) {
                                val deltaMs = (now - lastActiveChargingSampleAt).coerceIn(0L, 120_000L)
                                accumulatedChargingMs += deltaMs
                            }
                            lastActiveChargingSampleAt = now
                            repository.updateDurationProgress(sessionId, accumulatedChargingMs, now)
                        } else {
                            if (lastActiveChargingSampleAt > 0L) {
                                val deltaMs = (now - lastActiveChargingSampleAt).coerceIn(0L, 15_000L)
                                accumulatedChargingMs += deltaMs
                                lastActiveChargingSampleAt = 0L
                            }
                            repository.updateDurationProgress(sessionId, accumulatedChargingMs, 0L)
                        }

                        // 1. Check if Duration Timer target reached
                        if (activeTargetDurationMinutes > 0) {
                            val targetDurationMs = activeTargetDurationMinutes * 60_000L
                            if (accumulatedChargingMs >= targetDurationMs) {
                                val finished = attemptTargetStopAndFinish(
                                    sessionId = sessionId,
                                    consumedKwh = data.consumedEnergyKwh,
                                    totalDelivered = totalDelivered,
                                    isDurationTarget = true
                                )
                                if (finished) return
                            }
                        }

                        // 2. Check if kWh quota target reached (strictly when > 0.0)
                        if (stateMachine.getState() == SessionStatus.CHARGING &&
                            activeTargetKwh > 0.0 &&
                            totalDelivered >= activeTargetKwh
                        ) {
                            val finished = attemptTargetStopAndFinish(
                                sessionId = sessionId,
                                consumedKwh = data.consumedEnergyKwh,
                                totalDelivered = totalDelivered,
                                isDurationTarget = false
                            )
                            if (finished) return
                        }

                        // 3. Check if car finished charging (current < 0.5A for 5 min / 30 consecutive ticks)
                        if (stateMachine.getState() == SessionStatus.CHARGING &&
                            data.measuredCurrentAmperes < 0.5 &&
                            data.isChargingActive
                        ) {
                            zeroCurrentConsecutiveTicks++
                            if (zeroCurrentConsecutiveTicks >= 30) {
                                stateMachine.transition(StateEvent.VehicleStoppedCharging)
                                repository.markSessionVehicleStopped(sessionId, data.consumedEnergyKwh)
                                stopSelf()
                                return
                            }
                        } else {
                            zeroCurrentConsecutiveTicks = 0
                        }

                        // 4. Dynamic Load Balancing (DLB) by Grid Voltage
                        if (stateMachine.getState() == SessionStatus.CHARGING &&
                            voltageLoadBalancer.isEnabled &&
                            data.isChargingActive
                        ) {
                            val recommendedA = voltageLoadBalancer.evaluate(
                                currentVoltage = data.voltageCurrent,
                                currentAmperage = data.selectedCurrentAmperes
                            )
                            if (recommendedA != null && recommendedA != data.selectedCurrentAmperes) {
                                val prevA = data.selectedCurrentAmperes
                                val updateRes = repository.setAmperage(recommendedA)
                                if (updateRes.isSuccess) {
                                    lastKnownAmperes = recommendedA
                                    repository.updateSessionAmperage(sessionId, recommendedA)
                                    notificationHelper.showBalancingAlert(prevA, recommendedA, data.voltageCurrent)
                                }
                            }
                        }
                    }
                    SessionStatus.STOP_PENDING -> {
                        val reachedKwh = activeTargetKwh > 0.0 && totalDelivered >= activeTargetKwh
                        val reachedDur = activeTargetDurationMinutes > 0 && accumulatedChargingMs >= activeTargetDurationMinutes * 60_000L
                        val isManualStop = pendingStopIsManual || (!reachedKwh && !reachedDur)

                        val stopped = if (!data.isChargingActive) {
                            true
                        } else {
                            repository.stopAndConfirm(allowOfflineConfirmation = isManualStop)
                        }

                        if (stopped) {
                            val finalKwh = repository.telemetryState.value?.consumedEnergyKwh ?: data.consumedEnergyKwh
                            finalizeStopConfirmed(
                                sessionId = sessionId,
                                finalKwh = finalKwh,
                                totalDelivered = totalDelivered,
                                isManualStop = isManualStop,
                                isDurationTarget = reachedDur
                            )
                            return
                        } else if (!hasAlertedStopFailed) {
                            hasAlertedStopFailed = true
                            notificationHelper.showStopFailedAlert()
                        }
                    }
                    else -> {}
                }
            }

            // Update ongoing notification
            val ongoingStatusText = if (stateMachine.getState() == SessionStatus.STOP_PENDING) {
                "Очікування зупинки..."
            } else {
                data.statusText
            }
            val notification = notificationHelper.buildForegroundNotification(
                powerKw = data.powerKw,
                deliveredKwh = totalDelivered,
                targetKwh = activeTargetKwh,
                statusText = ongoingStatusText,
                targetDurationMinutes = activeTargetDurationMinutes,
                accumulatedChargingMs = accumulatedChargingMs
            )
            val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as android.app.NotificationManager
            notificationManager.notify(NotificationHelper.NOTIFICATION_ID_STATUS, notification)
        }.onFailure {
            consecutiveFailureTicks++
            if (consecutiveFailureTicks >= 3) {
                lastActiveChargingSampleAt = 0L
                repository.updateDurationProgress(sessionId, accumulatedChargingMs, 0L)
                if (!hasAlertedConnectionLost) {
                    hasAlertedConnectionLost = true
                    notificationHelper.showConnectionLostAlert(lastKnownAmperes)
                }
                if (stateMachine.getState() == SessionStatus.CHARGING) {
                    stateMachine.transition(StateEvent.OutageDetected)
                    repository.handleOutageDetected(sessionId, lastSampledCounterKwh)
                }
            }
        }
    }

    private suspend fun attemptTargetStopAndFinish(
        sessionId: String,
        consumedKwh: Double,
        totalDelivered: Double,
        isDurationTarget: Boolean
    ): Boolean {
        pendingStopIsManual = false
        val stopped = repository.markSessionTargetReached(sessionId, consumedKwh)
        return if (stopped) {
            stateMachine.transition(StateEvent.TargetQuotaReached)
            if (isDurationTarget) {
                notificationHelper.showDurationTargetReachedAlert(activeTargetDurationMinutes, totalDelivered)
            } else {
                notificationHelper.showTargetReachedAlert(totalDelivered)
            }
            stopSelf()
            true
        } else {
            stateMachine.transition(StateEvent.StopPending)
            if (!hasAlertedStopFailed) {
                hasAlertedStopFailed = true
                notificationHelper.showStopFailedAlert()
            }
            false
        }
    }

    private suspend fun finalizeStopConfirmed(
        sessionId: String,
        finalKwh: Double,
        totalDelivered: Double,
        isManualStop: Boolean,
        isDurationTarget: Boolean
    ) {
        if (isManualStop) {
            repository.finalizeSessionStopped(
                sessionId = sessionId,
                lastCounter = finalKwh,
                sliceReason = SliceEndReason.MANUAL_STOP,
                finalStatus = SessionStatus.CANCELLED
            )
            stateMachine.transition(StateEvent.CancelRequested)
        } else {
            repository.finalizeSessionStopped(
                sessionId = sessionId,
                lastCounter = finalKwh,
                sliceReason = SliceEndReason.TARGET_REACHED,
                finalStatus = SessionStatus.TARGET_REACHED
            )
            stateMachine.transition(StateEvent.TargetQuotaReached)
            if (isDurationTarget) {
                notificationHelper.showDurationTargetReachedAlert(activeTargetDurationMinutes, totalDelivered)
            } else {
                notificationHelper.showTargetReachedAlert(totalDelivered)
            }
        }
        stopSelf()
    }

    private fun handleManualStop() {
        scope.launch {
            val id = activeSessionId
            if (id != null) {
                val res = repository.stopActiveSession(id)
                if (res.isSuccess) {
                    stateMachine.transition(StateEvent.CancelRequested)
                    stopSelf()
                } else {
                    pendingStopIsManual = true
                    stateMachine.transition(StateEvent.StopPending)
                    if (!hasAlertedStopFailed) {
                        hasAlertedStopFailed = true
                        notificationHelper.showStopFailedAlert()
                    }
                }
            } else {
                stopSelf()
            }
        }
    }

    override fun onDestroy() {
        isRunning = false
        monitoringJob?.cancel()
        serviceJob.cancel()
        if (wakeLock?.isHeld == true) {
            wakeLock?.release()
        }
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        const val EXTRA_SESSION_ID = "EXTRA_SESSION_ID"
        const val EXTRA_TARGET_KWH = "EXTRA_TARGET_KWH"
        const val EXTRA_TARGET_DURATION_MIN = "EXTRA_TARGET_DURATION_MIN"

        @Volatile
        var isRunning: Boolean = false
            private set

        fun start(
            context: Context,
            sessionId: String,
            targetKwh: Double,
            targetDurationMinutes: Int = 0
        ) {
            val intent = Intent(context, ChargeMonitorService::class.java).apply {
                putExtra(EXTRA_SESSION_ID, sessionId)
                putExtra(EXTRA_TARGET_KWH, targetKwh)
                putExtra(EXTRA_TARGET_DURATION_MIN, targetDurationMinutes)
            }
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }
    }
}

