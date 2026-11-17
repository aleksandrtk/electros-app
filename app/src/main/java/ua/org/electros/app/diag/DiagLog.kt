package ua.org.electros.app.diag

import android.content.Context
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * Diagnostic journal the user can switch on in Settings and send for investigation.
 *
 * Lines are written on one background thread to a file inside the app's private storage. Until
 * [init] runs (for example in unit tests) or while the journal is off, every call does nothing.
 * Never pass the device number, password, cookies or raw station packets: the redactor is only a
 * safety net.
 */
object DiagLog {
    enum class Level(val code: Char) { DEBUG('D'), INFO('I'), WARN('W'), ERROR('E') }

    @Volatile
    var isEnabled: Boolean = false
        private set

    private val redactor = LogRedactor()
    @Volatile private var store: LogFileStore? = null
    @Volatile private var executor: ExecutorService? = null
    @Volatile private var appContext: Context? = null
    private val timeFormat = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSS")

    fun init(context: Context) {
        val app = context.applicationContext
        appContext = app
        store = LogFileStore(File(app.filesDir, LOG_DIR))
        executor = Executors.newSingleThreadExecutor { runnable ->
            Thread(runnable, "electros-diag-log").apply { isDaemon = true }
        }
        isEnabled = prefs(app).getBoolean(KEY_ENABLED, false)
        installCrashHandler()
    }

    fun setEnabled(enabled: Boolean) {
        val app = appContext ?: return
        if (enabled == isEnabled) return
        if (!enabled) i(TAG, "Journal switched off")
        isEnabled = enabled
        prefs(app).edit().putBoolean(KEY_ENABLED, enabled).apply()
        if (enabled) i(TAG, "Journal switched on")
    }

    fun setSecrets(vararg values: String) = redactor.setSecrets(values.toList())

    inline fun d(tag: String, message: () -> String) {
        if (isEnabled) write(Level.DEBUG, tag, message(), null)
    }

    fun i(tag: String, message: String) {
        if (isEnabled) write(Level.INFO, tag, message, null)
    }

    fun w(tag: String, message: String, error: Throwable? = null) {
        if (isEnabled) write(Level.WARN, tag, message, error)
    }

    fun e(tag: String, message: String, error: Throwable? = null) {
        if (isEnabled) write(Level.ERROR, tag, message, error)
    }

    @PublishedApi
    internal fun write(level: Level, tag: String, message: String, error: Throwable?) {
        val line = formatLine(level, tag, message, error)
        val target = store ?: return
        runCatching { executor?.execute { runCatching { target.append(line) } } }
    }

    internal fun formatLine(
        level: Level,
        tag: String,
        message: String,
        error: Throwable?,
        nowMs: Long = System.currentTimeMillis(),
        zone: ZoneId = ZoneId.systemDefault()
    ): String {
        val time = timeFormat.format(Instant.ofEpochMilli(nowMs).atZone(zone))
        val text = buildString {
            append(time).append(' ').append(level.code).append(' ').append(tag).append(": ").append(message)
            if (error != null) append('\n').append(stackTraceOf(error).trimEnd())
            append('\n')
        }
        return redactor.redact(text)
    }

    fun sizeBytes(): Long = runOnWriter { store?.sizeBytes() } ?: 0L

    fun clear() {
        runOnWriter { store?.clear() }
    }

    /**
     * Packs the journal with [header] into the zip [target] as one text file named [entryName];
     * false when there is nothing to export.
     */
    fun exportZip(target: File, entryName: String, header: String): Boolean = runOnWriter {
        val s = store ?: return@runOnWriter false
        if (s.isEmpty()) return@runOnWriter false
        s.exportZip(target, entryName, redactor.redact(header))
        true
    } ?: false

    /** Runs [block] after every line queued so far has been written. */
    private fun <T> runOnWriter(block: () -> T): T? {
        val ex = executor ?: return null
        return runCatching { ex.submit<T> { block() }.get(WRITER_WAIT_SECONDS, TimeUnit.SECONDS) }.getOrNull()
    }

    private fun installCrashHandler() {
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        if (previous is CrashRecorder) return
        Thread.setDefaultUncaughtExceptionHandler(CrashRecorder(previous))
    }

    private class CrashRecorder(
        private val delegate: Thread.UncaughtExceptionHandler?
    ) : Thread.UncaughtExceptionHandler {
        override fun uncaughtException(thread: Thread, error: Throwable) {
            if (isEnabled) {
                write(Level.ERROR, "Crash", "Uncaught exception in thread '${thread.name}'", error)
                // Give the writer thread a moment to put the stack trace on disk before the process dies.
                runOnWriter { }
            }
            delegate?.uncaughtException(thread, error)
        }
    }

    private fun stackTraceOf(error: Throwable): String =
        StringWriter().also { error.printStackTrace(PrintWriter(it)) }.toString()

    private fun prefs(context: Context) = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    private const val TAG = "Journal"
    private const val LOG_DIR = "diag"
    private const val PREFS_NAME = "diag_log"
    private const val KEY_ENABLED = "enabled"
    private const val WRITER_WAIT_SECONDS = 3L
}
