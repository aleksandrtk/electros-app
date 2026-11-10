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
import ua.org.electros.app.data.local.entity.SessionStatus
import ua.org.electros.app.data.repository.ChargeRepository
import ua.org.electros.app.domain.accumulator.EnergyAccumulator
import ua.org.electros.app.domain.statemachine.ChargingStateMachine
import ua.org.electros.app.domain.statemachine.StateEvent

class ChargeMonitorService : Service() {

    private val serviceJob = Job()
    private val scope = CoroutineScope(Dispatchers.Default + serviceJob)

    private lateinit var repository: ChargeRepository
    private lateinit var notificationHelper: NotificationHelper
    private lateinit var powerManager: PowerManager
    private var wakeLock: PowerManager.WakeLock? = null

    private val stateMachine = ChargingStateMachine(SessionStatus.CHARGING)
    private var activeSessionId: String? = null
    private var activeTargetKwh: Double = 30.0
    private var lastSampledCounterKwh: Double = 0.0
    private var zeroCurrentConsecutiveTicks = 0

    override fun onCreate() {
        super.onCreate()
        val app = application as ElectrosApplication
        repository = app.repository
        notificationHelper = NotificationHelper(this)
        powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager

        wakeLock = powerManager.newWakeLock(
            PowerManager.PARTIAL_WAKE_LOCK,
            "ElectrosApp:ChargeMonitorServiceWakeLock"
        ).apply {
            setReferenceCounted(false)
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == NotificationHelper.ACTION_STOP_CHARGE) {
            handleManualStop()
            return START_NOT_STICKY
        }

        activeSessionId = intent?.getStringExtra(EXTRA_SESSION_ID)
        activeTargetKwh = intent?.getDoubleExtra(EXTRA_TARGET_KWH, 30.0) ?: 30.0

        val initialNotification = notificationHelper.buildForegroundNotification(
            powerKw = 0.0,
            deliveredKwh = 0.0,
            targetKwh = activeTargetKwh,
            statusText = "Ініціалізація..."
        )
        startForeground(NotificationHelper.NOTIFICATION_ID_STATUS, initialNotification)

        startMonitoringLoop()

        return START_REDELIVER_INTENT
    }

    private fun startMonitoringLoop() {
        scope.launch {
            while (isActive) {
                val currentStatus = stateMachine.getState()
                if (currentStatus == SessionStatus.TARGET_REACHED ||
                    currentStatus == SessionStatus.CANCELLED ||
                    currentStatus == SessionStatus.VEHICLE_STOPPED
                ) {
                    stopSelf()
                    break
                }

                pollTelemetryAndEvaluateState()

                // Adaptive delay: 10 seconds while charging, 60 seconds during outage
                val pollDelayMs = if (currentStatus == SessionStatus.OUTAGE_DETECTED) 60_000L else 10_000L
                delay(pollDelayMs)
            }
        }
    }

    private suspend fun pollTelemetryAndEvaluateState() {
        // Acquire wake lock strictly for the duration of the telemetry network request
        wakeLock?.acquire(15_000L)
        val result = try {
            repository.fetchLatestTelemetry()
        } finally {
            if (wakeLock?.isHeld == true) {
                wakeLock?.release()
            }
        }

        val sessionId = activeSessionId ?: return

        result.onSuccess { data ->
            val slices = repository.getSlicesForSession(sessionId)
            val latestSlice = slices.lastOrNull()
            val sliceStart = latestSlice?.stationCounterStartKwh ?: data.consumedEnergyKwh

            // Check for counter regression (rapid station reboot)
            if (EnergyAccumulator.isCounterRegression(data.consumedEnergyKwh, lastSampledCounterKwh)) {
                repository.handleOutageDetected(sessionId, lastSampledCounterKwh)
                repository.resumeAfterOutage(sessionId)
            }
            lastSampledCounterKwh = data.consumedEnergyKwh

            val totalDelivered = EnergyAccumulator.calculateTotalDeliveredKwh(
                finalizedSlices = slices.filter { it.endedAt != null },
                activeSliceStartKwh = sliceStart,
                currentStationCounterKwh = data.consumedEnergyKwh
            )

            // Evaluate state machine events
            if (!data.isOnline) {
                if (stateMachine.getState() == SessionStatus.CHARGING) {
                    stateMachine.transition(StateEvent.OutageDetected)
                    repository.handleOutageDetected(sessionId, data.consumedEnergyKwh)
                    notificationHelper.showOutageAlert(totalDelivered)
                }
            } else {
                when (stateMachine.getState()) {
                    SessionStatus.OUTAGE_DETECTED -> {
                        stateMachine.transition(StateEvent.GridRestored(data.voltageCurrent))
                        repository.handleOutageStabilizing(sessionId)
                    }
                    SessionStatus.OUTAGE_STABILIZING -> {
                        if (data.voltageCurrent in 198..242) {
                            val nextState = stateMachine.transition(StateEvent.VoltageStable)
                            if (nextState == SessionStatus.AUTO_RESUMING) {
                                repository.resumeAfterOutage(sessionId)
                                stateMachine.transition(StateEvent.ResumeCommandSent)
                            }
                        } else {
                            stateMachine.transition(StateEvent.VoltageUnstable)
                        }
                    }
                    SessionStatus.CHARGING -> {
                        // Check if quota target reached
                        if (totalDelivered >= activeTargetKwh) {
                            stateMachine.transition(StateEvent.TargetQuotaReached)
                            repository.markSessionTargetReached(sessionId, data.consumedEnergyKwh)
                            notificationHelper.showTargetReachedAlert(totalDelivered)
                            stopSelf()
                            return
                        }

                        // Check if car finished charging (current < 0.5A for 5 min / 30 consecutive ticks)
                        if (data.measuredCurrentAmperes < 0.5 && data.isChargingActive) {
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
                    }
                    else -> {}
                }
            }

            // Update ongoing notification
            val notification = notificationHelper.buildForegroundNotification(
                powerKw = data.powerKw,
                deliveredKwh = totalDelivered,
                targetKwh = activeTargetKwh,
                statusText = data.statusText
            )
            val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as android.app.NotificationManager
            notificationManager.notify(NotificationHelper.NOTIFICATION_ID_STATUS, notification)
        }.onFailure {
            if (stateMachine.getState() == SessionStatus.CHARGING) {
                stateMachine.transition(StateEvent.OutageDetected)
                repository.handleOutageDetected(sessionId, lastSampledCounterKwh)
            }
        }
    }

    private fun handleManualStop() {
        scope.launch {
            activeSessionId?.let { id ->
                repository.stopActiveSession(id)
            }
            stopSelf()
        }
    }

    override fun onDestroy() {
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

        fun start(context: Context, sessionId: String, targetKwh: Double) {
            val intent = Intent(context, ChargeMonitorService::class.java).apply {
                putExtra(EXTRA_SESSION_ID, sessionId)
                putExtra(EXTRA_TARGET_KWH, targetKwh)
            }
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }
    }
}

