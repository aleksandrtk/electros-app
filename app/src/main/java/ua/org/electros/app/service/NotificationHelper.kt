package ua.org.electros.app.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import ua.org.electros.app.MainActivity

class NotificationHelper(private val context: Context) {

    companion object {
        const val CHANNEL_ID_CHARGING = "charging_status_channel"
        const val CHANNEL_ID_ALERTS = "charging_alerts_channel"
        const val NOTIFICATION_ID_STATUS = 1001
        const val NOTIFICATION_ID_ALERT = 1002

        const val ACTION_STOP_CHARGE = "ua.org.electros.app.ACTION_STOP_CHARGE"
    }

    private val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

    init {
        createNotificationChannels()
    }

    private fun createNotificationChannels() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val statusChannel = NotificationChannel(
                CHANNEL_ID_CHARGING,
                "Статус заряджання",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Постійне сповіщення про поточний процес зарядки"
                setShowBadge(false)
            }

            val alertsChannel = NotificationChannel(
                CHANNEL_ID_ALERTS,
                "Важливі сповіщення",
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "Сповіщення про відключення світла та завершення ліміту"
            }

            notificationManager.createNotificationChannel(statusChannel)
            notificationManager.createNotificationChannel(alertsChannel)
        }
    }

    fun buildForegroundNotification(
        powerKw: Double,
        deliveredKwh: Double,
        targetKwh: Double,
        statusText: String,
        targetDurationMinutes: Int = 0,
        accumulatedChargingMs: Long = 0L
    ): Notification {
        val openAppIntent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val openAppPendingIntent = PendingIntent.getActivity(
            context,
            0,
            openAppIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val stopIntent = Intent(context, ChargeMonitorService::class.java).apply {
            action = ACTION_STOP_CHARGE
        }
        val stopPendingIntent = PendingIntent.getService(
            context,
            1,
            stopIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val timerPart = if (targetDurationMinutes > 0) {
            val targetMs = targetDurationMinutes * 60_000L
            val remSec = ((targetMs - accumulatedChargingMs).coerceAtLeast(0L) / 1000L)
            val h = remSec / 3600
            val m = (remSec % 3600) / 60
            " • Таймер %02d:%02d".format(h, m)
        } else ""

        val progress = when {
            targetKwh > 0.0 -> ((deliveredKwh / targetKwh) * 100).toInt().coerceIn(0, 100)
            targetDurationMinutes > 0 -> ((accumulatedChargingMs.toDouble() / (targetDurationMinutes * 60_000.0)) * 100).toInt().coerceIn(0, 100)
            else -> 0
        }

        val contentText = when {
            targetKwh > 0.0 -> "Заряджено %.1f з %.0f кВт⋅год (%d%%)$timerPart".format(deliveredKwh, targetKwh, progress)
            targetDurationMinutes > 0 -> "Залито %.1f кВт⋅год$timerPart".format(deliveredKwh)
            else -> "Заряджено %.1f кВт⋅год (без ліміту)".format(deliveredKwh)
        }

        val builder = NotificationCompat.Builder(context, CHANNEL_ID_CHARGING)
            .setContentTitle("Electros: %.1f кВт • %s".format(powerKw, statusText))
            .setContentText(contentText)
            .setSmallIcon(android.R.drawable.ic_lock_idle_charging)
            .setOngoing(true)
            .setContentIntent(openAppPendingIntent)
            .addAction(android.R.drawable.ic_media_pause, "Зупинити", stopPendingIntent)
            .setPriority(NotificationCompat.PRIORITY_LOW)

        if (targetKwh > 0.0 || targetDurationMinutes > 0) {
            builder.setProgress(100, progress, false)
        }

        return builder.build()
    }

    fun showOutageAlert(retainedKwh: Double) {
        val builder = NotificationCompat.Builder(context, CHANNEL_ID_ALERTS)
            .setContentTitle("⚠️ Світло в гаражі зникло!")
            .setContentText("Станція OFFLINE. Збережено %.1f кВт⋅год. Зарядка продовжиться автоматично.".format(retainedKwh))
            .setSmallIcon(android.R.drawable.stat_sys_warning)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)

        notificationManager.notify(NOTIFICATION_ID_ALERT, builder.build())
    }

    fun showTargetReachedAlert(deliveredKwh: Double) {
        val builder = NotificationCompat.Builder(context, CHANNEL_ID_ALERTS)
            .setContentTitle("⚡ Зарядка завершена!")
            .setContentText("Цільовий ліміт досягнуто: залито %.1f кВт⋅год.".format(deliveredKwh))
            .setSmallIcon(android.R.drawable.ic_lock_idle_charging)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)

        notificationManager.notify(NOTIFICATION_ID_ALERT, builder.build())
    }

    fun showBalancingAlert(oldAmperes: Int, newAmperes: Int, voltage: Int) {
        val isLowered = newAmperes < oldAmperes
        val title = if (isLowered) {
            "⚡ Струм заряджання знижено!"
        } else {
            "⚡ Струм заряджання підвищено"
        }
        val text = if (isLowered) {
            "Просадка напруги ($voltage В). Струм зменшено: $oldAmperes А → $newAmperes А для захисту мережі."
        } else {
            "Напруга стабілізувалась ($voltage В). Струм підвищено: $oldAmperes А → $newAmperes А."
        }
        val icon = if (isLowered) android.R.drawable.stat_sys_warning else android.R.drawable.ic_lock_idle_charging

        val openAppIntent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val pendingIntent = PendingIntent.getActivity(
            context,
            2,
            openAppIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val builder = NotificationCompat.Builder(context, CHANNEL_ID_ALERTS)
            .setContentTitle(title)
            .setContentText(text)
            .setSmallIcon(icon)
            .setContentIntent(pendingIntent)
            .setPriority(if (isLowered) NotificationCompat.PRIORITY_HIGH else NotificationCompat.PRIORITY_DEFAULT)
            .setAutoCancel(true)

        val notifId = NOTIFICATION_ID_ALERT + 10 + (System.currentTimeMillis() % 100).toInt()
        notificationManager.notify(notifId, builder.build())
    }

    fun showConnectionLostAlert(currentAmperes: Int) {
        val builder = NotificationCompat.Builder(context, CHANNEL_ID_ALERTS)
            .setContentTitle("⚠️ Зв'язок зі станцією втрачено!")
            .setContentText("Станція утримує струм $currentAmperes А. За відсутності інтернету балансування тимчасово призупинено.")
            .setSmallIcon(android.R.drawable.stat_sys_warning)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)

        notificationManager.notify(NOTIFICATION_ID_ALERT + 2, builder.build())
    }

    fun showDurationTargetReachedAlert(targetDurationMinutes: Int, deliveredKwh: Double) {
        val hours = targetDurationMinutes / 60
        val mins = targetDurationMinutes % 60
        val durLabel = if (mins == 0) "$hours год" else "${hours}г ${mins}хв"
        val builder = NotificationCompat.Builder(context, CHANNEL_ID_ALERTS)
            .setContentTitle("⏱️ Таймер зарядки завершено!")
            .setContentText("Відпрацьовано %s. Залито %.1f кВт⋅год. Станцію зупинено.".format(durLabel, deliveredKwh))
            .setSmallIcon(android.R.drawable.ic_lock_idle_charging)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)

        notificationManager.notify(NOTIFICATION_ID_ALERT, builder.build())
    }

    fun showOutageResumedAlert(amperes: Int) {
        val builder = NotificationCompat.Builder(context, CHANNEL_ID_ALERTS)
            .setContentTitle("⚡ Світло повернулось — зарядку відновлено")
            .setContentText("Станцію знову запущено на $amperes А. Таймер і лічильник продовжено.")
            .setSmallIcon(android.R.drawable.ic_lock_idle_charging)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)

        notificationManager.notify(NOTIFICATION_ID_ALERT + 3, builder.build())
    }

    fun showStopFailedAlert() {
        val openAppIntent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val pendingIntent = PendingIntent.getActivity(
            context,
            4,
            openAppIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val builder = NotificationCompat.Builder(context, CHANNEL_ID_ALERTS)
            .setContentTitle("⚠️ Не вдалося зупинити станцію")
            .setContentText("Команда зупинки не підтвердилась. Продовжуємо повторні спроби зупинити зарядку.")
            .setSmallIcon(android.R.drawable.stat_sys_warning)
            .setContentIntent(pendingIntent)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)

        notificationManager.notify(NOTIFICATION_ID_ALERT + 4, builder.build())
    }
}

