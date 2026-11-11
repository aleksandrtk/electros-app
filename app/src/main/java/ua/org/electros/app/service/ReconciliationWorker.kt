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
        val activeSession = database.chargeDao().getActiveSession()

        if (activeSession != null) {
            // Re-launch foreground monitor service for the unfinished session
            ChargeMonitorService.start(
                applicationContext,
                activeSession.id,
                activeSession.targetKwh,
                activeSession.targetDurationMinutes
            )
        }
        return Result.success()
    }
}

