package ua.org.electros.app.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import ua.org.electros.app.MainActivity
import ua.org.electros.app.R

class NotificationHelper(private val context: Context) {

    companion object {
        const val CHANNEL_ID_CHARGING = "charging_status_channel"
        const val CHANNEL_ID_ALERTS = "charging_alerts_channel"
        const val CHANNEL_ID_BALANCING_INFO = "charging_balancing_info_channel"
        const val NOTIFICATION_ID_STATUS = 1001
        const val NOTIFICATION_ID_ALERT = 1002
        const val NOTIFICATION_ID_RESUME_ALERT = 1007
        const val NOTIFICATION_ID_FGS_BLOCKED = 1008

        const val ACTION_STOP_CHARGE = "ua.org.electros.app.ACTION_STOP_CHARGE"
        const val ACTION_RESUME_CHARGE = "ua.org.electros.app.ACTION_RESUME_CHARGE"
    }

    private fun buildServiceActionPendingIntent(requestCode: Int, action: String): PendingIntent {
        val intent = Intent(context, ChargeMonitorService::class.java).apply {
            this.action = action
        }
        val flags = PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        return PendingIntent.getForegroundService(context, requestCode, intent, flags)
    }

    private val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

    init {
        createNotificationChannels()
    }

    private fun createNotificationChannels() {
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

        val balancingInfoChannel = NotificationChannel(
            CHANNEL_ID_BALANCING_INFO,
            "Балансування напруги",
            NotificationManager.IMPORTANCE_DEFAULT
        ).apply {
            description = "Інформаційні сповіщення про відновлення струму після стабілізації напруги"
        }

        notificationManager.createNotificationChannel(statusChannel)
        notificationManager.createNotificationChannel(alertsChannel)
        notificationManager.createNotificationChannel(balancingInfoChannel)
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

        val stopPendingIntent = buildServiceActionPendingIntent(1, ACTION_STOP_CHARGE)

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
            .setSmallIcon(R.drawable.ic_nav_bolt)
            .setOngoing(true)
            .setContentIntent(openAppPendingIntent)
            .addAction(R.drawable.ic_notif_stop, "Зупинити", stopPendingIntent)

        if (targetKwh > 0.0 || targetDurationMinutes > 0) {
            builder.setProgress(100, progress, false)
        }

        return builder.build()
    }

    fun showOutageAlert(retainedKwh: Double, autoResumeEnabled: Boolean = true) {
        val text = if (autoResumeEnabled) {
            "Станція OFFLINE. Збережено %.1f кВт⋅год. Зарядка продовжиться автоматично.".format(retainedKwh)
        } else {
            "Станція OFFLINE. Збережено %.1f кВт⋅год. Автоматичне відновлення вимкнено.".format(retainedKwh)
        }
        val builder = NotificationCompat.Builder(context, CHANNEL_ID_ALERTS)
            .setContentTitle("⚠️ Світло в гаражі зникло!")
            .setContentText(text)
            .setSmallIcon(R.drawable.ic_notif_warning)
            .setAutoCancel(true)

        notificationManager.notify(NOTIFICATION_ID_ALERT, builder.build())
    }

