package ua.org.electros.app.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import ua.org.electros.app.diag.DiagLog
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

        DiagLog.i("Boot", "Received $action")
        val pendingResult = goAsync()
        val appContext = context.applicationContext
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            try {
                val dao = AppDatabase.getInstance(appContext).chargeDao()
                val activeSession = dao.getActiveSession()
                DiagLog.i("Boot", "Active session: ${activeSession?.let { "${it.id.take(8)} ${it.status}" } ?: "none"}")
                if (activeSession != null) {
                    if (shouldResumeOnBoot(activeSession)) {
                        // The system may still refuse the start; the service then shows the
                        // "manual restart required" alert itself.
                        ChargeMonitorService.start(
                            appContext,
                            activeSession.id,
                            activeSession.targetKwh,
                            activeSession.targetDurationMinutes
                        )
                    } else {
                        DiagLog.i("Boot", "Session had not started charging, cancelled")
                        dao.cancelActiveSessionsPreservingEnergy()
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
