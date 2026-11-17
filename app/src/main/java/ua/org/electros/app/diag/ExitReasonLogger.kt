package ua.org.electros.app.diag

import android.app.ActivityManager
import android.app.ApplicationExitInfo
import android.content.Context
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * Android remembers why the app's previous processes ended (crash, ANR, killed for memory or by the
 * battery saver). On each start this copies the reasons not yet in the journal into it, so a session
 * that stopped being watched overnight can be explained.
 */
object ExitReasonLogger {
    private const val TAG = "ProcessExit"
    private const val PREFS_NAME = "diag_log"
    private const val KEY_LAST_EXIT_MS = "last_exit_ms"
    private const val MAX_TRACE_CHARS = 48 * 1024
    private val timeFormat = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")

    fun recordPreviousExits(context: Context) {
        if (!DiagLog.isEnabled) return
        val am = context.getSystemService(ActivityManager::class.java) ?: return
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val lastSeen = prefs.getLong(KEY_LAST_EXIT_MS, 0L)
        val exits = runCatching { am.getHistoricalProcessExitReasons(context.packageName, 0, 16) }
            .getOrNull().orEmpty()
            .filter { it.timestamp > lastSeen }
            .sortedBy { it.timestamp }
        for (info in exits) {
            val text = buildString {
                append("Previous process ended at ").append(timeFormat.format(Instant.ofEpochMilli(info.timestamp).atZone(ZoneId.systemDefault())))
                append(": ").append(reasonName(info.reason))
                append(", importance=").append(info.importance)
                if (info.pss > 0) append(", memory=").append(info.pss / 1024).append("MB")
                info.description?.takeIf { it.isNotBlank() }?.let { append(", ").append(it) }
                if (info.reason == ApplicationExitInfo.REASON_ANR ||
                    info.reason == ApplicationExitInfo.REASON_CRASH_NATIVE
                ) {
                    readTrace(info)?.let { append("\n").append(it) }
                }
            }
            val isProblem = info.reason in setOf(
                ApplicationExitInfo.REASON_CRASH,
                ApplicationExitInfo.REASON_CRASH_NATIVE,
                ApplicationExitInfo.REASON_ANR,
                ApplicationExitInfo.REASON_LOW_MEMORY,
                ApplicationExitInfo.REASON_EXCESSIVE_RESOURCE_USAGE,
                ApplicationExitInfo.REASON_INITIALIZATION_FAILURE,
                ApplicationExitInfo.REASON_FREEZER
            )
            if (isProblem) DiagLog.e(TAG, text) else DiagLog.i(TAG, text)
        }
        exits.maxOfOrNull { it.timestamp }?.let { prefs.edit().putLong(KEY_LAST_EXIT_MS, it).apply() }
    }

    private fun readTrace(info: ApplicationExitInfo): String? = runCatching {
        info.traceInputStream?.bufferedReader()?.use { reader ->
            val buffer = CharArray(MAX_TRACE_CHARS)
            val read = reader.read(buffer)
            if (read <= 0) null else String(buffer, 0, read)
        }
    }.getOrNull()

    internal fun reasonName(reason: Int): String = when (reason) {
        ApplicationExitInfo.REASON_EXIT_SELF -> "exited by itself"
        ApplicationExitInfo.REASON_SIGNALED -> "killed by signal"
        ApplicationExitInfo.REASON_LOW_MEMORY -> "killed for low memory"
        ApplicationExitInfo.REASON_CRASH -> "CRASH (Java)"
        ApplicationExitInfo.REASON_CRASH_NATIVE -> "CRASH (native)"
        ApplicationExitInfo.REASON_ANR -> "ANR (app not responding)"
        ApplicationExitInfo.REASON_INITIALIZATION_FAILURE -> "initialization failure"
        ApplicationExitInfo.REASON_PERMISSION_CHANGE -> "permission changed"
        ApplicationExitInfo.REASON_EXCESSIVE_RESOURCE_USAGE -> "killed for excessive resource use"
        ApplicationExitInfo.REASON_USER_REQUESTED -> "stopped by the user (force stop / swipe)"
        ApplicationExitInfo.REASON_USER_STOPPED -> "user stopped"
        ApplicationExitInfo.REASON_DEPENDENCY_DIED -> "dependency died"
        ApplicationExitInfo.REASON_OTHER -> "killed by the system (other)"
        ApplicationExitInfo.REASON_FREEZER -> "killed while frozen"
        ApplicationExitInfo.REASON_PACKAGE_STATE_CHANGE -> "package state changed"
        ApplicationExitInfo.REASON_PACKAGE_UPDATED -> "app updated"
        else -> "reason $reason"
    }
}