    fun showManualResumeAvailableAlert(amperes: Int, retainedKwh: Double) {
        val resumePendingIntent = buildServiceActionPendingIntent(5, ACTION_RESUME_CHARGE)
        val openAppIntent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val openAppPendingIntent = PendingIntent.getActivity(
            context,
            6,
            openAppIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val builder = NotificationCompat.Builder(context, CHANNEL_ID_ALERTS)
            .setContentTitle("⚡ Світло з'явилось — станція в мережі")
            .setContentText("Збережено %.1f кВт⋅год. Натисніть «Відновити заряд» для запуску на %d А.".format(retainedKwh, amperes))
            .setSmallIcon(R.drawable.ic_nav_bolt)
            .setContentIntent(openAppPendingIntent)
            .addAction(R.drawable.ic_notif_play, "Відновити заряд", resumePendingIntent)
            .setAutoCancel(true)

        notificationManager.notify(NOTIFICATION_ID_RESUME_ALERT, builder.build())
    }

    fun cancelManualResumeAlert() {
        notificationManager.cancel(NOTIFICATION_ID_RESUME_ALERT)
    }

    fun showTargetReachedAlert(deliveredKwh: Double) {
        val builder = NotificationCompat.Builder(context, CHANNEL_ID_ALERTS)
            .setContentTitle("⚡ Зарядка завершена!")
            .setContentText("Цільовий ліміт досягнуто: залито %.1f кВт⋅год.".format(deliveredKwh))
            .setSmallIcon(R.drawable.ic_nav_bolt)
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
        val icon = if (isLowered) R.drawable.ic_notif_warning else R.drawable.ic_nav_bolt
        val channelId = if (isLowered) CHANNEL_ID_ALERTS else CHANNEL_ID_BALANCING_INFO

        val openAppIntent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val pendingIntent = PendingIntent.getActivity(
            context,
            2,
            openAppIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val builder = NotificationCompat.Builder(context, channelId)
            .setContentTitle(title)
            .setContentText(text)
            .setSmallIcon(icon)
            .setContentIntent(pendingIntent)
            .setAutoCancel(true)

        val notifId = NOTIFICATION_ID_ALERT + 10 + (System.currentTimeMillis() % 100).toInt()
        notificationManager.notify(notifId, builder.build())
    }

    fun showConnectionLostAlert(currentAmperes: Int) {
        val builder = NotificationCompat.Builder(context, CHANNEL_ID_ALERTS)
            .setContentTitle("⚠️ Зв'язок зі станцією втрачено!")
            .setContentText("Станція утримує струм $currentAmperes А. За відсутності інтернету балансування тимчасово призупинено.")
            .setSmallIcon(R.drawable.ic_notif_warning)
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
            .setSmallIcon(R.drawable.ic_nav_bolt)
            .setAutoCancel(true)

        notificationManager.notify(NOTIFICATION_ID_ALERT, builder.build())
    }

    fun showOutageResumedAlert(amperes: Int) {
        val builder = NotificationCompat.Builder(context, CHANNEL_ID_ALERTS)
            .setContentTitle("⚡ Світло повернулось — зарядку відновлено")
            .setContentText("Станцію знову запущено на $amperes А. Таймер і лічильник продовжено.")
            .setSmallIcon(R.drawable.ic_nav_bolt)
            .setAutoCancel(true)

        notificationManager.notify(NOTIFICATION_ID_ALERT + 3, builder.build())
    }

    private fun createOpenAppPendingIntent(requestCode: Int): PendingIntent {
        val openAppIntent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        return PendingIntent.getActivity(
            context,
            requestCode,
            openAppIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
    }

    fun showStopFailedAlert() {
        val pendingIntent = createOpenAppPendingIntent(4)

        val builder = NotificationCompat.Builder(context, CHANNEL_ID_ALERTS)
            .setContentTitle("⚠️ Не вдалося зупинити станцію")
            .setContentText("Команда зупинки не підтвердилась. Продовжуємо повторні спроби зупинити зарядку.")
            .setSmallIcon(R.drawable.ic_notif_warning)
            .setContentIntent(pendingIntent)
            .setAutoCancel(true)

        notificationManager.notify(NOTIFICATION_ID_ALERT + 4, builder.build())
    }

    fun showMonitorRestartRequiredAlert() {
        val pendingIntent = createOpenAppPendingIntent(5)

        val builder = NotificationCompat.Builder(context, CHANNEL_ID_ALERTS)
            .setContentTitle("Потрібен ручний запуск моніторингу")
            .setContentText("Відкрийте застосунок, щоб продовжити моніторинг")
            .setSmallIcon(R.drawable.ic_notif_warning)
            .setContentIntent(pendingIntent)
            .setAutoCancel(true)

        notificationManager.notify(NOTIFICATION_ID_FGS_BLOCKED, builder.build())
    }
}

