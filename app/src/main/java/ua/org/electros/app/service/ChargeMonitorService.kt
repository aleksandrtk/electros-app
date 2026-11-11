package ua.org.electros.app.service

import android.app.NotificationManager
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
import ua.org.electros.app.data.local.entity.ChargeSessionEntity
import ua.org.electros.app.data.local.entity.ChargeSliceEntity
import ua.org.electros.app.data.local.entity.SessionStatus
import ua.org.electros.app.data.local.entity.SliceEndReason
import ua.org.electros.app.data.model.TelemetryData
import ua.org.electros.app.data.repository.ChargeRepository
import ua.org.electros.app.domain.balancing.VoltageLoadBalancer
import ua.org.electros.app.domain.monitor.SessionMonitorGateway
import ua.org.electros.app.domain.monitor.SessionSupervisor
import ua.org.electros.app.domain.monitor.SupervisorEvent

class ChargeMonitorService : Service() {

    private val serviceJob = Job()
    private val scope = CoroutineScope(Dispatchers.Default + serviceJob)

    private lateinit var repository: ChargeRepository
    private lateinit var authPreferences: AuthPreferences
    private lateinit var notificationHelper: NotificationHelper
    private lateinit var powerManager: PowerManager
    private lateinit var supervisor: SessionSupervisor
    private var wakeLock: PowerManager.WakeLock? = null

    private val voltageLoadBalancer = VoltageLoadBalancer()
    private var monitoringJob: Job? = null
    private var foregroundStarted = false

