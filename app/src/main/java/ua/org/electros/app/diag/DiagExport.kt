package ua.org.electros.app.diag

import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import androidx.core.content.FileProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import ua.org.electros.app.BuildConfig
import ua.org.electros.app.ElectrosApplication
import ua.org.electros.app.R
import java.io.File
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * Builds the journal file to share and the "Share" intent for it (Telegram, mail and so on).
 * The file is a zip with one plain-text log inside: text logs shrink about ten times.
 */
object DiagExport {
    private const val EXPORT_DIR = "diag"
    private val fileTime = DateTimeFormatter.ofPattern("yyyyMMdd-HHmm")

    /** Returns the share intent, or null when the journal is empty. */
    suspend fun buildShareIntent(context: Context): Intent? = withContext(Dispatchers.IO) {
        val app = context.applicationContext as ElectrosApplication
        val dir = File(app.cacheDir, EXPORT_DIR).apply { mkdirs() }
        dir.listFiles()?.forEach { it.delete() }
        val baseName = "electros-log-${fileTime.format(LocalDateTime.now())}"
        val file = File(dir, "$baseName.zip")
        if (!DiagLog.exportZip(file, "$baseName.txt", header(app))) return@withContext null

        val uri: Uri = FileProvider.getUriForFile(app, "${app.packageName}.fileprovider", file)
        val send = Intent(Intent.ACTION_SEND).apply {
            type = "application/zip"
            putExtra(Intent.EXTRA_STREAM, uri)
            putExtra(Intent.EXTRA_SUBJECT, file.name)
            clipData = ClipData.newRawUri(file.name, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        Intent.createChooser(send, app.getString(R.string.settings_log_share_title))
    }

    private suspend fun header(app: ElectrosApplication): String {
        val settings = app.settingsStore.awaitSettings()
        val active = runCatching { app.repository.getActiveSession() }.getOrNull()
        val power = app.getSystemService(PowerManager::class.java)
        val batteryExempt = power?.isIgnoringBatteryOptimizations(app.packageName)
        return buildString {
            appendLine("===== Electros EV diagnostic journal =====")
            appendLine("Exported: ${LocalDateTime.now()} (${ZoneId.systemDefault()})")
            appendLine("App: ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE}) ${BuildConfig.BUILD_TYPE}")
            appendLine("Device: ${Build.MANUFACTURER} ${Build.MODEL}, Android ${Build.VERSION.RELEASE} (SDK ${Build.VERSION.SDK_INT})")
            appendLine("Locale: ${Locale.getDefault().toLanguageTag()}, battery optimization off: $batteryExempt")
            appendLine(
                "Settings: amperage=${settings.preferredAmperage}A" +
                    ", kWhLimit=${if (settings.isKwhLimitEnabled) settings.targetKwhLimit else "off"}" +
                    ", timer=${if (settings.isDurationTimerEnabled) "${settings.targetDurationHours}h" else "off"}" +
                    ", schedule=${if (settings.isScheduleEnabled) "${settings.scheduleStartTime}-${settings.scheduleStopTime}" else "off"}" +
                    ", autoResume=${settings.autoResumeAfterOutage}" +
                    ", autoCurrent=${if (settings.isVoltageBalancingEnabled) "${settings.voltageLowThreshold}/${settings.voltageHighThreshold}V ${settings.voltageMinAmperage}-${settings.voltageMaxAmperage}A" else "off"}"
            )
            appendLine(
                "Active session: " + (active?.let {
                    "${it.id.take(8)} status=${it.status} target=${it.targetKwh}kWh/${it.targetDurationMinutes}min " +
                        "amps=${it.amperageSetting} delivered=${"%.3f".format(Locale.US, it.totalKwhDelivered)} " +
                        "chargingMs=${it.accumulatedChargingMs}"
                } ?: "none")
            )
            appendLine("==========================================")
            appendLine()
        }
    }
}
