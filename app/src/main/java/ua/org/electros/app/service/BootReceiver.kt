package ua.org.electros.app.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.PowerManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import ua.org.electros.app.data.local.AppDatabase
import ua.org.electros.app.data.local.entity.ChargeSessionEntity
import ua.org.electros.app.data.local.entity.SessionStatus

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action
        if (!shouldHandleAction(action)) return

        val pendingResult = goAsync()
        val appContext = context.applicationContext
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            try {
                val dao = AppDatabase.getInstance(appContext).chargeDao()
                val activeSession = dao.getActiveSession()
                if (activeSession != null) {
                    if (shouldResumeOnBoot(activeSession)) {
                        val pm = appContext.getSystemService(PowerManager::class.java)
                        val batteryExempt = pm?.isIgnoringBatteryOptimizations(appContext.packageName) == true
                        if (canStartSpecialUseFgsFromReceiver(action, batteryExempt)) {
                            ChargeMonitorService.start(
                                appContext,
                                activeSession.id,
                                activeSession.targetKwh,
                                activeSession.targetDurationMinutes
                            )
                        } else {
                            NotificationHelper(appContext).showMonitorRestartRequiredAlert()
                        }
                    } else {
                        dao.cancelAllActiveSessions()
                    }
                }
            } finally {
                pendingResult.finish()
            }
        }
    }

    companion object {
        internal fun shouldHandleAction(action: String?): Boolean =
            action == Intent.ACTION_BOOT_COMPLETED || action == Intent.ACTION_MY_PACKAGE_REPLACED

        internal fun canStartSpecialUseFgsFromReceiver(
            action: String?,
            isIgnoringBatteryOptimizations: Boolean
        ): Boolean = when (action) {
            Intent.ACTION_BOOT_COMPLETED -> isIgnoringBatteryOptimizations
            Intent.ACTION_MY_PACKAGE_REPLACED -> true
            else -> false
        }

        internal fun shouldResumeOnBoot(session: ChargeSessionEntity): Boolean = when (session.status) {
            SessionStatus.STOP_PENDING -> true
            SessionStatus.CHARGING ->
                session.totalKwhDelivered > 0.0 ||
                    session.accumulatedChargingMs > 0L ||
                    session.lastTickAt > 0L
            SessionStatus.OUTAGE_DETECTED,
            SessionStatus.OUTAGE_STABILIZING,
            SessionStatus.AUTO_RESUMING ->
                session.totalKwhDelivered > 0.0 ||
                    session.accumulatedChargingMs > 0L
            else -> false
        }
    }
}
