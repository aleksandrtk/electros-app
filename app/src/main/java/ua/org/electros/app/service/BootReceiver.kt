package ua.org.electros.app.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import ua.org.electros.app.data.local.AppDatabase

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (!shouldHandleAction(intent.action)) return

        val pendingResult = goAsync()
        val appContext = context.applicationContext
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            try {
                val activeSession = AppDatabase.getInstance(appContext).chargeDao().getActiveSession()
                if (activeSession != null) {
                    ChargeMonitorService.start(
                        appContext,
                        activeSession.id,
                        activeSession.targetKwh,
                        activeSession.targetDurationMinutes
                    )
                }
            } finally {
                pendingResult.finish()
            }
        }
    }

    companion object {
        internal fun shouldHandleAction(action: String?): Boolean =
            action == Intent.ACTION_BOOT_COMPLETED || action == Intent.ACTION_MY_PACKAGE_REPLACED
    }
}