    override fun onCreate() {
        super.onCreate()
        isRunning = true
        val app = application as ElectrosApplication
        repository = app.repository
        authPreferences = app.authPreferences
        notificationHelper = NotificationHelper(this)
        powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager

        supervisor = SessionSupervisor(
            gateway = RepositoryGateway(),
            voltageLoadBalancer = voltageLoadBalancer
        )
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

    private fun ensureForegroundStarted() {
        if (foregroundStarted) return
        val initialNotification = notificationHelper.buildForegroundNotification(
            powerKw = 0.0,
            deliveredKwh = 0.0,
            targetKwh = supervisor.activeTargetKwh,
            statusText = "Ініціалізація...",
            targetDurationMinutes = supervisor.activeTargetDurationMinutes,
            accumulatedChargingMs = supervisor.accumulatedChargingMs
        )
        startForeground(NotificationHelper.NOTIFICATION_ID_STATUS, initialNotification)
        foregroundStarted = true
    }

    private fun acquireSessionWakeLock() {
        if (wakeLock?.isHeld != true) {
            wakeLock?.acquire(SESSION_WAKELOCK_TIMEOUT_MS)
        }
    }

    private fun releaseWakeLock() {
        if (wakeLock?.isHeld == true) {
            wakeLock?.release()
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        isRunning = true
        when (intent?.action) {
            NotificationHelper.ACTION_STOP_CHARGE -> {
                ensureForegroundStarted()
                handleManualStop()
                return START_NOT_STICKY
            }
            NotificationHelper.ACTION_RESUME_CHARGE -> {
                ensureForegroundStarted()
                acquireSessionWakeLock()
                handleManualResume()
                return START_REDELIVER_INTENT
            }
        }

        supervisor.onSessionStarted(
            sessionId = intent?.getStringExtra(EXTRA_SESSION_ID),
            targetKwh = intent?.getDoubleExtra(EXTRA_TARGET_KWH, 0.0) ?: 0.0,
            targetDurationMinutes = intent?.getIntExtra(EXTRA_TARGET_DURATION_MIN, 0) ?: 0
        )

        ensureForegroundStarted()
        // Keep PARTIAL_WAKE_LOCK across the whole session so Doze does not stretch poll intervals.
        acquireSessionWakeLock()
        startMonitoringLoop()

        return START_REDELIVER_INTENT
    }

    private fun startMonitoringLoop() {
        monitoringJob?.cancel()
        monitoringJob = scope.launch {
            supervisor.hydrateFromDatabase()

            while (isActive) {
                acquireSessionWakeLock()

                if (supervisor.currentStatus().isTerminal) {
                    stopSelf()
                    break
                }

                syncLoadBalancerPrefs()
                val outcome = supervisor.tick()
                dispatchSupervisorEvents(outcome.events)

                if (outcome.shouldStopService) {
                    stopSelf()
                    break
                }

                // Adaptive delay: 10 seconds while charging/stabilizing, 60 seconds during outage
                val pollDelayMs = if (outcome.status == SessionStatus.OUTAGE_DETECTED) 60_000L else 10_000L
                delay(pollDelayMs)
            }
        }
    }

    private fun dispatchSupervisorEvents(events: List<SupervisorEvent>) {
        for (event in events) {
            when (event) {
                is SupervisorEvent.ShowOutageAlert ->
                    notificationHelper.showOutageAlert(event.deliveredKwh, event.autoResumeEnabled)
                is SupervisorEvent.ShowManualResumeAvailable ->
                    notificationHelper.showManualResumeAvailableAlert(event.amperage, event.deliveredKwh)
                SupervisorEvent.CancelManualResumeAlert ->
                    notificationHelper.cancelManualResumeAlert()
                is SupervisorEvent.ShowOutageResumed ->
                    notificationHelper.showOutageResumedAlert(event.amperage)
                is SupervisorEvent.ShowTargetReached ->
                    notificationHelper.showTargetReachedAlert(event.deliveredKwh)
                is SupervisorEvent.ShowDurationTargetReached ->
                    notificationHelper.showDurationTargetReachedAlert(event.targetDurationMinutes, event.deliveredKwh)
                SupervisorEvent.ShowStopFailed ->
                    notificationHelper.showStopFailedAlert()
                is SupervisorEvent.ShowBalancingAlert ->
                    notificationHelper.showBalancingAlert(event.oldAmperage, event.newAmperage, event.voltage)
                is SupervisorEvent.ShowConnectionLost ->
                    notificationHelper.showConnectionLostAlert(event.lastKnownAmperes)
                is SupervisorEvent.UpdateOngoingNotification -> {
                    val notification = notificationHelper.buildForegroundNotification(
                        powerKw = event.powerKw,
                        deliveredKwh = event.deliveredKwh,
                        targetKwh = event.targetKwh,
                        statusText = event.statusText,
                        targetDurationMinutes = event.targetDurationMinutes,
                        accumulatedChargingMs = event.accumulatedChargingMs
                    )
                    val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
                    notificationManager.notify(NotificationHelper.NOTIFICATION_ID_STATUS, notification)
                }
            }
        }
    }

    private fun handleManualStop() {
        scope.launch {
            val outcome = supervisor.handleManualStop()
            dispatchSupervisorEvents(outcome.events)
            if (outcome.shouldStopService) {
                stopSelf()
            }
        }
    }

    private fun handleManualResume() {
        scope.launch {
            val (events, startLoopNeeded) = supervisor.handleManualResume()
            dispatchSupervisorEvents(events)
            if (supervisor.activeSessionId == null) {
                stopSelf()
            } else if (startLoopNeeded && monitoringJob?.isActive != true) {
                startMonitoringLoop()
            }
        }
    }

    override fun onDestroy() {
        isRunning = false
        foregroundStarted = false
        monitoringJob?.cancel()
        serviceJob.cancel()
        releaseWakeLock()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private inner class RepositoryGateway : SessionMonitorGateway {
        override suspend fun fetchTelemetry(): Result<TelemetryData> =
            repository.fetchLatestTelemetry(isMonitorServiceRunning = true)

        override suspend fun getSessionById(sessionId: String): ChargeSessionEntity? =
            repository.getSessionById(sessionId)

        override suspend fun getActiveSession(): ChargeSessionEntity? =
            repository.getActiveSession()

        override suspend fun getSlicesForSession(sessionId: String): List<ChargeSliceEntity> =
            repository.getSlicesForSession(sessionId)

        override suspend fun updateDurationProgress(sessionId: String, accumulatedMs: Long, lastTickAt: Long) =
            repository.updateDurationProgress(sessionId, accumulatedMs, lastTickAt)

        override suspend fun handleOutageDetected(sessionId: String, lastCounter: Double) =
            repository.handleOutageDetected(sessionId, lastCounter)

        override suspend fun handleOutageStabilizing(sessionId: String) =
            repository.handleOutageStabilizing(sessionId)

        override suspend fun restoreChargingFromExistingSlice(sessionId: String) =
            repository.restoreChargingFromExistingSlice(sessionId)

        override suspend fun resumeAfterOutage(sessionId: String, isManual: Boolean): Result<Unit> =
            repository.resumeAfterOutage(sessionId, isManual = isManual)

        override suspend fun markSessionTargetReached(sessionId: String, finalCounter: Double): Boolean =
            repository.markSessionTargetReached(sessionId, finalCounter)

        override suspend fun markSessionVehicleStopped(sessionId: String, finalCounter: Double) =
            repository.markSessionVehicleStopped(sessionId, finalCounter)

        override suspend fun finalizeSessionStopped(
            sessionId: String,
            lastCounter: Double,
            sliceReason: SliceEndReason,
            finalStatus: SessionStatus
        ) = repository.finalizeSessionStopped(sessionId, lastCounter, sliceReason, finalStatus)

        override suspend fun stopAndConfirm(allowOfflineConfirmation: Boolean): Boolean =
            repository.stopAndConfirm(allowOfflineConfirmation = allowOfflineConfirmation)

        override suspend fun stopActiveSession(sessionId: String): Result<Unit> =
            repository.stopActiveSession(sessionId)

        override suspend fun setAmperage(amperes: Int): Result<Unit> =
            repository.setAmperage(amperes)

        override suspend fun updateSessionAmperage(sessionId: String, amperes: Int) =
            repository.updateSessionAmperage(sessionId, amperes)

        override suspend fun refreshDeadManTimer(
            sessionId: String,
            currentVoltage: Int,
            currentDeliveredKwh: Double
        ) = repository.refreshDeadManTimer(sessionId, currentVoltage, currentDeliveredKwh)

        override fun latestCounterOr(fallback: Double): Double =
            repository.telemetryState.value?.consumedEnergyKwh ?: fallback

        override fun isAutoResumeEnabled(): Boolean =
            authPreferences.autoResumeAfterOutage
    }

    companion object {
        const val EXTRA_SESSION_ID = "EXTRA_SESSION_ID"
        const val EXTRA_TARGET_KWH = "EXTRA_TARGET_KWH"
        const val EXTRA_TARGET_DURATION_MIN = "EXTRA_TARGET_DURATION_MIN"
        private const val SESSION_WAKELOCK_TIMEOUT_MS = 12 * 60 * 60_000L

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
            when {
                android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S -> {
                    startForegroundApi31(context, intent)
                }
                android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O -> {
                    context.startForegroundService(intent)
                }
                else -> {
                    context.startService(intent)
                }
            }
        }

        @androidx.annotation.RequiresApi(android.os.Build.VERSION_CODES.S)
        private fun startForegroundApi31(context: Context, intent: Intent) {
            try {
                context.startForegroundService(intent)
            } catch (_: android.app.ForegroundServiceStartNotAllowedException) {
                NotificationHelper(context.applicationContext).showMonitorRestartRequiredAlert()
            }
        }
    }
}
