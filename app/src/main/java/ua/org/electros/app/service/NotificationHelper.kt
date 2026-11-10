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
        statusText: String
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

        val progress = if (targetKwh > 0.0) ((deliveredKwh / targetKwh) * 100).toInt() else 0
        val contentText = if (targetKwh > 0.0) {
            "Заряджено %.1f з %.0f кВт⋅год (%d%%)".format(deliveredKwh, targetKwh, progress)
        } else {
            "Заряджено %.1f кВт⋅год (без ліміту)".format(deliveredKwh)
        }

        val builder = NotificationCompat.Builder(context, CHANNEL_ID_CHARGING)
            .setContentTitle("Electros: %.1f кВт • %s".format(powerKw, statusText))
            .setContentText(contentText)
            .setSmallIcon(android.R.drawable.ic_lock_idle_charging)
            .setOngoing(true)
            .setContentIntent(openAppPendingIntent)
            .addAction(android.R.drawable.ic_media_pause, "Зупинити", stopPendingIntent)
            .setPriority(NotificationCompat.PRIORITY_LOW)

        if (targetKwh > 0.0) {
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
        val action = if (newAmperes < oldAmperes) "знижено" else "підвищено"
        val icon = if (newAmperes < oldAmperes) android.R.drawable.stat_sys_warning else android.R.drawable.ic_lock_idle_charging
        val builder = NotificationCompat.Builder(context, CHANNEL_ID_ALERTS)
            .setContentTitle("⚡ Балансування навантаження (DLB)")
            .setContentText("Струм $action: $oldAmperes А → $newAmperes А (напруга мережі: $voltage В)")
            .setSmallIcon(icon)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setAutoCancel(true)

        notificationManager.notify(NOTIFICATION_ID_ALERT + 1, builder.build())
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
}

