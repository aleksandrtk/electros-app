package ua.org.electros.app.service

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import ua.org.electros.app.data.local.AppDatabase

class ReconciliationWorker(
    appContext: Context,
    workerParams: WorkerParameters
) : CoroutineWorker(appContext, workerParams) {

    override suspend fun doWork(): Result {
        val database = AppDatabase.getInstance(applicationContext)
        val dao = database.chargeDao()
        val activeSession = dao.getActiveSession()

        if (activeSession != null) {
            if (BootReceiver.shouldResumeOnBoot(activeSession)) {
                ChargeMonitorService.start(
                    applicationContext,
                    activeSession.id,
                    activeSession.targetKwh,
                    activeSession.targetDurationMinutes
                )
            } else {
                dao.cancelAllActiveSessions()
            }
        }
        return Result.success()
    }
}

